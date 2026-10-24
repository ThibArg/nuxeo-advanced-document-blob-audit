/*
 * (C) Copyright 2026 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.nuxeo.audit.advanced.blob.extractor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.nuxeo.audit.advanced.blob.BlobTextExtractor;
import org.nuxeo.audit.advanced.blob.ContentLine;
import org.nuxeo.audit.advanced.blob.DiffableContent;
import org.nuxeo.audit.advanced.blob.io.OoxmlPackages;
import org.nuxeo.ecm.core.api.Blob;
import org.xml.sax.SAXException;

/**
 * Paragraph-level extractor for PowerPoint OOXML files ({@code .pptx}, {@code .pptm}, {@code .ppsx},
 * {@code .potx}), streamed with SAX the way Apache POI XSLF reads them.
 * <p>
 * Produces <b>positional</b> content. Each slide starts with a {@code ## Slide} marker line carrying
 * its title (never its number: numbering would make a slide insertion shift, and therefore
 * "modify", every following slide). Then come the text paragraphs of every shape in drawing order,
 * table cells as {@code [table] cell | cell | ...} rows, and optionally speaker notes prefixed with
 * {@code [notes]}. Group shapes are walked recursively.
 * <p>
 * Positional alignment (Hirschberg) is the right fit: inserting, deleting or moving a slide yields a
 * compact diff instead of the cascade a keyed "slide N" identity would produce.
 * <p>
 * Legacy binary {@code .ppt} needs poi-scratchpad (HSLF) and is left to the generic
 * {@link ConverterTextExtractor} ({@code any2text}).
 *
 * @since 2025.1
 */
public class PresentationExtractor implements BlobTextExtractor {

    public static final String SLIDE_MARKER = "## Slide";

    public static final String NOTES_PREFIX = "[notes] ";

    public static final String TABLE_PREFIX = "[table] ";

    protected boolean includeNotes = true;

    @Override
    public void init(Map<String, String> properties) {
        includeNotes = !"false".equalsIgnoreCase(properties.get("includeNotes"));
    }

    /**
     * Streams the slides one by one, and stops at {@code maxLines}.
     * <p>
     * {@code XMLSlideShow} parses every slide, layout, master and notes page into an XMLBeans tree
     * when it is opened, so its footprint followed the size of the deck rather than what is
     * extracted - from a file as well, which only spares buffering the compressed package. Each
     * slide is now read twice with SAX, first for its title, which the marker line needs before
     * anything else, then for its content.
     */
    @Override
    public DiffableContent extract(Blob blob, int maxLines) throws IOException {
        Collector collector = new Collector(maxLines);
        OPCPackage pkg = OoxmlPackages.open(blob);
        try {
            for (PackagePart slide : SlideHandler.slides(pkg)) {
                if (!collectSlide(slide, collector)) {
                    break;
                }
            }
        } catch (SAXException e) {
            throw OoxmlPackages.unreadable(e, "OOXML presentation: " + blob.getFilename());
        } finally {
            pkg.revert();
        }
        return DiffableContent.positional(collector.lines, collector.truncated);
    }

    /** @return {@code false} once maxLines has been reached */
    protected boolean collectSlide(PackagePart slide, Collector collector) throws IOException, SAXException {
        String title = title(slide);
        if (!collector.add(SLIDE_MARKER + (title == null || title.isBlank() ? "" : ": " + title.trim()))) {
            return false;
        }
        try {
            OoxmlPackages.parse(slide, new SlideText(collector, false));
            PackagePart notes = includeNotes ? SlideHandler.notes(slide) : null;
            if (notes != null) {
                OoxmlPackages.parse(notes, new SlideText(collector, true));
            }
        } catch (OoxmlPackages.Stop e) {
            return false;
        }
        return true;
    }

    /** The text of the first title placeholder of the slide, like {@code XSLFSlide#getTitle()}. */
    protected String title(PackagePart slide) throws IOException, SAXException {
        SlideTitle handler = new SlideTitle();
        try {
            OoxmlPackages.parse(slide, handler);
        } catch (OoxmlPackages.Stop e) {
            // the title shape has been read
        }
        return handler.title;
    }

    protected static class SlideTitle extends SlideHandler {

        protected final StringBuilder text = new StringBuilder();

        protected String title;

        @Override
        protected boolean readsText(boolean topLevel, String placeholder) {
            return isTitle(topLevel, placeholder);
        }

        @Override
        protected boolean readsTables() {
            return false;
        }

        @Override
        protected void paragraph(boolean topLevel, String placeholder, String paragraph) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(paragraph);
        }

        @Override
        protected void textShapeEnd(boolean topLevel, String placeholder) throws SAXException {
            if (isTitle(topLevel, placeholder)) {
                title = text.toString();
                throw new OoxmlPackages.Stop();
            }
        }

        protected boolean isTitle(boolean topLevel, String placeholder) {
            return topLevel && "title".equals(placeholder);
        }
    }

    /**
     * The lines of a slide, or of its notes page. Notes pages also hold the slide thumbnail and the
     * page number: only their body placeholder is kept.
     */
    protected static class SlideText extends SlideHandler {

        protected final Collector collector;

        protected final boolean notes;

        protected SlideText(Collector collector, boolean notes) {
            this.collector = collector;
            this.notes = notes;
        }

        @Override
        protected boolean readsText(boolean topLevel, String placeholder) {
            return !notes || (topLevel && "body".equals(placeholder));
        }

        @Override
        protected boolean readsTables() {
            return !notes;
        }

        @Override
        protected void paragraph(boolean topLevel, String placeholder, String text) throws SAXException {
            if (!text.isBlank()) {
                add((notes ? NOTES_PREFIX : "") + text.trim());
            }
        }

        @Override
        protected void tableRow(List<String> cells) throws SAXException {
            List<String> normalized = cells.stream().map(cell -> cell.replaceAll("\\s+", " ").trim()).toList();
            if (normalized.stream().anyMatch(cell -> !cell.isEmpty())) {
                add(TABLE_PREFIX + String.join(" | ", normalized));
            }
        }

        protected void add(String line) throws SAXException {
            if (!collector.add(line)) {
                throw new OoxmlPackages.Stop();
            }
        }
    }

    protected static class Collector {
        protected final int maxLines;

        protected final List<ContentLine> lines = new ArrayList<>();

        protected boolean truncated;

        protected Collector(int maxLines) {
            this.maxLines = maxLines;
        }

        protected boolean add(String value) {
            if (lines.size() >= maxLines) {
                truncated = true;
                return false;
            }
            lines.add(ContentLine.of(value));
            return true;
        }
    }
}

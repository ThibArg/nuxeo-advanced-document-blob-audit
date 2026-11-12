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
import org.nuxeo.audit.advanced.blob.BlobContentExtractor;
import org.nuxeo.audit.advanced.blob.ContentLine;
import org.nuxeo.audit.advanced.blob.DiffableContent;
import org.nuxeo.audit.advanced.blob.TextDiffer;
import org.nuxeo.audit.advanced.blob.io.MaterializedBlob;
import org.nuxeo.audit.advanced.blob.io.OoxmlPackages;
import org.nuxeo.ecm.core.api.Blob;
import org.xml.sax.Attributes;
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
public class PresentationExtractor implements BlobContentExtractor {

    public static final String SLIDE_MARKER = "## Slide";

    public static final String NOTES_PREFIX = "[notes] ";

    public static final String TABLE_PREFIX = "[table] ";

    protected boolean includeNotes = true;

    protected int maxValueLength = TextDiffer.DEFAULT_MAX_VALUE_LENGTH;

    @Override
    public void init(Map<String, String> properties) {
        includeNotes = !"false".equalsIgnoreCase(properties.get("includeNotes"));
        maxValueLength = BlobContentExtractor.maxValueLength(properties);
    }

    /**
     * Streams the slides one by one, and stops at {@code maxLines}.
     * <p>
     * {@code XMLSlideShow} parses every slide, layout, master and notes page into an XMLBeans tree
     * when it is opened, so its footprint followed the size of the deck rather than what is
     * extracted - from a file as well, which only spares buffering the compressed package. Each
     * slide is read once with SAX; its lines are held until its title, which the marker line needs
     * before anything else, has been read.
     */
    @Override
    public DiffableContent extract(Blob blob, int maxLines) throws IOException {
        Collector collector = new Collector(maxLines, maxValueLength);
        try (MaterializedBlob local = MaterializedBlob.of(blob)) {
            OPCPackage pkg = OoxmlPackages.open(local.file());
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
        }
        return DiffableContent.positional(collector.lines, collector.truncated);
    }

    /** @return {@code false} once maxLines has been reached */
    protected boolean collectSlide(PackagePart slide, Collector collector) throws IOException, SAXException {
        if (collector.remaining() <= 0) {
            collector.truncated = true;
            return false;
        }
        SlideText content = new SlideText(collector, false);
        try {
            OoxmlPackages.parse(slide, content);
        } catch (OoxmlPackages.Stop e) {
            // the slide overflows and its title is known: the adds below reach maxLines
        }
        String title = content.title;
        if (!collector.add(SLIDE_MARKER + (title == null || title.isBlank() ? "" : ": " + title.trim()))) {
            return false;
        }
        for (String line : content.lines) {
            if (!collector.add(line)) {
                return false;
            }
        }
        try {
            PackagePart notes = includeNotes ? SlideHandler.notes(slide) : null;
            if (notes != null) {
                OoxmlPackages.parse(notes, new SlideText(collector, true));
            }
        } catch (OoxmlPackages.Stop e) {
            return false;
        }
        return true;
    }

    /**
     * Whether a text shape is the title of its slide, like {@code XSLFSlide#getTitle()}: a top-level
     * shape whose placeholder type is {@code title}.
     */
    protected static boolean isTitle(boolean topLevel, String placeholder) {
        return topLevel && "title".equals(placeholder);
    }

    /**
     * The lines of a slide, or of its notes page. Notes pages also hold the slide thumbnail and the
     * page number: only their body placeholder is kept.
     * <p>
     * Notes lines go straight to the collector. Slide lines are held until the slide has been read:
     * the marker line, which comes first, carries the title, and the title shape can sit anywhere in
     * the slide. Its paragraphs are captured on the way, joined with {@code \n}. At most the room left
     * in the collector is held; past it, the rest of the slide is read the way the title alone needs -
     * other text and tables skipped unread - and the parse stops as soon as the title is known.
     */
    protected static class SlideText extends SlideHandler {

        protected final Collector collector;

        protected final boolean notes;

        /** Slide mode: the lines of the slide, at most {@link #capacity} of them. */
        protected final List<String> lines = new ArrayList<>();

        /** Slide mode: the room left in the collector when the slide starts, marker included. */
        protected final int capacity;

        /** Slide mode: set once a line arrived with {@link #capacity} lines already held. */
        protected boolean overflowing;

        /** Slide mode: the paragraphs of the title shape read so far. */
        protected final StringBuilder titleText = new StringBuilder();

        /** Slide mode: the text of the first title placeholder, {@code null} until it has been read. */
        protected String title;

        protected SlideText(Collector collector, boolean notes) {
            this.collector = collector;
            this.notes = notes;
            this.capacity = collector.remaining();
        }

        @Override
        protected boolean readsText(boolean topLevel, String placeholder) {
            if (notes) {
                return topLevel && "body".equals(placeholder);
            }
            // Once the slide overflows, only its title is still wanted: the rest is skipped unread.
            return !overflowing || isTitle(topLevel, placeholder);
        }

        @Override
        protected boolean readsTables() {
            return !notes && !overflowing;
        }

        /** Skips the rest of the shape or table being read when the slide overflows, unless it is the title. */
        @Override
        protected Frame child(Frame parent, String uri, String name, Attributes attributes) {
            if (overflowing && !(cell == null && isTitle(shapeTopLevel, placeholder))
                    && (parent.role == Role.TEXT_BODY || parent.role == Role.TABLE || parent.role == Role.ROW)) {
                return SKIP;
            }
            return super.child(parent, uri, name, attributes);
        }

        @Override
        protected void paragraph(boolean topLevel, String placeholder, String text) throws SAXException {
            // Blank paragraphs belong to the title too.
            if (!notes && title == null && isTitle(topLevel, placeholder)) {
                if (!titleText.isEmpty()) {
                    titleText.append('\n');
                }
                titleText.append(text);
            }
            if (!text.isBlank()) {
                add((notes ? NOTES_PREFIX : "") + text.trim());
            }
        }

        @Override
        protected void textShapeEnd(boolean topLevel, String placeholder) throws SAXException {
            if (!notes && title == null && isTitle(topLevel, placeholder)) {
                title = titleText.toString();
                if (overflowing) {
                    throw new OoxmlPackages.Stop();
                }
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
            if (notes) {
                if (!collector.add(line)) {
                    throw new OoxmlPackages.Stop();
                }
            } else if (lines.size() < capacity) {
                lines.add(line);
            } else {
                // Discarded: the slide is cut anyway, but its title has to be read before the parse ends.
                overflowing = true;
                if (title != null) {
                    throw new OoxmlPackages.Stop();
                }
            }
        }
    }

    protected static class Collector {
        protected final int maxLines;

        protected final int maxValueLength;

        protected final List<ContentLine> lines = new ArrayList<>();

        protected boolean truncated;

        protected Collector(int maxLines, int maxValueLength) {
            this.maxLines = maxLines;
            this.maxValueLength = maxValueLength;
        }

        /** Returns how many more lines can be added. */
        protected int remaining() {
            return maxLines - lines.size();
        }

        protected boolean add(String value) {
            if (lines.size() >= maxLines) {
                truncated = true;
                return false;
            }
            lines.add(ContentLine.of(null, value, maxValueLength));
            return true;
        }
    }
}

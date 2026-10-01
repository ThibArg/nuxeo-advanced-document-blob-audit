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

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.nuxeo.audit.advanced.blob.BlobTextExtractor;
import org.nuxeo.audit.advanced.blob.ContentLine;
import org.nuxeo.audit.advanced.blob.DiffableContent;
import org.nuxeo.ecm.core.api.Blob;

/**
 * Paragraph-level extractor for PowerPoint OOXML files ({@code .pptx}, {@code .pptm}, {@code .ppsx},
 * {@code .potx}), based on Apache POI XSLF.
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
 * @since 1.1
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
     * Reads the slideshow from the local file whenever there is one.
     * <p>
     * {@code new XMLSlideShow(InputStream)} buffers the whole OOXML package in heap - POI cannot
     * reposition a stream. Opening an {@link OPCPackage} on the file reads the parts on demand, in
     * random access and read only.
     * <p>
     * The package is closed explicitly: {@link XMLSlideShow#close()} does <b>not</b> close an
     * {@code OPCPackage} it was handed. {@link org.nuxeo.audit.advanced.blob.io.MaterializedBlob}
     * guarantees a file on the nominal path; the stream fallback is for the blobs built in memory
     * by the unit tests.
     */
    @Override
    public DiffableContent extract(Blob blob, int maxLines) throws Exception {
        Collector collector = new Collector(maxLines);
        File file = blob.getFile();
        if (file != null) {
            try (OPCPackage pkg = OPCPackage.open(file, PackageAccess.READ);
                    XMLSlideShow show = new XMLSlideShow(pkg)) {
                collect(show, collector);
            }
        } else {
            try (InputStream in = blob.getStream(); XMLSlideShow show = new XMLSlideShow(in)) {
                collect(show, collector);
            }
        }
        return DiffableContent.positional(collector.lines, collector.truncated);
    }

    protected void collect(XMLSlideShow show, Collector collector) {
        for (XSLFSlide slide : show.getSlides()) {
            String title = slide.getTitle();
            if (!collector.add(SLIDE_MARKER + (title == null || title.isBlank() ? "" : ": " + title.trim()))) {
                return;
            }
            if (!collectShapes(slide.getShapes(), "", collector)) {
                return;
            }
            if (includeNotes) {
                XSLFNotes notes = slide.getNotes();
                if (notes != null && !collectNotes(notes, collector)) {
                    return;
                }
            }
        }
    }

    /** @return {@code false} once maxLines has been reached */
    protected boolean collectShapes(List<? extends XSLFShape> shapes, String prefix, Collector collector) {
        for (XSLFShape shape : shapes) {
            if (shape instanceof XSLFGroupShape group) {
                if (!collectShapes(group.getShapes(), prefix, collector)) {
                    return false;
                }
            } else if (shape instanceof XSLFTable table) {
                for (XSLFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XSLFTableCell cell : row.getCells()) {
                        String text = cell.getText();
                        cells.add(text == null ? "" : text.replaceAll("\\s+", " ").trim());
                    }
                    if (cells.stream().anyMatch(c -> !c.isEmpty())
                            && !collector.add(prefix + TABLE_PREFIX + String.join(" | ", cells))) {
                        return false;
                    }
                }
            } else if (shape instanceof XSLFTextShape textShape) {
                for (XSLFTextParagraph paragraph : textShape.getTextParagraphs()) {
                    String text = paragraph.getText();
                    if (text != null && !text.isBlank() && !collector.add(prefix + text.trim())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Notes pages also hold the slide thumbnail and the page number: keep the body only. */
    protected boolean collectNotes(XSLFNotes notes, Collector collector) {
        for (XSLFShape shape : notes.getShapes()) {
            if (shape instanceof XSLFTextShape textShape && textShape.getTextType() != null
                    && textShape.getTextType().name().equals("BODY")) {
                for (XSLFTextParagraph paragraph : textShape.getTextParagraphs()) {
                    String text = paragraph.getText();
                    if (text != null && !text.isBlank() && !collector.add(NOTES_PREFIX + text.trim())) {
                        return false;
                    }
                }
            }
        }
        return true;
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

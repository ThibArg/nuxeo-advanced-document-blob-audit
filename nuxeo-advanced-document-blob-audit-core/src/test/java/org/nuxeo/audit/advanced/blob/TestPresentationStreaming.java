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
package org.nuxeo.audit.advanced.blob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.PPTX_MIME;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.onDisk;
import static org.nuxeo.audit.advanced.blob.extractor.PresentationExtractor.NOTES_PREFIX;
import static org.nuxeo.audit.advanced.blob.extractor.PresentationExtractor.SLIDE_MARKER;
import static org.nuxeo.audit.advanced.blob.extractor.PresentationExtractor.TABLE_PREFIX;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.After;
import org.junit.Test;
import org.nuxeo.audit.advanced.blob.extractor.PresentationExtractor;
import org.nuxeo.audit.advanced.blob.image.ImageInventoryExtractor;
import org.nuxeo.ecm.core.api.Blob;

import com.sun.management.ThreadMXBean;

/**
 * RES-03 (code review 2026-10), second half: PPTX decks are streamed slide by slide with SAX instead
 * of being loaded through {@code XMLSlideShow}, which parses every slide into an XMLBeans tree when
 * the deck is opened.
 * <p>
 * Both the text and the image inventory must stay exactly what the usermodel produced, down to its
 * handling of alternate content. The former usermodel code is kept here as the reference.
 *
 * @since 2025.1
 */
public class TestPresentationStreaming {

    protected static final int MAX_LINES = 10000;

    protected static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    protected static final byte[] PNG_2 = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    /** Stands for the relationship id of the picture of the slide in the hand-written shape trees. */
    protected static final String RID = "%RID%";

    protected static final String TABLE_GRAPHIC = "<a:graphic><a:graphicData uri=\""
            + "http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl><a:tblGrid><a:gridCol w=\"100\"/>"
            + "<a:gridCol w=\"100\"/></a:tblGrid><a:tr h=\"10\">" + cell("In") + cell("a group") + "</a:tr>"
            + "<a:tr h=\"10\">" + cell("") + cell(" ") + "</a:tr></a:tbl></a:graphicData></a:graphic>";

    /**
     * What POI's writer never produces and real decks do: alternate content - read from the first
     * element of its first choice only, never from the fallback - a field, a line break, a title
     * placeholder that is not the first shape, a second one, one inside a group, a non-table graphic
     * frame, a table in a group, a linked picture, and the same media placed three times.
     */
    protected static final String RICH_SHAPES = textShape(2, null, "<a:r><a:t>Page </a:t></a:r>"
            + "<a:fld id=\"{B6F15528-21DE-4FAA-801E-634DDDAF4B2B}\" type=\"slidenum\"><a:t>3</a:t></a:fld><a:br/>"
            + "<a:r><a:t>next line</a:t></a:r>") //
            + "<mc:AlternateContent><mc:Choice Requires=\"p14\">" //
            + group(3, textShape(4, "title", run("Grouped title")) + textShape(20, null, run("From the choice"))
                    + picture(5, "r:embed=\"" + RID + "\"")) //
            + "</mc:Choice><mc:Fallback>" //
            + textShape(6, null, run("From the fallback")) + picture(7, "r:embed=\"" + RID + "\"") //
            + "</mc:Fallback></mc:AlternateContent>" //
            + "<mc:AlternateContent><mc:Choice Requires=\"a14\">" + textShape(8, null, run("An equation"))
            + textShape(21, null, run("Second of the choice")) + "</mc:Choice><mc:Choice Requires=\"p14\">"
            + textShape(22, null, run("Second choice")) + "</mc:Choice><mc:Fallback>"
            + textShape(9, null, run("Its fallback")) + "</mc:Fallback></mc:AlternateContent>" //
            + textShape(10, "title", run("Late"), run("title")) //
            + textShape(11, "title", run("Second title")) //
            + textShape(12, null, run("  padded  "), "", run(" ")) //
            + frame(13, "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"/>"
                    + "</a:graphic>") //
            + group(14, frame(15, TABLE_GRAPHIC) + picture(16, "r:embed=\"" + RID + "\"")) //
            + picture(17, "r:link=\"rId99\"") //
            + picture(18, "r:embed=\"" + RID + "\"") //
            + "<p:cxnSp><p:nvCxnSpPr><p:cNvPr id=\"19\" name=\"c\"/><p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr>"
            + "<p:spPr/></p:cxnSp>";

    @After
    public void deleteTempFiles() throws Exception {
        BlobAuditTestHelper.deleteTempFiles();
    }

    protected static String run(String text) {
        return "<a:r><a:t>" + text + "</a:t></a:r>";
    }

    protected static String cell(String text) {
        return "<a:tc><a:txBody><a:bodyPr/><a:p>" + (text.isEmpty() ? "" : run(text)) + "</a:p></a:txBody></a:tc>";
    }

    /** A text shape, one paragraph per argument, each holding the given runs. */
    protected static String textShape(int id, String placeholder, String... paragraphs) {
        StringBuilder xml = new StringBuilder("<p:sp><p:nvSpPr><p:cNvPr id=\"" + id + "\" name=\"s" + id + "\"/>"
                + "<p:cNvSpPr/><p:nvPr>");
        if (placeholder != null) {
            xml.append("<p:ph type=\"").append(placeholder).append("\"/>");
        }
        xml.append("</p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/>");
        for (String paragraph : paragraphs) {
            xml.append("<a:p>").append(paragraph).append("</a:p>");
        }
        return xml.append("</p:txBody></p:sp>").toString();
    }

    protected static String group(int id, String shapes) {
        return "<p:grpSp><p:nvGrpSpPr><p:cNvPr id=\"" + id + "\" name=\"g" + id + "\"/><p:cNvGrpSpPr/><p:nvPr/>"
                + "</p:nvGrpSpPr><p:grpSpPr/>" + shapes + "</p:grpSp>";
    }

    protected static String frame(int id, String graphic) {
        return "<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"" + id + "\" name=\"f" + id + "\"/>"
                + "<p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x=\"0\" y=\"0\"/>"
                + "<a:ext cx=\"100\" cy=\"100\"/></p:xfrm>" + graphic + "</p:graphicFrame>";
    }

    protected static String picture(int id, String blip) {
        return "<p:pic><p:nvPicPr><p:cNvPr id=\"" + id + "\" name=\"p" + id + "\"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>"
                + "<p:blipFill><a:blip " + blip + "/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr/>"
                + "</p:pic>";
    }

    protected static String slide(String shapes) {
        return "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
                + " xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
                + " xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\""
                + " xmlns:p14=\"http://schemas.microsoft.com/office/powerpoint/2010/main\""
                + " xmlns:a14=\"http://schemas.microsoft.com/office/drawing/2010/main\"><p:cSld><p:spTree>"
                + "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>"
                + shapes + "</p:spTree></p:cSld></p:sld>";
    }

    /**
     * Two slides built by POI from the template layouts - a centred title, which is not a title for
     * {@code XSLFSlide#getTitle()}, then a title, a body, a table, a group, pictures and notes - and a
     * third one replaced by {@code lastSlideShapes}.
     */
    protected static Blob deck(String filename, boolean withPoiSlides, String lastSlideShapes) throws Exception {
        byte[] bytes;
        String rid;
        int last;
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFPictureData png = show.addPicture(PNG, PictureType.PNG);
            if (withPoiSlides) {
                XSLFSlideMaster master = show.getSlideMasters().get(0);
                XSLFSlide opening = show.createSlide(master.getLayout(SlideLayout.TITLE));
                opening.getPlaceholder(0).setText("Opening");
                opening.getPlaceholder(1).setText("Subtitle");

                XSLFSlide budget = show.createSlide(master.getLayout(SlideLayout.TITLE_AND_CONTENT));
                budget.getPlaceholder(0).setText("Budget");
                budget.getPlaceholder(0).addNewTextParagraph().addNewTextRun().setText("Q1");
                XSLFTextShape body = budget.getPlaceholder(1);
                body.setText("First");
                XSLFTextParagraph second = body.addNewTextParagraph();
                second.addNewTextRun().setText("Second");
                second.addLineBreak();
                second.addNewTextRun().setText("line");
                body.addNewTextParagraph();
                XSLFTable table = budget.createTable();
                XSLFTableRow row = table.addRow();
                row.addCell().setText("Q1");
                row.addCell().setText("100");
                XSLFTableRow multi = table.addRow();
                XSLFTableCell cell = multi.addCell();
                cell.setText("multi");
                cell.addNewTextParagraph().addNewTextRun().setText("paragraph");
                multi.addCell();
                XSLFGroupShape group = budget.createGroup();
                group.createTextBox().setText("In a group");
                group.createPicture(png);
                budget.createPicture(png);
                budget.createPicture(show.addPicture(PNG_2, PictureType.PNG));
                budget.createConnector();
                XSLFNotes notes = show.getNotesSlide(budget);
                for (XSLFTextShape shape : notes.getPlaceholders()) {
                    if (shape.getTextType() == Placeholder.BODY) {
                        shape.setText("Speaker note");
                    }
                }
            }
            XSLFSlide replaced = show.createSlide();
            replaced.createPicture(png);
            rid = replaced.getRelationId(png);
            last = show.getSlides().size();
            show.write(out);
            bytes = out.toByteArray();
        }
        String xml = slide(lastSlideShapes.replace(RID, rid));
        bytes = BlobAuditTestHelper.rewrite(bytes, "ppt/slides/slide" + last + ".xml", original -> xml);
        return BlobAuditTestHelper.blob(bytes, PPTX_MIME, filename);
    }

    /** The former usermodel text extraction, the reference the streamed lines must match. */
    protected static List<String> usermodelText(Blob blob, boolean includeNotes) throws Exception {
        List<String> lines = new ArrayList<>();
        try (InputStream in = blob.getStream(); XMLSlideShow show = new XMLSlideShow(in)) {
            for (XSLFSlide slide : show.getSlides()) {
                String title = slide.getTitle();
                lines.add(SLIDE_MARKER + (title == null || title.isBlank() ? "" : ": " + title.trim()));
                usermodelShapes(slide.getShapes(), lines);
                XSLFNotes notes = includeNotes ? slide.getNotes() : null;
                if (notes != null) {
                    for (XSLFShape shape : notes.getShapes()) {
                        if (shape instanceof XSLFTextShape text && text.getTextType() == Placeholder.BODY) {
                            usermodelParagraphs(text, NOTES_PREFIX, lines);
                        }
                    }
                }
            }
        }
        return lines;
    }

    protected static void usermodelShapes(List<? extends XSLFShape> shapes, List<String> lines) {
        for (XSLFShape shape : shapes) {
            if (shape instanceof XSLFGroupShape group) {
                usermodelShapes(group.getShapes(), lines);
            } else if (shape instanceof XSLFTable table) {
                for (XSLFTableRow row : table.getRows()) {
                    List<String> cells = row.getCells()
                                            .stream()
                                            .map(XSLFTableCell::getText)
                                            .map(text -> text == null ? "" : text.replaceAll("\\s+", " ").trim())
                                            .toList();
                    if (cells.stream().anyMatch(text -> !text.isEmpty())) {
                        lines.add(TABLE_PREFIX + String.join(" | ", cells));
                    }
                }
            } else if (shape instanceof XSLFTextShape text) {
                usermodelParagraphs(text, "", lines);
            }
        }
    }

    protected static void usermodelParagraphs(XSLFTextShape shape, String prefix, List<String> lines) {
        for (XSLFTextParagraph paragraph : shape.getTextParagraphs()) {
            String text = paragraph.getText();
            if (text != null && !text.isBlank()) {
                lines.add(prefix + text.trim());
            }
        }
    }

    /** The former usermodel image inventory. */
    protected static List<ContentLine> usermodelImages(Blob blob) throws Exception {
        List<ContentLine> images = new ArrayList<>();
        try (InputStream in = blob.getStream(); XMLSlideShow show = new XMLSlideShow(in)) {
            int slideNumber = 0;
            for (XSLFSlide slide : show.getSlides()) {
                slideNumber++;
                usermodelPictures(slide.getShapes(), "powerpoint:slide:" + slideNumber + ":image:", new HashMap<>(),
                        images);
            }
        }
        return images;
    }

    protected static void usermodelPictures(List<? extends XSLFShape> shapes, String prefix, Map<String, Integer> seen,
            List<ContentLine> images) throws Exception {
        for (XSLFShape shape : shapes) {
            if (shape instanceof XSLFGroupShape group) {
                usermodelPictures(group.getShapes(), prefix, seen, images);
            } else if (shape instanceof XSLFPictureShape picture && picture.getPictureData() != null) {
                XSLFPictureData data = picture.getPictureData();
                int count = seen.merge(data.getFileName(), 1, Integer::sum);
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getData()));
                images.add(ContentLine.of(prefix + data.getFileName() + (count > 1 ? "#" + count : ""),
                        "sha256:" + digest));
            }
        }
    }

    protected static List<String> values(DiffableContent content) {
        return content.lines().stream().map(ContentLine::value).toList();
    }

    @Test
    public void testStreamedLinesAreTheUsermodelOnes() throws Exception {
        Blob blob = deck("rich.pptx", true, RICH_SHAPES);

        List<String> streamed = values(new PresentationExtractor().extract(onDisk(blob), MAX_LINES));

        assertEquals(usermodelText(blob, true), streamed);
        assertTrue(streamed.contains(SLIDE_MARKER));
        assertTrue(streamed.contains(SLIDE_MARKER + ": Budget\nQ1"));
        assertTrue(streamed.contains(SLIDE_MARKER + ": Late\ntitle"));
        assertTrue(streamed.contains(NOTES_PREFIX + "Speaker note"));
        assertTrue(streamed.contains("Page 3\nnext line"));
        assertTrue(streamed.contains("From the choice"));
        assertTrue(streamed.contains("An equation"));
        assertTrue(streamed.contains(TABLE_PREFIX + "In | a group"));
        assertFalse(streamed.contains("From the fallback"));
        assertFalse(streamed.contains("Second of the choice"));
        assertFalse(streamed.contains("Second choice"));
    }

    @Test
    public void testNotesCanBeLeftOut() throws Exception {
        Blob blob = deck("rich.pptx", true, RICH_SHAPES);
        PresentationExtractor extractor = new PresentationExtractor();
        extractor.init(Map.of("includeNotes", "false"));

        List<String> streamed = values(extractor.extract(blob, MAX_LINES));

        assertEquals(usermodelText(blob, false), streamed);
        assertFalse(streamed.contains(NOTES_PREFIX + "Speaker note"));
    }

    @Test
    public void testStreamedImagesAreTheUsermodelOnes() throws Exception {
        Blob blob = deck("rich.pptx", true, RICH_SHAPES);

        List<ContentLine> streamed = new ImageInventoryExtractor().extract(onDisk(blob), MAX_LINES).lines();

        assertEquals(usermodelImages(blob), streamed);
        assertEquals("the first choice, the group and the slide itself", 3,
                streamed.stream().filter(line -> line.key().startsWith("powerpoint:slide:3:")).count());
    }

    protected static String paragraphs(int count) {
        String[] paragraphs = new String[count];
        for (int i = 0; i < count; i++) {
            paragraphs[i] = run("Paragraph " + i);
        }
        return textShape(2, null, paragraphs);
    }

    protected static long allocated() {
        return ((ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    /** The point of streaming: one slide of 200 000 paragraphs, of which {@code maxLines} are wanted. */
    @Test
    public void testStreamingStopsAtMaxLines() throws Exception {
        Blob blob = onDisk(deck("big.pptx", false, paragraphs(200_000)));
        long before = allocated();

        DiffableContent content = new PresentationExtractor().extract(blob, 100);

        long allocated = allocated() - before;
        assertEquals(100, content.size());
        assertTrue(content.truncated());
        assertTrue("extracting 100 lines allocated " + allocated + " bytes", allocated < 32L * 1024 * 1024);
    }

    /** The image inventory reads the whole slide, but builds none of it. */
    @Test
    public void testTheImageInventoryDoesNotBuildTheSlide() throws Exception {
        Blob blob = onDisk(deck("big.pptx", false, paragraphs(200_000) + picture(3, "r:embed=\"" + RID + "\"")));
        long before = allocated();

        DiffableContent content = new ImageInventoryExtractor().extract(blob, MAX_LINES);

        long allocated = allocated() - before;
        assertEquals(1, content.size());
        assertTrue("listing one picture allocated " + allocated + " bytes", allocated < 32L * 1024 * 1024);
    }
}

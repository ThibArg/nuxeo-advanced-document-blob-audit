/*
 * (C) Copyright 2026 Nuxeo SA (http://nuxeo.com/) and others.
 * Licensed under the Apache License, Version 2.0.
 */
package org.nuxeo.audit.advanced.blob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.Test;
import org.nuxeo.audit.advanced.blob.extractor.PresentationExtractor;
import org.nuxeo.audit.advanced.blob.image.ImageInventoryExtractor;
import org.nuxeo.ecm.core.api.Blob;

/**
 * PowerPoint (.pptx) text and image diff. No runtime needed: decks are generated in memory with POI.
 *
 * @since 1.1
 */
public class TestPresentationDiff {

    protected static final int MAX_LINES = 10000;

    protected static final byte[] PNG_A = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    protected static final byte[] PNG_B = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    protected final PresentationExtractor extractor = new PresentationExtractor();

    protected final ImageInventoryExtractor images = new ImageInventoryExtractor();

    protected final TextDiffer differ = new TextDiffer();

    /** Each slide spec is "text1|text2|..."; a text starting with "img:" adds a picture (A or B). */
    protected Blob pptx(String filename, String... slides) throws Exception {
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String spec : slides) {
                XSLFSlide slide = show.createSlide();
                for (String part : spec.split("\\|")) {
                    if (part.startsWith("img:")) {
                        XSLFPictureData data = show.addPicture("A".equals(part.substring(4)) ? PNG_A : PNG_B,
                                PictureData.PictureType.PNG);
                        slide.createPicture(data);
                    } else if (part.startsWith("table:")) {
                        XSLFTable table = slide.createTable();
                        XSLFTableRow row = table.addRow();
                        for (String cell : part.substring(6).split(",")) {
                            row.addCell().setText(cell);
                        }
                    } else if (!part.isEmpty()) {
                        XSLFTextBox box = slide.createTextBox();
                        box.setText(part);
                    }
                }
            }
            show.write(out);
            return BlobAuditTestHelper.blob(out.toByteArray(), BlobAuditTestHelper.PPTX_MIME, filename);
        }
    }

    protected DiffResult textDiff(Blob before, Blob after) throws Exception {
        return differ.diff(extractor.extract(before, MAX_LINES), extractor.extract(after, MAX_LINES));
    }

    protected DiffResult imageDiff(Blob before, Blob after) throws Exception {
        return differ.diff(images.extract(before, MAX_LINES), images.extract(after, MAX_LINES));
    }

    @Test
    public void testContentIsPositionalWithSlideMarkers() throws Exception {
        DiffableContent content = extractor.extract(pptx("f.pptx", "Hello", "World"), MAX_LINES);
        assertFalse(content.keyed());
        assertEquals(4, content.size());
        assertTrue(content.lines().get(0).value().startsWith(PresentationExtractor.SLIDE_MARKER));
        assertEquals("Hello", content.lines().get(1).value());
    }

    @Test
    public void testIdenticalDecksYieldNoDiff() throws Exception {
        assertTrue(textDiff(pptx("a.pptx", "Same|Text"), pptx("b.pptx", "Same|Text")).isEmpty());
    }

    @Test
    public void testParagraphModificationIsDetected() throws Exception {
        DiffResult result = textDiff(pptx("a.pptx", "Intro", "Budget is 1000"), pptx("b.pptx", "Intro", "Budget is 2000"));
        assertEquals(1, result.changed());
        assertEquals(0, result.added());
        assertEquals(0, result.removed());
        assertTrue(result.unified().contains("- Budget is 1000"));
        assertTrue(result.unified().contains("+ Budget is 2000"));
    }

    /** Slide numbers are not part of the content: inserting a slide must not cascade. */
    @Test
    public void testInsertingASlideDoesNotCascade() throws Exception {
        DiffResult result = textDiff(pptx("a.pptx", "One", "Two", "Three"),
                pptx("b.pptx", "One", "Inserted", "Two", "Three"));
        assertEquals(0, result.changed());
        assertEquals(0, result.removed());
        assertEquals(2, result.added()); // slide marker + its paragraph
        assertTrue(result.unified().contains("+ Inserted"));
    }

    @Test
    public void testDeletingASlide() throws Exception {
        DiffResult result = textDiff(pptx("a.pptx", "One", "Two"), pptx("b.pptx", "One"));
        assertEquals(2, result.removed());
        assertTrue(result.unified().contains("- Two"));
    }

    @Test
    public void testTableCellChange() throws Exception {
        DiffResult result = textDiff(pptx("a.pptx", "table:Q1,100"), pptx("b.pptx", "table:Q1,120"));
        assertEquals(1, result.changed());
        assertTrue(result.unified().contains("[table] Q1 | 120"));
    }

    @Test
    public void testTruncation() throws Exception {
        DiffableContent content = extractor.extract(pptx("f.pptx", "a|b|c", "d|e|f"), 3);
        assertEquals(3, content.size());
        assertTrue(content.truncated());
    }

    /* ------------------------------------------------------------ images */

    @Test
    public void testImageIsInventoriedBySlide() throws Exception {
        DiffableContent content = images.extract(pptx("f.pptx", "Title|img:A"), MAX_LINES);
        assertTrue(content.keyed());
        assertEquals(1, content.size());
        assertTrue(content.lines().get(0).key().startsWith("powerpoint:slide:1:image:"));
        assertTrue(content.lines().get(0).value().startsWith("sha256:"));
    }

    @Test
    public void testImageAddedOnASlide() throws Exception {
        DiffResult result = imageDiff(pptx("a.pptx", "Title"), pptx("b.pptx", "Title|img:A"));
        assertEquals(1, result.added());
        assertTrue(result.unified().contains("+ powerpoint:slide:1:image:"));
    }

    @Test
    public void testImageRemovedFromASlide() throws Exception {
        DiffResult result = imageDiff(pptx("a.pptx", "Title", "Other|img:A"), pptx("b.pptx", "Title", "Other"));
        assertEquals(1, result.removed());
        assertTrue(result.unified().contains("- powerpoint:slide:2:image:"));
    }

    @Test
    public void testImageReplacedOnASlide() throws Exception {
        DiffResult result = imageDiff(pptx("a.pptx", "Title|img:A"), pptx("b.pptx", "Title|img:B"));
        assertEquals(1, result.changed());
    }

    @Test
    public void testSameMediaTwiceOnOneSlideIsNotCollapsed() throws Exception {
        DiffableContent content = images.extract(pptx("f.pptx", "img:A|img:A"), MAX_LINES);
        assertEquals(2, content.size());
    }

    @Test
    public void testIdenticalImagesYieldNoDiff() throws Exception {
        assertTrue(imageDiff(pptx("a.pptx", "img:A"), pptx("b.pptx", "img:A")).isEmpty());
    }
}

/* (C) Copyright 2026 Nuxeo SA and others. Licensed under Apache License 2.0. */
package org.nuxeo.audit.advanced.blob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.Test;
import org.nuxeo.audit.advanced.blob.image.ImageInventoryExtractor;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;

public class TestImageInventoryExtractor {

    protected static final int MAX_LINES = 1000;

    protected static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    /** A second, byte-wise different 1x1 PNG, so that two media parts can coexist in a fixture. */
    protected static final byte[] PNG_2 = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    protected final ImageInventoryExtractor extractor = new ImageInventoryExtractor();

    protected Blob docx(String filename, byte[]... images) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (byte[] image : images) {
                document.createParagraph().createRun().addPicture(new ByteArrayInputStream(image),
                        Document.PICTURE_TYPE_PNG, filename + "-" + image.length + ".png", 9525, 9525);
            }
            document.write(out);
            return BlobAuditTestHelper.blob(out.toByteArray(), BlobAuditTestHelper.DOCX_MIME, filename);
        }
    }

    /**
     * File-backed on purpose: this is what {@code BlobDiffComponent#diff} hands to the extractor
     * once the binaries are materialised, and it is the path that reads the PDF straight from disk
     * instead of loading it whole in heap.
     */
    protected Blob pdf(String filename, byte[]... images) throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (byte[] image : images) {
                PDPage page = new PDPage();
                document.addPage(page);
                PDImageXObject object = PDImageXObject.createFromByteArray(document, image, "img");
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.drawImage(object, 10, 10, 50, 50);
                }
            }
            File file = File.createTempFile("blobdiff-test-", ".pdf");
            file.deleteOnExit();
            document.save(file);
            return new FileBlob(file, "application/pdf", null, filename, null);
        }
    }

    @Test
    public void testWordImageIsInventoriedByMediaPart() throws Exception {
        DiffableContent content = extractor.extract(docx("image.docx", PNG), MAX_LINES);
        assertEquals(1, content.size());
        assertTrue(content.lines().get(0).key().startsWith("word:image:"));
        assertTrue(content.lines().get(0).value().startsWith("sha256:"));
    }

    @Test
    public void testExcelImageUsesSheetAndAnchor() throws Exception {
        Blob blob;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Budget");
            CreationHelper helper = workbook.getCreationHelper();
            ClientAnchor anchor = helper.createClientAnchor();
            anchor.setCol1(1); anchor.setRow1(3); anchor.setCol2(5); anchor.setRow2(17);
            sheet.createDrawingPatriarch().createPicture(anchor, workbook.addPicture(PNG, XSSFWorkbook.PICTURE_TYPE_PNG));
            workbook.write(out);
            blob = BlobAuditTestHelper.blob(out.toByteArray(), BlobAuditTestHelper.XLSX_MIME, "image.xlsx");
        }
        DiffableContent content = extractor.extract(blob, MAX_LINES);
        assertEquals(1, content.size());
        assertEquals("excel:image:Budget:B4:F18", content.lines().get(0).key());
    }

    @Test
    public void testKeyedDiffReportsReplacement() throws Exception {
        DiffableContent before = DiffableContent.keyed(List.of(ContentLine.of("word:image:image1.png", "sha256:a")), false);
        DiffableContent after = DiffableContent.keyed(List.of(ContentLine.of("word:image:image1.png", "sha256:b")), false);
        DiffResult result = new TextDiffer().diff(before, after);
        assertEquals(1, result.changed());
    }

    /* ==================== maxLines ==================== */

    /**
     * The inventory used to be bounded by nothing at all: a deck or a PDF with thousands of images
     * produced as many entries, whatever {@code maxLines} said (item 10).
     */
    @Test
    public void testWordInventoryIsBoundedByMaxLines() throws Exception {
        Blob blob = docx("two.docx", PNG, PNG_2);

        assertEquals(2, extractor.extract(blob, MAX_LINES).size());

        DiffableContent truncated = extractor.extract(blob, 1);
        assertEquals(1, truncated.size());
        assertTrue(truncated.truncated());
    }

    @Test
    public void testPdfInventoryIsBoundedByMaxLines() throws Exception {
        Blob blob = pdf("two.pdf", PNG, PNG_2);

        assertEquals(2, extractor.extract(blob, MAX_LINES).size());

        DiffableContent truncated = extractor.extract(blob, 1);
        assertEquals(1, truncated.size());
        assertTrue(truncated.truncated());
    }

    @Test
    public void testUntruncatedInventoryIsNotFlagged() throws Exception {
        assertFalse(extractor.extract(docx("one.docx", PNG), MAX_LINES).truncated());
    }

    /* ==================== PDF ==================== */

    @Test
    public void testPdfImageIsInventoriedByPage() throws Exception {
        DiffableContent content = extractor.extract(pdf("image.pdf", PNG, PNG_2), MAX_LINES);

        assertTrue(content.keyed());
        assertEquals(2, content.size());
        assertEquals("pdf:page:1:image:1", content.lines().get(0).key());
        assertEquals("pdf:page:2:image:1", content.lines().get(1).key());
        assertTrue(content.lines().get(0).value().startsWith("sha256:"));
    }

    @Test
    public void testPdfImageReplacementIsReported() throws Exception {
        DiffResult result = new TextDiffer().diff(extractor.extract(pdf("a.pdf", PNG), MAX_LINES),
                extractor.extract(pdf("b.pdf", PNG_2), MAX_LINES));

        assertEquals(1, result.changed());
    }

    @Test
    public void testUnsupportedMimeTypeYieldsAnEmptyInventory() throws Exception {
        Blob blob = BlobAuditTestHelper.textBlob("hello", "text/plain", "f.txt");

        assertEquals(0, extractor.extract(blob, MAX_LINES).size());
    }

    /* ==================== Budget on a single image (SEC-02, SEC-04) ==================== */

    /**
     * The extractor with a lowered ceiling, so a fixture can cross it without the test having to
     * build - and the test JVM to inflate - a real multi-gigabyte bomb.
     */
    protected static class CappedExtractor extends ImageInventoryExtractor {

        protected final long cap;

        protected CappedExtractor(long cap) {
            this.cap = cap;
        }

        @Override
        protected long maxImageBytes() {
            return cap;
        }
    }

    /** A real, incompressible-enough PNG of the requested square size. */
    protected static byte[] png(int size) throws Exception {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                image.setRGB(x, y, (x * 31 + y * 17) & 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /**
     * SEC-02: a {@code word/media/} part used to go through {@code zip.readAllBytes()}, which
     * allocates an array of the <b>inflated</b> size with no ceiling whatsoever.
     * <p>
     * {@code getEligibility} only ever saw the deflated size of the container, so 10 MB of zip
     * passing {@code maxBlobSize} could expand to gigabytes here - twice over, the queue running two
     * works, and twice more through {@code getRetryCount()}. The fixture keeps the same shape at a
     * scale a test can afford: 64 KB of zeros, which deflates to near nothing.
     */
    @Test
    public void testAnOverSizedWordImageIsDroppedInsteadOfBuffered() throws Exception {
        Blob blob = docx("bomb.docx", new byte[64 * 1024], PNG);

        assertEquals("the fixture must hold two media parts at default settings", 2,
                extractor.extract(blob, MAX_LINES).size());

        DiffableContent content = new CappedExtractor(1024).extract(blob, MAX_LINES);

        assertEquals("the over-sized part must be dropped and the normal one kept", 1, content.size());
        assertTrue("dropping a part must be reported as a truncation", content.truncated());
    }

    /**
     * SEC-04: {@code image.createInputStream()} hands back the stream PDFBox has already
     * <b>decoded</b>, and the previous {@code transferTo} into a {@code ByteArrayOutputStream}
     * followed by {@code toByteArray()} held two full copies of it in heap - for an image whose
     * size the PDF alone declares.
     */
    @Test
    public void testAnOverSizedPdfImageIsDroppedInsteadOfBuffered() throws Exception {
        Blob blob = pdf("bomb.pdf", png(64));

        assertEquals("the fixture must hold one image at default settings", 1,
                extractor.extract(blob, MAX_LINES).size());

        DiffableContent content = new CappedExtractor(256).extract(blob, MAX_LINES);

        assertEquals("an image over the budget must be dropped", 0, content.size());
        assertTrue("dropping an image must be reported as a truncation", content.truncated());
    }
}

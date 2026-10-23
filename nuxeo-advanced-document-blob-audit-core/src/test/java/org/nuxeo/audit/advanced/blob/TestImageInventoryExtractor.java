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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.After;
import org.junit.Test;
import org.nuxeo.audit.advanced.blob.image.ImageInventoryExtractor;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.runtime.api.Framework;

import com.sun.management.ThreadMXBean;

public class TestImageInventoryExtractor {

    protected static final int MAX_LINES = 1000;

    /**
     * Temporary PDFs built by {@link #pdf(String, byte[]...)}, deleted after each test.
     * <p>
     * Not {@code deleteOnExit()}, which the Nuxeo guidelines rule out, and not
     * {@code Framework.trackFile} either: that resolves {@code EventService} and needs a running
     * runtime, which this pure-JUnit test does not have.
     */
    protected final List<File> tempFiles = new ArrayList<>();

    @After
    public void deleteTempFiles() throws Exception {
        for (File file : tempFiles) {
            Files.deleteIfExists(file.toPath());
        }
        tempFiles.clear();
    }

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
            return save(document, filename);
        }
    }

    /** Saves the document as a file-backed PDF blob, deleted after the test. */
    protected Blob save(PDDocument document, String filename) throws Exception {
        File file = Framework.createTempFile("blobdiff-test-", ".pdf");
        tempFiles.add(file);
        document.save(file);
        return new FileBlob(file, "application/pdf", null, filename, null);
    }

    /** Resources naming each XObject once, in order. */
    protected static PDResources resources(PDXObject... xobjects) {
        PDResources resources = new PDResources();
        for (int i = 0; i < xobjects.length; i++) {
            resources.put(COSName.getPDFName("X" + i), xobjects[i]);
        }
        return resources;
    }

    protected static PDFormXObject form(PDDocument document, PDXObject... xobjects) {
        PDFormXObject form = new PDFormXObject(document);
        form.setBBox(new PDRectangle(10, 10));
        form.setResources(resources(xobjects));
        return form;
    }

    protected static void addPage(PDDocument document, PDXObject... xobjects) {
        PDPage page = new PDPage();
        page.setResources(resources(xobjects));
        document.addPage(page);
    }

    /** A one-page PDF holding a 1x1 grey image whose Flate stream inflates to {@code inflatedSize} zeros. */
    protected Blob pdfWithFlateImage(String filename, long inflatedSize) throws Exception {
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(deflated)) {
            byte[] zeros = new byte[64 * 1024];
            for (long written = 0; written < inflatedSize; written += zeros.length) {
                out.write(zeros);
            }
        }
        try (PDDocument document = new PDDocument()) {
            COSStream stream = document.getDocument().createCOSStream();
            try (OutputStream raw = stream.createRawOutputStream()) {
                raw.write(deflated.toByteArray());
            }
            stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
            stream.setInt(COSName.WIDTH, 1);
            stream.setInt(COSName.HEIGHT, 1);
            stream.setInt(COSName.BITS_PER_COMPONENT, 8);
            stream.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(new PDImageXObject(new PDStream(stream), null), 10, 10, 50, 50);
            }
            return save(document, filename);
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
            sheet.createDrawingPatriarch()
                 .createPicture(anchor, workbook.addPicture(PNG, XSSFWorkbook.PICTURE_TYPE_PNG));
            workbook.write(out);
            blob = BlobAuditTestHelper.blob(out.toByteArray(), BlobAuditTestHelper.XLSX_MIME, "image.xlsx");
        }
        DiffableContent content = extractor.extract(blob, MAX_LINES);
        assertEquals(1, content.size());
        assertEquals("excel:image:Budget:B4:F18", content.lines().get(0).key());
    }

    @Test
    public void testKeyedDiffReportsReplacement() throws Exception {
        DiffableContent before = DiffableContent.keyed(
                List.of(ContentLine.of("word:image:image1.png", "sha256:a")), false);
        DiffableContent after = DiffableContent.keyed(
                List.of(ContentLine.of("word:image:image1.png", "sha256:b")), false);
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

    /** The encoding parameters are part of the digest: their rendering must not depend on the file. */
    @Test
    public void testTheSamePdfImageInTwoFilesIsNotReportedAsChanged() throws Exception {
        DiffResult result = new TextDiffer().diff(extractor.extract(pdf("a.pdf", PNG), MAX_LINES),
                extractor.extract(pdf("b.pdf", PNG), MAX_LINES));

        assertTrue(result.isEmpty());
    }

    /**
     * RES-01 (code review 2026-10): PDFBox decodes a whole image stream into heap before handing back
     * its first byte, so the budget used to apply only after the allocation it was meant to prevent.
     * A 1x1 image over 64 MB of Flate-compressed zeros was inflated whole, then dropped. It is now
     * identified by its encoded bytes - about 64 KB - and never decoded.
     */
    @Test
    public void testAPdfFlateBombIsDigestedWithoutBeingDecoded() throws Exception {
        Blob blob = pdfWithFlateImage("bomb.pdf", 64L * 1024 * 1024);
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();

        DiffableContent content = extractor.extract(blob, MAX_LINES);

        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertEquals("the image must be inventoried from its encoded bytes, not dropped", 1, content.size());
        assertFalse(content.truncated());
        assertTrue("extracting the inventory allocated " + allocated + " bytes", allocated < 16L * 1024 * 1024);
    }

    /**
     * RES-02 (code review 2026-10): a form XObject naming itself in its own resources recursed until
     * {@code StackOverflowError}, an {@code Error} no work catches, so the diff vanished without a
     * trace. The loop is now cut, the rest of the page still inventoried, and the inventory flagged
     * partial.
     */
    @Test
    public void testAPdfFormThatContainsItselfIsWalkedOnce() throws Exception {
        Blob blob;
        try (PDDocument document = new PDDocument()) {
            PDFormXObject form = form(document);
            form.getResources().put(COSName.getPDFName("Self"), form);
            addPage(document, form, PDImageXObject.createFromByteArray(document, PNG, "img"));
            blob = save(document, "loop.pdf");
        }

        DiffableContent content = extractor.extract(blob, MAX_LINES);

        assertEquals(1, content.size());
        assertTrue("a cut loop leaves a partial inventory", content.truncated());
    }

    /**
     * RES-02, second half: eight references to the same form at each of ten levels make 8^10 paths
     * through a few kilobytes of PDF, and the walk held a worker for as long as it took. Each form is
     * now walked once.
     */
    @Test(timeout = 10_000)
    public void testABranchingPdfFormGraphIsWalkedOncePerForm() throws Exception {
        Blob blob;
        try (PDDocument document = new PDDocument()) {
            PDFormXObject form = form(document);
            for (int level = 0; level < 10; level++) {
                form = form(document, Collections.nCopies(8, form).toArray(new PDXObject[0]));
            }
            addPage(document, form);
            blob = save(document, "branching.pdf");
        }

        DiffableContent content = extractor.extract(blob, MAX_LINES);

        assertEquals(0, content.size());
        assertFalse(content.truncated());
    }

    /** A form shared by every page - a letterhead, a background - is walked once and listed on each. */
    @Test
    public void testAPdfFormSharedByTwoPagesIsInventoriedOnEach() throws Exception {
        Blob blob;
        try (PDDocument document = new PDDocument()) {
            PDFormXObject letterhead = form(document, PDImageXObject.createFromByteArray(document, PNG, "img"));
            addPage(document, letterhead);
            addPage(document, letterhead);
            blob = save(document, "shared.pdf");
        }

        List<ContentLine> lines = extractor.extract(blob, MAX_LINES).lines();

        assertEquals(List.of("pdf:page:1:image:1", "pdf:page:2:image:1"),
                lines.stream().map(ContentLine::key).toList());
        assertEquals(lines.get(0).value(), lines.get(1).value());
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

    /** A real PNG of the requested square size, filled with seeded noise so that it does not compress. */
    protected static byte[] png(int size) throws Exception {
        Random random = new Random(size);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
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
     * size the PDF alone declares. Since RES-01 the budget applies to the encoded stream, which the
     * noise of the fixture keeps well above the lowered cap.
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

    /**
     * The same budget, on the two formats it was never applied to.
     * <p>
     * The DOCX and PDF paths dropped an entry past {@link ImageInventoryExtractor#maxImageBytes()};
     * the XLSX and PPTX ones called {@code PictureData#getData()}, which materialises the whole
     * inflated image. POI caps that at its own {@code MAX_IMAGE_SIZE} - 100 MB in 5.5.1 - so it was
     * never the unbounded allocation SEC-02 was, but it was three times this extractor's budget and
     * it made the budget mean two different things depending on the container.
     * <p>
     * The fixture keeps the same shape at a scale a test can afford: 64 KB against a 1 KB cap.
     */
    @Test
    public void testAnOverSizedSpreadsheetImageIsDroppedInsteadOfBuffered() throws Exception {
        Blob blob = xlsxWithPictures("bomb.xlsx", new byte[64 * 1024], PNG);

        assertEquals("the fixture must hold two pictures at default settings", 2,
                extractor.extract(blob, MAX_LINES).size());

        DiffableContent content = new CappedExtractor(1024).extract(blob, MAX_LINES);

        assertEquals("the over-sized picture must be dropped and the normal one kept", 1, content.size());
        assertTrue("dropping a picture must be reported as a truncation", content.truncated());
    }

    /** @see #testAnOverSizedSpreadsheetImageIsDroppedInsteadOfBuffered */
    @Test
    public void testAnOverSizedPresentationImageIsDroppedInsteadOfBuffered() throws Exception {
        Blob blob = pptxWithPictures("bomb.pptx", new byte[64 * 1024], PNG);

        assertEquals("the fixture must hold two pictures at default settings", 2,
                extractor.extract(blob, MAX_LINES).size());

        DiffableContent content = new CappedExtractor(1024).extract(blob, MAX_LINES);

        assertEquals("the over-sized picture must be dropped and the normal one kept", 1, content.size());
        assertTrue("dropping a picture must be reported as a truncation", content.truncated());
    }

    /**
     * A dropped picture must not renumber the ones after it: the PPTX key carries a {@code #2}
     * occurrence counter, and the XLSX key an ordinal when the shape has no anchor. Shifting either
     * would make every following placement look changed on one side of a diff.
     */
    @Test
    public void testDroppingAPictureDoesNotRenumberTheFollowingOnes() throws Exception {
        Blob blob = pptxWithPictures("mixed.pptx", new byte[64 * 1024], PNG);

        List<String> full = new CappedExtractor(128 * 1024).extract(blob, MAX_LINES)
                                                           .lines()
                                                           .stream()
                                                           .map(ContentLine::key)
                                                           .toList();
        List<String> capped = new CappedExtractor(1024).extract(blob, MAX_LINES)
                                                       .lines()
                                                       .stream()
                                                       .map(ContentLine::key)
                                                       .toList();

        assertEquals(2, full.size());
        assertEquals("the surviving key must be identical to the one it has when nothing is dropped",
                List.of(full.get(1)), capped);
    }

    protected Blob xlsxWithPictures(String filename, byte[]... images) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Budget");
            CreationHelper helper = workbook.getCreationHelper();
            var drawing = sheet.createDrawingPatriarch();
            int row = 0;
            for (byte[] image : images) {
                ClientAnchor anchor = helper.createClientAnchor();
                anchor.setCol1(0);
                anchor.setRow1(row);
                anchor.setCol2(2);
                anchor.setRow2(row + 4);
                row += 5;
                drawing.createPicture(anchor, workbook.addPicture(image, XSSFWorkbook.PICTURE_TYPE_PNG));
            }
            workbook.write(out);
            return BlobAuditTestHelper.blob(out.toByteArray(), BlobAuditTestHelper.XLSX_MIME, filename);
        }
    }

    protected Blob pptxWithPictures(String filename, byte[]... images) throws Exception {
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFSlide slide = show.createSlide();
            for (byte[] image : images) {
                slide.createPicture(show.addPicture(image, PictureType.PNG));
            }
            show.write(out);
            return BlobAuditTestHelper.blob(out.toByteArray(), BlobAuditTestHelper.PPTX_MIME, filename);
        }
    }
}

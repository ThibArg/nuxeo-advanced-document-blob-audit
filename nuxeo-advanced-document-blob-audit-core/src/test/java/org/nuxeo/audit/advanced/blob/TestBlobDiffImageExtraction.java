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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.audit.advanced.blob.image.ImageInventoryExtractor;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.impl.blob.ByteArrayBlob;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

/**
 * Covers item 10: the binaries are materialised once for the whole diff, and the image inventory
 * goes through the {@code imageExtractors} extension point instead of being hardcoded.
 *
 * @since 2025.1
 */
@RunWith(FeaturesRunner.class)
@Features(BlobAuditFeature.class)
@Deploy("nuxeo-advanced-document-blob-audit-core:blobaudit-test-imagelevel-config.xml")
public class TestBlobDiffImageExtraction {

    protected static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    protected static final byte[] PNG_2 = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Inject
    protected BlobDiffService blobDiffService;

    /* ==================== fixtures ==================== */

    /** A blob counting how many times its content is read from the source. */
    protected static class CountingBlob extends ByteArrayBlob {

        private static final long serialVersionUID = 1L;

        protected final transient AtomicInteger reads = new AtomicInteger();

        public CountingBlob(byte[] bytes, String mimeType, String filename) {
            super(bytes, mimeType);
            setFilename(filename);
        }

        @Override
        public InputStream getStream() {
            reads.incrementAndGet();
            return super.getStream();
        }
    }

    protected byte[] docxBytes(String paragraph, byte[] image) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(paragraph);
            document.createParagraph().createRun().addPicture(new ByteArrayInputStream(image),
                    Document.PICTURE_TYPE_PNG, "logo.png", 9525, 9525);
            document.write(out);
            return out.toByteArray();
        }
    }

    protected Blob docx(String filename, String paragraph, byte[] image) throws Exception {
        return BlobAuditTestHelper.blob(docxBytes(paragraph, image), BlobAuditTestHelper.DOCX_MIME, filename);
    }

    protected BlobDiffComponent component() {
        return (BlobDiffComponent) blobDiffService;
    }

    /* ==================== extension point ==================== */

    @Test
    public void testImageExtractorsArePluggedOnTheExtensionPoint() {
        assertNotNull("the Word image inventory must be contributed, not hardcoded",
                component().findImageExtractor(BlobAuditTestHelper.DOCX_MIME));
        assertNotNull(component().findImageExtractor(BlobAuditTestHelper.XLSX_MIME));
        assertNotNull(component().findImageExtractor(BlobAuditTestHelper.PPTX_MIME));
        assertNotNull(component().findImageExtractor("application/pdf"));
    }

    @Test
    public void testContributedImageExtractorIsTheInventoryOne() {
        assertTrue(component().findImageExtractor("application/pdf") instanceof ImageInventoryExtractor);
    }

    /** A format with no image inventory must not fall back on a catch-all. */
    @Test
    public void testUncoveredMimeTypeHasNoImageExtractor() {
        assertNull(component().findImageExtractor("text/plain"));
        assertNull(component().findImageExtractor(null));
    }

    /** Per-format disabling is what the extension point buys over the hardcoded instance. */
    @Test
    @Deploy("nuxeo-advanced-document-blob-audit-core:blobaudit-test-noimagepdf-config.xml")
    public void testAnImageExtractorCanBeDisabledPerFormat() {
        assertNull(component().findImageExtractor("application/pdf"));
        assertNotNull("disabling one format must not disable the others",
                component().findImageExtractor(BlobAuditTestHelper.DOCX_MIME));
    }

    /* ==================== diff ==================== */

    @Test
    public void testImageInventoryIsMergedIntoTheTextDiff() throws Exception {
        DiffResult result = blobDiffService.diff(docx("a.docx", "Same text", PNG),
                docx("b.docx", "Same text", PNG_2));

        assertNotNull(result);
        assertTrue("the image section must be merged into the unified diff",
                result.unified().contains("# Images"));
        assertTrue(result.unified().contains("word:image:"));
        assertEquals(1, result.changed());
    }

    @Test
    public void testIdenticalImagesProduceNoImageSection() throws Exception {
        DiffResult result = blobDiffService.diff(docx("a.docx", "Before", PNG), docx("b.docx", "After", PNG));

        assertNotNull(result);
        assertFalse(result.unified().contains("# Images"));
    }

    /**
     * COR-01 leaves this path untouched, and that is deliberate: the image inventory is a bonus,
     * the text diff is the point. A failing <i>text</i> extraction raises, a failing image
     * inventory degrades to the text result with a WARN.
     * <p>
     * The same fixture produces an image section when the inventory works
     * ({@link #testImageInventoryIsMergedIntoTheTextDiff}), so its absence here is the failure
     * being absorbed, not two identical images.
     */
    @Test
    @Deploy("nuxeo-advanced-document-blob-audit-core:blobaudit-test-failingimage-contrib.xml")
    public void testAFailingImageInventoryStillYieldsTheTextDiff() throws Exception {
        assertTrue("the failing inventory must win the Word mime type",
                component().findImageExtractor(BlobAuditTestHelper.DOCX_MIME) instanceof FailingImageExtractor);

        DiffResult result = blobDiffService.diff(docx("a.docx", "Before", PNG), docx("b.docx", "After", PNG_2));

        assertNotNull("a failing image inventory must not lose the text diff", result);
        assertFalse(result.unified().contains("# Images"));
        assertTrue(result.unified().contains("Before"));
        assertTrue(result.unified().contains("After"));
    }

    /* ==================== single materialisation ==================== */

    /**
     * The regression item 10 fixes: with the image inventory on, the text extractor and the image
     * extractor each used to open their own stream, so a pair of S3-backed blobs meant four
     * downloads for two files.
     */
    @Test
    public void testEachBinaryIsReadOnceWhateverTheNumberOfExtractionPasses() throws Exception {
        CountingBlob oldBlob = new CountingBlob(docxBytes("Same text", PNG), BlobAuditTestHelper.DOCX_MIME, "a.docx");
        CountingBlob newBlob = new CountingBlob(docxBytes("Same text", PNG_2), BlobAuditTestHelper.DOCX_MIME,
                "b.docx");

        DiffResult result = blobDiffService.diff(oldBlob, newBlob);

        assertTrue("the fixture must actually exercise the image inventory",
                result.unified().contains("# Images"));
        assertEquals("the old binary must be read exactly once", 1, oldBlob.reads.get());
        assertEquals("the new binary must be read exactly once", 1, newBlob.reads.get());
    }
}

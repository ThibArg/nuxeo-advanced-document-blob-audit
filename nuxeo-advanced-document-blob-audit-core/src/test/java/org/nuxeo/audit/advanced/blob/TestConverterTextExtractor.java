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

import java.io.ByteArrayOutputStream;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.audit.advanced.blob.extractor.ConverterTextExtractor;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.convert.api.ConversionService;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

/**
 * Tests for the Word / PDF path.
 * <p>
 * This extractor owns no format-specific code: it delegates to the platform {@code any2text}
 * converter. The tests therefore check the <em>contract</em> (paragraph-level positional content,
 * graceful degradation) rather than the extraction quality, which belongs to the converter.
 * <p>
 * Each test is guarded by an assumption: {@code any2text} depends on the convert bundles and, for
 * some formats, on external tools. A missing converter must skip the test, not fail the build.
 *
 * @since 1.0
 */
@RunWith(FeaturesRunner.class)
@Features(BlobAuditFeature.class)
@Deploy("org.nuxeo.ecm.core.convert")
@Deploy("org.nuxeo.ecm.core.convert.plugins")
public class TestConverterTextExtractor {

    protected static final int MAX_LINES = 10000;

    @Inject
    protected ConversionService conversionService;

    protected final ConverterTextExtractor extractor = new ConverterTextExtractor();

    protected final TextDiffer differ = new TextDiffer();

    protected void assumeConverterAvailable() {
        Assume.assumeTrue("any2text converter is not available in this test setup",
                conversionService.isConverterAvailable(ConverterTextExtractor.ANY_2_TEXT, true).isAvailable());
    }

    /** Builds a .docx with one paragraph per given string. */
    protected Blob docx(String filename, String... paragraphs) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String paragraph : paragraphs) {
                document.createParagraph().createRun().setText(paragraph);
            }
            document.write(out);
            return BlobAuditTestHelper.blob(out.toByteArray(), BlobAuditTestHelper.DOCX_MIME, filename);
        }
    }

    @Test
    public void testWordContentIsPositionalNotKeyed() throws Exception {
        assumeConverterAvailable();

        DiffableContent content = extractor.extract(docx("f.docx", "First paragraph", "Second paragraph"),
                MAX_LINES);

        assertFalse("Word content has no stable unit identity, it must stay positional", content.keyed());
        assertTrue(content.size() >= 2);
    }

    @Test
    public void testWordParagraphModificationIsDetected() throws Exception {
        assumeConverterAvailable();

        Blob before = docx("before.docx", "Introduction", "The amount is 1000 euros", "Conclusion");
        Blob after = docx("after.docx", "Introduction", "The amount is 2000 euros", "Conclusion");

        DiffResult result = differ.diff(extractor.extract(before, MAX_LINES), extractor.extract(after, MAX_LINES));

        assertEquals(1, result.changed());
        assertEquals(0, result.added());
        assertEquals(0, result.removed());
        assertTrue(result.unified().contains("1000"));
        assertTrue(result.unified().contains("2000"));
    }

    @Test
    public void testWordParagraphAppended() throws Exception {
        assumeConverterAvailable();

        Blob before = docx("before.docx", "Alpha", "Beta");
        Blob after = docx("after.docx", "Alpha", "Beta", "Gamma");

        DiffResult result = differ.diff(extractor.extract(before, MAX_LINES), extractor.extract(after, MAX_LINES));

        assertEquals(1, result.added());
        assertTrue(result.unified().contains("Gamma"));
    }

    @Test
    public void testIdenticalWordDocumentsYieldNoDiff() throws Exception {
        assumeConverterAvailable();

        DiffResult result = differ.diff(extractor.extract(docx("b.docx", "Same", "Content"), MAX_LINES),
                extractor.extract(docx("a.docx", "Same", "Content"), MAX_LINES));

        assertTrue(result.isEmpty());
    }

    /**
     * Formatting-only changes are invisible to a text extractor. Asserted so the limitation is
     * explicit: this is a textual diff, not a Word "track changes" equivalent. Bolding a word
     * produces no audit trail.
     */
    @Test
    public void testFormattingOnlyChangeIsNotDetected() throws Exception {
        assumeConverterAvailable();

        Blob plain = docx("plain.docx", "The amount is 1000 euros");
        Blob bold;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFRun run = document.createParagraph().createRun();
            run.setBold(true);
            run.setText("The amount is 1000 euros");
            document.write(out);
            bold = BlobAuditTestHelper.blob(out.toByteArray(), BlobAuditTestHelper.DOCX_MIME, "bold.docx");
        }

        DiffResult result = differ.diff(extractor.extract(plain, MAX_LINES), extractor.extract(bold, MAX_LINES));

        assertTrue("a styling change carries no textual difference", result.isEmpty());
    }

    @Test
    public void testShortLinesCanBeFilteredOut() throws Exception {
        assumeConverterAvailable();

        ConverterTextExtractor filtering = new ConverterTextExtractor();
        filtering.init(Map.of("converter", ConverterTextExtractor.ANY_2_TEXT, "minLineLength", "10"));

        DiffableContent content = filtering.extract(docx("f.docx", "Short", "A sufficiently long paragraph"),
                MAX_LINES);

        assertTrue(content.lines().stream().noneMatch(line -> line.value().length() < 10));
    }
}

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
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.textBlob;

import java.util.Map;

import org.junit.Test;
import org.nuxeo.audit.advanced.blob.extractor.PlainTextExtractor;
import java.nio.charset.StandardCharsets;

import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.impl.blob.ByteArrayBlob;

/**
 * Unit tests for the text family: {@code .txt}, {@code .json}, {@code .xml}, {@code .csv} and
 * source files. The simplest and most faithful of the three extractors, since nothing is lost in
 * translation.
 *
 * @since 1.0
 */
public class TestPlainTextExtractor {

    protected static final int MAX_LINES = 10000;

    protected final PlainTextExtractor extractor = new PlainTextExtractor();

    protected final TextDiffer differ = new TextDiffer();

    protected DiffResult diff(String before, String after, String mimeType) throws Exception {
        Blob oldBlob = textBlob(before, mimeType, "before.txt");
        Blob newBlob = textBlob(after, mimeType, "after.txt");
        return differ.diff(extractor.extract(oldBlob, MAX_LINES), extractor.extract(newBlob, MAX_LINES));
    }

    /* ------------------------------------------------- encoding and BOM */

    /**
     * A blob can carry any string as its encoding, including one no JVM knows. Passing it straight
     * to InputStreamReader threw UnsupportedEncodingException - an IOException, so the whole diff
     * ended up in error - or IllegalCharsetNameException, which is unchecked and escaped the
     * extractor entirely. A bad declaration must degrade to a readable diff.
     */
    @Test
    public void testUnknownEncodingFallsBackToUtf8() throws Exception {
        Blob blob = new ByteArrayBlob("alpha\nbeta".getBytes(StandardCharsets.UTF_8));
        blob.setMimeType("text/plain");
        blob.setFilename("f.txt");
        blob.setEncoding("definitely-not-a-charset");

        DiffableContent content = extractor.extract(blob, MAX_LINES);

        assertEquals(2, content.size());
    }

    /** An encoding name that is syntactically illegal, not merely unknown. */
    @Test
    public void testIllegalEncodingNameFallsBackToUtf8() throws Exception {
        Blob blob = new ByteArrayBlob("alpha".getBytes(StandardCharsets.UTF_8));
        blob.setMimeType("text/plain");
        blob.setFilename("f.txt");
        blob.setEncoding("utf 8 !");

        assertEquals(1, extractor.extract(blob, MAX_LINES).size());
    }

    @Test
    public void testBlankEncodingFallsBackToUtf8() throws Exception {
        Blob blob = new ByteArrayBlob("h\u00e9llo".getBytes(StandardCharsets.UTF_8));
        blob.setMimeType("text/plain");
        blob.setFilename("f.txt");
        blob.setEncoding("   ");

        assertEquals(1, extractor.extract(blob, MAX_LINES).size());
    }

    /**
     * The same text saved with and without a UTF-8 BOM must not look like a modified first line.
     * readLine keeps the decoded U+FEFF at the head of the first line, so an editor silently adding
     * the BOM would otherwise produce a phantom change on every version.
     */
    @Test
    public void testByteOrderMarkDoesNotCreateAPhantomChange() throws Exception {
        Blob withBom = textBlob("\uFEFFalpha\nbeta", "text/plain", "bom.txt");
        Blob without = textBlob("alpha\nbeta", "text/plain", "plain.txt");

        DiffResult result = differ.diff(extractor.extract(withBom, MAX_LINES),
                extractor.extract(without, MAX_LINES));

        assertTrue("a BOM is not a content change", result.isEmpty());
    }

    /** The BOM is stripped from the first line only, never from a legitimate U+FEFF further down. */
    @Test
    public void testByteOrderMarkIsStrippedOnTheFirstLineOnly() throws Exception {
        DiffableContent content = extractor.extract(
                textBlob("\uFEFFalpha\n\uFEFFbeta", "text/plain", "f.txt"), MAX_LINES);

        assertEquals("alpha", content.lines().get(0).render());
        assertEquals("\uFEFFbeta", content.lines().get(1).render());
    }

    @Test
    public void testContentIsPositionalNotKeyed() throws Exception {
        DiffableContent content = extractor.extract(textBlob("a\nb", "text/plain", "f.txt"), MAX_LINES);

        assertFalse(content.keyed());
        assertEquals(2, content.size());
    }

    @Test
    public void testLineModification() throws Exception {
        DiffResult result = diff("alpha\nbeta\ngamma", "alpha\nBETA\ngamma", "text/plain");

        assertEquals(1, result.changed());
        assertEquals(0, result.added());
        assertEquals(0, result.removed());
        assertTrue(result.unified().contains("- beta"));
        assertTrue(result.unified().contains("+ BETA"));
    }

    @Test
    public void testAppendedLines() throws Exception {
        DiffResult result = diff("alpha\nbeta", "alpha\nbeta\ngamma\ndelta", "text/plain");

        assertEquals(2, result.added());
        assertEquals(0, result.removed());
        assertEquals(0, result.changed());
    }

    /** Blank lines and indentation changes are noise in an audit context, and are skipped. */
    @Test
    public void testBlankLinesAndIndentationAreIgnoredByDefault() throws Exception {
        extractor.init(Map.of("trimLines", "true", "skipBlankLines", "true"));

        DiffResult result = diff("alpha\n\nbeta", "  alpha  \nbeta\n\n\n", "text/plain");

        assertTrue(result.isEmpty());
    }

    /** ...unless the caller asks for a byte-faithful comparison. */
    @Test
    public void testIndentationCanBeSignificant() throws Exception {
        PlainTextExtractor strict = new PlainTextExtractor();
        strict.init(Map.of("trimLines", "false", "skipBlankLines", "false"));

        DiffResult result = differ.diff(strict.extract(textBlob("alpha", "text/plain", "b.txt"), MAX_LINES),
                strict.extract(textBlob("  alpha", "text/plain", "a.txt"), MAX_LINES));

        assertEquals(1, result.changed());
    }

    @Test
    public void testJsonIsDiffedLineByLine() throws Exception {
        String before = "{\n  \"name\": \"widget\",\n  \"qty\": 100\n}";
        String after = "{\n  \"name\": \"widget\",\n  \"qty\": 120\n}";

        DiffResult result = diff(before, after, "application/json");

        assertEquals(1, result.changed());
        assertTrue(result.unified().contains("\"qty\": 100"));
        assertTrue(result.unified().contains("\"qty\": 120"));
    }

    @Test
    public void testXmlIsDiffedLineByLine() throws Exception {
        String before = "<root>\n  <item id=\"1\">old</item>\n</root>";
        String after = "<root>\n  <item id=\"1\">new</item>\n</root>";

        DiffResult result = diff(before, after, "application/xml");

        assertEquals(1, result.changed());
    }

    @Test
    public void testCsvRowAppended() throws Exception {
        DiffResult result = diff("id,name\n1,alpha", "id,name\n1,alpha\n2,beta", "text/csv");

        assertEquals(1, result.added());
        assertTrue(result.unified().contains("+ 2,beta"));
    }

    @Test
    public void testEmptyFileToContent() throws Exception {
        DiffResult result = diff("", "alpha\nbeta", "text/plain");

        assertEquals(2, result.added());
        assertEquals(0, result.removed());
    }

    @Test
    public void testTruncationAtMaxLines() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("line").append(i).append('\n');
        }

        DiffableContent content = extractor.extract(textBlob(sb.toString(), "text/plain", "f.txt"), 25);

        assertEquals(25, content.size());
        assertTrue(content.truncated());
    }
}

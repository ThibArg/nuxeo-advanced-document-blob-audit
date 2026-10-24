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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;
import org.nuxeo.audit.advanced.blob.extractor.PlainTextExtractor;
import org.nuxeo.audit.advanced.blob.extractor.PresentationExtractor;
import org.nuxeo.audit.advanced.blob.extractor.SpreadsheetExtractor;
import org.nuxeo.ecm.core.api.Blob;

/**
 * RES-07 (code review 2026-10): {@code maxLines} bounded how many values an extraction kept, never
 * how long they were. {@code maxValueLength} only applied when the diff was rendered, so 10 000 cells
 * of 32 767 characters were held whole: 327 M characters per side.
 * <p>
 * Values are now bounded when they are extracted, and still compared exactly: a long value keeps its
 * beginning, its length and a digest of the whole.
 *
 * @since 2025.1
 */
public class TestExtractedValueBounding {

    protected static final int MAX_LINES = 10000;

    protected static final int MAX_VALUE_LENGTH = 1000;

    protected static final Map<String, String> BOUNDED = Map.of("maxValueLength", String.valueOf(MAX_VALUE_LENGTH));

    protected static <T extends BlobTextExtractor> T bounded(T extractor) {
        extractor.init(BOUNDED);
        return extractor;
    }

    /** One cell per value, in column A of a sheet named {@code Sheet1}. */
    protected static Blob xlsx(String filename, String... values) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("Sheet1");
            for (int i = 0; i < values.length; i++) {
                sheet.createRow(i).createCell(0).setCellValue(values[i]);
            }
            workbook.write(out);
            return BlobAuditTestHelper.blob(out.toByteArray(), BlobAuditTestHelper.XLSX_MIME, filename);
        }
    }

    protected static int totalLength(DiffableContent content) {
        return content.lines().stream().mapToInt(line -> line.value().length()).sum();
    }

    /** The headline case, at a scale a test can afford: 200 distinct cells of 32 000 characters. */
    @Test
    public void testASpreadsheetHoldsNoValueLongerThanMaxValueLength() throws Exception {
        // Random letters: a repetitive fixture compresses past ZipSecureFile's ratio and is refused.
        Random random = new Random(7);
        String[] values = new String[200];
        for (int i = 0; i < values.length; i++) {
            StringBuilder value = new StringBuilder("cell " + i + " ");
            while (value.length() < 32_000) {
                value.append((char) ('a' + random.nextInt(26)));
            }
            values[i] = value.toString();
        }

        DiffableContent content = bounded(new SpreadsheetExtractor()).extract(xlsx("long.xlsx", values), MAX_LINES);

        assertEquals(200, content.size());
        assertTrue("the values held " + totalLength(content) + " characters",
                totalLength(content) <= values.length * MAX_VALUE_LENGTH);
        assertTrue(content.lines().get(7).value().startsWith("cell 7 "));
    }

    /** The other paths: binary {@code .xls}, a slide paragraph, a text line. */
    @Test
    public void testEveryTextExtractorBoundsItsValues() throws Exception {
        String long50k = "word ".repeat(10_000);
        Blob xls;
        try (HSSFWorkbook workbook = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("Sheet1").createRow(0).createCell(0).setCellValue(long50k.substring(0, 30_000));
            workbook.write(out);
            xls = BlobAuditTestHelper.blob(out.toByteArray(), "application/vnd.ms-excel", "long.xls");
        }
        Blob pptx;
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            show.createSlide().createTextBox().setText(long50k.trim());
            show.write(out);
            pptx = BlobAuditTestHelper.blob(out.toByteArray(), BlobAuditTestHelper.PPTX_MIME, "long.pptx");
        }
        Blob text = BlobAuditTestHelper.textBlob("short\n" + long50k + "\n", "text/plain", "long.txt");

        for (DiffableContent content : List.of(bounded(new SpreadsheetExtractor()).extract(xls, MAX_LINES),
                bounded(new PresentationExtractor()).extract(pptx, MAX_LINES),
                bounded(new PlainTextExtractor()).extract(text, MAX_LINES))) {
            for (ContentLine line : content.lines()) {
                assertTrue("a value of " + line.value().length() + " characters was kept",
                        line.value().length() <= MAX_VALUE_LENGTH);
            }
            assertTrue(content.lines().stream().anyMatch(line -> line.value().startsWith("word word")));
        }
    }

    /**
     * Bounding must not cost change detection, nor the rendering: two cells sharing their first 5 000
     * characters and differing on the last one are reported, and shown, as different.
     */
    @Test
    public void testAChangePastTheKeptBeginningIsReportedAndShown() throws Exception {
        String common = "a".repeat(5_000);
        SpreadsheetExtractor extractor = bounded(new SpreadsheetExtractor());
        TextDiffer differ = new TextDiffer(5000, 4 * 1024 * 1024, MAX_VALUE_LENGTH);

        DiffResult result = differ.diff(extractor.extract(xlsx("a.xlsx", common + "1"), MAX_LINES),
                extractor.extract(xlsx("b.xlsx", common + "2"), MAX_LINES));

        assertEquals(1, result.changed());
        String entry = result.unified().trim();
        String[] sides = entry.substring(entry.indexOf(" : ") + 3).split(" -> ");
        assertEquals(2, sides.length);
        assertNotEquals("the two sides of the change must not render the same", sides[0], sides[1]);
    }

    @Test
    public void testEqualLongValuesStayEqual() throws Exception {
        String value = "b".repeat(20_000);
        SpreadsheetExtractor extractor = bounded(new SpreadsheetExtractor());

        DiffResult result = new TextDiffer().diff(extractor.extract(xlsx("a.xlsx", value), MAX_LINES),
                extractor.extract(xlsx("b.xlsx", value), MAX_LINES));

        assertTrue(result.isEmpty());
    }
}

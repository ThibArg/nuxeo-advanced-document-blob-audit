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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.XLSX_MIME;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.onDisk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.After;
import org.junit.Test;
import org.nuxeo.audit.advanced.blob.extractor.SpreadsheetExtractor;
import org.nuxeo.ecm.core.api.Blob;

import com.sun.management.ThreadMXBean;

/**
 * RES-03 (code review 2026-10): OOXML workbooks are streamed with SAX instead of being loaded through
 * POI's usermodel, which builds the XMLBeans tree of every sheet before the first cell is read.
 * <p>
 * The streamed values must be exactly the usermodel ones, including what POI's own streaming
 * handler does not support: shared formulas, rebuilt for every cell of their range.
 *
 * @since 2025.1
 */
public class TestSpreadsheetStreaming {

    protected static final int MAX_LINES = 10000;

    protected static final String SHEET_NAMESPACE = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    /**
     * Every kind of cell the handler distinguishes, written the way Excel writes them and POI's
     * writer never does: shared formulas using a defined name or another sheet, an array formula, a
     * phonetic run, a styled date, a boolean, an error.
     */
    protected static final String RICH_SHEET = "<worksheet xmlns=\"" + SHEET_NAMESPACE + "\"><sheetData>" //
            + "<row r=\"1\">" //
            + "<c r=\"A1\"><v>1</v></c>" //
            + "<c r=\"B1\"><f t=\"shared\" ref=\"B1:B3\" si=\"0\">A1*Rate</f><v>0.2</v></c>" //
            + "<c r=\"C1\"><f t=\"array\" ref=\"C1:C3\">A1:A3*10</f><v>10</v></c>" //
            + "<c r=\"D1\" t=\"s\"><v>0</v></c>" //
            + "<c r=\"E1\" t=\"inlineStr\"><is><r><t>in</t></r><r><t>line</t></r>"
            + "<rPh sb=\"0\" eb=\"1\"><t>phonetic</t></rPh></is></c>" //
            + "<c r=\"F1\" t=\"b\"><v>1</v></c>" //
            + "<c r=\"G1\" t=\"e\"><v>#DIV/0!</v></c>" //
            + "<c r=\"H1\" s=\"1\"><v>45000</v></c>" //
            + "<c r=\"I1\"><f>SUM(A1:A3)</f><v>6</v></c>" //
            + "<c r=\"J1\"><v>9.5</v></c>" //
            + "<c r=\"K1\"><f t=\"shared\" ref=\"K1:K2\" si=\"1\">Other!A1*2</f><v>0.4</v></c>" //
            + "</row>" //
            + "<row r=\"2\"><c r=\"A2\"><v>2</v></c><c r=\"B2\"><f t=\"shared\" si=\"0\"/><v>0.4</v></c>"
            + "<c r=\"C2\"><v>20</v></c><c r=\"K2\"><f t=\"shared\" si=\"1\"/><v>0</v></c></row>" //
            + "<row r=\"3\"><c r=\"A3\"><v>3</v></c><c r=\"B3\"><f t=\"shared\" si=\"0\"/><v>0.6</v></c>"
            + "<c r=\"C3\"><v>30</v></c></row>" //
            + "</sheetData></worksheet>";

    @After
    public void deleteTempFiles() throws Exception {
        BlobAuditTestHelper.deleteTempFiles();
    }

    /** The usermodel path every streamed value must match, run on the very same package. */
    protected static class Usermodel extends SpreadsheetExtractor {

        protected List<ContentLine> extractWithUsermodel(Blob blob) throws Exception {
            List<ContentLine> lines = new ArrayList<>();
            try (InputStream in = blob.getStream(); Workbook workbook = WorkbookFactory.create(in)) {
                collect(workbook, MAX_LINES, lines);
            }
            return lines;
        }
    }

    /**
     * A workbook built by POI with what a hand-written sheet needs around it: a second sheet holding
     * a shared string, a date style at index 1, and the defined name {@code Rate}.
     */
    protected static byte[] template() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("Data");
            XSSFRow first = workbook.createSheet("Other").createRow(0);
            first.createCell(0).setCellValue(0.2);
            XSSFCellStyle date = workbook.createCellStyle();
            date.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            XSSFCell dated = first.createCell(2);
            dated.setCellValue(45000);
            dated.setCellStyle(date);
            workbook.getSheet("Other").createRow(1).createCell(0).setCellValue("label");
            Name rate = workbook.createName();
            rate.setNameName("Rate");
            rate.setRefersToFormula("Other!$A$1");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** Replaces the first sheet of the template with the given XML. */
    protected static Blob workbook(String dataSheet, String filename) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(template()));
                ZipOutputStream rewritten = new ZipOutputStream(out)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                rewritten.putNextEntry(new ZipEntry(entry.getName()));
                if ("xl/worksheets/sheet1.xml".equals(entry.getName())) {
                    rewritten.write(dataSheet.getBytes(UTF_8));
                } else {
                    in.transferTo(rewritten);
                }
                rewritten.closeEntry();
            }
        }
        return BlobAuditTestHelper.blob(out.toByteArray(), XLSX_MIME, filename);
    }

    protected static Map<String, String> byKey(List<ContentLine> lines) {
        Map<String, String> map = new LinkedHashMap<>();
        lines.forEach(line -> map.put(line.key(), line.value()));
        return map;
    }

    @Test
    public void testStreamedCellsAreTheUsermodelOnes() throws Exception {
        Blob blob = workbook(RICH_SHEET, "rich.xlsx");
        Usermodel extractor = new Usermodel();

        List<ContentLine> expected = withoutColumnK(extractor.extractWithUsermodel(blob));
        DiffableContent streamed = extractor.extract(onDisk(blob), MAX_LINES);

        assertEquals(expected, withoutColumnK(streamed.lines()));
        Map<String, String> cells = byKey(streamed.lines());
        assertEquals("a shared formula must be rebuilt for each cell", "=A3*Rate", cells.get("Data!B3"));
        assertEquals("=A1:A3*10", cells.get("Data!C2"));
        assertEquals("inline", cells.get("Data!E1"));
    }

    /** POI's usermodel renders this one as {@code =A2*2}: its shared formula code drops the sheet. */
    @Test
    public void testASharedFormulaKeepsTheSheetOfItsReferences() throws Exception {
        Map<String, String> cells = byKey(new SpreadsheetExtractor().extract(onDisk(workbook(RICH_SHEET, "rich.xlsx")),
                MAX_LINES).lines());

        assertEquals("=Other!A1*2", cells.get("Data!K1"));
        assertEquals("=Other!A2*2", cells.get("Data!K2"));
    }

    protected static List<ContentLine> withoutColumnK(List<ContentLine> lines) {
        return lines.stream().filter(line -> !line.key().startsWith("Data!K")).toList();
    }

    /**
     * COR-05 (code review 2026-10), on both paths: {@code useFormulas=false} compared formulas on
     * their text all the same, because {@code DataFormatter} renders a formula cell as its formula
     * when it has no evaluator.
     */
    @Test
    public void testFormulasAreComparedOnTheirCachedValueWhenUseFormulasIsOff() throws Exception {
        SpreadsheetExtractor values = new SpreadsheetExtractor();
        values.init(Map.of("useFormulas", "false"));

        Map<String, String> xlsx = byKey(values.extract(onDisk(workbook(RICH_SHEET, "rich.xlsx")), MAX_LINES).lines());
        Map<String, String> xls = byKey(values.extract(xls(), MAX_LINES).lines());
        Map<String, String> xlsWithFormulas = byKey(new SpreadsheetExtractor().extract(xls(), MAX_LINES).lines());

        assertEquals("0.4", xlsx.get("Data!B2"));
        assertEquals("6", xlsx.get("Data!I1"));
        assertEquals("2", xls.get("Sheet1!B1"));
        assertEquals("=A1*2", xlsWithFormulas.get("Sheet1!B1"));
    }

    protected static Blob xls() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            HSSFRow row = workbook.createSheet("Sheet1").createRow(0);
            row.createCell(0).setCellValue(1);
            row.createCell(1).setCellFormula("A1*2");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(out);
            return BlobAuditTestHelper.blob(out.toByteArray(), "application/vnd.ms-excel", "f.xls");
        }
    }

    /**
     * The point of streaming: 300 000 cells, of which {@code maxLines} are wanted. The usermodel built
     * the tree of all of them first.
     */
    @Test
    public void testStreamingStopsAtMaxLines() throws Exception {
        StringBuilder sheet = new StringBuilder("<worksheet xmlns=\"" + SHEET_NAMESPACE + "\"><sheetData>");
        for (int r = 1; r <= 100_000; r++) {
            sheet.append("<row r=\"").append(r).append("\">");
            for (String column : List.of("A", "B", "C")) {
                sheet.append("<c r=\"").append(column).append(r).append("\"><v>").append(r).append("</v></c>");
            }
            sheet.append("</row>");
        }
        Blob blob = onDisk(workbook(sheet.append("</sheetData></worksheet>").toString(), "big.xlsx"));
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();

        DiffableContent content = new SpreadsheetExtractor().extract(blob, 100);

        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertEquals(100, content.size());
        assertTrue(content.truncated());
        assertTrue("extracting 100 cells allocated " + allocated + " bytes", allocated < 32L * 1024 * 1024);
    }
}

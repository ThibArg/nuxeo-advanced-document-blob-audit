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
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.cells;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.xlsx;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;
import org.nuxeo.audit.advanced.blob.extractor.SpreadsheetExtractor;
import org.nuxeo.ecm.core.api.Blob;

/**
 * End-to-end tests of the spreadsheet path: {@link SpreadsheetExtractor} feeding {@link TextDiffer}.
 * <p>
 * This is the highest-value scenario of the plugin, and the only one producing a genuinely
 * structured diff ({@code Sheet1!B2 : 100 -> 120}) rather than a textual approximation.
 *
 * @since 2025.1
 */
public class TestExcelDiff {

    protected static final int MAX_LINES = 10000;

    protected final SpreadsheetExtractor extractor = new SpreadsheetExtractor();

    protected final TextDiffer differ = new TextDiffer();

    protected DiffResult diff(Blob oldBlob, Blob newBlob) throws Exception {
        DiffableContent oldContent = extractor.extract(oldBlob, MAX_LINES);
        DiffableContent newContent = extractor.extract(newBlob, MAX_LINES);
        assertTrue("Spreadsheet content must be keyed", oldContent.keyed());
        assertTrue("Spreadsheet content must be keyed", newContent.keyed());
        return differ.diff(oldContent, newContent);
    }

    @Test
    public void testIdenticalWorkbooksYieldNoDiff() throws Exception {
        Map<String, String> data = cells("A1", "Product", "B1", "Qty", "A2", "Widget", "B2", "100");
        Blob before = xlsx("Sheet1", data, "before.xlsx");
        Blob after = xlsx("Sheet1", data, "after.xlsx");

        DiffResult result = diff(before, after);

        assertTrue(result.isEmpty());
        assertEquals("No textual change detected", result.summary());
    }

    /** The reference scenario: one cell edited, one row appended, one cell emptied. */
    @Test
    public void testCellLevelDiffReportsExactChanges() throws Exception {
        Blob before = xlsx("Sheet1", cells( //
                "A1", "Product", "B1", "Qty", "C1", "Price", //
                "A2", "Widget", "B2", "100", "C2", "9.5", //
                "A3", "Gadget", "B3", "200", "C3", "19"), "before.xlsx");

        Blob after = xlsx("Sheet1", cells( //
                "A1", "Product", "B1", "Qty", "C1", "Price", //
                "A2", "Widget", "B2", "120", "C2", "9.5", // B2 edited
                "A3", "Gadget", "B3", "200", // C3 emptied
                "A4", "Doohickey", "B4", "50", "C4", "5"), "after.xlsx"); // row appended

        DiffResult result = diff(before, after);

        assertEquals(3, result.added());
        assertEquals(1, result.removed());
        assertEquals(1, result.changed());
        assertFalse(result.truncated());

        String unified = result.unified();
        assertTrue("edited cell must be identified", unified.contains("~ Sheet1!B2 : 100 -> 120"));
        assertTrue("emptied cell must be a removal", unified.contains("- Sheet1!C3"));
        assertTrue(unified.contains("+ Sheet1!A4 = Doohickey"));
        assertEquals("1 modification, 3 additions, 1 deletion", result.summary());
    }

    /** An empty cell is not a unit: blanking a cell is a removal, not a modification to "". */
    @Test
    public void testBlankingACellIsARemoval() throws Exception {
        Blob before = xlsx("Sheet1", cells("A1", "keep", "B1", "doomed"), "before.xlsx");
        Blob after = xlsx("Sheet1", cells("A1", "keep"), "after.xlsx");

        DiffResult result = diff(before, after);

        assertEquals(0, result.added());
        assertEquals(1, result.removed());
        assertEquals(0, result.changed());
        assertTrue(result.unified().contains("- Sheet1!B1 = doomed"));
    }

    /**
     * Formulas are compared as formulas, not as their cached result: an auditor needs to see that
     * {@code =SUM(A1:A2)} became {@code =SUM(A1:A3)}, which is precisely the kind of silent change
     * that a value-only comparison would miss when the totals happen to match.
     */
    @Test
    public void testFormulaChangeIsDetected() throws Exception {
        Blob before = xlsx("Sheet1", cells("A1", "10", "A2", "20", "A3", "30", "B1", "=SUM(A1:A2)"), "before.xlsx");
        Blob after = xlsx("Sheet1", cells("A1", "10", "A2", "20", "A3", "30", "B1", "=SUM(A1:A3)"), "after.xlsx");

        DiffResult result = diff(before, after);

        assertEquals(1, result.changed());
        assertTrue(result.unified().contains("Sheet1!B1"));
        assertTrue(result.unified().contains("=SUM(A1:A2)"));
        assertTrue(result.unified().contains("=SUM(A1:A3)"));
    }

    @Test
    public void testMultipleSheetsAreDisambiguatedByName() throws Exception {
        Map<String, Map<String, String>> before = new LinkedHashMap<>();
        before.put("Budget", cells("A1", "100"));
        before.put("Actuals", cells("A1", "100"));

        Map<String, Map<String, String>> after = new LinkedHashMap<>();
        after.put("Budget", cells("A1", "100"));
        after.put("Actuals", cells("A1", "150"));

        DiffResult result = diff(xlsx(before, "before.xlsx"), xlsx(after, "after.xlsx"));

        assertEquals(1, result.changed());
        assertTrue(result.unified().contains("Actuals!A1"));
        assertFalse(result.unified().contains("Budget!A1"));
    }

    /** Sheet order is part of the key space only through names, so moving a sheet changes nothing. */
    @Test
    public void testReorderingSheetsYieldsNoDiff() throws Exception {
        Map<String, Map<String, String>> before = new LinkedHashMap<>();
        before.put("First", cells("A1", "a"));
        before.put("Second", cells("A1", "b"));

        Map<String, Map<String, String>> after = new LinkedHashMap<>();
        after.put("Second", cells("A1", "b"));
        after.put("First", cells("A1", "a"));

        assertTrue(diff(xlsx(before, "before.xlsx"), xlsx(after, "after.xlsx")).isEmpty());
    }

    /** Renaming a sheet invalidates every key it holds. Expected, but worth knowing. */
    @Test
    public void testRenamingASheetInvalidatesAllItsKeys() throws Exception {
        Blob before = xlsx("Old", cells("A1", "x", "B1", "y"), "before.xlsx");
        Blob after = xlsx("New", cells("A1", "x", "B1", "y"), "after.xlsx");

        DiffResult result = diff(before, after);

        assertEquals(2, result.added());
        assertEquals(2, result.removed());
        assertEquals(0, result.changed());
    }

    /**
     * Known limitation, asserted on purpose so that it cannot regress silently into a false claim:
     * because units are keyed by absolute cell reference, inserting a row shifts every cell below it
     * and the diff reports the whole shifted region as modified.
     * <p>
     * Mitigating this would require a row-alignment pass (align rows first, then compare cells
     * within aligned rows). That is a worthwhile v2, not a bug in the current design.
     */
    @Test
    public void testInsertingARowShiftsCellsAndInflatesTheDiff() throws Exception {
        Blob before = xlsx("Sheet1", cells( //
                "A1", "Product", "B1", "Qty", //
                "A2", "Widget", "B2", "100", //
                "A3", "Gadget", "B3", "200"), "before.xlsx");

        Blob after = xlsx("Sheet1", cells( //
                "A1", "NEW", "B1", "HEADER", // row inserted at the top
                "A2", "Product", "B2", "Qty", //
                "A3", "Widget", "B3", "100", //
                "A4", "Gadget", "B4", "200"), "after.xlsx");

        DiffResult result = diff(before, after);

        assertEquals(6, result.changed());
        assertEquals(2, result.added());
        assertEquals(0, result.removed());
    }

    @Test
    public void testExtractionIsTruncatedAtMaxLines() throws Exception {
        Map<String, String> many = new LinkedHashMap<>();
        for (int row = 1; row <= 50; row++) {
            many.put("A" + row, "value" + row);
        }
        Blob blob = xlsx("Sheet1", many, "big.xlsx");

        DiffableContent content = extractor.extract(blob, 10);

        assertEquals(10, content.size());
        assertTrue(content.truncated());
    }

    @Test
    public void testTruncationPropagatesToTheDiffResult() throws Exception {
        Map<String, String> before = new LinkedHashMap<>();
        Map<String, String> after = new LinkedHashMap<>();
        for (int row = 1; row <= 50; row++) {
            before.put("A" + row, "value" + row);
            after.put("A" + row, "changed" + row);
        }

        DiffResult result = differ.diff(extractor.extract(xlsx("Sheet1", before, "b.xlsx"), 10),
                extractor.extract(xlsx("Sheet1", after, "a.xlsx"), 10));

        assertTrue(result.truncated());
        assertTrue(result.summary().endsWith("(truncated)"));
    }
}

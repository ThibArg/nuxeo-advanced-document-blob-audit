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
package org.nuxeo.audit.advanced.blob.extractor;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.nuxeo.audit.advanced.blob.BlobTextExtractor;
import org.nuxeo.audit.advanced.blob.ContentLine;
import org.nuxeo.audit.advanced.blob.DiffableContent;
import org.nuxeo.ecm.core.api.Blob;

/**
 * Cell-level extractor for spreadsheets ({@code .xlsx}, {@code .xlsm}, {@code .xls}).
 * <p>
 * Produces <b>keyed</b> content, one {@link ContentLine} per non-empty cell, keyed by
 * {@code SheetName!A1}. This is what makes a spreadsheet diff genuinely useful: the differ reports
 * {@code Sheet1!B12 : 100 -> 120} instead of a textual approximation.
 * <p>
 * <b>Known limitation.</b> Keys are absolute cell references, so inserting a row shifts every cell
 * below it and the diff reports the whole shifted region as modified. Keyed comparison is immune to
 * extraction order and to sheet reordering, but not to row shifts. Fixing that would require a row
 * alignment pass before comparing cells.
 *
 * @since 1.0
 */
public class SpreadsheetExtractor implements BlobTextExtractor {

    protected boolean useFormulas = true;

    @Override
    public void init(Map<String, String> properties) {
        useFormulas = !"false".equalsIgnoreCase(properties.get("useFormulas"));
    }

    /**
     * Reads the workbook from the local file whenever there is one.
     * <p>
     * {@code WorkbookFactory.create(InputStream)} buffers the whole OOXML package in heap before
     * building the XMLBeans model - POI cannot reposition a stream. The {@code File} variant opens
     * the zip in random access, read only, and reads the parts on demand, so the footprint follows
     * what is actually extracted rather than the size of the package.
     * <p>
     * {@link org.nuxeo.audit.advanced.blob.io.MaterializedBlob} guarantees a file on the nominal
     * path. The stream fallback is for the blobs built in memory by the unit tests, and for the
     * case where materialisation failed.
     */
    @Override
    public DiffableContent extract(Blob blob, int maxLines) throws Exception {
        List<ContentLine> lines = new ArrayList<>();
        boolean truncated;
        File file = blob.getFile();
        if (file != null) {
            try (Workbook workbook = WorkbookFactory.create(file, null, true)) {
                truncated = collect(workbook, maxLines, lines);
            }
        } else {
            try (InputStream in = blob.getStream(); Workbook workbook = WorkbookFactory.create(in)) {
                truncated = collect(workbook, maxLines, lines);
            }
        }
        return DiffableContent.keyed(lines, truncated);
    }

    /** @return {@code true} once maxLines has been reached */
    protected boolean collect(Workbook workbook, int maxLines, List<ContentLine> lines) {
        DataFormatter formatter = new DataFormatter();
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            Sheet sheet = workbook.getSheetAt(s);
            String sheetName = sheet.getSheetName();
            for (Row row : sheet) {
                for (Cell cell : row) {
                    String value = formatCell(cell, formatter);
                    if (value == null || value.isEmpty()) {
                        continue;
                    }
                    if (lines.size() >= maxLines) {
                        return true;
                    }
                    String key = sheetName + "!" + new CellReference(cell).formatAsString(false);
                    lines.add(ContentLine.of(key, value));
                }
            }
        }
        return false;
    }

    /**
     * Formula cells are compared on their formula when {@code useFormulas} is on: an auditor wants
     * to know that {@code =SUM(A1:A10)} became {@code =SUM(A1:A20)}, not only that the result moved
     * - and especially when the result did <em>not</em> move.
     */
    protected String formatCell(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return null;
        }
        if (useFormulas && cell.getCellType() == CellType.FORMULA) {
            return "=" + cell.getCellFormula();
        }
        return formatter.formatCellValue(cell).trim();
    }
}

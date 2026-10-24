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

import static org.apache.poi.ooxml.POIXMLTypeLoader.DEFAULT_XML_OPTIONS;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.formula.FormulaParser;
import org.apache.poi.ss.formula.FormulaRenderer;
import org.apache.poi.ss.formula.FormulaType;
import org.apache.poi.ss.formula.ptg.AreaPtgBase;
import org.apache.poi.ss.formula.ptg.OperandPtg;
import org.apache.poi.ss.formula.ptg.Ptg;
import org.apache.poi.ss.formula.ptg.RefPtgBase;
import org.apache.poi.ss.usermodel.BuiltinFormats;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFEvaluationWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.xmlbeans.XmlException;
import org.nuxeo.audit.advanced.blob.BlobTextExtractor;
import org.nuxeo.audit.advanced.blob.ContentLine;
import org.nuxeo.audit.advanced.blob.DiffableContent;
import org.nuxeo.audit.advanced.blob.TextDiffer;
import org.nuxeo.audit.advanced.blob.io.OoxmlPackages;
import org.nuxeo.ecm.core.api.Blob;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTDefinedName;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTSheet;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.WorkbookDocument;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

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
 * @since 2025.1
 */
public class SpreadsheetExtractor implements BlobTextExtractor {

    private static final Logger log = LogManager.getLogger(SpreadsheetExtractor.class);

    protected boolean useFormulas = true;

    protected int maxValueLength = TextDiffer.DEFAULT_MAX_VALUE_LENGTH;

    @Override
    public void init(Map<String, String> properties) {
        useFormulas = !"false".equalsIgnoreCase(properties.get("useFormulas"));
        maxValueLength = BlobTextExtractor.maxValueLength(properties);
    }

    /**
     * OOXML workbooks are streamed sheet by sheet, and the parse stops at {@code maxLines}.
     * <p>
     * POI's usermodel builds the XMLBeans tree of <b>every</b> sheet as soon as the workbook is
     * opened - from a file or not, which only spares buffering the compressed package - so its
     * footprint followed the number of cells rather than what is extracted: a few hundred thousand
     * cells cost hundreds of megabytes for the first {@code maxLines} of them. Binary {@code .xls}
     * keeps the usermodel: HSSF reads the whole record stream regardless, and {@code maxBlobSize}
     * bounds it.
     */
    @Override
    public DiffableContent extract(Blob blob, int maxLines) throws IOException {
        List<ContentLine> lines = new ArrayList<>();
        boolean truncated;
        File file = blob.getFile();
        if (file != null) {
            if (FileMagic.valueOf(file) == FileMagic.OOXML) {
                truncated = stream(OoxmlPackages.open(file), maxLines, lines);
            } else {
                try (Workbook workbook = WorkbookFactory.create(file, null, true)) {
                    truncated = collect(workbook, maxLines, lines);
                }
            }
        } else {
            try (InputStream in = FileMagic.prepareToCheckMagic(blob.getStream())) {
                if (FileMagic.valueOf(in) == FileMagic.OOXML) {
                    truncated = stream(OoxmlPackages.open(in), maxLines, lines);
                } else {
                    try (Workbook workbook = WorkbookFactory.create(in)) {
                        truncated = collect(workbook, maxLines, lines);
                    }
                }
            }
        }
        return DiffableContent.keyed(lines, truncated);
    }

    /**
     * Streams every sheet of the package, then releases it.
     *
     * @return {@code true} once maxLines has been reached
     * @since 2025.1
     */
    protected boolean stream(OPCPackage pkg, int maxLines, List<ContentLine> lines) throws IOException {
        try {
            XSSFReader reader = new XSSFReader(pkg);
            CTWorkbook workbook;
            try (InputStream in = reader.getWorkbookData()) {
                workbook = WorkbookDocument.Factory.parse(in, DEFAULT_XML_OPTIONS).getWorkbook();
            }
            boolean date1904 = workbook.isSetWorkbookPr() && workbook.getWorkbookPr().getDate1904();
            // Without phonetic runs, like XSSFRichTextString#getString in the usermodel.
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg, false);
            StylesTable styles = reader.getStylesTable();
            SharedFormulas formulas = new SharedFormulas(workbook);
            DataFormatter formatter = new DataFormatter();
            XSSFReader.SheetIterator sheets = reader.getSheetIterator();
            while (sheets.hasNext()) {
                try (InputStream sheet = sheets.next()) {
                    XMLReader parser = XMLHelper.newXMLReader();
                    parser.setContentHandler(new SheetHandler(sheets.getSheetName(), lines, maxLines, strings, styles,
                            formatter, date1904, formulas));
                    parser.parse(new InputSource(sheet));
                } catch (MaxLinesReached e) {
                    return true;
                }
            }
            return false;
        } catch (OpenXML4JException | XmlException | SAXException | ParserConfigurationException e) {
            throw new IOException("Not a readable OOXML workbook", e);
        } finally {
            pkg.revert();
        }
    }

    /**
     * Usermodel path, for binary {@code .xls}.
     *
     * @return {@code true} once maxLines has been reached
     */
    protected boolean collect(Workbook workbook, int maxLines, List<ContentLine> lines) {
        DataFormatter formatter = new DataFormatter();
        // Without it, a formula cell renders as its formula text whatever useFormulas says.
        formatter.setUseCachedValuesForFormulaCells(!useFormulas);
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
                    lines.add(ContentLine.of(key, value, maxValueLength));
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

    /** Ends the parse of a sheet once {@code maxLines} is reached. */
    protected static class MaxLinesReached extends SAXException {

        private static final long serialVersionUID = 1L;

        protected MaxLinesReached() {
            super("maxLines reached");
        }
    }

    /** A formula written once for a range of cells: the master of a shared formula, or an array formula. */
    protected record RangeFormula(CellRangeAddress range, String text) {
    }

    /**
     * Rebuilds the formula of a cell sharing the one written on the first cell of its range, like
     * {@code XSSFCell#getCellFormula}, on a workbook holding nothing but the sheet and defined names:
     * enough to parse and render a formula, built on first use only.
     * <p>
     * One deliberate difference: POI's {@code SharedFormula} turns a reference to another sheet into
     * a plain one, so the usermodel rendered {@code Other!A1*2} shared down a column as {@code A2*2}.
     * References are shifted here in place, and keep their sheet.
     *
     * @since 2025.1
     */
    protected static class SharedFormulas {

        protected static final Ptg[] UNPARSABLE = new Ptg[0];

        protected static final SpreadsheetVersion VERSION = SpreadsheetVersion.EXCEL2007;

        protected final CTWorkbook workbook;

        protected final Map<String, Ptg[]> parsed = new HashMap<>();

        protected XSSFWorkbook names;

        protected XSSFEvaluationWorkbook context;

        protected boolean unavailable;

        protected SharedFormulas(CTWorkbook workbook) {
            this.workbook = workbook;
        }

        /** @return the formula of the cell, or {@code null} when it cannot be rebuilt */
        protected String translate(String sheetName, String si, RangeFormula master, int row, int column) {
            XSSFEvaluationWorkbook book = context();
            if (book == null) {
                return null;
            }
            int sheetIndex = names.getSheetIndex(sheetName);
            Ptg[] ptgs = parsed.computeIfAbsent(sheetIndex + ":" + si,
                    key -> parse(book, master.text(), sheetIndex, row));
            if (ptgs == UNPARSABLE) {
                return null;
            }
            try {
                Ptg[] shifted = shift(ptgs, row - master.range().getFirstRow(),
                        column - master.range().getFirstColumn());
                return FormulaRenderer.toFormulaString(book, shifted);
            } catch (RuntimeException e) { // NOSONAR - an unrenderable formula falls back to the value
                log.debug("Cannot render shared formula {} at row {} column {}", master.text(), row, column, e);
                return null;
            }
        }

        protected static Ptg[] shift(Ptg[] ptgs, int rows, int columns) {
            Ptg[] shifted = new Ptg[ptgs.length];
            for (int i = 0; i < ptgs.length; i++) {
                Ptg ptg = ptgs[i];
                if (ptg instanceof OperandPtg operand) {
                    ptg = operand.copy();
                    if (ptg instanceof RefPtgBase ref) {
                        ref.setRow(shiftRow(ref.getRow(), rows, ref.isRowRelative()));
                        ref.setColumn(shiftColumn(ref.getColumn(), columns, ref.isColRelative()));
                    } else if (ptg instanceof AreaPtgBase area) {
                        area.setFirstRow(shiftRow(area.getFirstRow(), rows, area.isFirstRowRelative()));
                        area.setLastRow(shiftRow(area.getLastRow(), rows, area.isLastRowRelative()));
                        area.setFirstColumn(shiftColumn(area.getFirstColumn(), columns, area.isFirstColRelative()));
                        area.setLastColumn(shiftColumn(area.getLastColumn(), columns, area.isLastColRelative()));
                    }
                }
                shifted[i] = ptg;
            }
            return shifted;
        }

        /** Wraps past the last row, as Excel and POI do. */
        protected static int shiftRow(int row, int offset, boolean relative) {
            return relative ? (row + offset) & VERSION.getLastRowIndex() : row;
        }

        protected static int shiftColumn(int column, int offset, boolean relative) {
            return relative ? (column + offset) & VERSION.getLastColumnIndex() : column;
        }

        protected static Ptg[] parse(XSSFEvaluationWorkbook book, String formula, int sheetIndex, int row) {
            try {
                return FormulaParser.parse(formula, book, FormulaType.CELL, sheetIndex, row);
            } catch (RuntimeException e) { // NOSONAR - external links, tables: compared on their value
                log.debug("Cannot parse shared formula {}", formula, e);
                return UNPARSABLE;
            }
        }

        protected XSSFEvaluationWorkbook context() {
            if (context == null && !unavailable) {
                try {
                    // In memory only: nothing to release.
                    names = new XSSFWorkbook();
                    for (CTSheet sheet : workbook.getSheets().getSheetArray()) {
                        names.createSheet(sheet.getName());
                    }
                    if (workbook.isSetDefinedNames()) {
                        for (CTDefinedName defined : workbook.getDefinedNames().getDefinedNameArray()) {
                            addName(defined);
                        }
                    }
                    context = XSSFEvaluationWorkbook.create(names);
                } catch (RuntimeException e) { // NOSONAR - shared formulas then fall back to their value
                    log.debug("Cannot build the formula context of the workbook", e);
                    unavailable = true;
                }
            }
            return context;
        }

        protected void addName(CTDefinedName defined) {
            Name name = names.createName();
            try {
                if (defined.isSetLocalSheetId()) {
                    name.setSheetIndex(Math.toIntExact(defined.getLocalSheetId()));
                }
                name.setNameName(defined.getName());
                // Only the name is rendered; the parser merely refuses a name with no definition.
                name.setRefersToFormula("#REF!");
            } catch (RuntimeException e) { // NOSONAR - a formula using it is then compared on its value
                names.removeName(name);
            }
        }
    }

    /**
     * SAX handler of one worksheet, producing the same values as {@link #formatCell} does on the
     * usermodel: formula text when {@code useFormulas} is on, otherwise the cell formatted by
     * {@link DataFormatter} from its cached value and style.
     *
     * @since 2025.1
     */
    protected class SheetHandler extends DefaultHandler {

        protected final String sheetName;

        protected final List<ContentLine> lines;

        protected final int maxLines;

        protected final ReadOnlySharedStringsTable strings;

        protected final StylesTable styles;

        protected final DataFormatter formatter;

        protected final boolean date1904;

        protected final SharedFormulas formulas;

        protected final Map<String, RangeFormula> sharedMasters = new HashMap<>();

        protected final List<RangeFormula> arrayFormulas = new ArrayList<>();

        protected final StringBuilder value = new StringBuilder();

        protected final StringBuilder inline = new StringBuilder();

        protected final StringBuilder formula = new StringBuilder();

        /** Where character data currently goes, {@code null} outside of a value. */
        protected StringBuilder text;

        protected boolean inInlineString;

        protected boolean inPhoneticRun;

        protected int row = -1;

        protected int nextColumn;

        protected String ref;

        protected int cellRow;

        protected int cellColumn;

        protected String type;

        protected String style;

        protected boolean hasFormula;

        protected String formulaType;

        protected String formulaSi;

        protected String formulaRef;

        protected SheetHandler(String sheetName, List<ContentLine> lines, int maxLines,
                ReadOnlySharedStringsTable strings, StylesTable styles, DataFormatter formatter, boolean date1904,
                SharedFormulas formulas) {
            this.sheetName = sheetName;
            this.lines = lines;
            this.maxLines = maxLines;
            this.strings = strings;
            this.styles = styles;
            this.formatter = formatter;
            this.date1904 = date1904;
            this.formulas = formulas;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            switch (localName) {
                case "row" -> {
                    String r = attributes.getValue("r");
                    row = r == null ? row + 1 : Integer.parseInt(r) - 1;
                    nextColumn = 0;
                }
                case "c" -> startCell(attributes);
                case "v" -> text = value;
                case "f" -> {
                    hasFormula = true;
                    formulaType = attributes.getValue("t");
                    formulaSi = attributes.getValue("si");
                    formulaRef = attributes.getValue("ref");
                    text = formula;
                }
                case "is" -> inInlineString = true;
                case "rPh" -> inPhoneticRun = true;
                case "t" -> {
                    if (inInlineString && !inPhoneticRun) {
                        text = inline;
                    }
                }
                default -> {
                    // nothing to collect
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (text != null) {
                text.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            switch (localName) {
                case "v", "f", "t" -> text = null;
                case "is" -> inInlineString = false;
                case "rPh" -> inPhoneticRun = false;
                case "c" -> endCell();
                default -> {
                    // nothing to close
                }
            }
        }

        protected void startCell(Attributes attributes) {
            String r = attributes.getValue("r");
            CellReference reference = r == null ? new CellReference(row, nextColumn) : new CellReference(r);
            cellRow = reference.getRow();
            cellColumn = reference.getCol();
            ref = reference.formatAsString(false);
            nextColumn = cellColumn + 1;
            type = attributes.getValue("t");
            style = attributes.getValue("s");
            hasFormula = false;
            formulaType = null;
            formulaSi = null;
            formulaRef = null;
            value.setLength(0);
            inline.setLength(0);
            formula.setLength(0);
        }

        protected void endCell() throws SAXException {
            String cellText = cellText();
            if (cellText == null || cellText.isEmpty()) {
                return;
            }
            if (lines.size() >= maxLines) {
                throw new MaxLinesReached();
            }
            lines.add(ContentLine.of(sheetName + "!" + ref, cellText, maxValueLength));
        }

        protected String cellText() {
            if (useFormulas) {
                String formulaText = formulaText();
                if (formulaText != null) {
                    return "=" + formulaText;
                }
            }
            return formattedValue().trim();
        }

        /** @return the formula of the cell as {@code XSSFCell#getCellFormula} returns it, or {@code null} */
        protected String formulaText() {
            if (!hasFormula) {
                return arrayFormulaCovering();
            }
            if ("dataTable".equals(formulaType)) {
                return null;
            }
            if ("shared".equals(formulaType)) {
                return sharedFormula();
            }
            String written = formula.toString();
            if ("array".equals(formulaType) && formulaRef != null) {
                arrayFormulas.add(new RangeFormula(CellRangeAddress.valueOf(formulaRef), written));
            }
            if (written.isEmpty()) {
                String covering = arrayFormulaCovering();
                return covering == null ? written : covering;
            }
            return written;
        }

        protected String arrayFormulaCovering() {
            for (RangeFormula array : arrayFormulas) {
                if (array.range().isInRange(cellRow, cellColumn)) {
                    return array.text();
                }
            }
            return null;
        }

        /** The master of a group is the cell carrying its range; every member is rebuilt from it. */
        protected String sharedFormula() {
            String written = formula.toString();
            if (formulaRef != null) {
                sharedMasters.put(formulaSi, new RangeFormula(CellRangeAddress.valueOf(formulaRef), written));
            }
            RangeFormula master = sharedMasters.get(formulaSi);
            String rebuilt = master == null ? null
                    : formulas.translate(sheetName, formulaSi, master, cellRow, cellColumn);
            if (rebuilt != null) {
                return rebuilt;
            }
            return written.isEmpty() ? null : written;
        }

        protected String formattedValue() {
            String raw = value.toString();
            if (type == null || "n".equals(type)) {
                return raw.isEmpty() ? "" : number(raw);
            }
            return switch (type) {
                case "s" -> raw.isEmpty() ? "" : strings.getItemAt(Integer.parseInt(raw.trim())).getString();
                case "inlineStr" -> inline.length() > 0 ? inline.toString() : raw;
                case "b" -> {
                    if (raw.isEmpty()) {
                        yield "";
                    }
                    yield "1".equals(raw.trim()) ? "TRUE" : "FALSE";
                }
                default -> raw;
            };
        }

        protected String number(String raw) {
            double number;
            try {
                number = Double.parseDouble(raw);
            } catch (NumberFormatException e) { // NOSONAR - not a number after all: kept as written
                return raw;
            }
            XSSFCellStyle cellStyle = null;
            if (styles != null) {
                if (style != null) {
                    cellStyle = styles.getStyleAt(Integer.parseInt(style));
                } else if (styles.getNumCellStyles() > 0) {
                    cellStyle = styles.getStyleAt(0);
                }
            }
            if (cellStyle == null) {
                return formatter.formatRawCellContents(number, 0, "General", date1904);
            }
            short formatIndex = cellStyle.getDataFormat();
            String formatString = cellStyle.getDataFormatString();
            if (formatString == null) {
                formatString = BuiltinFormats.getBuiltinFormat(formatIndex);
            }
            return formatter.formatRawCellContents(number, formatIndex,
                    formatString == null ? "General" : formatString, date1904);
        }
    }
}

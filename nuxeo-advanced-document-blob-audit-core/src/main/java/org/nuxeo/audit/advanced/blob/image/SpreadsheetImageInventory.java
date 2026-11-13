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
package org.nuxeo.audit.advanced.blob.image;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.hssf.usermodel.HSSFPicture;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.PictureData;
import org.apache.poi.ss.usermodel.Shape;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.nuxeo.audit.advanced.blob.DiffableContent;
import org.nuxeo.audit.advanced.blob.io.MaterializedBlob;
import org.nuxeo.audit.advanced.blob.io.OoxmlPackages;
import org.nuxeo.ecm.core.api.Blob;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Image inventory of spreadsheets ({@code .xlsx}, {@code .xlsm}, {@code .xls}): one entry per picture
 * placed on a sheet, keyed by {@code excel:image:<sheet>:<location>}.
 *
 * @since 2025.1
 */
public class SpreadsheetImageInventory extends AbstractImageInventory {

    private static final Logger log = LogManager.getLogger(SpreadsheetImageInventory.class);

    /** The elements of a sheet drawing that place a shape on the sheet. */
    protected static final Set<String> ANCHORS = Set.of("twoCellAnchor", "oneCellAnchor", "absoluteAnchor");

    /**
     * Lists the pictures of every sheet drawing without ever parsing a sheet: {@code .xlsx} through
     * the package parts, binary {@code .xls} through the usermodel, which HSSF loads whole anyway.
     * <p>
     * The usermodel parses every sheet into an XMLBeans tree to reach drawings that are separate
     * parts, so a workbook with a few hundred thousand cells would cost hundreds of megabytes for a
     * single picture.
     */
    @Override
    public DiffableContent extract(Blob blob, int maxLines) throws IOException {
        var inventory = new Inventory(maxLines, new ImageBudget(new HashMap<>()));
        boolean truncated;
        try (MaterializedBlob local = MaterializedBlob.of(blob)) {
            File file = local.file();
            if (FileMagic.valueOf(file) == FileMagic.OOXML) {
                truncated = streamSpreadsheetImages(OoxmlPackages.open(file), inventory);
            } else {
                try (Workbook workbook = WorkbookFactory.create(file, null, true)) {
                    truncated = collectSpreadsheetImages(workbook, inventory);
                }
            }
        }
        if (truncated) {
            inventory.truncate();
        }
        return inventory.result();
    }

    /**
     * Reads the drawing of each sheet, then releases the package.
     *
     * @return {@code true} if the {@code maxLines} budget was exhausted
     * @since 2025.1
     */
    protected boolean streamSpreadsheetImages(OPCPackage pkg, Inventory inventory) throws IOException {
        try {
            XSSFReader.SheetIterator sheets = new XSSFReader(pkg).getSheetIterator();
            while (sheets.hasNext()) {
                // Only the part is wanted: the stream next() opens on the sheet is never read.
                sheets.next().close();
                List<PackagePart> drawings = OoxmlPackages.relatedOfType(sheets.getSheetPart(),
                        XSSFRelation.DRAWINGS.getRelation());
                if (!drawings.isEmpty()) {
                    PackagePart drawing = drawings.get(0);
                    OoxmlPackages.parse(drawing, new DrawingPictures(drawing, sheets.getSheetName(), inventory));
                }
            }
            return false;
        } catch (OoxmlPackages.Stop e) {
            return true;
        } catch (SAXException e) {
            throw OoxmlPackages.unreadable(e, "OOXML workbook");
        } catch (OpenXML4JException e) {
            throw new IOException("Not a readable OOXML workbook", e);
        } finally {
            pkg.revert();
        }
    }

    /**
     * The pictures of a sheet drawing, as {@code XSSFDrawing#getShapes()} lists them: a {@code pic}
     * placed directly in a cell anchor. The usermodel lists none inside a group or alternate content
     * either.
     * <p>
     * A two-cell anchor is keyed on its two cells. A one-cell anchor is keyed on its cell alone: the
     * usermodel derives the second one from the size of the picture and the row heights and column
     * widths of the sheet, which would mean parsing it. An absolute anchor gets an ordinal.
     *
     * @since 2025.1
     */
    protected class DrawingPictures extends DefaultHandler {

        protected final PackagePart drawing;

        protected final String sheetName;

        protected final Inventory inventory;

        protected final Map<String, Integer> seen = new HashMap<>();

        protected final List<String> path = new ArrayList<>();

        protected int ordinal;

        protected String anchor;

        /** Row and column of the {@code from} and {@code to} cells, -1 when not given. */
        protected int[] from;

        protected int[] to;

        protected StringBuilder number;

        protected String blip;

        protected DrawingPictures(PackagePart drawing, String sheetName, Inventory inventory) {
            this.drawing = drawing;
            this.sheetName = sheetName;
            this.inventory = inventory;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            path.add(localName);
            int depth = path.size();
            if (depth == 2) {
                anchor = ANCHORS.contains(localName) ? localName : null;
                from = null;
                to = null;
            } else if (anchor == null) {
                return;
            } else if (depth == 3 && "from".equals(localName)) {
                from = new int[] { -1, -1 };
            } else if (depth == 3 && "to".equals(localName)) {
                to = new int[] { -1, -1 };
            } else if (depth == 3 && "pic".equals(localName)) {
                blip = null;
            } else if (depth == 4 && ("row".equals(localName) || "col".equals(localName))
                    && ("from".equals(path.get(2)) || "to".equals(path.get(2)))) {
                number = new StringBuilder();
            } else if (depth == 5 && "blip".equals(localName) && "pic".equals(path.get(2))
                    && "blipFill".equals(path.get(3))) {
                blip = OoxmlPackages.relationshipAttribute(attributes, "embed");
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (number != null) {
                number.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            int depth = path.size();
            if (depth == 4 && number != null) {
                int[] cell = "from".equals(path.get(2)) ? from : to;
                cell["row".equals(localName) ? 0 : 1] = parseIndex(number.toString());
                number = null;
            } else if (depth == 3 && anchor != null && "pic".equals(localName) && blip != null && !blip.isEmpty()) {
                picture();
            }
            path.remove(depth - 1);
        }

        protected void picture() throws SAXException {
            PackagePart media = OoxmlPackages.related(drawing, blip);
            if (media == null) {
                return; // linked (external) picture: no embedded bytes to digest
            }
            if (inventory.isFull()) {
                throw new OoxmlPackages.Stop();
            }
            String location;
            if ("twoCellAnchor".equals(anchor) && isCell(from) && isCell(to)) {
                location = cell(from) + ":" + cell(to);
            } else if ("oneCellAnchor".equals(anchor) && isCell(from)) {
                location = cell(from);
            } else {
                // The ordinal advances even for a dropped image: renumbering one side would make
                // every following image look changed.
                location = "image:" + (++ordinal);
            }
            inventory(media, occurrence(seen, "excel:image:" + sheetName + ":" + location), inventory);
        }

        protected boolean isCell(int[] cell) {
            return cell != null && cell[0] >= 0 && cell[1] >= 0;
        }

        protected String cell(int[] cell) {
            return new CellReference(cell[0], cell[1]).formatAsString(false);
        }

        protected int parseIndex(String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    /**
     * Lists the pictures through the usermodel, for binary {@code .xls}.
     *
     * @return {@code true} if the {@code maxLines} budget was exhausted
     */
    protected boolean collectSpreadsheetImages(Workbook workbook, Inventory inventory) throws IOException {
        ImageBudget budget = inventory.budget;
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            Drawing<?> drawing = sheet.getDrawingPatriarch();
            if (drawing == null) {
                continue;
            }
            int ordinal = 0;
            for (Shape shape : drawing) {
                if (shape instanceof Picture picture) {
                    if (inventory.isFull()) {
                        return true;
                    }
                    PictureData data = picture.getPictureData();
                    if (data == null) {
                        continue;
                    }
                    ClientAnchor anchor = picture.getClientAnchor();
                    // The ordinal advances even for a skipped image: an anchor-less key is
                    // positional, so renumbering one side would make every following image
                    // look changed.
                    String location = anchor == null ? "image:" + (++ordinal) : anchor(anchor);
                    String key = occurrence(seen, "excel:image:" + sheet.getSheetName() + ":" + location);
                    // getData() copies the picture on every call: memoized on the picture index, and
                    // only called on the first placement.
                    int index = picture instanceof HSSFPicture hssf ? hssf.getPictureIndex() : -1;
                    String digest = digestOnce(budget, index >= 0 ? Integer.valueOf(index) : key, () -> {
                        byte[] bytes = data.getData();
                        budget.spend(bytes.length);
                        return digestBytes(bytes, key);
                    });
                    if (budget.exhausted) {
                        return true;
                    }
                    inventory.add(key, digest);
                }
            }
        }
        return false;
    }

    /**
     * Digests a picture whose bytes are already in heap, the binary {@code .xls} case: it has no
     * package part to stream. The length is still checked, so an over-sized image is reported the
     * same way whatever the container.
     * <p>
     * Not free for all that: {@code HSSFPictureData#getData()} returns a <b>copy</b> of the picture
     * whenever it is 16 bytes or more long, to look for a Mac PNG header, so the caller reads it once
     * per picture through {@link #digestOnce}, keyed on the picture index.
     *
     * @return the digest, or {@code null} if the image is over budget and must be dropped
     * @since 2025.1
     */
    protected String digestBytes(byte[] bytes, String key) {
        if (bytes.length > maxImageBytes()) {
            log.warn("Skipping {}: {} bytes, over the {} byte inventory budget", key, bytes.length, maxImageBytes());
            return null;
        }
        return digest(bytes);
    }

    protected String anchor(ClientAnchor anchor) {
        return new CellReference(anchor.getRow1(), anchor.getCol1()).formatAsString(false) + ":"
                + new CellReference(anchor.getRow2(), anchor.getCol2()).formatAsString(false);
    }
}
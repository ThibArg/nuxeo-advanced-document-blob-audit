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
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.cells;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.onDisk;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.xlsx;

import java.io.ByteArrayOutputStream;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.Test;
import org.nuxeo.audit.advanced.blob.extractor.PresentationExtractor;
import org.nuxeo.audit.advanced.blob.extractor.SpreadsheetExtractor;
import org.nuxeo.audit.advanced.blob.image.ImageInventoryExtractor;
import org.nuxeo.ecm.core.api.Blob;

/**
 * The POI extractors read the local file when the blob has one, and fall back to the stream.
 * <p>
 * POI cannot reposition an {@code InputStream}, so {@code WorkbookFactory.create(InputStream)} and
 * {@code new XMLSlideShow(InputStream)} buffer the <b>whole</b> OOXML package in heap before
 * building the model. The {@code File} variants open the zip in random access, read only, and read
 * the parts on demand. {@code BlobDiffComponent#diff} already materialises both binaries locally
 * for exactly that reason, so the file branch is the one that runs in production - yet every other
 * extractor test builds its fixtures in memory and therefore only ever exercises the fallback.
 * <p>
 * What is asserted here is <b>equivalence</b>: the two branches must produce the same content. The
 * memory saving itself is not unit-testable in any dependable way; it is covered by the manual
 * check recorded in item 22 of {@code AGENTS.md}.
 *
 * @since 2025.4
 */
public class TestExtractorFileBacking {

    protected static final int MAX_LINES = 10000;

    protected final SpreadsheetExtractor spreadsheet = new SpreadsheetExtractor();

    protected final PresentationExtractor presentation = new PresentationExtractor();

    protected final ImageInventoryExtractor images = new ImageInventoryExtractor();

    protected Blob pptx(String filename, String... slideTexts) throws Exception {
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : slideTexts) {
                XSLFSlide slide = show.createSlide();
                XSLFTextBox box = slide.createTextBox();
                box.setText(text);
            }
            show.write(out);
            return BlobAuditTestHelper.blob(out.toByteArray(), BlobAuditTestHelper.PPTX_MIME, filename);
        }
    }

    /** Both branches must be reachable, otherwise the comparisons below prove nothing. */
    @Test
    public void testTheFixturesCoverBothBranches() throws Exception {
        Blob inMemory = xlsx("Sheet1", cells("A1", "Widget"), "f.xlsx");

        assertNull("the in-memory fixture must exercise the stream fallback", inMemory.getFile());
        assertNotNull("onDisk() must exercise the file branch", onDisk(inMemory).getFile());
    }

    @Test
    public void testSpreadsheetExtractionIsIdenticalFromFileAndFromStream() throws Exception {
        Blob inMemory = xlsx("Sheet1", cells("A1", "Product", "B1", "Qty", "A2", "Widget", "B2", "100"), "f.xlsx");

        DiffableContent fromStream = spreadsheet.extract(inMemory, MAX_LINES);
        DiffableContent fromFile = spreadsheet.extract(onDisk(inMemory), MAX_LINES);

        assertEquals(4, fromFile.size());
        assertEquals(fromStream.lines(), fromFile.lines());
        assertEquals(fromStream.keyed(), fromFile.keyed());
        assertEquals(fromStream.truncated(), fromFile.truncated());
    }

    @Test
    public void testPresentationExtractionIsIdenticalFromFileAndFromStream() throws Exception {
        Blob inMemory = pptx("f.pptx", "Intro", "Budget is 1000");

        DiffableContent fromStream = presentation.extract(inMemory, MAX_LINES);
        DiffableContent fromFile = presentation.extract(onDisk(inMemory), MAX_LINES);

        assertFalse(fromFile.lines().isEmpty());
        assertEquals(fromStream.lines(), fromFile.lines());
        assertEquals(fromStream.keyed(), fromFile.keyed());
    }

    /** The image inventory has its own pair of POI call sites, on the same two formats. */
    @Test
    public void testImageInventoryIsIdenticalFromFileAndFromStream() throws Exception {
        Blob workbook = xlsx("Sheet1", cells("A1", "Widget"), "f.xlsx");
        Blob deck = pptx("f.pptx", "Intro");

        assertEquals(images.extract(workbook, MAX_LINES).lines(), images.extract(onDisk(workbook), MAX_LINES).lines());
        assertEquals(images.extract(deck, MAX_LINES).lines(), images.extract(onDisk(deck), MAX_LINES).lines());
    }

    /**
     * {@code maxLines} must still cut at the same place on the file branch: the truncation lives in
     * the collector, which both branches share, and that sharing is the point of the refactoring.
     */
    @Test
    public void testTruncationBehavesIdenticallyOnBothBranches() throws Exception {
        Blob inMemory = xlsx("Sheet1", cells("A1", "a", "A2", "b", "A3", "c", "A4", "d"), "f.xlsx");

        DiffableContent fromStream = spreadsheet.extract(inMemory, 2);
        DiffableContent fromFile = spreadsheet.extract(onDisk(inMemory), 2);

        assertTrue(fromFile.truncated());
        assertEquals(2, fromFile.size());
        assertEquals(fromStream.lines(), fromFile.lines());
    }
}

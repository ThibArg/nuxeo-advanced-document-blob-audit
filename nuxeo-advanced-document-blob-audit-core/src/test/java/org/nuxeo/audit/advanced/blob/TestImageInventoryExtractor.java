/* (C) Copyright 2026 Nuxeo SA and others. Licensed under Apache License 2.0. */
package org.nuxeo.audit.advanced.blob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.Test;
import org.nuxeo.audit.advanced.blob.image.ImageInventoryExtractor;
import org.nuxeo.ecm.core.api.Blob;

public class TestImageInventoryExtractor {

    protected static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    protected final ImageInventoryExtractor extractor = new ImageInventoryExtractor();

    @Test
    public void testWordImageIsInventoriedByMediaPart() throws Exception {
        Blob blob;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().addPicture(new java.io.ByteArrayInputStream(PNG),
                    Document.PICTURE_TYPE_PNG, "logo.png", 9525, 9525);
            document.write(out);
            blob = BlobAuditTestHelper.blob(out.toByteArray(), BlobAuditTestHelper.DOCX_MIME, "image.docx");
        }
        DiffableContent content = extractor.extract(blob);
        assertEquals(1, content.size());
        assertTrue(content.lines().get(0).key().startsWith("word:image:"));
        assertTrue(content.lines().get(0).value().startsWith("sha256:"));
    }

    @Test
    public void testExcelImageUsesSheetAndAnchor() throws Exception {
        Blob blob;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Budget");
            CreationHelper helper = workbook.getCreationHelper();
            ClientAnchor anchor = helper.createClientAnchor();
            anchor.setCol1(1); anchor.setRow1(3); anchor.setCol2(5); anchor.setRow2(17);
            sheet.createDrawingPatriarch().createPicture(anchor, workbook.addPicture(PNG, XSSFWorkbook.PICTURE_TYPE_PNG));
            workbook.write(out);
            blob = BlobAuditTestHelper.blob(out.toByteArray(), BlobAuditTestHelper.XLSX_MIME, "image.xlsx");
        }
        DiffableContent content = extractor.extract(blob);
        assertEquals(1, content.size());
        assertEquals("excel:image:Budget:B4:F18", content.lines().get(0).key());
    }

    @Test
    public void testKeyedDiffReportsReplacement() throws Exception {
        DiffableContent before = DiffableContent.keyed(java.util.List.of(ContentLine.of("word:image:image1.png", "sha256:a")), false);
        DiffableContent after = DiffableContent.keyed(java.util.List.of(ContentLine.of("word:image:image1.png", "sha256:b")), false);
        DiffResult result = new TextDiffer().diff(before, after);
        assertEquals(1, result.changed());
    }
}

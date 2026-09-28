/*
 * (C) Copyright 2026 Nuxeo SA and others.
 * Licensed under the Apache License, Version 2.0.
 */
package org.nuxeo.audit.advanced.blob.image;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.PictureData;
import org.apache.poi.ss.usermodel.Shape;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.nuxeo.audit.advanced.blob.ContentLine;
import org.nuxeo.audit.advanced.blob.DiffableContent;
import org.nuxeo.ecm.core.api.Blob;

/** Digest-based image inventory used by imageAnalysisLevel 1. */
public class ImageInventoryExtractor {

    public DiffableContent extract(Blob blob) throws Exception {
        String mime = blob.getMimeType() == null ? "" : blob.getMimeType().toLowerCase();
        if (mime.contains("wordprocessingml")) {
            return extractDocx(blob);
        }
        if (mime.contains("spreadsheetml") || mime.contains("ms-excel")) {
            return extractSpreadsheet(blob);
        }
        if ("application/pdf".equals(mime)) {
            return extractPdf(blob);
        }
        return DiffableContent.keyed(List.of(), false);
    }

    protected DiffableContent extractDocx(Blob blob) throws Exception {
        List<ContentLine> images = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(blob.getStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().startsWith("word/media/")) {
                    images.add(ContentLine.of("word:image:" + entry.getName().substring("word/media/".length()),
                            digest(zip.readAllBytes())));
                }
            }
        }
        return DiffableContent.keyed(images, false);
    }

    protected DiffableContent extractSpreadsheet(Blob blob) throws Exception {
        List<ContentLine> images = new ArrayList<>();
        try (InputStream in = blob.getStream(); Workbook workbook = WorkbookFactory.create(in)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                Drawing<?> drawing = sheet.getDrawingPatriarch();
                if (drawing == null) {
                    continue;
                }
                int ordinal = 0;
                for (Shape shape : drawing) {
                    if (shape instanceof Picture picture) {
                        PictureData data = picture.getPictureData();
                        ClientAnchor anchor = picture.getClientAnchor();
                        String location = anchor == null ? "image:" + (++ordinal) : anchor(anchor);
                        images.add(ContentLine.of("excel:image:" + sheet.getSheetName() + ":" + location,
                                digest(data.getData())));
                    }
                }
            }
        }
        return DiffableContent.keyed(images, false);
    }

    protected String anchor(ClientAnchor anchor) {
        return new CellReference(anchor.getRow1(), anchor.getCol1()).formatAsString(false) + ":"
                + new CellReference(anchor.getRow2(), anchor.getCol2()).formatAsString(false);
    }

    protected DiffableContent extractPdf(Blob blob) throws Exception {
        List<ContentLine> images = new ArrayList<>();
        byte[] bytes;
        try (InputStream in = blob.getStream()) {
            bytes = in.readAllBytes();
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            int pageNumber = 0;
            for (PDPage page : document.getPages()) {
                pageNumber++;
                collectPdfResources(page.getResources(), "pdf:page:" + pageNumber, images, new int[] { 0 });
            }
        }
        return DiffableContent.keyed(images, false);
    }

    protected void collectPdfResources(PDResources resources, String prefix, List<ContentLine> images, int[] ordinal)
            throws Exception {
        if (resources == null) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDImageXObject image) {
                try (InputStream in = image.createInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    in.transferTo(out);
                    images.add(ContentLine.of(prefix + ":image:" + (++ordinal[0]), digest(out.toByteArray())));
                }
            } else if (object instanceof PDFormXObject form) {
                collectPdfResources(form.getResources(), prefix, images, ordinal);
            }
        }
    }

    protected String digest(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

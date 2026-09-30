/*
 * (C) Copyright 2026 Nuxeo SA and others.
 * Licensed under the Apache License, Version 2.0.
 */
package org.nuxeo.audit.advanced.blob.image;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.nuxeo.audit.advanced.blob.BlobTextExtractor;
import org.nuxeo.audit.advanced.blob.ContentLine;
import org.nuxeo.audit.advanced.blob.DiffableContent;
import org.nuxeo.audit.advanced.blob.io.MaterializedBlob;
import org.nuxeo.ecm.core.api.Blob;

/**
 * Digest-based image inventory used by {@code imageAnalysisLevel 1}.
 * <p>
 * A regular {@link BlobTextExtractor}, contributed to the {@code imageExtractors} extension point
 * of {@code org.nuxeo.audit.advanced.blob.BlobDiffComponent}: selectable and disableable per mime
 * type like any other extractor, and bounded by the configured {@code maxLines}.
 * <p>
 * The output is <b>keyed</b> content, one entry per placed image, the value being the SHA-256 of
 * its bytes. It is compared by the same {@code TextDiffer} as the text, then merged into a
 * {@code # Images} section of the unified diff.
 *
 * @since 1.0
 */
public class ImageInventoryExtractor implements BlobTextExtractor {

    @Override
    public DiffableContent extract(Blob blob, int maxLines) throws Exception {
        String mime = blob.getMimeType() == null ? "" : blob.getMimeType().toLowerCase();
        if (mime.contains("wordprocessingml")) {
            return extractDocx(blob, maxLines);
        }
        if (mime.contains("spreadsheetml") || mime.contains("ms-excel")) {
            return extractSpreadsheet(blob, maxLines);
        }
        if (mime.contains("presentationml")) {
            return extractPresentation(blob, maxLines);
        }
        if ("application/pdf".equals(mime)) {
            return extractPdf(blob, maxLines);
        }
        return DiffableContent.keyed(List.of(), false);
    }

    protected DiffableContent extractDocx(Blob blob, int maxLines) throws Exception {
        List<ContentLine> images = new ArrayList<>();
        boolean truncated = false;
        try (ZipInputStream zip = new ZipInputStream(blob.getStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().startsWith("word/media/")) {
                    continue;
                }
                if (images.size() >= maxLines) {
                    truncated = true;
                    break;
                }
                images.add(ContentLine.of("word:image:" + entry.getName().substring("word/media/".length()),
                        digest(zip.readAllBytes())));
            }
        }
        return DiffableContent.keyed(images, truncated);
    }

    protected DiffableContent extractSpreadsheet(Blob blob, int maxLines) throws Exception {
        List<ContentLine> images = new ArrayList<>();
        boolean truncated = false;
        try (InputStream in = blob.getStream(); Workbook workbook = WorkbookFactory.create(in)) {
            sheets: for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                Drawing<?> drawing = sheet.getDrawingPatriarch();
                if (drawing == null) {
                    continue;
                }
                int ordinal = 0;
                for (Shape shape : drawing) {
                    if (shape instanceof Picture picture) {
                        if (images.size() >= maxLines) {
                            truncated = true;
                            break sheets;
                        }
                        PictureData data = picture.getPictureData();
                        ClientAnchor anchor = picture.getClientAnchor();
                        String location = anchor == null ? "image:" + (++ordinal) : anchor(anchor);
                        images.add(ContentLine.of("excel:image:" + sheet.getSheetName() + ":" + location,
                                digest(data.getData())));
                    }
                }
            }
        }
        return DiffableContent.keyed(images, truncated);
    }

    protected String anchor(ClientAnchor anchor) {
        return new CellReference(anchor.getRow1(), anchor.getCol1()).formatAsString(false) + ":"
                + new CellReference(anchor.getRow2(), anchor.getCol2()).formatAsString(false);
    }

    /**
     * PDFBox reads the document straight from the file. The previous implementation did
     * {@code in.readAllBytes()}, holding the whole PDF in heap - twice per diff, times the
     * concurrency of the {@code blobDiff} queue.
     */
    protected DiffableContent extractPdf(Blob blob, int maxLines) throws Exception {
        List<ContentLine> images = new ArrayList<>();
        boolean truncated = false;
        try (MaterializedBlob local = MaterializedBlob.of(blob)) {
            File file = local.blob().getFile();
            if (file == null) {
                // Materialisation failed: degraded, but still better than losing the inventory.
                try (InputStream in = blob.getStream()) {
                    truncated = collectPdf(Loader.loadPDF(in.readAllBytes()), images, maxLines);
                }
            } else {
                truncated = collectPdf(Loader.loadPDF(file), images, maxLines);
            }
        }
        return DiffableContent.keyed(images, truncated);
    }

    protected boolean collectPdf(PDDocument document, List<ContentLine> images, int maxLines) throws Exception {
        try (document) {
            int pageNumber = 0;
            for (PDPage page : document.getPages()) {
                pageNumber++;
                if (collectPdfResources(page.getResources(), "pdf:page:" + pageNumber, images, new int[] { 0 },
                        maxLines)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** @return {@code true} if the {@code maxLines} budget was exhausted */
    protected boolean collectPdfResources(PDResources resources, String prefix, List<ContentLine> images,
            int[] ordinal, int maxLines) throws Exception {
        if (resources == null) {
            return false;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDImageXObject image) {
                if (images.size() >= maxLines) {
                    return true;
                }
                try (InputStream in = image.createInputStream();
                        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    in.transferTo(out);
                    images.add(ContentLine.of(prefix + ":image:" + (++ordinal[0]), digest(out.toByteArray())));
                }
            } else if (object instanceof PDFormXObject form
                    && collectPdfResources(form.getResources(), prefix, images, ordinal, maxLines)) {
                return true;
            }
        }
        return false;
    }

    protected String digest(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * PowerPoint: one entry per picture placed on a slide, keyed by
     * {@code powerpoint:slide:<n>:image:<media-part>}. The same media part used twice on one slide
     * gets a {@code #2}, {@code #3}... suffix so no placement is lost. Pictures inside group shapes
     * are included; layouts and masters are not (they belong to the template, not the content).
     */
    protected DiffableContent extractPresentation(Blob blob, int maxLines) throws Exception {
        List<ContentLine> images = new ArrayList<>();
        boolean truncated = false;
        try (InputStream in = blob.getStream(); XMLSlideShow show = new XMLSlideShow(in)) {
            int slideNumber = 0;
            for (XSLFSlide slide : show.getSlides()) {
                slideNumber++;
                Map<String, Integer> seen = new HashMap<>();
                if (collectSlidePictures(slide.getShapes(), "powerpoint:slide:" + slideNumber + ":image:", images,
                        seen, maxLines)) {
                    truncated = true;
                    break;
                }
            }
        }
        return DiffableContent.keyed(images, truncated);
    }

    /** @return {@code true} if the {@code maxLines} budget was exhausted */
    protected boolean collectSlidePictures(List<? extends XSLFShape> shapes, String prefix, List<ContentLine> images,
            Map<String, Integer> seen, int maxLines) throws Exception {
        for (XSLFShape shape : shapes) {
            if (shape instanceof XSLFGroupShape group) {
                if (collectSlidePictures(group.getShapes(), prefix, images, seen, maxLines)) {
                    return true;
                }
            } else if (shape instanceof XSLFPictureShape picture) {
                XSLFPictureData data = picture.getPictureData();
                if (data == null) {
                    continue; // linked (external) picture: no embedded bytes to digest
                }
                if (images.size() >= maxLines) {
                    return true;
                }
                String name = data.getFileName();
                int count = seen.merge(name, 1, Integer::sum);
                String key = prefix + name + (count > 1 ? "#" + count : "");
                images.add(ContentLine.of(key, digest(data.getData())));
            }
        }
        return false;
    }
}

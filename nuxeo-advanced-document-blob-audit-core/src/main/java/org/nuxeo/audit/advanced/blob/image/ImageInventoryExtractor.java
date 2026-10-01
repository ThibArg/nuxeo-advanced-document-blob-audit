/*
 * (C) Copyright 2026 Nuxeo SA and others.
 * Licensed under the Apache License, Version 2.0.
 */
package org.nuxeo.audit.advanced.blob.image;

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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    private static final Logger log = LogManager.getLogger(ImageInventoryExtractor.class);

    /**
     * Upper bound on the <b>decompressed</b> size of a single embedded image, in bytes.
     * <p>
     * Nothing else bounded it. The only guard upstream is {@code BlobDiffComponent#getEligibility},
     * which compares {@code blob.getLength()} - the size of the container, deflated - to
     * {@code maxBlobSize} (10 MB by default). Ten megabytes of deflate expand to several gigabytes,
     * and {@code java.util.zip.ZipInputStream} has none of POI's {@code ZipSecureFile} protections.
     * Times two concurrent works on the {@code blobDiff} queue, times the two retries of
     * {@code getRetryCount()}, that was an OOM reachable by anyone able to version a {@code .docx}.
     *
     * @since 2025.4
     */
    protected static final long MAX_IMAGE_BYTES = 32L * 1024 * 1024;

    /** Maximum inflated/deflated ratio of a zip entry. The value POI's {@code ZipSecureFile} uses. */
    protected static final double MAX_INFLATION_RATIO = 100.0d;

    /**
     * Below this inflated size the ratio is not evidence of anything: a small solid-colour bitmap
     * legitimately deflates by far more than {@link #MAX_INFLATION_RATIO}. POI makes the same
     * allowance, under the same name.
     */
    protected static final long GRACE_ENTRY_SIZE = 100L * 1024;

    /** Overridable so a test can lower the cap instead of building a real multi-gigabyte bomb. */
    protected long maxImageBytes() {
        return MAX_IMAGE_BYTES;
    }

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

    /**
     * Streams every {@code word/media/} part, never materialising one.
     * <p>
     * An entry over {@link #maxImageBytes()} is dropped and flags the inventory as truncated: the
     * report loses one line, which is the whole point - the previous {@code zip.readAllBytes()}
     * allocated an array of the <b>inflated</b> size with no ceiling at all.
     */
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
                String name = entry.getName().substring("word/media/".length());
                if (isInflationBomb(entry)) {
                    log.warn("Skipping image {}: {} bytes deflated to {}, over the inventory budget", name,
                            entry.getSize(), entry.getCompressedSize());
                    truncated = true;
                    continue;
                }
                // Not closed on purpose: the iteration must go on, and getNextEntry() skips
                // whatever is left of a partially read entry.
                String digest = digestStream(zip);
                if (digest == null) {
                    log.warn("Skipping image {}: inflates past the {} byte inventory budget", name, maxImageBytes());
                    truncated = true;
                    continue;
                }
                images.add(ContentLine.of("word:image:" + name, digest));
            }
        }
        return DiffableContent.keyed(images, truncated);
    }

    /**
     * Cheap pre-check on the declared sizes, before a single byte is inflated.
     * <p>
     * Deliberately opportunistic: read through a {@link ZipInputStream}, an entry whose sizes live
     * in a trailing data descriptor rather than in the local header reports {@code -1} for both, and
     * this returns {@code false}. {@link #digestStream} is the guard that always applies.
     */
    protected boolean isInflationBomb(ZipEntry entry) {
        long inflated = entry.getSize();
        long deflated = entry.getCompressedSize();
        if (inflated < 0 || deflated <= 0) {
            return false;
        }
        return inflated > maxImageBytes()
                || (inflated > GRACE_ENTRY_SIZE && (double) inflated / deflated > MAX_INFLATION_RATIO);
    }

    /**
     * SHA-256 of the stream, computed in 8 KB chunks and abandoned past {@link #maxImageBytes()}.
     * <p>
     * Does <b>not</b> close the stream: the callers iterate over a shared {@link ZipInputStream}.
     *
     * @return the digest, or {@code null} if the stream went over the budget
     * @since 2025.4
     */
    protected String digestStream(InputStream in) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxImageBytes()) {
                return null;
            }
            digest.update(buffer, 0, read);
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
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
        boolean[] dropped = new boolean[1];
        try (MaterializedBlob local = MaterializedBlob.of(blob)) {
            File file = local.blob().getFile();
            if (file == null) {
                // Materialisation failed: degraded, but still better than losing the inventory.
                try (InputStream in = blob.getStream()) {
                    truncated = collectPdf(Loader.loadPDF(in.readAllBytes()), images, maxLines, dropped);
                }
            } else {
                truncated = collectPdf(Loader.loadPDF(file), images, maxLines, dropped);
            }
        }
        return DiffableContent.keyed(images, truncated || dropped[0]);
    }

    protected boolean collectPdf(PDDocument document, List<ContentLine> images, int maxLines, boolean[] dropped)
            throws Exception {
        try (document) {
            int pageNumber = 0;
            for (PDPage page : document.getPages()) {
                pageNumber++;
                if (collectPdfResources(page.getResources(), "pdf:page:" + pageNumber, images, new int[] { 0 },
                        maxLines, dropped)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @param dropped one-element out parameter, set when an image was skipped for being over
     *            budget. Separate from the return value, which means "the {@code maxLines} budget is
     *            exhausted, stop": an over-sized image must be skipped without ending the walk.
     * @return {@code true} if the {@code maxLines} budget was exhausted
     */
    protected boolean collectPdfResources(PDResources resources, String prefix, List<ContentLine> images,
            int[] ordinal, int maxLines, boolean[] dropped) throws Exception {
        if (resources == null) {
            return false;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDImageXObject image) {
                if (images.size() >= maxLines) {
                    return true;
                }
                // The ordinal advances even for a skipped image: keys are positional here, so
                // renumbering one side would make every following image look changed.
                String key = prefix + ":image:" + (++ordinal[0]);
                if (exceedsPixelBudget(image)) {
                    log.warn("Skipping {}: {}x{} at {} bits per component is over the inventory budget", key,
                            image.getWidth(), image.getHeight(), image.getBitsPerComponent());
                    dropped[0] = true;
                    continue;
                }
                try (InputStream in = image.createInputStream()) {
                    String digest = digestStream(in);
                    if (digest == null) {
                        log.warn("Skipping {}: decodes past the {} byte inventory budget", key, maxImageBytes());
                        dropped[0] = true;
                        continue;
                    }
                    images.add(ContentLine.of(key, digest));
                }
            } else if (object instanceof PDFormXObject form
                    && collectPdfResources(form.getResources(), prefix, images, ordinal, maxLines, dropped)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rejects an image on its declared geometry, before reading a byte of it.
     * <p>
     * {@code image.createInputStream()} hands back the <b>decoded</b> stream - PDFBox has already
     * applied {@code FlateDecode} - so the previous {@code transferTo} into a
     * {@code ByteArrayOutputStream} followed by {@code toByteArray()} held two full copies of it in
     * heap, for an image whose size the PDF alone declares.
     * <p>
     * The estimate ignores the number of colour components, so it <b>under</b>-estimates a RGB
     * image threefold. That is the safe direction for a pre-check: what it lets through,
     * {@link #digestStream} still stops.
     *
     * @since 2025.4
     */
    protected boolean exceedsPixelBudget(PDImageXObject image) {
        int bitsPerComponent = Math.max(image.getBitsPerComponent(), 1);
        // Expressed as a pixel count rather than a byte count, so an absurd declaration cannot
        // overflow the multiplication it is compared against.
        long maxPixels = maxImageBytes() * 8 / bitsPerComponent;
        long pixels = (long) Math.max(image.getWidth(), 0) * Math.max(image.getHeight(), 0);
        return pixels > maxPixels;
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

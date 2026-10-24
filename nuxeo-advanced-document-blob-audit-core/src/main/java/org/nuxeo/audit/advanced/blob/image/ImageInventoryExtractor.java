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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
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
import org.nuxeo.audit.advanced.blob.BlobTextExtractor;
import org.nuxeo.audit.advanced.blob.ContentLine;
import org.nuxeo.audit.advanced.blob.DiffableContent;
import org.nuxeo.audit.advanced.blob.extractor.SlideHandler;
import org.nuxeo.audit.advanced.blob.io.MaterializedBlob;
import org.nuxeo.audit.advanced.blob.io.OoxmlPackages;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

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
 * @since 2025.1
 */
public class ImageInventoryExtractor implements BlobTextExtractor {

    private static final Logger log = LogManager.getLogger(ImageInventoryExtractor.class);

    /**
     * Upper bound on the size of a single embedded image as stored, in bytes: the <b>inflated</b> media
     * part for OOXML, the <b>encoded</b> stream for PDF, which is never decoded.
     * <p>
     * Nothing else bounded it. The only guard upstream is {@code BlobDiffComponent#getEligibility},
     * which compares {@code blob.getLength()} - the size of the container, deflated - to
     * {@code maxBlobSize} (10 MB by default). Ten megabytes of deflate expand to several gigabytes,
     * and {@code java.util.zip.ZipInputStream} has none of POI's {@code ZipSecureFile} protections.
     * Times two concurrent works on the {@code blobDiff} queue, times the two retries of
     * {@code getRetryCount()}, that was an OOM reachable by anyone able to version a {@code .docx}.
     *
     * @since 2025.1
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

    /**
     * Nesting bound when rendering PDF filter parameters, so a self-referencing dictionary cannot
     * recurse for ever.
     */
    protected static final int MAX_COS_DEPTH = 4;

    /** Nesting bound for PDF form XObjects, far past anything a real producer nests. */
    protected static final int MAX_FORM_DEPTH = 16;

    /** The elements of a sheet drawing that place a shape on the sheet. */
    protected static final Set<String> ANCHORS = Set.of("twoCellAnchor", "oneCellAnchor", "absoluteAnchor");

    /** Overridable so a test can lower the cap instead of building a real multi-gigabyte bomb. */
    protected long maxImageBytes() {
        return MAX_IMAGE_BYTES;
    }

    @Override
    public DiffableContent extract(Blob blob, int maxLines) throws IOException {
        String mime = blob.getMimeType() == null ? "" : blob.getMimeType().toLowerCase(Locale.ROOT);
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
    protected DiffableContent extractDocx(Blob blob, int maxLines) throws IOException {
        List<ContentLine> images = new ArrayList<>();
        boolean truncated = false;
        // Two resources on purpose: if the ZipInputStream constructor throws - it allocates a
        // buffer and an Inflater - the underlying stream must still be closed.
        try (InputStream source = blob.getStream(); ZipInputStream zip = new ZipInputStream(source)) {
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
     * @since 2025.1
     */
    protected String digestStream(InputStream in) throws IOException {
        return digestStream(newDigest(), in);
    }

    /**
     * Same as {@link #digestStream(InputStream)}, continuing a digest that may already hold a header.
     *
     * @since 2025.1
     */
    protected String digestStream(MessageDigest digest, InputStream in) throws IOException {
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

    /**
     * Returns a SHA-256 digester.
     * <p>
     * The JCA guarantees {@code SHA-256} on every conformant JDK, so a missing algorithm is an
     * unrecoverable environment fault, not something eleven call sites should carry a checked
     * exception for.
     *
     * @since 2025.1
     */
    protected MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new NuxeoException("SHA-256 is not available on this JVM", e);
        }
    }

    /**
     * Lists the pictures of every sheet drawing without ever parsing a sheet: {@code .xlsx} through
     * the package parts, binary {@code .xls} through the usermodel, which HSSF loads whole anyway.
     * <p>
     * The usermodel parsed every sheet into an XMLBeans tree to reach drawings that are separate
     * parts, so a workbook with a few hundred thousand cells cost hundreds of megabytes for a single
     * picture.
     */
    protected DiffableContent extractSpreadsheet(Blob blob, int maxLines) throws IOException {
        List<ContentLine> images = new ArrayList<>();
        boolean truncated;
        boolean[] dropped = new boolean[1];
        File file = blob.getFile();
        if (file != null) {
            if (FileMagic.valueOf(file) == FileMagic.OOXML) {
                truncated = streamSpreadsheetImages(OoxmlPackages.open(file), images, maxLines, dropped);
            } else {
                try (Workbook workbook = WorkbookFactory.create(file, null, true)) {
                    truncated = collectSpreadsheetImages(workbook, images, maxLines, dropped);
                }
            }
        } else {
            try (InputStream in = FileMagic.prepareToCheckMagic(blob.getStream())) {
                if (FileMagic.valueOf(in) == FileMagic.OOXML) {
                    truncated = streamSpreadsheetImages(OoxmlPackages.open(in), images, maxLines, dropped);
                } else {
                    try (Workbook workbook = WorkbookFactory.create(in)) {
                        truncated = collectSpreadsheetImages(workbook, images, maxLines, dropped);
                    }
                }
            }
        }
        return DiffableContent.keyed(images, truncated || dropped[0]);
    }

    /**
     * Reads the drawing of each sheet, then releases the package.
     *
     * @param dropped one-element out parameter, set when an image was skipped for being over budget
     * @return {@code true} if the {@code maxLines} budget was exhausted
     * @since 2025.1
     */
    protected boolean streamSpreadsheetImages(OPCPackage pkg, List<ContentLine> images, int maxLines,
            boolean[] dropped) throws IOException {
        try {
            XSSFReader.SheetIterator sheets = new XSSFReader(pkg).getSheetIterator();
            while (sheets.hasNext()) {
                // Only the part is wanted: the stream next() opens on the sheet is never read.
                sheets.next().close();
                List<PackagePart> drawings = OoxmlPackages.relatedOfType(sheets.getSheetPart(),
                        XSSFRelation.DRAWINGS.getRelation());
                if (!drawings.isEmpty()) {
                    PackagePart drawing = drawings.get(0);
                    OoxmlPackages.parse(drawing,
                            new DrawingPictures(drawing, sheets.getSheetName(), images, maxLines, dropped));
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
     * usermodel derived the second one from the size of the picture and the row heights and column
     * widths of the sheet, which would mean parsing it. An absolute anchor gets an ordinal.
     *
     * @since 2025.1
     */
    protected class DrawingPictures extends DefaultHandler {

        protected final PackagePart drawing;

        protected final String sheetName;

        protected final List<ContentLine> images;

        protected final int maxLines;

        protected final boolean[] dropped;

        protected final Map<String, Integer> seen = new HashMap<>();

        protected final List<String> path = new ArrayList<>();

        protected int ordinal;

        protected String anchor;

        /** Row and column of the {@code from} and {@code to} cells, -1 when not given. */
        protected int[] from;

        protected int[] to;

        protected StringBuilder number;

        protected String blip;

        protected DrawingPictures(PackagePart drawing, String sheetName, List<ContentLine> images, int maxLines,
                boolean[] dropped) {
            this.drawing = drawing;
            this.sheetName = sheetName;
            this.images = images;
            this.maxLines = maxLines;
            this.dropped = dropped;
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
            if (images.size() >= maxLines) {
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
            inventory(media, occurrence(seen, "excel:image:" + sheetName + ":" + location), images, dropped);
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
     * Appends {@code #2}, {@code #3}... to a key already used, so that two pictures with the same
     * location are both listed. Counted before the budget check: a dropped picture must not renumber
     * the ones after it.
     *
     * @since 2025.1
     */
    protected static String occurrence(Map<String, Integer> seen, String key) {
        int count = seen.merge(key, 1, Integer::sum);
        return count > 1 ? key + "#" + count : key;
    }

    /** Digests an embedded picture part and lists it, or flags the inventory if it is over budget. */
    protected void inventory(PackagePart media, String key, List<ContentLine> images, boolean[] dropped)
            throws SAXException {
        String digest;
        try {
            digest = digestOoxmlPicture(media, key);
        } catch (IOException e) {
            throw new SAXException(e);
        }
        if (digest == null) {
            dropped[0] = true;
        } else {
            images.add(ContentLine.of(key, digest));
        }
    }

    /**
     * Usermodel path, for binary {@code .xls}.
     *
     * @param dropped one-element out parameter, set when an image was skipped for being over budget
     * @return {@code true} if the {@code maxLines} budget was exhausted
     */
    protected boolean collectSpreadsheetImages(Workbook workbook, List<ContentLine> images, int maxLines,
            boolean[] dropped) {
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
                    if (images.size() >= maxLines) {
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
                    String digest = digestBytes(data.getData(), key);
                    if (digest == null) {
                        dropped[0] = true;
                        continue;
                    }
                    images.add(ContentLine.of(key, digest));
                }
            }
        }
        return false;
    }

    /**
     * Digests an embedded OOXML picture part without ever holding it whole in heap.
     * <p>
     * {@code PictureData#getData()} returns the <b>inflated</b> image as a single array, capped only
     * by POI's own {@code MAX_IMAGE_SIZE} (100 MB in 5.5.1). The part is streamed instead, so the
     * {@link #maxImageBytes()} budget is the same across the four formats.
     *
     * @return the digest, or {@code null} if the image is over budget and must be dropped
     * @since 2025.1
     */
    protected String digestOoxmlPicture(PackagePart part, String key) throws IOException {
        long declared = part.getSize();
        if (declared > maxImageBytes()) {
            log.warn("Skipping {}: {} declared bytes, over the {} byte inventory budget", key, declared,
                    maxImageBytes());
            return null;
        }
        try (InputStream in = part.getInputStream()) {
            String digest = digestStream(in);
            if (digest == null) {
                log.warn("Skipping {}: inflates past the {} byte inventory budget", key, maxImageBytes());
            }
            return digest;
        }
    }

    /**
     * Digests a picture whose bytes are already in heap, the binary {@code .xls} and {@code .ppt}
     * case: those have no package part, and their content is held by the in-memory workbook anyway,
     * so reading it adds no unbounded allocation. The length is still checked, so an over-sized
     * image is reported the same way whatever the container.
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

    /**
     * PDFBox reads the document straight from the file, so nothing of it is held in heap.
     * <p>
     * The {@code PDDocument} is a resource of <b>this</b> method, not an argument built in the call
     * to {@link #collectPdf}: closing it there worked only because {@code try (document)} happened
     * to be the first statement of that method, and any guard inserted ahead of it would have
     * turned both call sites into leaks with no compiler help.
     * <p>
     * The fallback branch still buffers the whole PDF, because a random-access read is what the
     * parser needs and there is no local file to give it. That is the cost of a failed
     * materialisation, and the reason it is only a fallback.
     */
    protected DiffableContent extractPdf(Blob blob, int maxLines) throws IOException {
        List<ContentLine> images = new ArrayList<>();
        boolean truncated;
        boolean[] dropped = new boolean[1];
        try (MaterializedBlob local = MaterializedBlob.of(blob)) {
            File file = local.blob().getFile();
            if (file != null) {
                try (PDDocument document = Loader.loadPDF(file)) {
                    truncated = collectPdf(document, images, maxLines, dropped);
                }
            } else {
                try (InputStream in = blob.getStream();
                        RandomAccessRead source = new RandomAccessReadBuffer(in);
                        PDDocument document = Loader.loadPDF(source)) {
                    truncated = collectPdf(document, images, maxLines, dropped);
                }
            }
        }
        return DiffableContent.keyed(images, truncated || dropped[0]);
    }

    protected boolean collectPdf(PDDocument document, List<ContentLine> images, int maxLines, boolean[] dropped)
            throws IOException {
        PdfWalk walk = new PdfWalk(images, maxLines, dropped);
        int pageNumber = 0;
        for (PDPage page : document.getPages()) {
            pageNumber++;
            if (collectPdfResources(page.getResources(), "pdf:page:" + pageNumber, new int[] { 0 }, walk, 0, null)) {
                return true;
            }
        }
        return false;
    }

    /**
     * State of one PDF inventory walk.
     * <p>
     * A form XObject can contain itself, directly or not, and forms can be shared so that the number
     * of paths through them doubles at each level. Walked naively, the first ends in a
     * {@code StackOverflowError} - an {@code Error}, which no work catches - and the second spins for
     * ever on a few kilobytes of PDF. Each form is therefore walked once per document, its placements
     * recorded, and replayed afterwards; a form already on the current path is skipped, and nesting
     * is bounded by {@link #MAX_FORM_DEPTH}.
     *
     * @since 2025.1
     */
    protected static class PdfWalk {

        protected final List<ContentLine> images;

        protected final int maxLines;

        protected final boolean[] dropped;

        /** Placements of each fully walked form, in order; {@code null} for an image over budget. */
        protected final Map<COSBase, List<String>> forms = new IdentityHashMap<>();

        protected final Set<COSBase> onPath = Collections.newSetFromMap(new IdentityHashMap<>());

        /** Counts dropped images too, so that replaying them is bounded by {@code maxLines} as well. */
        protected int placements;

        protected PdfWalk(List<ContentLine> images, int maxLines, boolean[] dropped) {
            this.images = images;
            this.maxLines = maxLines;
            this.dropped = dropped;
        }

        protected boolean exhausted() {
            return placements >= maxLines;
        }

        protected void place(String key, String digest, List<String> record) {
            placements++;
            if (digest == null) {
                dropped[0] = true;
            } else {
                images.add(ContentLine.of(key, digest));
            }
            if (record != null) {
                record.add(digest);
            }
        }
    }

    /**
     * @param ordinal per-page counter; advanced even for a skipped image, because keys are positional
     *            and renumbering one side would make every following image look changed
     * @param record placements of the enclosing form, {@code null} at page level
     * @return {@code true} if the {@code maxLines} budget was exhausted
     */
    protected boolean collectPdfResources(PDResources resources, String prefix, int[] ordinal, PdfWalk walk,
            int depth, List<String> record) throws IOException {
        if (resources == null) {
            return false;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDImageXObject image) {
                if (walk.exhausted()) {
                    return true;
                }
                String key = prefix + ":image:" + (++ordinal[0]);
                String digest = digestPdfImage(image.getCOSObject());
                if (digest == null) {
                    log.warn("Skipping {}: encoded stream over the {} byte inventory budget", key, maxImageBytes());
                }
                walk.place(key, digest, record);
            } else if (object instanceof PDFormXObject form
                    && collectPdfForm(form, prefix, ordinal, walk, depth, record)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks a form the first time it is met, replays its recorded placements every following time.
     *
     * @return {@code true} if the {@code maxLines} budget was exhausted
     * @since 2025.1
     */
    protected boolean collectPdfForm(PDFormXObject form, String prefix, int[] ordinal, PdfWalk walk, int depth,
            List<String> record) throws IOException {
        COSBase id = form.getCOSObject();
        List<String> known = walk.forms.get(id);
        if (known != null) {
            for (String digest : known) {
                if (walk.exhausted()) {
                    return true;
                }
                walk.place(prefix + ":image:" + (++ordinal[0]), digest, record);
            }
            return false;
        }
        if (depth >= MAX_FORM_DEPTH || !walk.onPath.add(id)) {
            log.warn("Skipping a form XObject of {} that contains itself or nests deeper than {}", prefix,
                    MAX_FORM_DEPTH);
            walk.dropped[0] = true;
            return false;
        }
        List<String> own = new ArrayList<>();
        try {
            if (collectPdfResources(form.getResources(), prefix, ordinal, walk, depth + 1, own)) {
                return true;
            }
        } finally {
            walk.onPath.remove(id);
            if (record != null) {
                record.addAll(own);
            }
        }
        walk.forms.put(id, own);
        return false;
    }

    /**
     * Identity of a PDF image: SHA-256 of its encoding parameters followed by its <b>encoded</b>
     * bytes.
     * <p>
     * Nothing is decoded, on purpose. PDFBox 3 runs every filter to completion into a heap buffer
     * ({@code Filter#decode}) before {@code createInputStream()} returns its first byte, so no budget
     * can apply to the decoded form: a few megabytes of {@code FlateDecode} declaring a 1x1 image
     * inflated to gigabytes first, and {@code getWidth()} alone fully decodes a JPX image. The encoded
     * bytes are bounded by the file, and they are what the OOXML paths digest too - the image as
     * stored.
     * <p>
     * The parameters belong to the identity because the same bytes under another filter, predictor or
     * geometry are another image.
     *
     * @return the digest, or {@code null} if the encoded stream is over {@link #maxImageBytes()}
     * @since 2025.1
     */
    protected String digestPdfImage(COSStream stream) throws IOException {
        String parameters = "Filter=" + canonical(stream.getDictionaryObject(COSName.FILTER), 0)
                + ";DecodeParms=" + canonical(stream.getDictionaryObject(COSName.DECODE_PARMS, COSName.DP), 0)
                + ";Width=" + stream.getInt(COSName.WIDTH) + ";Height=" + stream.getInt(COSName.HEIGHT)
                + ";BitsPerComponent=" + stream.getInt(COSName.BITS_PER_COMPONENT) + "\n";
        MessageDigest digest = newDigest();
        digest.update(parameters.getBytes(StandardCharsets.UTF_8));
        try (InputStream in = stream.createRawInputStream()) {
            return digestStream(digest, in);
        }
    }

    /**
     * Stable text form of a filter or decode parameter value: dictionaries with sorted keys, arrays in
     * order, indirect references followed up to {@link #MAX_COS_DEPTH}.
     *
     * @since 2025.1
     */
    protected static String canonical(COSBase value, int depth) {
        COSBase resolved = value instanceof COSObject object ? object.getObject() : value;
        if (resolved == null || depth > MAX_COS_DEPTH) {
            return "null";
        }
        if (resolved instanceof COSName name) {
            return "/" + name.getName();
        }
        if (resolved instanceof COSArray array) {
            StringJoiner items = new StringJoiner(",", "[", "]");
            for (COSBase item : array) {
                items.add(canonical(item, depth + 1));
            }
            return items.toString();
        }
        if (resolved instanceof COSDictionary dictionary) {
            Map<String, String> entries = new TreeMap<>();
            for (Map.Entry<COSName, COSBase> entry : dictionary.entrySet()) {
                entries.put(entry.getKey().getName(), canonical(entry.getValue(), depth + 1));
            }
            return entries.toString();
        }
        return resolved.toString();
    }

    protected String digest(byte[] bytes) {
        return "sha256:" + HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    /**
     * PowerPoint: one entry per picture placed on a slide, keyed by
     * {@code powerpoint:slide:<n>:image:<media-part>}. The same media part used twice on one slide
     * gets a {@code #2}, {@code #3}... suffix so no placement is lost. Pictures inside group shapes
     * are included; layouts and masters are not (they belong to the template, not the content).
     * <p>
     * Slides are streamed one by one with SAX: {@code XMLSlideShow} parsed the whole deck into
     * XMLBeans trees before the first picture was read.
     */
    protected DiffableContent extractPresentation(Blob blob, int maxLines) throws IOException {
        List<ContentLine> images = new ArrayList<>();
        boolean truncated = false;
        boolean[] dropped = new boolean[1];
        OPCPackage pkg = OoxmlPackages.open(blob);
        try {
            int slideNumber = 0;
            for (PackagePart slide : SlideHandler.slides(pkg)) {
                slideNumber++;
                OoxmlPackages.parse(slide, new SlidePictures(slide, "powerpoint:slide:" + slideNumber + ":image:",
                        images, maxLines, dropped));
            }
        } catch (OoxmlPackages.Stop e) {
            truncated = true;
        } catch (SAXException e) {
            throw OoxmlPackages.unreadable(e, "OOXML presentation: " + blob.getFilename());
        } finally {
            pkg.revert();
        }
        return DiffableContent.keyed(images, truncated || dropped[0]);
    }

    protected class SlidePictures extends SlideHandler {

        protected final PackagePart slide;

        protected final String prefix;

        protected final List<ContentLine> images;

        protected final int maxLines;

        protected final boolean[] dropped;

        protected final Map<String, Integer> seen = new HashMap<>();

        protected SlidePictures(PackagePart slide, String prefix, List<ContentLine> images, int maxLines,
                boolean[] dropped) {
            this.slide = slide;
            this.prefix = prefix;
            this.images = images;
            this.maxLines = maxLines;
            this.dropped = dropped;
        }

        @Override
        protected boolean readsText(boolean topLevel, String placeholder) {
            return false;
        }

        @Override
        protected boolean readsTables() {
            return false;
        }

        @Override
        protected void picture(String relationshipId) throws SAXException {
            PackagePart media = OoxmlPackages.related(slide, relationshipId);
            if (media == null) {
                return; // linked (external) picture: no embedded bytes to digest
            }
            if (images.size() >= maxLines) {
                throw new OoxmlPackages.Stop();
            }
            String name = media.getPartName().getName();
            inventory(media, occurrence(seen, prefix + name.substring(name.lastIndexOf('/') + 1)), images, dropped);
        }
    }
}

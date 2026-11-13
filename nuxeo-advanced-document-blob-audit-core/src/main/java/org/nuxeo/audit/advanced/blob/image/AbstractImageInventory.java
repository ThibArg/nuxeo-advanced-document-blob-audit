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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.nuxeo.audit.advanced.blob.BlobContentExtractor;
import org.nuxeo.audit.advanced.blob.ContentLine;
import org.nuxeo.audit.advanced.blob.DiffableContent;
import org.nuxeo.audit.advanced.blob.io.OoxmlPackages;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.xml.sax.SAXException;

/**
 * Digest-based image inventory of one format, used when the image inventory is on.
 * <p>
 * A regular {@link BlobContentExtractor}, contributed to the {@code imageExtractors} extension point
 * of {@code org.nuxeo.audit.advanced.blob.BlobDiffComponent} once per format: selectable and
 * disableable per mime type like any other extractor, and bounded by the configured {@code maxLines}.
 * <p>
 * The output is <b>keyed</b> content, one entry per placed image, the value being the SHA-256 of
 * its bytes. It is compared by the same {@code TextDiffer} as the text, then merged into a
 * {@code # Images} section of the unified diff.
 *
 * @since 2025.1
 */
public abstract class AbstractImageInventory implements BlobContentExtractor {

    private static final Logger log = LogManager.getLogger(AbstractImageInventory.class);

    /**
     * Upper bound on the size of a single embedded image as stored, in bytes: the <b>inflated</b> media
     * part for OOXML, the <b>encoded</b> stream for PDF, which is never decoded.
     * <p>
     * Nothing else bounds it. The only guard upstream is the eligibility check of
     * {@code BlobDiffComponent}, which compares {@code blob.getLength()} - the size of the container,
     * deflated - to {@code maxBlobSize} (10 MB by default). Ten megabytes of deflate expand to several
     * gigabytes, and {@code java.util.zip.ZipInputStream} has none of POI's {@code ZipSecureFile}
     * protections. Without this cap, anyone able to version a {@code .docx} could exhaust the heap -
     * times two concurrent works on the {@code blobDiff} queue, times the two retries of
     * {@code getRetryCount()}.
     *
     * @since 2025.1
     */
    protected static final long MAX_IMAGE_BYTES = 32L * 1024 * 1024;

    /**
     * Returns {@link #MAX_IMAGE_BYTES}; overridable so a test can lower the cap instead of building a
     * real multi-gigabyte bomb.
     */
    protected long maxImageBytes() {
        return MAX_IMAGE_BYTES;
    }

    /**
     * Returns the upper bound on the bytes read from all the images of one file together: eight
     * images at {@link #maxImageBytes()}, so 256 MB by default.
     *
     * @since 2025.1
     */
    protected long maxTotalImageBytes() {
        return 8 * maxImageBytes();
    }

    /**
     * The images of one file already digested, and the bytes that may still be read from it.
     * <p>
     * An image placed many times - a logo on every slide, a scanned background on every page - is
     * read once, and all the images of the file together within {@link #maxTotalImageBytes()}.
     * Without either, a few kilobytes of placements could keep a worker inflating and hashing the
     * same picture for hours.
     *
     * @since 2025.1
     */
    protected class ImageBudget {

        /** By part name for OOXML, by stream identity for PDF; {@code null} for an image over budget. */
        protected final Map<Object, String> digests;

        protected long remaining = maxTotalImageBytes();

        protected boolean exhausted;

        protected ImageBudget(Map<Object, String> digests) {
            this.digests = digests;
        }

        protected void spend(long bytes) throws BudgetExhausted {
            remaining -= bytes;
            if (remaining < 0) {
                if (!exhausted) {
                    log.warn("Image inventory stopped: the images of one file are over {} bytes",
                            maxTotalImageBytes());
                }
                exhausted = true;
                throw new BudgetExhausted();
            }
        }

        /** Returns a view of the stream that spends every byte read from it. */
        protected InputStream meter(InputStream in) {
            return new FilterInputStream(in) {
                @Override
                public int read() throws IOException {
                    int value = super.read();
                    if (value >= 0) {
                        spend(1);
                    }
                    return value;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int read = super.read(buffer, offset, length);
                    if (read > 0) {
                        spend(read);
                    }
                    return read;
                }
            };
        }
    }

    /** Raised once the images of a file are over {@link #maxTotalImageBytes()}. */
    protected static class BudgetExhausted extends IOException {

        private static final long serialVersionUID = 1L;

        protected BudgetExhausted() {
            super("image inventory budget exhausted");
        }
    }

    @FunctionalInterface
    protected interface ImageDigester {
        String digest() throws IOException;
    }

    /**
     * The images listed so far for one file, and whether the inventory is incomplete.
     *
     * @since 2025.1
     */
    protected static class Inventory {

        protected final List<ContentLine> images = new ArrayList<>();

        protected final int maxLines;

        protected final ImageBudget budget;

        protected boolean truncated;

        protected Inventory(int maxLines, ImageBudget budget) {
            this.maxLines = maxLines;
            this.budget = budget;
        }

        /** Returns whether {@code maxLines} images are already listed. */
        protected boolean isFull() {
            return images.size() >= maxLines;
        }

        /** Lists the image, or flags the inventory when the image had to be dropped (null digest). */
        protected void add(String key, String digest) {
            if (digest == null) {
                truncated = true;
            } else {
                images.add(ContentLine.of(key, digest));
            }
        }

        /** Flags the inventory as incomplete. */
        protected void truncate() {
            truncated = true;
        }

        /** Returns the inventory as keyed content. */
        protected DiffableContent result() {
            return DiffableContent.keyed(images, truncated);
        }
    }

    /**
     * Digests an image the first time it is met in its file, within the budget of the file.
     *
     * @param image what identifies the image within its file
     * @return the digest, or {@code null} if the image is over a budget and must be dropped; the
     *         inventory must stop as well once {@link ImageBudget#exhausted} is set
     * @since 2025.1
     */
    protected String digestOnce(ImageBudget budget, Object image, ImageDigester digester) throws IOException {
        if (budget.digests.containsKey(image)) {
            return budget.digests.get(image);
        }
        String digest = null;
        if (!budget.exhausted) {
            try {
                digest = digester.digest();
            } catch (BudgetExhausted e) {
                // already logged, and flagged on the budget
            }
        }
        budget.digests.put(image, digest);
        return digest;
    }

    /**
     * Returns the SHA-256 of the stream, continuing a digest that may already hold a header, computed
     * in 8 KB chunks and abandoned past {@link #maxImageBytes()}.
     * <p>
     * Does <b>not</b> close the stream: the Word path iterates over a shared {@code ZipInputStream}.
     *
     * @return the digest, or {@code null} if the stream went over the budget
     * @since 2025.1
     */
    protected String digestStream(MessageDigest digest, InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        long limit = maxImageBytes();
        long total = 0;
        int read;
        // One byte past the limit is proof enough, and every byte read is charged to the file.
        while ((read = in.read(buffer, 0, (int) Math.min(buffer.length, limit + 1 - total))) != -1) {
            total += read;
            if (total > limit) {
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
     * unrecoverable environment fault, not something every caller should carry a checked exception
     * for.
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

    protected String digest(byte[] bytes) {
        return "sha256:" + HexFormat.of().formatHex(newDigest().digest(bytes));
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

    /**
     * Digests an embedded picture part and lists it, or flags the inventory if it is over budget, or
     * stops it once the file is.
     */
    protected void inventory(PackagePart media, String key, Inventory inventory) throws SAXException {
        ImageBudget budget = inventory.budget;
        String digest;
        try {
            digest = digestOnce(budget, media.getPartName(), () -> digestOoxmlPicture(media, budget));
        } catch (IOException e) {
            throw new SAXException(e);
        }
        if (budget.exhausted) {
            throw new OoxmlPackages.Stop();
        }
        inventory.add(key, digest);
    }

    /**
     * Digests an embedded OOXML picture part without ever holding it whole in heap.
     * <p>
     * {@code PictureData#getData()} returns the <b>inflated</b> image as a single array, capped only
     * by POI's own {@code MAX_IMAGE_SIZE} (100 MB in 5.5.1). The part is streamed instead, so the
     * {@link #maxImageBytes()} budget is the same across the four formats.
     * <p>
     * A dropped picture is logged by its part name, never by its inventory key: the key of a
     * spreadsheet picture carries the name of its sheet, content of the document, which the server
     * log must not hold.
     *
     * @return the digest, or {@code null} if the image is over budget and must be dropped
     * @since 2025.1
     */
    protected String digestOoxmlPicture(PackagePart part, ImageBudget budget) throws IOException {
        String name = part.getPartName().getName();
        long declared = part.getSize();
        if (declared > maxImageBytes()) {
            log.warn("Skipping {}: {} declared bytes, over the {} byte inventory budget", name, declared,
                    maxImageBytes());
            return null;
        }
        try (InputStream in = budget.meter(part.getInputStream())) {
            String digest = digestStream(newDigest(), in);
            if (digest == null) {
                log.warn("Skipping {}: inflates past the {} byte inventory budget", name, maxImageBytes());
            }
            return digest;
        }
    }
}
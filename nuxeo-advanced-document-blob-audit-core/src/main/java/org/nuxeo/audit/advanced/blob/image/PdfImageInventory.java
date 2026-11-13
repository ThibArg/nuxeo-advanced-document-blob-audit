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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.nuxeo.audit.advanced.blob.DiffableContent;
import org.nuxeo.audit.advanced.blob.io.MaterializedBlob;
import org.nuxeo.ecm.core.api.Blob;

/**
 * Image inventory of PDF files: one entry per image placed on a page, directly or through form
 * XObjects, keyed by {@code pdf:page:<n>:image:<ordinal>}.
 * <p>
 * An image is identified by its encoding parameters and its <b>encoded</b> bytes, and never decoded
 * (see {@link #digestPdfImage}).
 *
 * @since 2025.1
 */
public class PdfImageInventory extends AbstractImageInventory {

    private static final Logger log = LogManager.getLogger(PdfImageInventory.class);

    /**
     * Nesting bound when rendering PDF filter parameters, so a self-referencing dictionary cannot
     * recurse for ever.
     */
    protected static final int MAX_COS_DEPTH = 4;

    /** Nesting bound for PDF form XObjects, far past anything a real producer nests. */
    protected static final int MAX_FORM_DEPTH = 16;

    /**
     * Lists the images placed on every page. PDFBox reads the document straight from the local file,
     * so nothing of it is held in heap.
     * <p>
     * The {@code PDDocument} is a resource of <b>this</b> method, not an argument built in the call
     * to {@link #collectPdf}: closed by the callee, it would leak as soon as a guard is inserted
     * ahead of the callee's {@code try}, with no compiler help.
     */
    @Override
    public DiffableContent extract(Blob blob, int maxLines) throws IOException {
        var inventory = new Inventory(maxLines, new ImageBudget(new IdentityHashMap<>()));
        boolean truncated;
        try (MaterializedBlob local = MaterializedBlob.of(blob); PDDocument document = Loader.loadPDF(local.file())) {
            truncated = collectPdf(document, inventory);
        }
        if (truncated) {
            inventory.truncate();
        }
        return inventory.result();
    }

    protected boolean collectPdf(PDDocument document, Inventory inventory) throws IOException {
        var walk = new PdfWalk(inventory);
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

        protected final Inventory inventory;

        /** Placements of each fully walked form, in order; {@code null} for an image over budget. */
        protected final Map<COSBase, List<String>> forms = new IdentityHashMap<>();

        protected final Set<COSBase> onPath = Collections.newSetFromMap(new IdentityHashMap<>());

        /** Counts dropped images too, so that replaying them is bounded by {@code maxLines} as well. */
        protected int placements;

        protected PdfWalk(Inventory inventory) {
            this.inventory = inventory;
        }

        protected boolean exhausted() {
            return placements >= inventory.maxLines || inventory.budget.exhausted;
        }

        protected void place(String key, String digest, List<String> record) {
            placements++;
            inventory.add(key, digest);
            if (record != null) {
                record.add(digest);
            }
        }
    }

    /**
     * Lists the images of a resource dictionary, and those of the form XObjects it holds.
     *
     * @param ordinal per-page counter; advanced even for a skipped image, because keys are positional
     *            and renumbering one side would make every following image look changed
     * @param record placements of the enclosing form, {@code null} at page level
     * @return {@code true} if the {@code maxLines} budget or the byte budget of the file was exhausted
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
                COSStream stream = image.getCOSObject();
                ImageBudget budget = walk.inventory.budget;
                String digest = digestOnce(budget, stream, () -> {
                    String computed = digestPdfImage(stream, budget);
                    if (computed == null) {
                        log.warn("Skipping {}: encoded stream over the {} byte inventory budget", key,
                                maxImageBytes());
                    }
                    return computed;
                });
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
            walk.inventory.truncate();
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
     * Returns the identity of a PDF image: the SHA-256 of its encoding parameters followed by its
     * <b>encoded</b> bytes.
     * <p>
     * Nothing is decoded, on purpose. PDFBox 3 runs every filter to completion into a heap buffer
     * ({@code Filter#decode}) before {@code createInputStream()} returns its first byte, so no budget
     * can apply to the decoded form: a few megabytes of {@code FlateDecode} declaring a 1x1 image
     * inflate to gigabytes first, and {@code getWidth()} alone fully decodes a JPX image. The encoded
     * bytes are bounded by the file, and they are what the OOXML paths digest too - the image as
     * stored.
     * <p>
     * The parameters belong to the identity because the same bytes under another filter, predictor or
     * geometry are another image.
     *
     * @return the digest, or {@code null} if the encoded stream is over {@link #maxImageBytes()}
     * @since 2025.1
     */
    protected String digestPdfImage(COSStream stream, ImageBudget budget) throws IOException {
        String parameters = "Filter=" + canonical(stream.getDictionaryObject(COSName.FILTER), 0)
                + ";DecodeParms=" + canonical(stream.getDictionaryObject(COSName.DECODE_PARMS, COSName.DP), 0)
                + ";Width=" + stream.getInt(COSName.WIDTH) + ";Height=" + stream.getInt(COSName.HEIGHT)
                + ";BitsPerComponent=" + stream.getInt(COSName.BITS_PER_COMPONENT) + "\n";
        MessageDigest digest = newDigest();
        digest.update(parameters.getBytes(StandardCharsets.UTF_8));
        try (InputStream in = budget.meter(stream.createRawInputStream())) {
            return digestStream(digest, in);
        }
    }

    /**
     * Returns a stable text form of a filter or decode parameter value: dictionaries with sorted
     * keys, arrays in order, indirect references followed up to {@link #MAX_COS_DEPTH}.
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
            var items = new StringJoiner(",", "[", "]");
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
}
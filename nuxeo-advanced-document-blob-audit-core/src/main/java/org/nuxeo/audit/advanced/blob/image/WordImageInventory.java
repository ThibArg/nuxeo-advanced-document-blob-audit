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
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.audit.advanced.blob.DiffableContent;
import org.nuxeo.ecm.core.api.Blob;

/**
 * Image inventory of Word documents ({@code .docx}): one entry per {@code word/media/} part, keyed
 * by {@code word:image:<media-part>}.
 * <p>
 * The package is read as a plain zip stream, each media part digested as it is inflated.
 *
 * @since 2025.1
 */
public class WordImageInventory extends AbstractImageInventory {

    private static final Logger log = LogManager.getLogger(WordImageInventory.class);

    /** Maximum inflated/deflated ratio of a zip entry. The value POI's {@code ZipSecureFile} uses. */
    protected static final double MAX_INFLATION_RATIO = 100.0d;

    /**
     * Below this inflated size the ratio is not evidence of anything: a small solid-colour bitmap
     * legitimately deflates by far more than {@link #MAX_INFLATION_RATIO}. POI makes the same
     * allowance, under the same name.
     */
    protected static final long GRACE_ENTRY_SIZE = 100L * 1024;

    /**
     * Streams every {@code word/media/} part, never materialising one.
     * <p>
     * An entry over {@link #maxImageBytes()} is dropped and flags the inventory as truncated: the
     * report loses one line, where reading the entry whole would allocate an array of its
     * <b>inflated</b> size, with no ceiling at all.
     */
    @Override
    public DiffableContent extract(Blob blob, int maxLines) throws IOException {
        Inventory inventory = new Inventory(maxLines, new ImageBudget(new HashMap<>()));
        ImageBudget budget = inventory.budget;
        // Two resources on purpose: if the ZipInputStream constructor throws - it allocates a
        // buffer and an Inflater - the underlying stream must still be closed.
        try (InputStream source = blob.getStream(); ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().startsWith("word/media/")) {
                    continue;
                }
                if (inventory.isFull()) {
                    inventory.truncate();
                    break;
                }
                String name = entry.getName().substring("word/media/".length());
                if (isInflationBomb(entry)) {
                    log.warn("Skipping image {}: {} bytes deflated to {}, over the inventory budget", name,
                            entry.getSize(), entry.getCompressedSize());
                    inventory.truncate();
                    continue;
                }
                // Not closed on purpose: the iteration must go on, and getNextEntry() skips
                // whatever is left of a partially read entry.
                String digest = digestOnce(budget, entry.getName(),
                        () -> digestStream(newDigest(), budget.meter(zip)));
                if (budget.exhausted) {
                    inventory.truncate();
                    break;
                }
                if (digest == null) {
                    log.warn("Skipping image {}: inflates past the {} byte inventory budget", name, maxImageBytes());
                    inventory.truncate();
                    continue;
                }
                inventory.add("word:image:" + name, digest);
            }
        }
        return inventory.result();
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
}

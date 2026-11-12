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

import java.util.ArrayList;
import java.util.List;

import org.nuxeo.common.xmap.annotation.XNode;
import org.nuxeo.common.xmap.annotation.XNodeList;
import org.nuxeo.common.xmap.annotation.XObject;
import org.nuxeo.runtime.model.Descriptor;

/**
 * Global configuration. Everything is opt-in and bounded on purpose: diffing binaries is expensive
 * and copies business content outside of the source document.
 * <p>
 * Contributions <b>merge</b>: a later one only overrides what it sets, and an omitted element keeps
 * the value of the earlier ones. A list replaces the earlier one only when it is not empty; list
 * {@value #ANY} to mean every document type, or every blob xpath.
 *
 * @since 2025.1
 */
@XObject("config")
public class BlobDiffConfigDescriptor implements Descriptor {

    public static final String DEFAULT_ID = "default";

    /** @since 2025.1 */
    public static final String ANY = "*";

    /** @since 2025.1 */
    public static final String DEFAULT_XPATH = "file:content";

    /** @since 2025.1 */
    public static final long DEFAULT_MAX_BLOB_SIZE = 10L * 1024 * 1024;

    /** @since 2025.1 */
    public static final int DEFAULT_MAX_LINES = 10000;

    @XNode("@enabled")
    protected Boolean enabled;

    /** Blobs bigger than this (bytes) are never extracted. */
    @XNode("maxBlobSize")
    protected Long maxBlobSize;

    /**
     * Hard cap on extracted units.
     * <p>
     * This bounds <b>running time</b>, which stays O(n*m) even with Hirschberg. Measured on JDK 17
     * with one paragraph modified: 304 ms at 4 900 lines, ~1.1 s at 10 000, ~4.6 s at 20 000. A
     * 200-page Word document extracts to roughly 5 000 paragraphs, so 10 000 leaves a 2x margin
     * while keeping the worst case near one second per work.
     */
    @XNode("maxLines")
    protected Integer maxLines;

    /** Hard cap on reported differences. */
    @XNode("maxDiffEntries")
    protected Integer maxDiffEntries;

    /**
     * Hard cap on the size of the produced unified diff, in characters.
     * <p>
     * {@code maxDiffEntries} bounds how many differences are reported, never how long they are. An
     * Excel cell holds up to 32 767 characters and a Word paragraph is unbounded, so the two caps
     * together are what keeps the diff body - built in a {@code StringBuilder}, copied by
     * {@code toString()}, copied again into a blob - proportional to something known in advance.
     *
     * @since 2025.1
     */
    @XNode("maxDiffChars")
    protected Integer maxDiffChars;

    /**
     * A single extracted unit longer than this is elided in the diff body.
     *
     * @since 2025.1
     */
    @XNode("maxValueLength")
    protected Integer maxValueLength;

    /**
     * Whether the image inventory (extractors of the imageExtractors point) runs next to the text
     * diff. Off by default.
     *
     * @since 2025.1
     */
    @XNode("imageInventory")
    protected Boolean imageInventory;

    /** Empty or {@value #ANY} means "every document type". */
    @XNodeList(value = "docTypes/docType", type = ArrayList.class, componentType = String.class)
    protected List<String> docTypes = new ArrayList<>();

    /** Empty means {@value #DEFAULT_XPATH}, {@value #ANY} means "every blob xpath". */
    @XNodeList(value = "xpaths/xpath", type = ArrayList.class, componentType = String.class)
    protected List<String> xpaths = new ArrayList<>();

    @Override
    public String getId() {
        return DEFAULT_ID;
    }

    /**
     * Overlays a later contribution on this one. The default merge returned the later contribution
     * alone, so an override setting only {@code docTypes} silently disabled the feature and emptied
     * {@code xpaths}, which then meant "every blob xpath".
     *
     * @since 2025.1
     */
    @Override
    public Descriptor merge(Descriptor o) {
        BlobDiffConfigDescriptor other = (BlobDiffConfigDescriptor) o;
        BlobDiffConfigDescriptor merged = new BlobDiffConfigDescriptor();
        merged.enabled = either(other.enabled, enabled);
        merged.maxBlobSize = either(other.maxBlobSize, maxBlobSize);
        merged.maxLines = either(other.maxLines, maxLines);
        merged.maxDiffEntries = either(other.maxDiffEntries, maxDiffEntries);
        merged.maxDiffChars = either(other.maxDiffChars, maxDiffChars);
        merged.maxValueLength = either(other.maxValueLength, maxValueLength);
        merged.imageInventory = either(other.imageInventory, imageInventory);
        merged.docTypes = Descriptor.getIfEmpty(other.docTypes, docTypes);
        merged.xpaths = Descriptor.getIfEmpty(other.xpaths, xpaths);
        return merged;
    }

    protected static <T> T either(T value, T fallback) {
        return value != null ? value : fallback;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public long getMaxBlobSize() {
        return either(maxBlobSize, DEFAULT_MAX_BLOB_SIZE);
    }

    public int getMaxLines() {
        return either(maxLines, DEFAULT_MAX_LINES);
    }

    public int getMaxDiffEntries() {
        return either(maxDiffEntries, TextDiffer.DEFAULT_MAX_DIFF_ENTRIES);
    }

    /** @since 2025.1 */
    public int getMaxDiffChars() {
        return either(maxDiffChars, TextDiffer.DEFAULT_MAX_DIFF_CHARS);
    }

    /** @since 2025.1 */
    public int getMaxValueLength() {
        return either(maxValueLength, TextDiffer.DEFAULT_MAX_VALUE_LENGTH);
    }

    /** @since 2025.1 */
    public boolean isImageInventory() {
        return Boolean.TRUE.equals(imageInventory);
    }

    public List<String> getDocTypes() {
        return docTypes;
    }

    /** The blob xpaths diffed, {@value #DEFAULT_XPATH} when none is configured. */
    public List<String> getXPaths() {
        return xpaths.isEmpty() ? List.of(DEFAULT_XPATH) : xpaths;
    }

    /**
     * Whether every blob xpath of the document type is diffed.
     *
     * @since 2025.1
     */
    public boolean isEveryXPath() {
        return xpaths.contains(ANY);
    }

    public boolean acceptsDocType(String docType) {
        return docTypes.isEmpty() || docTypes.contains(ANY) || docTypes.contains(docType);
    }

    public boolean acceptsXPath(String xpath) {
        return isEveryXPath() || getXPaths().contains(xpath);
    }
}

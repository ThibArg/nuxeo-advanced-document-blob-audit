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

import static java.lang.Boolean.TRUE;

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

    /**
     * Wildcard of {@code docTypes} and {@code xpaths}: every document type, every blob xpath.
     *
     * @since 2025.1
     */
    public static final String ANY = "*";

    /**
     * Blob xpath diffed when none is configured.
     *
     * @since 2025.1
     */
    public static final String DEFAULT_XPATH = "file:content";

    /**
     * Default of {@code maxBlobSize}: 10 MB.
     *
     * @since 2025.1
     */
    public static final long DEFAULT_MAX_BLOB_SIZE = 10L * 1024 * 1024;

    /**
     * Default of {@code maxLines}.
     *
     * @since 2025.1
     */
    public static final int DEFAULT_MAX_LINES = 10000;

    @XNode("@enabled")
    protected Boolean enabled;

    /** Blobs bigger than this (bytes) are never extracted. */
    @XNode("maxBlobSize")
    protected Long maxBlobSize;

    /**
     * Hard cap on extracted units, per side.
     * <p>
     * This bounds the <b>worst case</b> of the diff, a document rewritten from end to end: its running
     * time stays O(n*m) even with Hirschberg, while a few edited paragraphs cost milliseconds whatever
     * the size of the document. The figures are in {@link TextDiffer}. A 200-page Word document
     * extracts to roughly 5 000 paragraphs, so 10 000 leaves a 2x margin.
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
     * A single extracted unit longer than this is compacted when it is extracted (see
     * {@link ContentLine#of(String, String, int)}) and elided in the diff body.
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
     * Overlays a later contribution on this one: a value it sets wins, a value it omits keeps this
     * one's. The default {@link Descriptor#merge} returns the later contribution alone, so an
     * override setting only {@code docTypes} would silently reset everything else - {@code enabled}
     * to {@code false} among them.
     *
     * @since 2025.1
     */
    @Override
    public Descriptor merge(Descriptor o) {
        var other = (BlobDiffConfigDescriptor) o;
        var merged = new BlobDiffConfigDescriptor();
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

    /** Returns whether the feature is on; off unless a contribution sets {@code enabled="true"}. */
    public boolean isEnabled() {
        return TRUE.equals(enabled);
    }

    /** Returns the size in bytes past which a blob is never extracted. */
    public long getMaxBlobSize() {
        return either(maxBlobSize, DEFAULT_MAX_BLOB_SIZE);
    }

    /** Returns the maximum number of units extracted from each side. */
    public int getMaxLines() {
        return either(maxLines, DEFAULT_MAX_LINES);
    }

    /** Returns the maximum number of differences written to the diff body. */
    public int getMaxDiffEntries() {
        return either(maxDiffEntries, TextDiffer.DEFAULT_MAX_DIFF_ENTRIES);
    }

    /**
     * Returns the maximum length of the diff body, in characters.
     *
     * @since 2025.1
     */
    public int getMaxDiffChars() {
        return either(maxDiffChars, TextDiffer.DEFAULT_MAX_DIFF_CHARS);
    }

    /**
     * Returns the length past which a single extracted unit is compacted and elided.
     *
     * @since 2025.1
     */
    public int getMaxValueLength() {
        return either(maxValueLength, TextDiffer.DEFAULT_MAX_VALUE_LENGTH);
    }

    /**
     * Returns whether the image inventory runs next to the text diff.
     *
     * @since 2025.1
     */
    public boolean isImageInventory() {
        return TRUE.equals(imageInventory);
    }

    /** Returns the document types diffed as configured; empty or {@value #ANY} means every type. */
    public List<String> getDocTypes() {
        return docTypes;
    }

    /** Returns the blob xpaths diffed, {@value #DEFAULT_XPATH} when none is configured. */
    public List<String> getXPaths() {
        return xpaths.isEmpty() ? List.of(DEFAULT_XPATH) : xpaths;
    }

    /**
     * Returns whether every blob xpath of the document type is diffed.
     *
     * @since 2025.1
     */
    public boolean isEveryXPath() {
        return xpaths.contains(ANY);
    }

    /** Returns whether documents of this type are diffed. */
    public boolean acceptsDocType(String docType) {
        return docTypes.isEmpty() || docTypes.contains(ANY) || docTypes.contains(docType);
    }

    /** Returns whether the blob at this xpath is diffed. */
    public boolean acceptsXPath(String xpath) {
        return isEveryXPath() || getXPaths().contains(xpath);
    }
}

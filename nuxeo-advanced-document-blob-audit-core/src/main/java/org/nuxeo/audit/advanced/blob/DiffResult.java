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

/**
 * Outcome of a comparison between two {@link DiffableContent}.
 * <p>
 * {@code similarity} is the share of the smaller side kept unchanged in the other, in characters,
 * between {@code 0} and {@code 1}; {@code null} when it was not measured (an image inventory, a
 * side with no content). {@code replaced} is set by the service when the pair changed format and
 * scored under the {@code minSimilarity} of its extractor: the body is still computed, so that a
 * caller can use it, but {@code BlobDiffWork} files the pair as {@code skippedContentReplaced} and
 * does not store it.
 *
 * @since 2025.1
 */
public record DiffResult(int added, int removed, int changed, boolean truncated, String unified, Double similarity,
        boolean replaced) {

    public DiffResult(int added, int removed, int changed, boolean truncated, String unified) {
        this(added, removed, changed, truncated, unified, null, false);
    }

    /**
     * Returns the same result with its similarity set.
     *
     * @since 2025.1
     */
    public DiffResult withSimilarity(Double value) {
        return new DiffResult(added, removed, changed, truncated, unified, value, replaced);
    }

    /**
     * Returns the same result flagged as a replaced content.
     *
     * @since 2025.1
     */
    public DiffResult asReplaced() {
        return new DiffResult(added, removed, changed, truncated, unified, similarity, true);
    }

    public boolean isEmpty() {
        return added == 0 && removed == 0 && changed == 0;
    }

    /**
     * Short, index-friendly description stored on the BlobDiff document.
     * <p>
     * <b>Deliberately English, do not localise it here.</b> This string is built once, when the
     * diff is computed, and persisted as is in {@code bdiff:summary}. Composing it in the UI
     * instead would display newly translated sentences next to the English ones already stored,
     * and realigning them would mean rewriting every existing {@code BlobDiff}. A UI that wants a
     * localised summary builds it from {@link #added()}, {@link #removed()}, {@link #changed()} and
     * {@link #truncated()}, which are persisted as separate fields for exactly that reason -
     * {@code nuxeo-blobdiff-status} already does so for its counters.
     * <p>
     * Product decision, 2026-09. See item 14 in {@code AGENTS.md}.
     */
    public String summary() {
        if (isEmpty()) {
            return "No textual change detected";
        }
        StringBuilder sb = new StringBuilder();
        appendCount(sb, changed, "modification");
        appendCount(sb, added, "addition");
        appendCount(sb, removed, "deletion");
        if (truncated) {
            sb.append(" (truncated)");
        }
        return sb.toString();
    }

    protected static void appendCount(StringBuilder sb, int count, String label) {
        if (count == 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(count).append(' ').append(label);
        if (count > 1) {
            sb.append('s');
        }
    }
}

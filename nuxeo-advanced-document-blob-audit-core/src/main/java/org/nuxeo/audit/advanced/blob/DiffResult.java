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
 *
 * @since 1.0
 */
public record DiffResult(int added, int removed, int changed, boolean truncated, String unified) {

    public boolean isEmpty() {
        return added == 0 && removed == 0 && changed == 0;
    }

    /** Short, index-friendly description stored on the BlobDiff document. */
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

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

import java.util.List;

/**
 * Format-agnostic representation of a blob content, produced by a {@link BlobTextExtractor} and
 * consumed by {@link TextDiffer}.
 * <p>
 * This is the single contract every format must implement: adding Word, PDF, ODT or CSV support
 * means writing an extractor that returns this structure, and nothing else.
 *
 * @since 1.0
 */
public record DiffableContent(List<ContentLine> lines, boolean keyed, boolean truncated) {

    public static DiffableContent positional(List<ContentLine> lines, boolean truncated) {
        return new DiffableContent(lines, false, truncated);
    }

    public static DiffableContent keyed(List<ContentLine> lines, boolean truncated) {
        return new DiffableContent(lines, true, truncated);
    }

    public int size() {
        return lines.size();
    }
}

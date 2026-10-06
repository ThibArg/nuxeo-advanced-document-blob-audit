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

import java.io.IOException;
import java.util.Map;

import org.nuxeo.ecm.core.api.Blob;

/**
 * Turns a blob into a comparable {@link DiffableContent}.
 * <p>
 * This is the only interface to implement in order to support a new format. Extractors are
 * contributed to the {@code extractors} extension point of
 * {@code org.nuxeo.audit.advanced.blob.BlobDiffComponent} and selected by mime type, with an
 * ordering that lets a specialised extractor take precedence over a generic one.
 *
 * @since 2025.1
 */
public interface BlobTextExtractor {

    /**
     * Extracts the comparable content of the given blob.
     * <p>
     * <b>Declares {@code IOException}, deliberately not {@code Exception}.</b> It used to declare
     * the latter, which forced every caller into a {@code catch (Exception)} - the shape the Nuxeo
     * guidelines call out, because it also catches {@code InterruptedException} and swallows the
     * cooperative-cancellation signal of the {@code blobDiff} worker threads. An implementation
     * that needs to report a non-I/O failure should throw a {@link org.nuxeo.ecm.core.api.NuxeoException}.
     *
     * @param blob the blob to read, never {@code null}
     * @param maxLines the maximum number of {@link ContentLine} to produce; implementations must
     *            stop past this limit and flag the result as truncated
     */
    DiffableContent extract(Blob blob, int maxLines) throws IOException;

    /** Optional initialisation hook, called once with the contributed descriptor properties. */
    default void init(Map<String, String> properties) {
        // NOP
    }
}

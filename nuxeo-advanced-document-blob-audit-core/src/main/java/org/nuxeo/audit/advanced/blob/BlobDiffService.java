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

import java.util.Date;

import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;

/**
 * Entry point of the blob diff feature.
 *
 * @since 1.0
 */
public interface BlobDiffService {

    BlobDiffConfigDescriptor getConfig();

    /** {@code true} if the given document/xpath/blob is eligible for a content diff. */
    boolean isDiffable(String docType, String xpath, Blob blob);

    /** Extracts the comparable content, or {@code null} if no extractor handles the mime type. */
    DiffableContent extract(Blob blob);

    /** Compares two blobs, or {@code null} if either side could not be extracted. */
    DiffResult diff(Blob oldBlob, Blob newBlob);

    /**
     * Creates the {@code BlobDiff} document holding the result, inside the dated container. The
     * session must be privileged.
     */
    DocumentModel createDiffDocument(CoreSession session, DocumentModel source, String xpath, Blob oldBlob,
            Blob newBlob, String user, Date date, DiffResult result, String status, String correlationId);

    /**
     * Same as above for a source that may no longer exist (deleted before the asynchronous work
     * ran): only its id, repository and last known title are needed.
     */
    DocumentModel createDiffDocument(CoreSession session, String sourceId, String sourceRepository,
            String sourceTitle, String xpath, Blob oldBlob, Blob newBlob, String user, Date date, DiffResult result,
            String status, String correlationId);

    /**
     * Full variant: also persists the storage identity of both binaries, which is what makes a diff
     * in {@code error} replayable.
     *
     * @since 1.2
     */
    DocumentModel createDiffDocument(CoreSession session, String sourceId, String sourceRepository,
            String sourceTitle, String xpath, Blob oldBlob, Blob newBlob, FrozenBlobs frozen, String user, Date date,
            DiffResult result, String status, String correlationId);

    /** Full version-aware variant used by version-triggered works. */
    DocumentModel createDiffDocument(CoreSession session, String sourceId, String sourceRepository,
            String sourceTitle, String xpath, Blob oldBlob, Blob newBlob, FrozenBlobs frozen,
            VersionContext versions, String user, Date date, DiffResult result, String status, String correlationId);

    /**
     * Returns (creating it if needed) the root {@code /change-diff} container and makes sure its
     * ACL is the expected one. The session must be privileged.
     */
    DocumentModel ensureRootContainer(CoreSession session);

    /** Resets the root container ACL if it differs from the expected one; {@code true} if repaired. */
    boolean repairSecurity(CoreSession session, DocumentModel root);

    /** Returns (creating it if needed) the dated container for the given date. */
    DocumentModel getOrCreateContainer(CoreSession session, Date date);
}

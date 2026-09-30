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
package org.nuxeo.audit.advanced.blob.work;

import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.DIFF_DOCTYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_ERROR;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_OK;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_TYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.SUMMARY_SOURCE_MISSING;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.WORK_CATEGORY;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_CORRELATION_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_SUMMARY;

import java.util.Date;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.audit.advanced.blob.BlobDiffService;
import org.nuxeo.audit.advanced.blob.DiffResult;
import org.nuxeo.audit.advanced.blob.FrozenBlobs;
import org.nuxeo.audit.advanced.blob.VersionContext;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.blob.BlobInfo;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.BlobProvider;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.core.work.AbstractWork;
import org.nuxeo.runtime.api.Framework;

/**
 * Asynchronous side of the blob diff: extraction and comparison never run in the user transaction.
 * <p>
 * Both sides are frozen by provider id and storage key when the work is scheduled. The work never
 * rereads the blob property from the current document, because a later save could otherwise make a
 * queued v1 -> v2 work compare v1 -> v3.
 *
 * @since 1.0
 */
public class BlobDiffWork extends AbstractWork {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LogManager.getLogger(BlobDiffWork.class);

    protected final String sourceTitle;

    protected final String xpath;

    protected final String oldBlobProviderId;

    protected final String oldBlobKey;

    protected final String oldFilename;

    protected final String oldMimeType;

    protected final String oldDigest;

    protected final long oldLength;

    protected final String newBlobProviderId;

    protected final String newBlobKey;

    protected final String newFilename;

    protected final String newMimeType;

    protected final String newDigest;

    protected final long newLength;

    protected final String principal;

    protected final long eventTime;

    protected final String correlationId;

    protected final VersionContext versions;

    /**
     * Id of a previous {@code BlobDiff} this work replaces ({@code BlobDiff.Retry}). It is removed
     * only once the new document exists, so a retry that fails again still leaves a trace.
     */
    protected String replaceDiffId;

    public BlobDiffWork(String repositoryName, String docId, String xpath, String oldBlobProviderId,
            String oldBlobKey, String oldFilename, String oldMimeType, String oldDigest, long oldLength,
            String newBlobProviderId, String newBlobKey, String newFilename, String newMimeType, String newDigest,
            long newLength, String principal, long eventTime, String correlationId) {
        this(repositoryName, docId, null, xpath, oldBlobProviderId, oldBlobKey, oldFilename, oldMimeType, oldDigest,
                oldLength, newBlobProviderId, newBlobKey, newFilename, newMimeType, newDigest, newLength, principal,
                eventTime, correlationId, VersionContext.NONE);
    }

    public BlobDiffWork(String repositoryName, String docId, String sourceTitle, String xpath,
            String oldBlobProviderId, String oldBlobKey, String oldFilename, String oldMimeType, String oldDigest,
            long oldLength, String newBlobProviderId, String newBlobKey, String newFilename, String newMimeType,
            String newDigest, long newLength, String principal, long eventTime, String correlationId,
            VersionContext versions) {
        super(workId(repositoryName, docId, xpath, oldDigest, newDigest, correlationId, versions));
        setDocument(repositoryName, docId);
        this.sourceTitle = sourceTitle;
        this.xpath = xpath;
        this.oldBlobProviderId = oldBlobProviderId;
        this.oldBlobKey = oldBlobKey;
        this.oldFilename = oldFilename;
        this.oldMimeType = oldMimeType;
        this.oldDigest = oldDigest;
        this.oldLength = oldLength;
        this.newBlobProviderId = newBlobProviderId;
        this.newBlobKey = newBlobKey;
        this.newFilename = newFilename;
        this.newMimeType = newMimeType;
        this.newDigest = newDigest;
        this.newLength = newLength;
        this.principal = principal;
        this.eventTime = eventTime;
        this.correlationId = correlationId;
        this.versions = versions == null ? VersionContext.NONE : versions;
    }

    /** @since 1.2 */
    public BlobDiffWork withReplaceDiffId(String diffId) {
        this.replaceDiffId = diffId;
        return this;
    }

    /**
     * Builds the work id from the <b>business identity</b> of the comparison, never from a random
     * correlation id.
     * <p>
     * The id is what {@link org.nuxeo.ecm.core.work.api.WorkManager.Scheduling#IF_NOT_RUNNING_OR_SCHEDULED}
     * deduplicates on. Deriving it from a per-event UUID made every work unique, so the scheduling
     * flag never deduplicated anything and a burst of check-ins piled up redundant works.
     * <p>
     * Identity is the version pair when known (the nominal case), otherwise the digest pair, which
     * is just as stable: the same two binaries always produce the same diff. The correlation id is
     * only a last-resort fallback when neither is available.
     *
     * @since 2025.2
     */
    protected static String workId(String repositoryName, String docId, String xpath, String oldDigest,
            String newDigest, String correlationId, VersionContext versions) {
        String identity;
        if (versions != null && versions.previousVersionId() != null && versions.newVersionId() != null) {
            identity = versions.previousVersionId() + ">" + versions.newVersionId();
        } else if (oldDigest != null && newDigest != null) {
            identity = oldDigest + ">" + newDigest;
        } else {
            identity = String.valueOf(correlationId);
        }
        return repositoryName + ':' + docId + ':' + xpath + ':' + identity + ":blobDiff";
    }

    /**
     * Transient failures are the common case here: an S3 read timing out, a converter temporarily
     * unavailable, a concurrent update on the dated container. Retrying is safe because
     * {@link #existingDiffId()} makes the work idempotent.
     *
     * @since 2025.2
     */
    @Override
    public int getRetryCount() {
        return 2;
    }

    protected FrozenBlobs frozen() {
        return new FrozenBlobs(oldBlobProviderId, oldBlobKey, oldMimeType, oldLength, newBlobProviderId, newBlobKey,
                newMimeType, newLength);
    }

    /**
     * Id of an already recorded {@code BlobDiff} for this exact correlation id, or {@code null}.
     * <p>
     * The WorkManager is <b>at-least-once</b>: a node crash, a redeployment or a retry can run the
     * same work twice. Without this guard the second run simply created a second document - the
     * computed name being already taken, the core silently renamed it - and the audit trail ended up
     * with duplicated, indistinguishable entries.
     * <p>
     * The diff being replaced by {@code BlobDiff.Retry} is excluded on purpose: it carries the same
     * correlation id by design, and is precisely what this run must supersede.
     *
     * @since 2025.2
     */
    protected String existingDiffId() {
        if (correlationId == null) {
            return null;
        }
        String nxql = "SELECT * FROM " + DIFF_DOCTYPE + " WHERE " + XP_CORRELATION_ID + " = "
                + NXQL.escapeString(correlationId);
        for (DocumentModel diff : session.query(nxql, null, 2, 0, false)) {
            if (!diff.getId().equals(replaceDiffId)) {
                return diff.getId();
            }
        }
        return null;
    }

    /** Removes the diff this work replaces, if any. */
    protected void removeReplaced() {
        if (replaceDiffId == null) {
            return;
        }
        IdRef ref = new IdRef(replaceDiffId);
        if (session.exists(ref)) {
            session.removeDocument(ref);
            log.debug("Replaced BlobDiff {} removed", replaceDiffId);
        }
    }

    @Override
    public String getCategory() {
        return WORK_CATEGORY;
    }

    @Override
    public String getTitle() {
        return "Blob content diff for " + docId + " (" + xpath + ")";
    }

    @Override
    public void work() {
        setStatus("Diffing");
        openSystemSession();

        String alreadyRecorded = existingDiffId();
        if (alreadyRecorded != null) {
            // Already done by a previous run of this very work: nothing to add, and above all
            // nothing to duplicate.
            log.debug("BlobDiff {} already exists for correlation id {}, skipping", alreadyRecorded, correlationId);
            removeReplaced();
            setStatus("Done");
            return;
        }

        IdRef sourceRef = new IdRef(docId);
        if (!session.exists(sourceRef)) {
            // Deleted (or permanently purged) between the save and this work: the audit entry
            // already exists, so leave a trace instead of failing silently.
            log.warn("Source document {} no longer exists, recording an error BlobDiff ({})", docId, xpath);
            DocumentModel diffDoc = Framework.getService(BlobDiffService.class)
                                             .createDiffDocument(session, docId, repositoryName, sourceTitle,
                                                     xpath, null, null, frozen(), versions, principal,
                                                     new Date(eventTime), null, STATUS_ERROR, correlationId);
            diffDoc.setPropertyValue(XP_SUMMARY, SUMMARY_SOURCE_MISSING);
            session.saveDocument(diffDoc);
            removeReplaced();
            setStatus("Done");
            return;
        }
        DocumentModel source = session.getDocument(sourceRef);
        Blob oldBlob = resolveBlob(oldBlobProviderId, oldBlobKey, oldFilename, oldMimeType, oldDigest, oldLength,
                "previous");
        Blob newBlob = resolveBlob(newBlobProviderId, newBlobKey, newFilename, newMimeType, newDigest, newLength,
                "new");
        BlobDiffService service = Framework.getService(BlobDiffService.class);

        DiffResult result = null;
        String status = STATUS_OK;
        if (oldBlob == null || newBlob == null) {
            log.warn("Frozen blob pair ({}, {}) is no longer fully readable, cannot diff {} ({})", oldBlobKey,
                    newBlobKey, docId, xpath);
            status = STATUS_ERROR;
        } else {
            try {
                result = service.diff(oldBlob, newBlob);
                if (result == null) {
                    // Since COR-01, a null can only mean "no extractor for that mime type": an
                    // extraction that fails raises and lands in the catch below, as an error.
                    status = STATUS_SKIPPED_TYPE;
                }
            } catch (RuntimeException e) {
                log.warn("Blob diff failed for {} ({})", docId, xpath, e);
                status = STATUS_ERROR;
            }
        }

        DocumentModel diffDoc = service.createDiffDocument(session, source.getId(), source.getRepositoryName(),
                source.getTitle(), xpath, oldBlob, newBlob, frozen(), versions, principal, new Date(eventTime), result,
                status, correlationId);
        removeReplaced();
        log.debug("Created BlobDiff {} for {} ({})", diffDoc.getId(), docId, xpath);
        setStatus("Done");
    }

    /** Re-hydrates one frozen side of the comparison through its blob provider. */
    protected Blob resolveBlob(String providerId, String key, String filename, String mimeType, String digest,
            long length, String side) {
        if (key == null) {
            return null;
        }
        try {
            BlobProvider provider = lookupProvider(providerId);
            if (provider == null) {
                log.warn("No blob provider {} to resolve {} blob {}", providerId, side, key);
                return null;
            }
            BlobInfo info = new BlobInfo();
            info.key = key;
            info.filename = filename;
            info.mimeType = mimeType;
            info.digest = digest;
            info.length = length < 0 ? null : Long.valueOf(length);
            return provider.readBlob(info);
        } catch (Exception e) { // NOSONAR - a missing frozen binary must not fail the work
            log.warn("Cannot resolve {} blob {} from provider {}", side, key, providerId, e);
            return null;
        }
    }

    /**
     * Returns the blob provider registered under the given id.
     * <p>
     * <b>Single point of adaptation.</b> If {@code BlobManager#getBlobProvider(String)} is not the
     * accessor available in your target version, this is the only method to change.
     */
    protected BlobProvider lookupProvider(String providerId) {
        return Framework.getService(BlobManager.class).getBlobProvider(providerId);
    }
}

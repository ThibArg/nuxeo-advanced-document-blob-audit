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

import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.CONTAINER_NAME;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.DIFF_DOCTYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_ERROR;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_OK;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_CONTENT_REPLACED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_TYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.SUMMARY_SOURCE_MISSING;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.WORK_CATEGORY;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_CORRELATION_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_SUMMARY;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.audit.advanced.blob.BlobDiffService;
import org.nuxeo.audit.advanced.blob.BlobDiffStore;
import org.nuxeo.audit.advanced.blob.DiffRequest;
import org.nuxeo.audit.advanced.blob.DiffResult;
import org.nuxeo.audit.advanced.blob.FrozenBlob;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.core.work.AbstractWork;
import org.nuxeo.runtime.api.Framework;

/**
 * Asynchronous side of the blob diff: extraction and comparison run in no transaction at all, neither
 * the user's nor the work's.
 * <p>
 * Both sides are frozen by provider id and storage key when the work is scheduled. The work never
 * rereads the blob property from the current document, because a later save could otherwise make a
 * queued v1 -> v2 work compare v1 -> v3.
 *
 * @since 2025.1
 */
public class BlobDiffWork extends AbstractWork {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LogManager.getLogger(BlobDiffWork.class);

    /** What this work compares and what its {@code BlobDiff} records; it also names the work. */
    protected final DiffRequest request;

    /**
     * Id of a previous {@code BlobDiff} this work replaces ({@code BlobDiff.Retry}). Removed only once
     * the new document exists: a retry that fails again leaves the error diff in place.
     */
    protected String replaceDiffId;

    /**
     * Creates the work comparing the pair the request describes, named after it.
     *
     * @since 2025.1
     */
    public BlobDiffWork(DiffRequest request) {
        super(request.workId());
        setDocument(request.repository(), request.sourceId());
        this.request = request;
    }

    /**
     * Makes this work the replay of the {@code BlobDiff} {@code diffId}, under an id of its own.
     * <p>
     * Under the nominal id, the WorkManager would take the replay for a duplicate of the run that
     * recorded the error: the stream implementation skips an idempotent work whose id is among the
     * last ones its thread processed, so the retry would be dropped in silence.
     *
     * @since 2025.1
     */
    public BlobDiffWork withReplaceDiffId(String diffId) {
        this.replaceDiffId = diffId;
        id = id + ":retry:" + diffId;
        return this;
    }

    /**
     * Returns {@code 2}: transient failures are the common case here - an S3 read timing out, a
     * converter temporarily unavailable, a concurrent update on the dated container. Retrying is safe
     * because {@link #existingDiffId()} makes the work idempotent.
     *
     * @since 2025.1
     */
    @Override
    public int getRetryCount() {
        return 2;
    }

    /**
     * Returns the id of an already recorded {@code BlobDiff} for this exact correlation id, or
     * {@code null}.
     * <p>
     * The WorkManager is <b>at-least-once</b>: a node crash, a redeployment or a retry can run the
     * same work twice. Without this guard the second run would create a second document - the
     * computed name being already taken, the core would silently rename it - and the audit trail
     * would hold duplicated, indistinguishable entries.
     * <p>
     * The diff being replaced by {@code BlobDiff.Retry} is excluded on purpose: it carries the same
     * correlation id by design, and is precisely what this run must supersede.
     * <p>
     * Only {@code /change-diff} counts. The correlation id is published in the audit entry, so a
     * {@code BlobDiff} carrying it elsewhere could have been forged to suppress this very diff.
     *
     * @since 2025.1
     */
    protected String existingDiffId() {
        String correlationId = request.correlationId();
        if (correlationId == null) {
            return null;
        }
        String nxql = "SELECT * FROM " + DIFF_DOCTYPE + " WHERE " + XP_CORRELATION_ID + " = "
                + NXQL.escapeString(correlationId) + " AND ecm:path STARTSWITH "
                + NXQL.escapeString("/" + CONTAINER_NAME) + " AND ecm:isProxy = 0 AND ecm:isVersion = 0";
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
        var ref = new IdRef(replaceDiffId);
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
        return "Blob content diff for " + docId + " (" + request.xpath() + ")";
    }

    /**
     * Reads in the work transaction, extracts and compares with <b>no transaction open</b>, then
     * writes in a new one.
     * <p>
     * Materialising and parsing two binaries can outlast the transaction timeout. Inside the work
     * transaction, the commit would then fail: nothing would be recorded, not even an {@code error}
     * diff, and every retry would run the whole extraction again. Idempotency is checked again before
     * writing, since another run of this work may have recorded the diff in the meantime.
     */
    @Override
    public void work() {
        setStatus("Diffing");
        openSystemSession();

        if (alreadyRecorded()) {
            return;
        }

        String xpath = request.xpath();
        var sourceRef = new IdRef(docId);
        if (!session.exists(sourceRef)) {
            // Deleted (or permanently purged) between the save and this work: the audit entry
            // already exists, so leave a trace instead of failing silently.
            log.warn("Source document {} no longer exists, recording an error BlobDiff ({})", docId, xpath);
            DocumentModel diffDoc = BlobDiffStore.createDiffDocument(session, request, STATUS_ERROR, null);
            diffDoc.setPropertyValue(XP_SUMMARY, SUMMARY_SOURCE_MISSING);
            session.saveDocument(diffDoc);
            removeReplaced();
            setStatus("Done");
            return;
        }
        DocumentModel source = session.getDocument(sourceRef);
        String sourceType = source.getType();
        // The diff records the title of the source at work time, not at check-in.
        DiffRequest recorded = request.withSourceTitle(source.getTitle());
        FrozenBlob oldSide = request.oldBlob();
        FrozenBlob newSide = request.newBlob();
        // DiffRequest allows a side with no stored blob: it is as unreadable as a key that resolves to nothing.
        Blob oldBlob = oldSide == null ? null : oldSide.resolve();
        Blob newBlob = newSide == null ? null : newSide.resolve();
        var service = Framework.getService(BlobDiffService.class);

        commitOrRollbackTransaction();
        DiffResult result = null;
        String status = STATUS_OK;
        if (oldBlob == null || newBlob == null) {
            // The filenames, never the storage keys: see FrozenBlob#resolve.
            log.warn("Frozen blob pair ({} -> {}) is no longer fully readable, cannot diff {} ({})",
                    filenameOf(oldSide), filenameOf(newSide), docId, xpath);
            status = STATUS_ERROR;
        } else {
            // Re-checked at work time: a retry or a queued work can outlive a configuration change. Not a
            // security boundary (lengths come from the BlobDiff, see BlobDiffRetryOp#checkProvenance).
            // NOT_APPLICABLE has no skip reason: the diff goes on.
            String ineligible = service.getEligibility(sourceType, xpath, oldBlob, newBlob).getSkipReason();
            if (ineligible != null) {
                log.warn("Frozen blob pair for {} ({}) is no longer eligible for a diff ({}), recording the skip",
                        docId, xpath, ineligible);
                status = ineligible;
            } else {
                try {
                    result = service.diff(oldBlob, newBlob);
                    if (result == null) {
                        // A null only means "no extractor for that mime type": an extraction that
                        // fails raises and lands in the catch below, as an error.
                        status = STATUS_SKIPPED_TYPE;
                    } else if (result.replaced()) {
                        status = STATUS_SKIPPED_CONTENT_REPLACED;
                    }
                } catch (RuntimeException e) {
                    log.warn("Blob diff failed for {} ({})", docId, xpath, e);
                    status = STATUS_ERROR;
                }
            }
        }
        startTransaction();

        if (alreadyRecorded()) {
            return;
        }
        DocumentModel diffDoc = BlobDiffStore.createDiffDocument(session, recorded, status, result);
        removeReplaced();
        log.debug("Created BlobDiff {} for {} ({})", diffDoc.getId(), docId, xpath);
        setStatus("Done");
    }

    protected static String filenameOf(FrozenBlob side) {
        return side == null ? null : side.filename();
    }

    /**
     * Returns whether a run of this very work already recorded the diff, in which case there is
     * nothing to add and above all nothing to duplicate. The diff this work replaces is removed all
     * the same.
     *
     * @since 2025.1
     */
    protected boolean alreadyRecorded() {
        String recorded = existingDiffId();
        if (recorded == null) {
            return false;
        }
        log.debug("BlobDiff {} already exists for correlation id {}, skipping", recorded, request.correlationId());
        removeReplaced();
        setStatus("Done");
        return true;
    }
}

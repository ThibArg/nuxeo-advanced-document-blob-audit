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
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_RETRYING;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_TYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.SUMMARY_SOURCE_MISSING;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.WORK_CATEGORY;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_CORRELATION_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_STATUS;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_SUMMARY;

import java.io.IOException;
import java.util.Date;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.audit.advanced.blob.BlobDiffService;
import org.nuxeo.audit.advanced.blob.DiffEligibility;
import org.nuxeo.audit.advanced.blob.DiffResult;
import org.nuxeo.audit.advanced.blob.FrozenBlobs;
import org.nuxeo.audit.advanced.blob.VersionContext;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.blob.BlobInfo;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.BlobProvider;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.core.work.AbstractWork;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.transaction.TransactionHelper;

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

    /**
     * Makes this work the replay of the {@code BlobDiff} {@code diffId}, under an id of its own.
     * <p>
     * Under the nominal id, the WorkManager took the replay for a duplicate of the run that recorded
     * the error: the stream implementation skips an idempotent work whose id is among the last ones
     * its thread processed, so the retry was dropped in silence.
     *
     * @since 2025.1
     */
    public BlobDiffWork withReplaceDiffId(String diffId) {
        this.replaceDiffId = diffId;
        id = id + ":retry:" + diffId;
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
     * @since 2025.1
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
     * @since 2025.1
     */
    @Override
    public int getRetryCount() {
        return 2;
    }

    /**
     * Puts the replaced diff back in {@code error} when a replay fails for good, so that it can be
     * retried again rather than staying {@code retrying}.
     *
     * @since 2025.1
     */
    @Override
    public void workFailed(RuntimeException exception) {
        if (replaceDiffId != null) {
            restoreReplacedStatus();
        }
        super.workFailed(exception);
    }

    protected void restoreReplacedStatus() {
        try {
            TransactionHelper.runInTransaction(() -> CoreInstance.doPrivileged(repositoryName, s -> {
                IdRef ref = new IdRef(replaceDiffId);
                if (!s.exists(ref)) {
                    return;
                }
                DocumentModel diff = s.getDocument(ref);
                if (STATUS_RETRYING.equals(diff.getPropertyValue(XP_STATUS))) {
                    diff.setPropertyValue(XP_STATUS, STATUS_ERROR);
                    s.saveDocument(diff);
                }
            }));
        } catch (RuntimeException e) {
            log.warn("Cannot put BlobDiff {} back in error after its retry failed", replaceDiffId, e);
        }
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
     * <p>
     * Only {@code /change-diff} counts. The correlation id is published in the audit entry, so a
     * {@code BlobDiff} carrying it elsewhere could have been forged to suppress this very diff.
     *
     * @since 2025.1
     */
    protected String existingDiffId() {
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

    /**
     * Reads in the work transaction, extracts and compares with <b>no transaction open</b>, then
     * writes in a new one.
     * <p>
     * Materialising and parsing two binaries can outlast the transaction timeout. Inside the work
     * transaction that made the commit fail: nothing was recorded, not even an {@code error} diff, and
     * every retry ran the whole extraction again. Idempotency is checked again before writing, since
     * another run of this work may have recorded the diff in the meantime.
     */
    @Override
    public void work() {
        setStatus("Diffing");
        openSystemSession();

        if (alreadyRecorded()) {
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
        String sourceType = source.getType();
        String sourceRepository = source.getRepositoryName();
        String currentTitle = source.getTitle();
        Blob oldBlob = resolveBlob(oldBlobProviderId, oldBlobKey, oldFilename, oldMimeType, oldDigest, oldLength,
                "previous");
        Blob newBlob = resolveBlob(newBlobProviderId, newBlobKey, newFilename, newMimeType, newDigest, newLength,
                "new");
        BlobDiffService service = Framework.getService(BlobDiffService.class);

        commitOrRollbackTransaction();
        DiffResult result = null;
        String status = STATUS_OK;
        if (oldBlob == null || newBlob == null) {
            log.warn("Frozen blob pair ({}, {}) is no longer fully readable, cannot diff {} ({})", oldBlobKey,
                    newBlobKey, docId, xpath);
            status = STATUS_ERROR;
        } else {
            String ineligible = ineligibleStatus(service, sourceType, oldBlob, newBlob);
            if (ineligible != null) {
                log.warn("Frozen blob pair for {} ({}) is no longer eligible for a diff ({}), recording the skip",
                        docId, xpath, ineligible);
                status = ineligible;
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
        }
        startTransaction();

        if (alreadyRecorded()) {
            return;
        }
        DocumentModel diffDoc = service.createDiffDocument(session, docId, sourceRepository, currentTitle, xpath,
                oldBlob, newBlob, frozen(), versions, principal, new Date(eventTime), result, status, correlationId);
        removeReplaced();
        log.debug("Created BlobDiff {} for {} ({})", diffDoc.getId(), docId, xpath);
        setStatus("Done");
    }

    /**
     * Whether a run of this very work already recorded the diff, in which case there is nothing to add
     * and above all nothing to duplicate. The diff this work replaces is removed all the same.
     *
     * @since 2025.1
     */
    protected boolean alreadyRecorded() {
        String recorded = existingDiffId();
        if (recorded == null) {
            return false;
        }
        log.debug("BlobDiff {} already exists for correlation id {}, skipping", recorded, correlationId);
        removeReplaced();
        setStatus("Done");
        return true;
    }

    /**
     * Re-evaluates {@code maxBlobSize} and the mime type whitelist at work time.
     * <p>
     * The eligibility decided at check-in can be stale by the time the work runs: the queue is
     * asynchronous, and {@code BlobDiff.Retry} replays a pair that was frozen arbitrarily long ago
     * with no eligibility check of its own. Lowering {@code maxBlobSize} or disabling an extractor
     * would otherwise leave queued and replayable works free to extract what the configuration now
     * forbids.
     * <p>
     * <b>This is not the security boundary of {@code BlobDiff.Retry}</b>, and must not be mistaken
     * for one: the length compared here comes from {@code BlobInfo}, which {@code resolveBlob}
     * fills from {@code bdiff:oldLength} / {@code bdiff:newLength} - document properties, hence
     * forgeable. What stops a forged input is
     * {@code BlobDiffRetryOp#checkProvenance}. This check is about configuration drift, plus
     * defence in depth.
     * <p>
     * {@code NOT_APPLICABLE} is deliberately <b>not</b> a skip: it means the feature was turned off
     * or the xpath left the watched set, which says nothing about the binary. Filing it as
     * {@code skippedTooLarge} or {@code skippedUnsupportedType} would record a reason that is not
     * the real one. {@link DiffEligibility#getSkipReason()} returns {@code null} for it already, so
     * the work simply carries on. The audit entry written at check-in carries a
     * {@code diffCorrelationId}, so a {@code BlobDiff} has to exist for it either way.
     *
     * @return {@code STATUS_SKIPPED_SIZE}, {@code STATUS_SKIPPED_TYPE}, or {@code null} to diff
     * @since 2025.1
     */
    protected String ineligibleStatus(BlobDiffService service, String docType, Blob oldBlob, Blob newBlob) {
        return DiffEligibility.combine(service.getEligibility(docType, xpath, oldBlob),
                service.getEligibility(docType, xpath, newBlob)).getSkipReason();
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
        } catch (IOException | RuntimeException e) {
            // Narrow on purpose: readBlob declares IOException and nothing else. Catching Exception
            // here also caught InterruptedException, on a WorkManager thread that is interrupted at
            // shutdown - the work then filed a bogus "error" diff instead of letting the cancel
            // through.
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

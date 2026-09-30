/*
 * (C) Copyright 2026 Nuxeo SA (http://nuxeo.com/) and others.
 * Licensed under the Apache License, Version 2.0.
 */
package org.nuxeo.audit.advanced.blob;

import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.audit.advanced.blob.work.BlobDiffWork;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.work.api.Work;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.runtime.api.Framework;

/** Cheap synchronous decision point for a diff between two persisted Nuxeo versions. */
public final class BlobDiffTrigger {

    private static final Logger log = LogManager.getLogger(BlobDiffTrigger.class);

    private BlobDiffTrigger() {
        // utility class
    }

    /**
     * What the trigger decided for one xpath.
     * <p>
     * A nullable correlation id was not enough: it cannot tell "nothing to do" from "the binary
     * changed but cannot be diffed", and the caller must audit the second case.
     *
     * @param correlationId set when a {@link BlobDiffWork} was scheduled, {@code null} otherwise
     * @param skipReason one of {@code BlobAuditConstants.STATUS_SKIPPED_*} when the change must be
     *            audited without a diff, {@code null} otherwise
     * @since 2025.2
     */
    public record Outcome(String correlationId, String skipReason) {

        protected static final Outcome NONE = new Outcome(null, null);

        public static Outcome scheduled(String correlationId) {
            return new Outcome(correlationId, null);
        }

        public static Outcome skipped(String skipReason) {
            return new Outcome(null, skipReason);
        }

        public static Outcome none() {
            return NONE;
        }

        /** {@code true} when an audit entry must be written, whichever the case. */
        public boolean isAuditable() {
            return correlationId != null || skipReason != null;
        }
    }

    /**
     * Schedules a content diff if the blob really differs between two successive versions, or
     * reports a non-diffable change.
     * <p>
     * <b>The order of the checks is significant.</b> Eligibility used to be evaluated before
     * {@link #sameContent}; reporting a skip requires the opposite, otherwise every new version of
     * an over-sized document would be audited as a binary change even when the binary did not move.
     *
     * @return never {@code null}; see {@link Outcome}
     */
    public static Outcome scheduleIfNeeded(DocumentModel liveDoc, String xpath, Blob oldBlob, Blob newBlob,
            VersionContext versions, String principal, long eventTime) {
        // Additions/removals are deliberately not expanded into a complete-file diff. The feature currently
        // audits replacement of an existing blob, as the original save-based implementation did.
        if (oldBlob == null || newBlob == null) {
            return Outcome.none();
        }
        BlobDiffService service = Framework.getService(BlobDiffService.class);
        if (service == null) {
            return Outcome.none();
        }
        DiffEligibility eligibility = DiffEligibility.combine(
                service.getEligibility(liveDoc.getType(), xpath, oldBlob),
                service.getEligibility(liveDoc.getType(), xpath, newBlob));
        if (eligibility == DiffEligibility.NOT_APPLICABLE) {
            return Outcome.none();
        }
        // Must run before reporting a skip: only an actual binary change is worth an audit entry.
        if (sameContent(oldBlob, newBlob)) {
            return Outcome.none();
        }
        if (eligibility.isReportable()) {
            log.debug("Binary changed at {} on {} but cannot be diffed ({}), auditing without a diff", xpath,
                    liveDoc.getId(), eligibility.getSkipReason());
            return Outcome.skipped(eligibility.getSkipReason());
        }
        if (!(oldBlob instanceof ManagedBlob oldManaged) || !(newBlob instanceof ManagedBlob newManaged)) {
            log.warn("Version blobs at {} on {} are not both managed, cannot diff asynchronously", xpath,
                    liveDoc.getId());
            return Outcome.none();
        }

        String correlationId = UUID.randomUUID().toString();
        Work work = new BlobDiffWork(liveDoc.getRepositoryName(), liveDoc.getId(), liveDoc.getTitle(), xpath,
                oldManaged.getProviderId(), oldManaged.getKey(), oldBlob.getFilename(), oldBlob.getMimeType(),
                oldBlob.getDigest(), oldBlob.getLength(), newManaged.getProviderId(), newManaged.getKey(),
                newBlob.getFilename(), newBlob.getMimeType(), newBlob.getDigest(), newBlob.getLength(), principal,
                eventTime, correlationId, versions);

        // The version and the audit event belong to the current transaction. Running only after commit guarantees
        // that a rollback creates neither the audit entry nor a BlobDiff.
        Framework.getService(WorkManager.class)
                 .schedule(work, WorkManager.Scheduling.IF_NOT_RUNNING_OR_SCHEDULED, true);
        log.debug("Scheduled version blob diff {} for {} ({}, {} -> {})", correlationId, liveDoc.getId(), xpath,
                versions.previousVersionLabel(), versions.newVersionLabel());
        return Outcome.scheduled(correlationId);
    }

    protected static boolean sameContent(Blob oldBlob, Blob newBlob) {
        String oldDigest = oldBlob.getDigest();
        String newDigest = newBlob.getDigest();
        if (oldDigest == null || newDigest == null) {
            return false;
        }
        String oldAlgorithm = oldBlob.getDigestAlgorithm();
        String newAlgorithm = newBlob.getDigestAlgorithm();
        return (oldAlgorithm == null || newAlgorithm == null || oldAlgorithm.equalsIgnoreCase(newAlgorithm))
                && oldDigest.equalsIgnoreCase(newDigest);
    }
}

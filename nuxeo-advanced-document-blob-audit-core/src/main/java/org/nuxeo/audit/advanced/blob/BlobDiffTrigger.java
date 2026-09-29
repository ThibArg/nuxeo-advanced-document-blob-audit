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
     * Schedules a content diff if the blob really differs between two successive versions.
     *
     * @return the correlation id, or {@code null} when no work was scheduled
     */
    public static String scheduleIfNeeded(DocumentModel liveDoc, String xpath, Blob oldBlob, Blob newBlob,
            VersionContext versions, String principal, long eventTime) {
        // Additions/removals are deliberately not expanded into a complete-file diff. The feature currently
        // audits replacement of an existing blob, as the original save-based implementation did.
        if (oldBlob == null || newBlob == null) {
            return null;
        }
        BlobDiffService service = Framework.getService(BlobDiffService.class);
        if (service == null || !service.isDiffable(liveDoc.getType(), xpath, oldBlob)
                || !service.isDiffable(liveDoc.getType(), xpath, newBlob)) {
            return null;
        }
        if (sameContent(oldBlob, newBlob)) {
            return null;
        }
        if (!(oldBlob instanceof ManagedBlob oldManaged) || !(newBlob instanceof ManagedBlob newManaged)) {
            log.warn("Version blobs at {} on {} are not both managed, cannot diff asynchronously", xpath,
                    liveDoc.getId());
            return null;
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
        return correlationId;
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

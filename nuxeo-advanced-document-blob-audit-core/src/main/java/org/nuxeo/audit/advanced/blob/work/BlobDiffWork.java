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

import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_ERROR;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_OK;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_TYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.SUMMARY_SOURCE_MISSING;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.WORK_CATEGORY;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_SUMMARY;

import java.util.Date;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.audit.advanced.blob.BlobDiffService;
import org.nuxeo.audit.advanced.blob.DiffResult;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.blob.BlobInfo;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.BlobProvider;
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

    public BlobDiffWork(String repositoryName, String docId, String xpath, String oldBlobProviderId,
            String oldBlobKey, String oldFilename, String oldMimeType, String oldDigest, long oldLength,
            String newBlobProviderId, String newBlobKey, String newFilename, String newMimeType,
            String newDigest, long newLength, String principal, long eventTime, String correlationId) {
        super(repositoryName + ':' + docId + ':' + xpath + ':' + correlationId + ":blobDiff");
        setDocument(repositoryName, docId);
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

        IdRef sourceRef = new IdRef(docId);
        if (!session.exists(sourceRef)) {
            // Deleted (or permanently purged) between the save and this work: the audit entry
            // already exists, so leave a trace instead of failing silently.
            log.warn("Source document {} no longer exists, recording an error BlobDiff ({})", docId, xpath);
            DocumentModel diffDoc = Framework.getService(BlobDiffService.class)
                                             .createDiffDocument(session, docId, repositoryName, null, xpath, null,
                                                     null, principal, new Date(eventTime), null, STATUS_ERROR,
                                                     correlationId);
            diffDoc.setPropertyValue(XP_SUMMARY, SUMMARY_SOURCE_MISSING);
            session.saveDocument(diffDoc);
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
                    status = STATUS_SKIPPED_TYPE;
                }
            } catch (RuntimeException e) {
                log.warn("Blob diff failed for {} ({})", docId, xpath, e);
                status = STATUS_ERROR;
            }
        }

        DocumentModel diffDoc = service.createDiffDocument(session, source, xpath, oldBlob, newBlob, principal,
                new Date(eventTime), result, status, correlationId);
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
            info.length = Long.valueOf(length);
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

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
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.WORK_CATEGORY;

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
 * <b>Known architectural weakness.</b> The previous blob is not passed by value - a {@code Blob} is
 * not a safe work payload - only its provider id and storage key are, and the blob is re-resolved
 * at execution time. This works because the binary store does not delete a binary as soon as it is
 * dereferenced; orphans are only removed by the binaries GC. If your GC is scheduled aggressively,
 * a diff can be lost (recorded with an {@code error} status). The robust alternative is to diff
 * version N-1 against the current version instead.
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

    protected final String principal;

    protected final long eventTime;

    protected final String correlationId;

    public BlobDiffWork(String repositoryName, String docId, String xpath, String oldBlobProviderId,
            String oldBlobKey, String oldFilename, String oldMimeType, String oldDigest, long oldLength,
            String principal, long eventTime, String correlationId) {
        super(repositoryName + ':' + docId + ':' + xpath + ':' + eventTime + ":blobDiff");
        setDocument(repositoryName, docId);
        this.xpath = xpath;
        this.oldBlobProviderId = oldBlobProviderId;
        this.oldBlobKey = oldBlobKey;
        this.oldFilename = oldFilename;
        this.oldMimeType = oldMimeType;
        this.oldDigest = oldDigest;
        this.oldLength = oldLength;
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
        // Privileged session: by design the modifying user has no write access to the diff
        // container, and may not even be allowed to read it.
        openSystemSession();
        DocumentModel source = session.getDocument(new IdRef(docId));
        Blob newBlob = (Blob) source.getPropertyValue(xpath);
        Blob oldBlob = resolveOldBlob();

        BlobDiffService service = Framework.getService(BlobDiffService.class);
        DiffResult result = null;
        String status = STATUS_OK;
        if (oldBlob == null) {
            // The previous binary is gone (aggressive binaries GC, provider reconfiguration...).
            // Recorded rather than silently dropped: an audit trail must say when it failed.
            log.warn("Previous blob {} is no longer readable, cannot diff {} ({})", oldBlobKey, docId, xpath);
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

    /**
     * Re-hydrates the previous blob from its storage key, through its blob provider.
     * <p>
     * {@code BlobManager} has no {@code readBlob(BlobInfo, String)}: reading from a key goes
     * through {@link BlobProvider#readBlob(BlobInfo)}. The {@code BlobInfo} members are public
     * fields, not setters.
     */
    protected Blob resolveOldBlob() {
        if (oldBlobKey == null) {
            return null;
        }
        try {
            BlobProvider provider = lookupProvider(oldBlobProviderId);
            if (provider == null) {
                log.warn("No blob provider {} to resolve previous blob {}", oldBlobProviderId, oldBlobKey);
                return null;
            }
            BlobInfo info = new BlobInfo();
            info.key = oldBlobKey;
            info.filename = oldFilename;
            info.mimeType = oldMimeType;
            info.digest = oldDigest;
            info.length = Long.valueOf(oldLength);
            return provider.readBlob(info);
        } catch (Exception e) { // NOSONAR - a missing previous binary must not fail the work
            log.warn("Cannot resolve previous blob {} from provider {}", oldBlobKey, oldBlobProviderId, e);
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

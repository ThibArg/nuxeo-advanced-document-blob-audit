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

import java.util.Objects;
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

/**
 * Synchronous decision point, called from {@link BlobModificationListener}.
 * <p>
 * Everything expensive is deferred: this class only compares digests and schedules a
 * {@link BlobDiffWork}. Keeping it separate from the listener makes it reusable from an Automation
 * operation or a bulk re-processing command.
 *
 * @since 1.0
 */
public class BlobDiffTrigger {

    private static final Logger log = LogManager.getLogger(BlobDiffTrigger.class);

    private BlobDiffTrigger() {
        // utility class
    }

    /**
     * Schedules a content diff if the binary really changed.
     *
     * @return the correlation id to store in the audit entry, or {@code null} if nothing was
     *         scheduled (feature disabled, unsupported type, rename only, blob too large...)
     */
    public static String scheduleIfNeeded(DocumentModel doc, String xpath, Blob oldBlob, Blob newBlob,
            String principal, long eventTime) {
        // A rename, a metadata-only save or a re-upload of the very same bytes is not a content
        // change: the digest short-circuit is what keeps this feature affordable.
        if (oldBlob == null || newBlob == null) {
            return null;
        }
        if (Objects.equals(oldBlob.getDigest(), newBlob.getDigest())) {
            return null;
        }
        BlobDiffService service = Framework.getService(BlobDiffService.class);
        if (service == null || !service.isDiffable(doc.getType(), xpath, newBlob)
                || !service.isDiffable(doc.getType(), xpath, oldBlob)) {
            return null;
        }
        if (!(oldBlob instanceof ManagedBlob managed)) {
            log.debug("Previous blob at {} on {} is not managed, cannot diff asynchronously", xpath, doc.getId());
            return null;
        }

        // The provider id is captured now, while we still hold the ManagedBlob: reading a blob back
        // from a key goes through BlobProvider.readBlob(BlobInfo), and the provider is looked up by
        // id. Deriving it later from the key alone would rely on the key prefix format.
        String providerId = providerIdOf(managed);

        String correlationId = UUID.randomUUID().toString();
        Work work = new BlobDiffWork(doc.getRepositoryName(), doc.getId(), xpath, providerId, managed.getKey(),
                oldBlob.getFilename(), oldBlob.getMimeType(), oldBlob.getDigest(), oldBlob.getLength(), principal,
                eventTime, correlationId);
        // IF_NOT_RUNNING_OR_SCHEDULED: a burst of saves must not pile up identical works
        Framework.getService(WorkManager.class).schedule(work, WorkManager.Scheduling.IF_NOT_RUNNING_OR_SCHEDULED);
        log.debug("Scheduled blob diff {} for {} ({})", correlationId, doc.getId(), xpath);
        return correlationId;
    }

    /**
     * Returns the id of the blob provider holding the given blob.
     * <p>
     * <b>Single point of adaptation.</b> If {@code ManagedBlob#getProviderId()} does not exist in
     * your target version, replace the body with the key-prefix form documented in File Storage
     * ("the key starts with a prefix designating the blob provider"):
     *
     * <pre>
     * String key = managed.getKey();
     * int colon = key.indexOf(':');
     * return colon &gt; 0 ? key.substring(0, colon) : "default";
     * </pre>
     */
    protected static String providerIdOf(ManagedBlob managed) {
        return managed.getProviderId();
    }
}

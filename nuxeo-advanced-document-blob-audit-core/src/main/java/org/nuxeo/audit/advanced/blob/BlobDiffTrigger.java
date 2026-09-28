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
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.audit.advanced.blob.work.BlobDiffWork;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.BlobProvider;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.work.api.Work;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.runtime.api.Framework;

/**
 * Synchronous decision point, called from {@link BlobModificationListener}.
 * <p>
 * Everything expensive is deferred: this class only compares content digests and schedules a
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
        // Eligibility first: it is cheap, and it bounds the size of what sameContent may hash.
        BlobDiffService service = Framework.getService(BlobDiffService.class);
        if (service == null || !service.isDiffable(doc.getType(), xpath, newBlob)
                || !service.isDiffable(doc.getType(), xpath, oldBlob)) {
            return null;
        }
        if (sameContent(oldBlob, newBlob)) {
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
        BlobProvider provider = Framework.getService(BlobManager.class).getBlobProvider(providerId);
        if (provider == null) {
            log.warn("No blob provider {} to persist the new blob for {} ({})", providerId, doc.getId(), xpath);
            return null;
        }

        // Freeze the exact new binary now, before the document is committed. The work must never
        // resolve its right-hand side from the current document: a later save could otherwise make
        // a queued v1 -> v2 work compare v1 -> v3. Blob providers are content-addressed, so writing
        // the same bytes again is normally deduplicated. A transaction rollback may leave an
        // unreferenced binary, which is handled by the regular binaries garbage collector.
        String newBlobKey;
        try {
            newBlobKey = provider.writeBlob(newBlob);
        } catch (IOException e) {
            log.warn("Cannot persist the new blob for an exact asynchronous diff of {} ({})", doc.getId(), xpath, e);
            return null;
        }

        String correlationId = UUID.randomUUID().toString();
        Work work = new BlobDiffWork(doc.getRepositoryName(), doc.getId(), xpath, providerId, managed.getKey(),
                oldBlob.getFilename(), oldBlob.getMimeType(), oldBlob.getDigest(), oldBlob.getLength(), providerId,
                newBlobKey, newBlob.getFilename(), newBlob.getMimeType(), newBlob.getDigest(), newBlob.getLength(),
                principal, eventTime, correlationId);
        // afterCommit = true: we are called from beforeDocumentModification, so the new blob is
        // not persisted yet. Run earlier and the work, reading the source through its own system
        // session, would still see the previous blob and diff it against itself. It also means a
        // rolled-back save schedules nothing, consistent with the audit event.
        // IF_NOT_RUNNING_OR_SCHEDULED: a burst of saves must not pile up identical works.
        Framework.getService(WorkManager.class)
                 .schedule(work, WorkManager.Scheduling.IF_NOT_RUNNING_OR_SCHEDULED, true);
        log.debug("Scheduled blob diff {} for {} ({})", correlationId, doc.getId(), xpath);
        return correlationId;
    }

    /**
     * {@code true} if both blobs hold the same bytes.
     * <p>
     * In {@code beforeDocumentModification} the new blob has not been written to the blob store
     * yet: it is usually a plain in-memory or file blob whose {@link Blob#getDigest()} is
     * {@code null}. Comparing digests directly would then report every save as a change, including
     * a pure rename. So when the new digest is missing, it is computed with the algorithm of the
     * old one. The new blob size is already bounded by {@code maxBlobSize} at this point.
     */
    protected static boolean sameContent(Blob oldBlob, Blob newBlob) {
        String oldDigest = oldBlob.getDigest();
        if (oldDigest == null) {
            return false; // cannot prove equality: treat as a change
        }
        long oldLength = oldBlob.getLength();
        long newLength = newBlob.getLength();
        if (oldLength >= 0 && newLength >= 0 && oldLength != newLength) {
            return false;
        }
        String algorithm = digestAlgorithmOf(oldBlob);
        String newDigest = newBlob.getDigest();
        if (newDigest != null && algorithm != null && algorithm.equalsIgnoreCase(digestAlgorithmOf(newBlob))) {
            return oldDigest.equalsIgnoreCase(newDigest);
        }
        if (algorithm == null) {
            return false;
        }
        try (InputStream in = newBlob.getStream()) {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                md.update(buffer, 0, n);
            }
            return oldDigest.equalsIgnoreCase(HexFormat.of().formatHex(md.digest()));
        } catch (IOException | NoSuchAlgorithmException e) {
            log.debug("Cannot compute digest of new blob, assuming it changed", e);
            return false;
        }
    }

    /** Digest algorithm of the blob, falling back on the digest length when it is not reported. */
    protected static String digestAlgorithmOf(Blob blob) {
        String algorithm = blob.getDigestAlgorithm();
        if (algorithm != null) {
            return algorithm;
        }
        String digest = blob.getDigest();
        if (digest == null) {
            return null;
        }
        return switch (digest.length()) {
            case 32 -> "MD5";
            case 40 -> "SHA-1";
            case 64 -> "SHA-256";
            default -> null;
        };
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

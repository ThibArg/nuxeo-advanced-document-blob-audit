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
import java.io.Serializable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.blob.BlobInfo;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.BlobProvider;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.runtime.api.Framework;

/**
 * Storage identity of one compared binary, frozen when the diff is scheduled.
 * <p>
 * The work re-reads the binary from its provider with this identity, never from the blob property of
 * the current document: a later save could otherwise make a queued v1 -> v2 work compare v1 -> v3.
 * Filename and digest are part of it, since a diff filed without readable blobs must still record
 * them and a retry rebuilds its work from the diff alone.
 *
 * @param providerId the blob provider id, {@code null} for a blob that is not managed
 * @param key the storage key in that provider, {@code null} for a blob that is not managed
 * @param length the length in bytes, negative when unknown
 * @since 2025.1
 */
public record FrozenBlob(String providerId, String key, String filename, String mimeType, String digest,
        long length) implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LogManager.getLogger(FrozenBlob.class);

    /**
     * Freezes the identity of a blob: provider id and key when it is a {@link ManagedBlob}, filename,
     * mime type, digest and length in any case.
     */
    public static FrozenBlob of(Blob blob) {
        String providerId = blob instanceof ManagedBlob managed ? managed.getProviderId() : null;
        String key = blob instanceof ManagedBlob managed ? managed.getKey() : null;
        return new FrozenBlob(providerId, key, blob.getFilename(), blob.getMimeType(), blob.getDigest(),
                blob.getLength());
    }

    /**
     * Re-reads the binary through its blob provider.
     * <p>
     * {@code provider.readBlob} is a keyed store lookup: it applies no document ACL. Only resolve an
     * identity the plugin froze itself, or read back from a <b>stored</b> {@code BlobDiff} whose
     * provenance was checked (see {@link DiffRequest#readFrom}).
     * <p>
     * Its warnings name the file and the provider, never the key nor the digest: the default key
     * strategy makes the key the digest, and either one identifies a binary to whoever reads the logs.
     *
     * @return the blob, or {@code null} when there is no key, no such provider, or the read failed
     */
    public Blob resolve() {
        if (key == null) {
            return null;
        }
        try {
            BlobProvider provider = Framework.getService(BlobManager.class).getBlobProvider(providerId);
            if (provider == null) {
                log.warn("No blob provider {} to resolve {}", providerId, filename);
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
            log.warn("Cannot resolve {} from provider {}", filename, providerId, e);
            return null;
        }
    }
}

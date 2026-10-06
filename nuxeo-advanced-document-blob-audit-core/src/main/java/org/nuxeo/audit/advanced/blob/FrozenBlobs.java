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

import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.blob.ManagedBlob;

/**
 * Storage identity of the two binaries compared by a diff. Persisted on the {@code BlobDiff}
 * document so that a diff in {@code error} can be replayed later ({@code BlobDiff.Retry}), even when
 * the binaries could not be read at the time.
 *
 * @since 2025.1
 */
public record FrozenBlobs(String oldProviderId, String oldKey, String oldMimeType, long oldLength,
        String newProviderId, String newKey, String newMimeType, long newLength) {

    public static final FrozenBlobs NONE = new FrozenBlobs(null, null, null, -1, null, null, null, -1);

    /** Derives the storage identity from resolved blobs, when they are managed. */
    public static FrozenBlobs of(Blob oldBlob, Blob newBlob) {
        return new FrozenBlobs(providerOf(oldBlob), keyOf(oldBlob), oldBlob == null ? null : oldBlob.getMimeType(),
                oldBlob == null ? -1 : oldBlob.getLength(), providerOf(newBlob), keyOf(newBlob),
                newBlob == null ? null : newBlob.getMimeType(), newBlob == null ? -1 : newBlob.getLength());
    }

    /** {@code true} when both sides can be re-read from their blob provider. */
    public boolean isReplayable() {
        return oldProviderId != null && oldKey != null && newProviderId != null && newKey != null;
    }

    protected static String providerOf(Blob blob) {
        return blob instanceof ManagedBlob managed ? managed.getProviderId() : null;
    }

    protected static String keyOf(Blob blob) {
        return blob instanceof ManagedBlob managed ? managed.getKey() : null;
    }
}

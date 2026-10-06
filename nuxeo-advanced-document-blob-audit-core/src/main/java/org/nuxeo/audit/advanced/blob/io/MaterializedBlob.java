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
package org.nuxeo.audit.advanced.blob.io;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CloseableFile;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;

/**
 * A blob guaranteed to be backed by a local file, for the duration of a try-with-resources block.
 * <p>
 * Diffing one version pair runs several extraction passes over the same two binaries - the text
 * extractor and, when {@code imageAnalysisLevel > 0}, the image inventory extractor. Each pass used
 * to call {@link Blob#getStream()}, so a pair of S3-backed blobs meant <b>four downloads for two
 * files</b>. Materialising each side once and handing the local blob to every pass brings that back
 * to one download per side.
 * <p>
 * <b>Ownership.</b> When the source blob is already file-backed, this is a pass-through: the very
 * same blob is returned and {@link #close()} does nothing. Nothing must ever delete a file that
 * belongs to the blob store. Otherwise the file is a temporary copy owned by the underlying
 * {@link CloseableFile}, which deletes it on close.
 * <p>
 * <b>Failure is not fatal.</b> If the copy fails, the source blob is returned as is: a diff read
 * the slow way is better than no diff at all.
 *
 * @since 2025.1
 */
public class MaterializedBlob implements AutoCloseable {

    private static final Logger log = LogManager.getLogger(MaterializedBlob.class);

    protected final Blob blob;

    protected final CloseableFile file;

    protected MaterializedBlob(Blob blob, CloseableFile file) {
        this.blob = blob;
        this.file = file;
    }

    /**
     * Materialises the given blob on the local filesystem, unless it already is.
     *
     * @param source the blob to materialise, may be {@code null}
     */
    public static MaterializedBlob of(Blob source) {
        if (source == null || source.getFile() != null) {
            // Already local, or nothing to do: never take ownership of a blob store file.
            return new MaterializedBlob(source, null);
        }
        CloseableFile closeable = null;
        try {
            closeable = source.getCloseableFile();
            // Metadata must be carried over: extractors are selected on the mime type, and
            // ConverterTextExtractor hands the blob to the ConversionService, which does the same.
            Blob local = new FileBlob(closeable.getFile(), source.getMimeType(), source.getEncoding(),
                    source.getFilename(), source.getDigest());
            return new MaterializedBlob(local, closeable);
        } catch (IOException | RuntimeException e) { // NOSONAR - falling back beats failing the diff
            // RuntimeException included on purpose: getCloseableFile() goes through
            // Framework.createTempFile, which needs a running Nuxeo runtime. The fallback is
            // functionally identical, only slower.
            //
            // The temp file must be released on the way out. If getCloseableFile() succeeded and it
            // is the FileBlob construction that threw, dropping the reference would leak the file
            // for good: CloseableFile has no finaliser and no GC fallback, close() is the only
            // thing that deletes it.
            closeQuietly(closeable, source);
            log.warn("Cannot materialise blob {} locally, reading it from the source instead",
                    source.getFilename(), e);
            return new MaterializedBlob(source, null);
        }
    }

    /** @since 2025.1 */
    protected static void closeQuietly(CloseableFile closeable, Blob source) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) { // NOSONAR - already on a failure path
            log.warn("Cannot delete the abandoned temporary copy of blob {}", source.getFilename(), e);
        }
    }

    /** The local blob, or {@code null} if the source was {@code null}. */
    public Blob blob() {
        return blob;
    }

    @Override
    public void close() {
        if (file == null) {
            return;
        }
        try {
            file.close();
        } catch (IOException e) { // NOSONAR - a leftover temp file must not fail the diff
            log.warn("Cannot delete the temporary copy of blob {}", blob == null ? null : blob.getFilename(), e);
        }
    }
}

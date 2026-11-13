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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.runtime.api.Framework;

/**
 * A blob guaranteed to be backed by a local file, for the duration of a try-with-resources block.
 * <p>
 * One local file per binary: the extractors that need random access (POI, PDFBox) read that file,
 * and nothing else. Diffing one version pair runs several extraction passes over the same two
 * binaries, so {@code BlobDiffComponent#diff} materialises each side once and every pass reads the
 * same local copy.
 * <p>
 * <b>Ownership.</b> When the source blob is already backed by a local file, this is a pass-through:
 * the very same blob is returned and {@link #close()} deletes nothing - a file that belongs to the
 * blob store must never be deleted. Otherwise the content is copied to a temporary file created with
 * {@link Framework#createTempFile}, which works with or without a running runtime; that copy is owned
 * by this instance and deleted by {@link #close()}.
 * <p>
 * <b>A failed copy raises.</b> There is no in-heap fallback: {@link #of(Blob)} throws a
 * {@link NuxeoException}, the diff is then filed {@code error}, and {@code BlobDiff.Retry} replays it.
 *
 * @since 2025.1
 */
public class MaterializedBlob implements AutoCloseable {

    private static final Logger log = LogManager.getLogger(MaterializedBlob.class);

    protected final Blob blob;

    /** The temporary copy owned by this instance, deleted on close, or {@code null} for a pass-through. */
    protected final File copy;

    protected MaterializedBlob(Blob blob, File copy) {
        this.blob = blob;
        this.copy = copy;
    }

    /**
     * Materialises the given blob on the local filesystem, unless it already is.
     *
     * @param source the blob to materialise, may be {@code null}
     * @throws NuxeoException if the blob cannot be copied to a local file
     */
    public static MaterializedBlob of(Blob source) {
        if (source == null || source.getFile() != null) {
            // Already local: never take ownership of a blob store file.
            return new MaterializedBlob(source, null);
        }
        File copy = null;
        try {
            copy = Framework.createTempFile("blobaudit-", ".bin");
            try (InputStream in = source.getStream()) {
                Files.copy(in, copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            // Metadata must be carried over: extractors are selected on the mime type, and
            // ConverterTextExtractor hands the blob to the ConversionService, which does the same.
            return new MaterializedBlob(new FileBlob(copy, source.getMimeType(), source.getEncoding(),
                    source.getFilename(), source.getDigest()), copy);
        } catch (IOException e) {
            delete(copy);
            throw new NuxeoException("Cannot copy blob " + source.getFilename() + " to a local file", e);
        } catch (RuntimeException e) {
            delete(copy);
            throw e;
        }
    }

    /** Returns the local blob, or {@code null} if the source was {@code null}. */
    public Blob blob() {
        return blob;
    }

    /**
     * Returns the local file of the blob, or {@code null} if the source was {@code null}.
     *
     * @since 2025.1
     */
    public File file() {
        return blob == null ? null : blob.getFile();
    }

    @Override
    public void close() {
        delete(copy);
    }

    protected static void delete(File file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) { // NOSONAR - a leftover temp file must not fail the diff
            log.warn("Cannot delete the temporary copy {}", file, e);
        }
    }
}

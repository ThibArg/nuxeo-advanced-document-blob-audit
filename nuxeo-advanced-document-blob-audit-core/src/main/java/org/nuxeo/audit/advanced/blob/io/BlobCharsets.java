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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.Blob;

/**
 * Resolves the charset to decode a blob with, never throwing.
 * <p>
 * {@code blob.getEncoding()} is whatever was stored with the binary, or whatever a converter
 * declared on its output: it can be {@code null}, blank, or a name no JVM knows. Every decode in
 * this plugin goes through here rather than through {@code Blob#getString()} or
 * {@code new InputStreamReader(in, String)}, both of which turn a bad declaration into an
 * {@code UnsupportedEncodingException} - and therefore turn a readable diff into a {@code error}
 * status for a reason that has nothing to do with the content.
 *
 * @since 2025.1
 */
public final class BlobCharsets {

    private static final Logger log = LogManager.getLogger(BlobCharsets.class);

    private BlobCharsets() {
        // utility class
    }

    /**
     * Returns the charset declared on the blob, or UTF-8 when there is none or it is unusable.
     *
     * @param blob the blob whose declared encoding is to be resolved, may be {@code null}
     */
    public static Charset of(Blob blob) {
        String encoding = blob == null ? null : blob.getEncoding();
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding.trim());
        } catch (IllegalArgumentException e) {
            // covers both IllegalCharsetNameException and UnsupportedCharsetException
            log.warn("Blob {} declares an unusable encoding '{}', falling back to UTF-8", blob.getFilename(),
                    encoding);
            return StandardCharsets.UTF_8;
        }
    }
}

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
package org.nuxeo.audit.advanced.blob.extractor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.nuxeo.audit.advanced.blob.BlobTextExtractor;
import org.nuxeo.audit.advanced.blob.ContentLine;
import org.nuxeo.audit.advanced.blob.DiffableContent;
import org.nuxeo.audit.advanced.blob.io.BlobCharsets;
import org.nuxeo.ecm.core.api.Blob;

/**
 * Line-based extractor for text formats: {@code .txt}, {@code .json}, {@code .xml}, {@code .csv},
 * {@code .md}, source files, etc. Positional content: the differ aligns it with Hirschberg.
 * <p>
 * The cheapest and most faithful of the three extractors, since nothing is lost in translation.
 *
 * @since 2025.1
 */
public class PlainTextExtractor implements BlobTextExtractor {

    protected boolean trimLines = true;

    protected boolean skipBlankLines = false;

    @Override
    public void init(Map<String, String> properties) {
        trimLines = !"false".equalsIgnoreCase(properties.get("trimLines"));
        skipBlankLines = "true".equalsIgnoreCase(properties.get("skipBlankLines"));
    }

    @Override
    public DiffableContent extract(Blob blob, int maxLines) throws IOException {
        List<ContentLine> lines = new ArrayList<>();
        boolean truncated = false;
        try (InputStream in = blob.getStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, charsetOf(blob)))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (lines.size() >= maxLines) {
                    truncated = true;
                    break;
                }
                if (first) {
                    line = stripByteOrderMark(line);
                    first = false;
                }
                String value = trimLines ? line.trim() : line;
                if (skipBlankLines && value.isEmpty()) {
                    continue;
                }
                lines.add(ContentLine.of(value));
            }
        }
        return DiffableContent.positional(lines, truncated);
    }

    /**
     * Charset declared on the blob, falling back to UTF-8.
     * <p>
     * Delegates to {@link BlobCharsets#of(Blob)}, which is shared with
     * {@link ConverterTextExtractor}: {@code blob.getEncoding()} can be empty, or a name no JVM
     * knows. Passing it straight to {@code InputStreamReader} threw
     * {@code UnsupportedEncodingException} (an {@code IOException}, so the extraction failed and
     * the whole diff was reported in error) or {@code IllegalCharsetNameException} (unchecked, so
     * it escaped the extractor entirely). A bad encoding declaration must degrade to a readable
     * diff, not lose the audit.
     * <p>
     * Kept as an instance method so a subclass can still impose its own charset policy.
     */
    protected Charset charsetOf(Blob blob) {
        return BlobCharsets.of(blob);
    }

    /**
     * Removes a leading BOM from the first line.
     * <p>
     * A UTF-8 BOM decodes to U+FEFF, which {@code readLine} keeps at the head of the first line.
     * Two files with the same text, one saved with a BOM and one without, would otherwise report
     * their first line as modified on every single version - and an editor adding or removing the
     * BOM silently would produce a phantom change in the audit.
     */
    protected String stripByteOrderMark(String line) {
        return !line.isEmpty() && line.charAt(0) == '\uFEFF' ? line.substring(1) : line;
    }
}

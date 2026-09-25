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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.nuxeo.audit.advanced.blob.BlobTextExtractor;
import org.nuxeo.audit.advanced.blob.ContentLine;
import org.nuxeo.audit.advanced.blob.DiffableContent;
import org.nuxeo.ecm.core.api.Blob;

/**
 * Line-based extractor for text formats: {@code .txt}, {@code .json}, {@code .xml}, {@code .csv},
 * {@code .md}, source files, etc. Positional content: the differ aligns it with Hirschberg.
 * <p>
 * The cheapest and most faithful of the three extractors, since nothing is lost in translation.
 *
 * @since 1.0
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
        String encoding = blob.getEncoding();
        try (InputStream in = blob.getStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in,
                        encoding == null ? StandardCharsets.UTF_8.name() : encoding))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() >= maxLines) {
                    truncated = true;
                    break;
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
}

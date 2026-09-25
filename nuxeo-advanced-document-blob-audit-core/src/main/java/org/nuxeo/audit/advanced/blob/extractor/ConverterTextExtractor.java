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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.nuxeo.audit.advanced.blob.BlobTextExtractor;
import org.nuxeo.audit.advanced.blob.ContentLine;
import org.nuxeo.audit.advanced.blob.DiffableContent;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.blobholder.BlobHolder;
import org.nuxeo.ecm.core.api.blobholder.SimpleBlobHolder;
import org.nuxeo.ecm.core.convert.api.ConversionService;
import org.nuxeo.runtime.api.Framework;

/**
 * Generic extractor delegating to the platform {@code ConversionService} ({@code any2text}, backed
 * by Tika). This is what covers Word, PDF, OpenDocument, RTF, PowerPoint... without a single line of
 * format-specific code: supporting one more format is one more {@code mimeType} element.
 * <p>
 * Output is positional and paragraph-oriented. Layout, styles and tables are lost - this is a
 * textual diff, not a Word "track changes" equivalent, and a scanned PDF yields nothing without OCR.
 *
 * @since 1.0
 */
public class ConverterTextExtractor implements BlobTextExtractor {

    public static final String ANY_2_TEXT = "any2text";

    protected String converter = ANY_2_TEXT;

    /** Minimum length for a line to be considered a comparable unit. */
    protected int minLineLength = 1;

    @Override
    public void init(Map<String, String> properties) {
        converter = properties.getOrDefault("converter", ANY_2_TEXT);
        String min = properties.get("minLineLength");
        if (min != null) {
            minLineLength = Integer.parseInt(min);
        }
    }

    @Override
    public DiffableContent extract(Blob blob, int maxLines) throws Exception {
        ConversionService conversionService = Framework.getService(ConversionService.class);
        BlobHolder result = conversionService.convert(converter, new SimpleBlobHolder(blob), null);
        Blob textBlob = result == null ? null : result.getBlob();
        if (textBlob == null) {
            return DiffableContent.positional(List.of(), false);
        }
        String text = textBlob.getString();
        List<ContentLine> lines = new ArrayList<>();
        boolean truncated = false;
        for (String raw : text.split("\\R")) {
            String value = raw.trim();
            if (value.length() < minLineLength) {
                continue;
            }
            if (lines.size() >= maxLines) {
                truncated = true;
                break;
            }
            lines.add(ContentLine.of(value));
        }
        return DiffableContent.positional(lines, truncated);
    }
}

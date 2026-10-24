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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.nuxeo.audit.advanced.blob.BlobTextExtractor;
import org.nuxeo.audit.advanced.blob.ContentLine;
import org.nuxeo.audit.advanced.blob.DiffableContent;
import org.nuxeo.audit.advanced.blob.io.BlobCharsets;
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
 * @since 2025.1
 */
public class ConverterTextExtractor implements BlobTextExtractor {

    public static final String ANY_2_TEXT = "any2text";

    /** The line breaks {@code \R} matches and {@code BufferedReader#readLine()} does not. */
    protected static final Pattern OTHER_LINE_BREAKS = Pattern.compile("[\\u000B\\u000C\\u0085\\u2028\\u2029]");

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

    /**
     * Reads the converter output line by line and stops at {@code maxLines}: a long document converts
     * to hundreds of megabytes of text, of which only the first lines are kept.
     * <p>
     * The charset is resolved by {@link BlobCharsets}, not by {@code textBlob.getString()}: the
     * converter is a contributed property, and a blank or unknown declared encoding would otherwise
     * file the whole diff as {@code error}.
     */
    @Override
    public DiffableContent extract(Blob blob, int maxLines) throws IOException {
        ConversionService conversionService = Framework.getService(ConversionService.class);
        BlobHolder result = conversionService.convert(converter, new SimpleBlobHolder(blob), null);
        Blob textBlob = result == null ? null : result.getBlob();
        if (textBlob == null) {
            return DiffableContent.positional(List.of(), false);
        }
        List<ContentLine> lines = new ArrayList<>();
        try (InputStream in = textBlob.getStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, BlobCharsets.of(textBlob)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                for (String raw : OTHER_LINE_BREAKS.split(line, -1)) {
                    String value = raw.trim();
                    if (value.length() < minLineLength) {
                        continue;
                    }
                    if (lines.size() >= maxLines) {
                        return DiffableContent.positional(lines, true);
                    }
                    lines.add(ContentLine.of(value));
                }
            }
        }
        return DiffableContent.positional(lines, false);
    }
}

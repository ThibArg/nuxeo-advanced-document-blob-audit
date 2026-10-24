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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.nuxeo.ecm.core.api.NuxeoException;

/**
 * One comparable unit of content extracted from a blob.
 * <p>
 * {@code key} is the stable identity of the unit when the format provides one (a spreadsheet cell
 * reference such as {@code Sheet1!B12}, a JSON pointer, an XML path...). When {@code key} is
 * {@code null} the unit is positional (a plain text line, a Word paragraph) and the differ falls
 * back to a sequence alignment.
 *
 * @since 2025.1
 */
public record ContentLine(String key, String value) {

    public static ContentLine of(String value) {
        return new ContentLine(null, value);
    }

    public static ContentLine of(String key, String value) {
        return new ContentLine(key, value);
    }

    /**
     * Same as {@link #of(String, String)}, with the value bounded by {@link #compact(String, int)}.
     *
     * @since 2025.1
     */
    public static ContentLine of(String key, String value, int maxValueLength) {
        return new ContentLine(key, compact(value, maxValueLength));
    }

    /**
     * Bounds a value without losing what tells it apart: past {@code maxValueLength}, it keeps its
     * beginning followed by its full length and the start of its SHA-256, so that two long values
     * still compare equal exactly when they are, and the result is still {@code maxValueLength}
     * characters at most - {@link TextDiffer} then renders it as is.
     *
     * @since 2025.1
     */
    public static String compact(String value, int maxValueLength) {
        if (value == null || value.length() <= maxValueLength) {
            return value;
        }
        String marker = "… [" + value.length() + " chars, sha256:" + digest(value) + "]";
        return value.substring(0, Math.max(0, maxValueLength - marker.length())) + marker;
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new NuxeoException("SHA-256 is not available on this JVM", e);
        }
    }

    public boolean isKeyed() {
        return key != null;
    }

    /** Human readable rendering used in the unified diff output. */
    public String render() {
        return key == null ? value : key + " = " + value;
    }
}

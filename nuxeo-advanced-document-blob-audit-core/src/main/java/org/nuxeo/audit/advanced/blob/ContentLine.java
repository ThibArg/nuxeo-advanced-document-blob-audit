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

/**
 * One comparable unit of content extracted from a blob.
 * <p>
 * {@code key} is the stable identity of the unit when the format provides one (a spreadsheet cell
 * reference such as {@code Sheet1!B12}, a JSON pointer, an XML path...). When {@code key} is
 * {@code null} the unit is positional (a plain text line, a Word paragraph) and the differ falls
 * back to a sequence alignment.
 *
 * @since 1.0
 */
public record ContentLine(String key, String value) {

    public static ContentLine of(String value) {
        return new ContentLine(null, value);
    }

    public static ContentLine of(String key, String value) {
        return new ContentLine(key, value);
    }

    public boolean isKeyed() {
        return key != null;
    }

    /** Human readable rendering used in the unified diff output. */
    public String render() {
        return key == null ? value : key + " = " + value;
    }
}

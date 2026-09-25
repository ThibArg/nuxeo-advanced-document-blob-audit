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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Unit tests for mime type matching in {@link ExtractorDescriptor}. No runtime needed.
 * <p>
 * Getting this wrong is silent: a mistyped mime type simply means no extractor is found and the
 * change is logged without a diff, with no error anywhere.
 *
 * @since 1.0
 */
public class TestExtractorSelection {

    protected ExtractorDescriptor descriptor(String... mimeTypes) {
        ExtractorDescriptor descriptor = new ExtractorDescriptor();
        descriptor.mimeTypes = List.of(mimeTypes);
        return descriptor;
    }

    @Test
    public void testExactMatch() {
        ExtractorDescriptor descriptor = descriptor("application/pdf");

        assertTrue(descriptor.accepts("application/pdf"));
        assertFalse(descriptor.accepts("application/json"));
    }

    @Test
    public void testMatchIsCaseInsensitive() {
        assertTrue(descriptor("application/pdf").accepts("APPLICATION/PDF"));
    }

    /** Browsers and Tika routinely append a charset; it must not defeat the match. */
    @Test
    public void testParametersAreStripped() {
        assertTrue(descriptor("text/plain").accepts("text/plain; charset=UTF-8"));
        assertTrue(descriptor("application/json").accepts("application/json;charset=utf-8"));
    }

    @Test
    public void testSubtypeWildcard() {
        ExtractorDescriptor descriptor = descriptor("text/*");

        assertTrue(descriptor.accepts("text/plain"));
        assertTrue(descriptor.accepts("text/csv"));
        assertTrue(descriptor.accepts("text/x-java-source"));
        assertFalse(descriptor.accepts("application/pdf"));
    }

    @Test
    public void testCatchAll() {
        ExtractorDescriptor descriptor = descriptor("*");

        assertTrue(descriptor.accepts("anything/at-all"));
        assertTrue("a catch-all must also swallow an unknown mime type", descriptor.accepts(null));
    }

    /** A null mime type must not match a specific extractor, only an explicit catch-all. */
    @Test
    public void testNullMimeTypeIsRejectedBySpecificExtractors() {
        assertFalse(descriptor("application/pdf").accepts(null));
        assertFalse(descriptor("text/*").accepts(null));
    }

    @Test
    public void testMultipleMimeTypes() {
        ExtractorDescriptor descriptor = descriptor("application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        assertTrue(descriptor.accepts("application/vnd.ms-excel"));
        assertTrue(descriptor.accepts("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        assertFalse(descriptor.accepts("application/msword"));
    }
}

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.nuxeo.audit.advanced.blob.extractor.ConverterTextExtractor;

/**
 * Unit tests for mime type matching and merging in {@link ExtractorDescriptor}. No runtime needed.
 * <p>
 * Getting this wrong is silent: a mistyped mime type simply means no extractor is found and the
 * change is logged without a diff, with no error anywhere.
 *
 * @since 2025.1
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

    /**
     * API-01 (code review 2026-10): re-declaring one property of an extractor must not lose the
     * rest of it. The default merge returned the override alone, with no class: the extractor
     * failed to instantiate, was skipped, and its formats became unsupported.
     */
    @Test
    public void testOverridingOnePropertyKeepsTheRestOfTheExtractor() {
        ExtractorDescriptor shipped = descriptor("application/pdf");
        shipped.name = "converter";
        shipped.klass = ConverterTextExtractor.class;
        shipped.order = 90;
        shipped.properties = new HashMap<>(Map.of("converter", "any2text", "minLineLength", "1"));

        ExtractorDescriptor override = new ExtractorDescriptor();
        override.name = "converter";
        override.properties = new HashMap<>(Map.of("minLineLength", "2"));

        ExtractorDescriptor merged = (ExtractorDescriptor) shipped.merge(override);

        assertEquals(ConverterTextExtractor.class, merged.getKlass());
        assertEquals(90, merged.getOrder());
        assertTrue(merged.isEnabled());
        assertEquals(List.of("application/pdf"), merged.getMimeTypes());
        assertEquals(Map.of("converter", "any2text", "minLineLength", "2"), merged.getProperties());
        assertEquals("merge must not mutate the earlier contribution", "1",
                shipped.getProperties().get("minLineLength"));
    }

    /** Disabling an extractor by name, the documented way to turn one off, still works. */
    @Test
    public void testDisablingAnExtractorKeepsItsDefinition() {
        ExtractorDescriptor shipped = descriptor("application/pdf");
        shipped.name = "converter";
        shipped.klass = ConverterTextExtractor.class;

        ExtractorDescriptor override = new ExtractorDescriptor();
        override.name = "converter";
        override.enabled = false;

        ExtractorDescriptor merged = (ExtractorDescriptor) shipped.merge(override);

        assertFalse(merged.isEnabled());
        assertEquals(ConverterTextExtractor.class, merged.getKlass());
    }
}

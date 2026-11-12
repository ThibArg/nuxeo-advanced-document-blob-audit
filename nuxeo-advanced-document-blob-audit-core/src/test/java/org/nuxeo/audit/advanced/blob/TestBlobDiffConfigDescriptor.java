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

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** Unit tests for the global configuration: image inventory switch, merge, defaults. */
public class TestBlobDiffConfigDescriptor {

    @Test
    public void testImageInventoryIsOffByDefault() {
        BlobDiffConfigDescriptor config = new BlobDiffConfigDescriptor();
        assertFalse(config.isImageInventory());
    }

    @Test
    public void testImageInventoryCanBeTurnedOn() {
        BlobDiffConfigDescriptor config = new BlobDiffConfigDescriptor();
        config.imageInventory = Boolean.TRUE;
        assertTrue(config.isImageInventory());
    }

    @Test
    public void testAuditorsGroupDefaultsToAdministrators() {
        BlobDiffConfigDescriptor config = new BlobDiffConfigDescriptor();
        assertEquals(BlobAuditConstants.DEFAULT_AUDITORS_GROUP, config.getAuditorsGroup());
        config.auditorsGroup = "  ";
        assertEquals(BlobAuditConstants.DEFAULT_AUDITORS_GROUP, config.getAuditorsGroup());
        config.auditorsGroup = " auditors ";
        assertEquals("auditors", config.getAuditorsGroup());
    }

    /**
     * API-01 (code review 2026-10): a later contribution only overrides what it sets.
     * <p>
     * {@code Descriptor#merge} returns its argument by default, so an override carrying nothing but
     * {@code <docTypes>} replaced the whole configuration: {@code enabled} fell back to
     * {@code false}, every bound to its Java default, and {@code xpaths} to an empty list - which
     * then meant "every blob xpath".
     */
    @Test
    public void testAPartialContributionOnlyOverridesWhatItSets() {
        BlobDiffConfigDescriptor shipped = new BlobDiffConfigDescriptor();
        shipped.enabled = true;
        shipped.maxBlobSize = 2048L;
        shipped.maxLines = 50;
        shipped.imageInventory = Boolean.TRUE;
        shipped.auditorsGroup = "auditors";
        shipped.xpaths = new ArrayList<>(List.of("file:content", "files:files/*/file"));

        BlobDiffConfigDescriptor override = new BlobDiffConfigDescriptor();
        override.maxLines = 70;
        override.docTypes = new ArrayList<>(List.of("Contract"));

        BlobDiffConfigDescriptor merged = (BlobDiffConfigDescriptor) shipped.merge(override);

        assertTrue("an omitted @enabled must not switch the feature off", merged.isEnabled());
        assertEquals(2048, merged.getMaxBlobSize());
        assertEquals(70, merged.getMaxLines());
        assertTrue(merged.isImageInventory());
        assertEquals("auditors", merged.getAuditorsGroup());
        assertEquals(List.of("Contract"), merged.getDocTypes());
        assertEquals("an omitted list keeps the earlier one", List.of("file:content", "files:files/*/file"),
                merged.getXPaths());
    }

    /** Merging must not take away the means to switch the feature off, nor touch either side. */
    @Test
    public void testAnExplicitValueStillWins() {
        BlobDiffConfigDescriptor shipped = new BlobDiffConfigDescriptor();
        shipped.enabled = true;
        shipped.maxLines = 50;
        BlobDiffConfigDescriptor override = new BlobDiffConfigDescriptor();
        override.enabled = false;

        BlobDiffConfigDescriptor merged = (BlobDiffConfigDescriptor) shipped.merge(override);

        assertFalse(merged.isEnabled());
        assertTrue("merge must not mutate the earlier contribution", shipped.isEnabled());
    }

    /**
     * With no xpath configured, only {@code file:content} is diffed, as the shipped contribution
     * says. An empty list used to mean "every blob xpath", so a contribution that left the element
     * out widened the scope instead of narrowing it.
     */
    @Test
    public void testNoXPathMeansFileContent() {
        BlobDiffConfigDescriptor config = new BlobDiffConfigDescriptor();

        assertEquals(List.of("file:content"), config.getXPaths());
        assertTrue(config.acceptsXPath("file:content"));
        assertFalse(config.acceptsXPath("files:files/0/file"));
    }

    /** Every blob xpath, or every document type, is now an explicit choice. */
    @Test
    public void testAStarMeansEveryXPathAndEveryDocType() {
        BlobDiffConfigDescriptor config = new BlobDiffConfigDescriptor();
        config.xpaths = new ArrayList<>(List.of("*"));
        config.docTypes = new ArrayList<>(List.of("*"));

        assertTrue(config.acceptsXPath("files:files/0/file"));
        assertTrue(config.acceptsDocType("Contract"));
    }
}

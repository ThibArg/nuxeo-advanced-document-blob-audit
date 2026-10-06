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

import org.junit.Test;

/** Unit tests for bounded image analysis configuration. */
public class TestBlobDiffConfigDescriptor {

    @Test
    public void testImageAnalysisIsDisabledByDefault() {
        BlobDiffConfigDescriptor config = new BlobDiffConfigDescriptor();
        assertEquals(0, config.getImageAnalysisLevel());
        assertEquals(0, config.normalizeImageAnalysisLevel());
    }

    @Test
    public void testSupportedImageAnalysisLevelIsPreserved() {
        BlobDiffConfigDescriptor config = new BlobDiffConfigDescriptor();
        config.imageAnalysisLevel = 1;
        assertEquals(1, config.normalizeImageAnalysisLevel());
        assertEquals(1, config.getImageAnalysisLevel());
    }

    @Test
    public void testNegativeImageAnalysisLevelIsClampedToZero() {
        BlobDiffConfigDescriptor config = new BlobDiffConfigDescriptor();
        config.imageAnalysisLevel = -4;
        assertEquals(0, config.normalizeImageAnalysisLevel());
    }

    @Test
    public void testFutureImageAnalysisLevelIsClampedToCurrentMaximum() {
        BlobDiffConfigDescriptor config = new BlobDiffConfigDescriptor();
        config.imageAnalysisLevel = 7;
        assertEquals(1, config.normalizeImageAnalysisLevel());
    }

    /**
     * Normalising must not write back.
     * <p>
     * The descriptor instance is shared by the whole runtime - {@code getDescriptor(XP_CONFIG, ...)}
     * hands out the registered one - and {@code normalizeImageAnalysisLevel()} is called both from
     * {@code BlobDiffComponent#start} and from {@code diffLocal}, that is concurrently from the
     * {@code blobDiff} worker threads. Assigning the clamped value back to a non-{@code volatile}
     * field was a data race on a registry object, and it silently turned the "configured value
     * differs from the normalized one" warning in {@code start()} into a one-shot: the second call
     * could no longer see the original contribution.
     */
    @Test
    public void testNormalizingDoesNotMutateTheSharedDescriptor() {
        BlobDiffConfigDescriptor config = new BlobDiffConfigDescriptor();
        config.imageAnalysisLevel = 7;

        assertEquals(1, config.normalizeImageAnalysisLevel());

        assertEquals("the contributed value must survive normalisation", 7, config.getImageAnalysisLevel());
        assertEquals("normalising must be repeatable", 1, config.normalizeImageAnalysisLevel());
        assertEquals(7, config.getImageAnalysisLevel());
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
}

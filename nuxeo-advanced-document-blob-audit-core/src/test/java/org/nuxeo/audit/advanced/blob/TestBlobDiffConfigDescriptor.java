/*
 * (C) Copyright 2026 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
        assertEquals(0, config.getImageAnalysisLevel());
    }

    @Test
    public void testFutureImageAnalysisLevelIsClampedToCurrentMaximum() {
        BlobDiffConfigDescriptor config = new BlobDiffConfigDescriptor();
        config.imageAnalysisLevel = 7;
        assertEquals(1, config.normalizeImageAnalysisLevel());
        assertEquals(1, config.getImageAnalysisLevel());
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

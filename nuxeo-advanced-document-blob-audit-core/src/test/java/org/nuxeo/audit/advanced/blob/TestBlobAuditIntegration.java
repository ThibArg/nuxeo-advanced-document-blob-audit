/*
 * (C) Copyright 2026 Nuxeo SA and others.
 * Licensed under the Apache License, Version 2.0.
 */
package org.nuxeo.audit.advanced.blob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.cells;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.textBlob;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.xlsx;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.audit.api.LogEntry;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.platform.query.api.PageProvider;
import org.nuxeo.ecm.platform.query.api.PageProviderService;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

/** End-to-end validation of version-triggered blob diffs. */
@RunWith(FeaturesRunner.class)
@Features(BlobAuditFeature.class)
public class TestBlobAuditIntegration {

    public static final String AUDIT_PP = "BLOBAUDIT_ENTRIES";
    public static final String DIFFS_PP = "BLOB_DIFFS_FOR_DOCUMENT";

    @Inject protected CoreSession session;
    @Inject protected PageProviderService pageProviderService;
    @Inject protected TransactionalFeature txFeature;

    protected DocumentModel createFileWith(Blob blob, String name) {
        DocumentModel doc = session.createDocumentModel("/", name, "File");
        doc.setPropertyValue("file:content", (Serializable) blob);
        doc = session.createDocument(doc);
        session.save();
        return doc;
    }

    protected DocumentModel replaceBlob(DocumentModel doc, Blob blob) {
        doc = session.getDocument(doc.getRef());
        doc.setPropertyValue("file:content", (Serializable) blob);
        doc = session.saveDocument(doc);
        session.save();
        return doc;
    }

    protected DocumentModel checkIn(DocumentModel live, VersioningOption option) {
        DocumentRef versionRef = session.checkIn(live.getRef(), option, "blob audit test");
        session.save();
        txFeature.nextTransaction(); // commits and waits for BlobDiffWork
        return session.getDocument(versionRef);
    }

    @SuppressWarnings("unchecked")
    protected List<LogEntry> auditEntries(String eventId, String docId) {
        Map<String, Serializable> props = new HashMap<>();
        props.put("coreSession", (Serializable) session);
        return (List<LogEntry>) (List<?>) pageProviderService.getPageProvider(AUDIT_PP, null, null, null, props,
                eventId, docId).getCurrentPage();
    }

    @SuppressWarnings("unchecked")
    protected List<DocumentModel> diffsFor(String docId) {
        Map<String, Serializable> props = new HashMap<>();
        props.put("coreSession", (Serializable) session);
        PageProvider<DocumentModel> pp = (PageProvider<DocumentModel>) (PageProvider<?>) pageProviderService
                .getPageProvider(DIFFS_PP, null, null, null, props, docId);
        return pp.getCurrentPage();
    }

    @Test
    public void testFirstVersionProducesNoDiff() throws Exception {
        DocumentModel doc = createFileWith(textBlob("v1", "text/plain", "notes.txt"), "first-version");
        checkIn(doc, VersioningOption.MAJOR);
        assertTrue(auditEntries(BlobAuditConstants.EVENT_BLOB_MODIFIED, doc.getId()).isEmpty());
        assertTrue(diffsFor(doc.getId()).isEmpty());
    }

    @Test
    public void testOrdinarySavesProduceNoDiffAndNextVersionProducesOne() throws Exception {
        DocumentModel doc = createFileWith(textBlob("v1", "text/plain", "notes.txt"), "many-saves");
        checkIn(doc, VersioningOption.MAJOR);
        doc = replaceBlob(doc, textBlob("v2", "text/plain", "notes.txt"));
        doc = replaceBlob(doc, textBlob("v3", "text/plain", "notes.txt"));
        doc = replaceBlob(doc, textBlob("v4", "text/plain", "notes.txt"));
        txFeature.nextTransaction();
        assertTrue(diffsFor(doc.getId()).isEmpty());

        DocumentModel currentVersion = checkIn(doc, VersioningOption.MINOR);
        List<DocumentModel> diffs = diffsFor(doc.getId());
        assertEquals(1, diffs.size());
        assertEquals("1.0", diffs.get(0).getPropertyValue(BlobAuditConstants.XP_PREVIOUS_VERSION_LABEL));
        assertEquals(currentVersion.getVersionLabel(),
                diffs.get(0).getPropertyValue(BlobAuditConstants.XP_NEW_VERSION_LABEL));
        assertEquals(doc.getId(), diffs.get(0).getPropertyValue(BlobAuditConstants.XP_SOURCE_ID));
    }

    @Test
    public void testModifyingASpreadsheetProducesVersionDiff() throws Exception {
        DocumentModel doc = createFileWith(xlsx("Sheet1", cells("A1", "Qty", "B1", "100"), "v1.xlsx"),
                "budget");
        checkIn(doc, VersioningOption.MAJOR);
        doc = replaceBlob(doc, xlsx("Sheet1", cells("A1", "Qty", "B1", "120"), "v2.xlsx"));
        checkIn(doc, VersioningOption.MINOR);

        List<DocumentModel> diffs = diffsFor(doc.getId());
        assertEquals(1, diffs.size());
        DocumentModel diff = diffs.get(0);
        assertEquals(Long.valueOf(1), diff.getPropertyValue(BlobAuditConstants.XP_CHANGED));
        assertNotNull(diff.getPropertyValue(BlobAuditConstants.XP_DIFF_BLOB));
        assertEquals(1, auditEntries(BlobAuditConstants.EVENT_BLOB_MODIFIED, doc.getId()).size());
    }

    @Test
    public void testIdenticalBlobInSuccessiveVersionsProducesNoDiff() throws Exception {
        Blob content = textBlob("stable", "text/plain", "notes.txt");
        DocumentModel doc = createFileWith(content, "same-content");
        checkIn(doc, VersioningOption.MAJOR);
        doc = session.getDocument(doc.getRef());
        doc.setPropertyValue("dc:description", "metadata only");
        session.saveDocument(doc);
        session.save();
        checkIn(doc, VersioningOption.MINOR);
        assertTrue(diffsFor(doc.getId()).isEmpty());
    }

    @Test
    public void testVersionMetadataIdentifiesTheExactPair() throws Exception {
        DocumentModel doc = createFileWith(textBlob("alpha", "text/plain", "notes.txt"), "version-metadata");
        DocumentModel previous = checkIn(doc, VersioningOption.MAJOR);
        doc = replaceBlob(doc, textBlob("beta", "text/plain", "notes.txt"));
        DocumentModel current = checkIn(doc, VersioningOption.MINOR);

        DocumentModel diff = diffsFor(doc.getId()).get(0);
        assertEquals(previous.getId(), diff.getPropertyValue(BlobAuditConstants.XP_PREVIOUS_VERSION_ID));
        assertEquals(current.getId(), diff.getPropertyValue(BlobAuditConstants.XP_NEW_VERSION_ID));
        assertEquals(current.getVersionSeriesId(),
                diff.getPropertyValue(BlobAuditConstants.XP_VERSION_SERIES_ID));
    }
}

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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.cells;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.textBlob;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.xlsx;

import java.io.InputStream;
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
import org.nuxeo.ecm.platform.query.api.PageProvider;
import org.nuxeo.ecm.platform.query.api.PageProviderService;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

/**
 * End-to-end tests: modifying a binary must produce a lightweight audit entry synchronously and a
 * {@code BlobDiff} document asynchronously, the two being tied by a correlation id.
 *
 * @since 1.0
 */
@RunWith(FeaturesRunner.class)
@Features(BlobAuditFeature.class)
public class TestBlobAuditIntegration {

    public static final String AUDIT_PP = "BLOBAUDIT_ENTRIES";

    public static final String DIFFS_PP = "BLOB_DIFFS_FOR_DOCUMENT";

    @Inject
    protected CoreSession session;

    @Inject
    protected PageProviderService pageProviderService;

    @Inject
    protected TransactionalFeature txFeature;

    /* ----------------------------------------------------------------- helpers */

    protected DocumentModel createFileWith(Blob blob, String name) {
        DocumentModel doc = session.createDocumentModel("/", name, "File");
        doc.setPropertyValue("file:content", (Serializable) blob);
        doc = session.createDocument(doc);
        session.save();
        txFeature.nextTransaction();
        return doc;
    }

    protected DocumentModel replaceBlob(DocumentModel doc, Blob blob) {
        doc.setPropertyValue("file:content", (Serializable) blob);
        doc = session.saveDocument(doc);
        session.save();
        // commits and, crucially, waits for the asynchronous BlobDiffWork to complete
        txFeature.nextTransaction();
        return doc;
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

    /* -------------------------------------------------------------------- tests */

    @Test
    public void testModifyingASpreadsheetProducesAuditEntryAndDiffDocument() throws Exception {
        DocumentModel doc = createFileWith(xlsx("Sheet1", cells("A1", "Qty", "B1", "100"), "v1.xlsx"), "budget");

        replaceBlob(doc, xlsx("Sheet1", cells("A1", "Qty", "B1", "120"), "v2.xlsx"));

        // 1. synchronous audit entry: no business content, just a pointer
        List<LogEntry> entries = auditEntries(BlobAuditConstants.EVENT_BLOB_MODIFIED, doc.getId());
        assertEquals(1, entries.size());
        LogEntry entry = entries.get(0);
        assertEquals("file:content", entry.getExtended().get(BlobAuditConstants.EXT_XPATH));
        assertEquals("v1.xlsx", entry.getExtended().get(BlobAuditConstants.EXT_OLD_FILENAME));
        assertEquals("v2.xlsx", entry.getExtended().get(BlobAuditConstants.EXT_NEW_FILENAME));
        String correlationId = (String) entry.getExtended().get(BlobAuditConstants.EXT_CORRELATION_ID);
        assertNotNull(correlationId);

        // the audit log must never carry the content itself
        assertFalse("no business content may leak into the audit entry",
                String.valueOf(entry.getExtended()).contains("120"));

        // 2. asynchronous BlobDiff document, tied by the correlation id
        List<DocumentModel> diffs = diffsFor(doc.getId());
        assertEquals(1, diffs.size());
        DocumentModel diffDoc = diffs.get(0);
        assertEquals(correlationId, diffDoc.getPropertyValue(BlobAuditConstants.XP_CORRELATION_ID));
        assertEquals(BlobAuditConstants.STATUS_OK, diffDoc.getPropertyValue(BlobAuditConstants.XP_STATUS));
        assertEquals("1 modification", diffDoc.getPropertyValue(BlobAuditConstants.XP_SUMMARY));

        Blob diffBlob = (Blob) diffDoc.getPropertyValue(BlobAuditConstants.XP_DIFF_BLOB);
        assertNotNull(diffBlob);
        assertTrue(diffBlob.getString().contains("~ Sheet1!B1 : 100 -> 120"));
    }

    /**
     * The digest short-circuit: re-uploading the exact same bytes under a different name is a
     * rename, not a content change. No work must be scheduled and no audit entry written by this
     * plugin - renames are the scalar-field audit plugin's business, not ours.
     */
    @Test
    public void testRenamingOnlyDoesNotTriggerADiff() throws Exception {
        Blob v1 = xlsx("Sheet1", cells("A1", "stable"), "original.xlsx");
        byte[] bytes;
        try (InputStream in = v1.getStream()) {
            bytes = in.readAllBytes();
        }
        DocumentModel doc = createFileWith(v1, "renamed-only");

        replaceBlob(doc, BlobAuditTestHelper.blob(bytes, BlobAuditTestHelper.XLSX_MIME, "renamed.xlsx"));

        assertTrue("no content diff must be scheduled for a pure rename",
                auditEntries(BlobAuditConstants.EVENT_BLOB_MODIFIED, doc.getId()).isEmpty());
        assertTrue(diffsFor(doc.getId()).isEmpty());
    }

    /** Successive edits accumulate one BlobDiff each, which is the history the feature is for. */
    @Test
    public void testSuccessiveEditsAccumulateDiffs() throws Exception {
        DocumentModel doc = createFileWith(textBlob("v1", "text/plain", "notes.txt"), "notes");

        doc = replaceBlob(doc, textBlob("v2", "text/plain", "notes.txt"));
        doc = replaceBlob(doc, textBlob("v3", "text/plain", "notes.txt"));

        List<DocumentModel> diffs = diffsFor(doc.getId());
        assertEquals(2, diffs.size());
        // the page provider orders by date descending: most recent first
        assertTrue(((Blob) diffs.get(0).getPropertyValue(BlobAuditConstants.XP_DIFF_BLOB)).getString()
                                                                                          .contains("v3"));
    }

    /** Each diff records its own author: this is the per-user history of the binary. */
    @Test
    public void testDiffRecordsTheModifyingUser() throws Exception {
        DocumentModel doc = createFileWith(textBlob("v1", "text/plain", "notes.txt"), "authored");

        replaceBlob(doc, textBlob("v2", "text/plain", "notes.txt"));

        DocumentModel diffDoc = diffsFor(doc.getId()).get(0);
        assertEquals(session.getPrincipal().getName(), diffDoc.getPropertyValue(BlobAuditConstants.XP_USER));
        assertNotNull(diffDoc.getPropertyValue(BlobAuditConstants.XP_DATE));
    }

    /** Metadata-only edits must not go anywhere near the diff machinery. */
    @Test
    public void testModifyingOnlyMetadataTriggersNoDiff() throws Exception {
        DocumentModel doc = createFileWith(textBlob("v1", "text/plain", "notes.txt"), "metadata-only");

        doc.setPropertyValue("dc:description", "updated description");
        session.saveDocument(doc);
        session.save();
        txFeature.nextTransaction();

        assertTrue(diffsFor(doc.getId()).isEmpty());
    }

    /** The diff document must not pollute the source document in any way. */
    @Test
    public void testSourceDocumentIsLeftUntouched() throws Exception {
        DocumentModel doc = createFileWith(xlsx("Sheet1", cells("A1", "100"), "v1.xlsx"), "untouched");
        String versionLabel = doc.getVersionLabel();

        doc = replaceBlob(doc, xlsx("Sheet1", cells("A1", "120"), "v2.xlsx"));

        DocumentModel reloaded = session.getDocument(doc.getRef());
        assertFalse("no diff facet may be added to the source", reloaded.hasFacet("BlobChangedDiff"));
        assertEquals("diffing must not create a version", versionLabel, reloaded.getVersionLabel());
    }

    /** Diffs survive their source: an audit trail that disappears with its subject is worthless. */
    @Test
    public void testDiffsSurviveSourceDeletion() throws Exception {
        DocumentModel doc = createFileWith(textBlob("v1", "text/plain", "notes.txt"), "doomed");
        doc = replaceBlob(doc, textBlob("v2", "text/plain", "notes.txt"));
        String docId = doc.getId();
        assertEquals(1, diffsFor(docId).size());

        session.removeDocument(doc.getRef());
        session.save();
        txFeature.nextTransaction();

        assertEquals("diffs must outlive the source document", 1, diffsFor(docId).size());
    }

    /** An unsupported binary produces no diff at all. */
    @Test
    public void testUnsupportedTypeProducesNoDiff() throws Exception {
        DocumentModel doc = createFileWith(
                BlobAuditTestHelper.blob("v1".getBytes(), "application/x-unknown-binary", "f.bin"), "unsupported");

        replaceBlob(doc, BlobAuditTestHelper.blob("v2".getBytes(), "application/x-unknown-binary", "f.bin"));

        assertTrue("no diff is scheduled for an unsupported mime type", diffsFor(doc.getId()).isEmpty());
    }
}

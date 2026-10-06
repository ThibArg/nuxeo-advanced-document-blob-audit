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
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.textBlob;
import static org.nuxeo.audit.advanced.blob.BlobModificationListener.DISABLE_BLOB_DIFF_LISTENER;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.audit.api.LogEntry;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.api.versioning.VersioningService;
import org.nuxeo.ecm.platform.query.api.PageProviderService;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

/**
 * Dynamic disabling of {@link BlobModificationListener}, per operation and per thread.
 *
 * @since 2025.1
 */
@RunWith(FeaturesRunner.class)
@Features(BlobAuditFeature.class)
public class TestBlobDiffListenerDisabling {

    @Inject
    protected CoreSession session;

    @Inject
    protected PageProviderService pageProviderService;

    @Inject
    protected TransactionalFeature txFeature;

    protected DocumentModel createVersionedFile(String name, String content) throws Exception {
        DocumentModel doc = session.createDocumentModel("/", name, "File");
        doc.setPropertyValue("file:content", (Serializable) textBlob(content, "text/plain", "notes.txt"));
        doc = session.createDocument(doc);
        session.save();
        session.checkIn(doc.getRef(), VersioningOption.MAJOR, "initial");
        session.save();
        txFeature.nextTransaction();
        return session.getDocument(doc.getRef());
    }

    protected DocumentModel setBlob(DocumentModel doc, String content) throws Exception {
        doc = session.getDocument(doc.getRef());
        doc.setPropertyValue("file:content", (Serializable) textBlob(content, "text/plain", "notes.txt"));
        return doc;
    }

    @SuppressWarnings("unchecked")
    protected List<DocumentModel> diffsFor(String docId) {
        Map<String, Serializable> props = new HashMap<>();
        props.put("coreSession", (Serializable) session);
        return (List<DocumentModel>) (List<?>) pageProviderService
                .getPageProvider("BLOB_DIFFS_FOR_DOCUMENT", null, null, null, props, docId).getCurrentPage();
    }

    @SuppressWarnings("unchecked")
    protected List<LogEntry> auditEntries(String docId) {
        Map<String, Serializable> props = new HashMap<>();
        props.put("coreSession", (Serializable) session);
        return (List<LogEntry>) (List<?>) pageProviderService.getPageProvider("BLOBAUDIT_ENTRIES", null, null, null,
                props, BlobAuditConstants.EVENT_BLOB_MODIFIED, docId).getCurrentPage();
    }

    /* ------------------------------------------------------- baseline sanity */

    @Test
    public void testWithoutTheFlagADiffIsProduced() throws Exception {
        DocumentModel doc = createVersionedFile("control", "v1");
        doc = setBlob(doc, "v2");
        doc.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.MINOR);
        session.saveDocument(doc);
        session.save();
        txFeature.nextTransaction();

        assertEquals(1, diffsFor(doc.getId()).size());
        assertEquals(1, auditEntries(doc.getId()).size());
    }

    /* ------------------------------------------------- per-operation context data */

    /**
     * saveDocument copies the document context data into the event options, which notifyCheckedInVersion
     * forwards to the documentCreated event fired on the new version.
     */
    @Test
    public void testContextDataFlagDisablesTheListenerOnSaveDocument() throws Exception {
        DocumentModel doc = createVersionedFile("muted-save", "v1");
        doc = setBlob(doc, "v2");
        doc.putContextData(DISABLE_BLOB_DIFF_LISTENER, Boolean.TRUE);
        doc.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.MINOR);
        DocumentModel saved = session.saveDocument(doc);
        session.save();
        txFeature.nextTransaction();

        assertTrue("No BlobDiff must be created", diffsFor(doc.getId()).isEmpty());
        assertTrue("No audit entry must be written", auditEntries(doc.getId()).isEmpty());
        // The version itself is created normally: only the audit side is muted
        assertEquals(2, session.getVersions(saved.getRef()).size());
    }

    /** The flag is scoped to one operation and must not leak onto the next save. */
    @Test
    public void testTheFlagDoesNotLeakToTheNextVersion() throws Exception {
        DocumentModel doc = createVersionedFile("no-leak", "v1");
        doc = setBlob(doc, "v2");
        doc.putContextData(DISABLE_BLOB_DIFF_LISTENER, Boolean.TRUE);
        doc.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.MINOR);
        session.saveDocument(doc);
        session.save();
        txFeature.nextTransaction();
        assertTrue(diffsFor(doc.getId()).isEmpty());

        doc = setBlob(doc, "v3");
        doc.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.MINOR);
        session.saveDocument(doc);
        session.save();
        txFeature.nextTransaction();

        assertEquals("The following version must be audited again", 1, diffsFor(doc.getId()).size());
    }

    /* ------------------------------------------------------------ thread scope */

    /**
     * session.checkIn builds a fresh, empty option map, so the context data flag cannot reach the
     * event. This is precisely the gap runDisabled closes.
     */
    @Test
    public void testRunDisabledCoversExplicitCheckIn() throws Exception {
        DocumentModel doc = createVersionedFile("muted-checkin", "v1");
        DocumentModel updated = setBlob(doc, "v2");
        session.saveDocument(updated);
        session.save();

        BlobModificationListener.runDisabled(
                () -> session.checkIn(doc.getRef(), VersioningOption.MINOR, "muted"));
        session.save();
        txFeature.nextTransaction();

        assertTrue(diffsFor(doc.getId()).isEmpty());
        assertTrue(auditEntries(doc.getId()).isEmpty());
    }

    @Test
    public void testRunDisabledReturnsTheSuppliedValue() throws Exception {
        DocumentModel doc = createVersionedFile("supplier", "v1");
        DocumentModel updated = setBlob(doc, "v2");
        session.saveDocument(updated);
        session.save();

        DocumentRef ref = BlobModificationListener.runDisabled(
                () -> session.checkIn(doc.getRef(), VersioningOption.MINOR, "muted"));
        session.save();
        txFeature.nextTransaction();

        assertTrue(session.exists(ref));
        assertTrue(diffsFor(doc.getId()).isEmpty());
    }

    /** The state must be restored even when the body throws, and nesting must be safe. */
    @Test
    public void testRunDisabledRestoresTheStateOnException() {
        assertFalse(BlobModificationListener.isDisabledForThread());
        try {
            BlobModificationListener.runDisabled(() -> {
                assertTrue(BlobModificationListener.isDisabledForThread());
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException e) {
            // expected
        }
        assertFalse("A failing body must not leave the listener disabled for the whole thread",
                BlobModificationListener.isDisabledForThread());
    }

    @Test
    public void testRunDisabledNestsSafely() {
        BlobModificationListener.runDisabled(() -> {
            assertTrue(BlobModificationListener.isDisabledForThread());
            BlobModificationListener.runDisabled(
                    () -> assertTrue(BlobModificationListener.isDisabledForThread()));
            assertTrue("The inner block must not re-enable the listener",
                    BlobModificationListener.isDisabledForThread());
        });
        assertFalse(BlobModificationListener.isDisabledForThread());
    }

    /** Once the thread-scoped block is over, auditing resumes. */
    @Test
    public void testAuditingResumesAfterRunDisabled() throws Exception {
        DocumentModel doc = createVersionedFile("resume", "v1");
        DocumentModel updated = setBlob(doc, "v2");
        session.saveDocument(updated);
        session.save();
        BlobModificationListener.runDisabled(
                () -> session.checkIn(doc.getRef(), VersioningOption.MINOR, "muted"));
        session.save();
        txFeature.nextTransaction();
        assertTrue(diffsFor(doc.getId()).isEmpty());

        DocumentModel again = setBlob(session.getDocument(doc.getRef()), "v3");
        session.saveDocument(again);
        session.save();
        session.checkIn(doc.getRef(), VersioningOption.MINOR, "audited");
        session.save();
        txFeature.nextTransaction();

        assertEquals(1, diffsFor(doc.getId()).size());
    }
}

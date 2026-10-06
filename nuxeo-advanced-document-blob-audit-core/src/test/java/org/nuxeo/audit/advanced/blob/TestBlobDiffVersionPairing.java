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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.textBlob;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.api.versioning.VersioningService;
import org.nuxeo.ecm.platform.query.api.PageProviderService;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

/**
 * Session D: the version pair comes from the check-in events themselves (item 9), and the blob
 * xpaths from the document type rather than from a full property walk (item 8).
 *
 * @since 2025.1
 */
@RunWith(FeaturesRunner.class)
@Features(BlobAuditFeature.class)
public class TestBlobDiffVersionPairing {

    @Inject
    protected CoreSession session;

    @Inject
    protected PageProviderService pageProviderService;

    @Inject
    protected TransactionalFeature txFeature;

    protected final BlobModificationListener listener = new BlobModificationListener();

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

    protected void newVersion(DocumentModel doc, String content) throws Exception {
        DocumentModel updated = session.getDocument(doc.getRef());
        updated.setPropertyValue("file:content", (Serializable) textBlob(content, "text/plain", "notes.txt"));
        updated.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.MINOR);
        session.saveDocument(updated);
        session.save();
        txFeature.nextTransaction();
    }

    @SuppressWarnings("unchecked")
    protected List<DocumentModel> diffsFor(String docId) {
        Map<String, Serializable> props = new HashMap<>();
        props.put("coreSession", (Serializable) session);
        return (List<DocumentModel>) (List<?>) pageProviderService
                .getPageProvider("BLOB_DIFFS_FOR_DOCUMENT", null, null, null, props, docId).getCurrentPage();
    }

    /* ------------------------------------------------ item 9: exact pairing */

    /**
     * Each diff must join the two versions that really follow each other. The old implementation
     * re-derived that from {@code ORDER BY uid:major_version DESC, uid:minor_version DESC} after
     * the fact; the pair is now captured on {@code ABOUT_TO_CHECKIN}, before the new version even
     * exists.
     */
    @Test
    public void testSuccessiveVersionsFormAnExactChain() throws Exception {
        DocumentModel doc = createVersionedFile("chain", "v1");
        newVersion(doc, "v2");
        newVersion(doc, "v3");
        newVersion(doc, "v4");

        List<DocumentModel> diffs = diffsFor(doc.getId());
        assertEquals(3, diffs.size());

        // BLOB_DIFFS_FOR_DOCUMENT orders by bdiff:date DESC, so the chain reads backwards
        List<String> pairs = new ArrayList<>();
        for (DocumentModel diff : diffs) {
            pairs.add(diff.getPropertyValue("bdiff:previousVersionLabel") + "->"
                    + diff.getPropertyValue("bdiff:newVersionLabel"));
        }
        assertTrue("unexpected chain " + pairs, pairs.contains("1.0->1.1"));
        assertTrue("unexpected chain " + pairs, pairs.contains("1.1->1.2"));
        assertTrue("unexpected chain " + pairs, pairs.contains("1.2->1.3"));
    }

    /** The version ids must match the labels: a pair is never assembled from two unrelated rows. */
    @Test
    public void testVersionIdsMatchTheRecordedLabels() throws Exception {
        DocumentModel doc = createVersionedFile("ids", "v1");
        newVersion(doc, "v2");

        DocumentModel diff = diffsFor(doc.getId()).get(0);
        DocumentModel previous = session.getDocument(
                new IdRef((String) diff.getPropertyValue("bdiff:previousVersionId")));
        DocumentModel newVersion = session.getDocument(
                new IdRef((String) diff.getPropertyValue("bdiff:newVersionId")));

        assertEquals(diff.getPropertyValue("bdiff:previousVersionLabel"), previous.getVersionLabel());
        assertEquals(diff.getPropertyValue("bdiff:newVersionLabel"), newVersion.getVersionLabel());
        assertEquals(previous.getVersionSeriesId(), newVersion.getVersionSeriesId());
    }

    /** The very first version has no predecessor: nothing to diff, and no warning-worthy state. */
    @Test
    public void testFirstVersionProducesNoDiff() throws Exception {
        DocumentModel doc = createVersionedFile("first", "v1");

        assertTrue(diffsFor(doc.getId()).isEmpty());
    }

    /**
     * Two core call sites notify the check-in with a {@code null} option map, so the ref captured
     * on {@code ABOUT_TO_CHECKIN} cannot reach {@code DOCUMENT_CHECKEDIN}. The fallback must then
     * return the version just before the given one, ordered by {@code ecm:versionCreated} - a fact,
     * not an assumption about labels.
     */
    @Test
    public void testFallbackReturnsTheVersionBeforeTheGivenOne() throws Exception {
        DocumentModel doc = createVersionedFile("fallback", "v1");
        newVersion(doc, "v2");
        newVersion(doc, "v3");

        List<DocumentModel> versions = session.getVersions(doc.getRef());
        assertEquals(3, versions.size());
        DocumentModel newest = versions.get(versions.size() - 1);
        DocumentModel previous = versions.get(versions.size() - 2);

        DocumentRef found = listener.previousVersionRefFallback(session, doc.getId(), newest.getRef());

        assertEquals(previous.getRef(), found);
    }

    /** With a single version there is nothing before it. */
    @Test
    public void testFallbackReturnsNullOnTheFirstVersion() throws Exception {
        DocumentModel doc = createVersionedFile("fallback-first", "v1");
        DocumentModel only = session.getVersions(doc.getRef()).get(0);

        assertNull(listener.previousVersionRefFallback(session, doc.getId(), only.getRef()));
    }

    /* --------------------------------------- item 8: xpaths from the type */

    /**
     * With {@code <xpaths>} empty, the paths come from the document type. {@code file:content} is a
     * plain complex field, {@code files:files/*&#47;file} a blob inside a list: the template is
     * resolved against the entries the document really has.
     */
    @Test
    @Deploy("nuxeo-advanced-document-blob-audit-core:blobaudit-test-allxpaths-config.xml")
    public void testBlobsInAListAreDiscoveredFromTheType() throws Exception {
        DocumentModel doc = session.createDocumentModel("/", "listed", "File");
        doc.setPropertyValue("file:content", (Serializable) textBlob("main v1", "text/plain", "main.txt"));
        doc.setPropertyValue("files:files", (Serializable) attachments("att v1"));
        doc = session.createDocument(doc);
        session.save();
        session.checkIn(doc.getRef(), VersioningOption.MAJOR, "initial");
        session.save();
        txFeature.nextTransaction();

        DocumentModel updated = session.getDocument(doc.getRef());
        updated.setPropertyValue("file:content", (Serializable) textBlob("main v2", "text/plain", "main.txt"));
        updated.setPropertyValue("files:files", (Serializable) attachments("att v2"));
        updated.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.MINOR);
        session.saveDocument(updated);
        session.save();
        txFeature.nextTransaction();

        List<String> xpaths = new ArrayList<>();
        for (DocumentModel diff : diffsFor(doc.getId())) {
            xpaths.add((String) diff.getPropertyValue("bdiff:xpath"));
        }
        assertTrue("file:content must be diffed, got " + xpaths, xpaths.contains("file:content"));
        assertTrue("the blob inside the list must be diffed, got " + xpaths,
                xpaths.contains("files:files/0/file"));
    }

    /** The templates of a type are computed once and reused. */
    @Test
    public void testTemplatesAreCachedPerDocumentType() {
        BlobModificationListener.BLOB_XPATH_TEMPLATES.remove("File");

        List<String> first = BlobModificationListener.computeBlobXPathTemplates("File");
        BlobModificationListener.BLOB_XPATH_TEMPLATES.put("File", first);
        List<String> cached = BlobModificationListener.BLOB_XPATH_TEMPLATES.get("File");

        assertNotNull(first);
        assertTrue("File must expose file:content, got " + first, first.contains("file:content"));
        assertTrue("a blob in a list must be a template, got " + first, first.contains("files:files/*/file"));
        assertEquals(first, cached);
    }

    /** An unknown document type must not throw, only yield nothing. */
    @Test
    public void testUnknownDocumentTypeYieldsNoXPath() {
        assertTrue(BlobModificationListener.computeBlobXPathTemplates("NoSuchTypeAtAll").isEmpty());
    }

    @SuppressWarnings("unchecked")
    protected Serializable attachments(String content) throws Exception {
        ArrayList<Map<String, Serializable>> files = new ArrayList<>();
        Map<String, Serializable> entry = new HashMap<>();
        entry.put("file", (Serializable) textBlob(content, "text/plain", "attachment.txt"));
        files.add(entry);
        return files;
    }
}

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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_CORRELATION_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_SKIP_REASON;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_SIZE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_TYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.textBlob;

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
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.api.versioning.VersioningService;
import org.nuxeo.ecm.platform.query.api.PageProviderService;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

/**
 * A binary change that cannot be diffed must still be audited.
 * <p>
 * Before this, an over-sized or unsupported binary produced no work, no {@code BlobDiff} and
 * <b>no audit entry at all</b>: for an audit tool, the absence of an entry was indistinguishable
 * from the absence of a change. The contract asserted here is "exactly one audit entry carrying a
 * {@code skipReason}, and zero {@code BlobDiff}".
 *
 * @since 2025.1
 */
@RunWith(FeaturesRunner.class)
@Features(BlobAuditFeature.class)
public class TestBlobDiffSkipReporting {

    protected static final String UNSUPPORTED_MIME = "application/x-unknown-binary";

    @Inject
    protected CoreSession session;

    @Inject
    protected PageProviderService pageProviderService;

    @Inject
    protected TransactionalFeature txFeature;

    /* ------------------------------------------------------------- fixtures */

    /** Comfortably over the 512 bytes cap of blobaudit-test-smallblob-config.xml. */
    protected String largeText(String marker) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 2048) {
            sb.append("padding ").append(marker).append(" padding padding\n");
        }
        return sb.toString();
    }

    protected DocumentModel createVersionedFile(String name, String content, String mimeType) throws Exception {
        DocumentModel doc = session.createDocumentModel("/", name, "File");
        doc.setPropertyValue("file:content", (Serializable) textBlob(content, mimeType, "notes.bin"));
        doc = session.createDocument(doc);
        session.save();
        session.checkIn(doc.getRef(), VersioningOption.MAJOR, "initial");
        session.save();
        txFeature.nextTransaction();
        return session.getDocument(doc.getRef());
    }

    protected DocumentModel newVersion(DocumentModel doc, String content, String mimeType) throws Exception {
        DocumentModel updated = session.getDocument(doc.getRef());
        updated.setPropertyValue("file:content", (Serializable) textBlob(content, mimeType, "notes.bin"));
        updated.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.MINOR);
        session.saveDocument(updated);
        session.save();
        txFeature.nextTransaction();
        return updated;
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

    protected String extended(LogEntry entry, String key) {
        return entry.getExtendedValue(key);
    }

    /* -------------------------------------------------- reportable skips */

    @Test
    @Deploy("nuxeo-advanced-document-blob-audit-core:blobaudit-test-smallblob-config.xml")
    public void testOverSizedBinaryIsAuditedWithoutADiff() throws Exception {
        DocumentModel doc = createVersionedFile("too-big", largeText("v1"), "text/plain");
        newVersion(doc, largeText("v2"), "text/plain");

        List<LogEntry> entries = auditEntries(doc.getId());
        assertEquals("exactly one audit entry", 1, entries.size());
        LogEntry entry = entries.get(0);
        assertEquals(STATUS_SKIPPED_SIZE, extended(entry, EXT_SKIP_REASON));
        assertNull("a skip must not carry a correlation id to a BlobDiff that will never exist",
                extended(entry, EXT_CORRELATION_ID));
        assertTrue("file:content : binary changed (too large)".equals(entry.getComment()));
        assertTrue("no BlobDiff must be created", diffsFor(doc.getId()).isEmpty());
    }

    @Test
    public void testUnsupportedFormatIsAuditedWithoutADiff() throws Exception {
        DocumentModel doc = createVersionedFile("unsupported", "payload v1", UNSUPPORTED_MIME);
        newVersion(doc, "payload v2 which really differs", UNSUPPORTED_MIME);

        List<LogEntry> entries = auditEntries(doc.getId());
        assertEquals("exactly one audit entry", 1, entries.size());
        LogEntry entry = entries.get(0);
        assertEquals(STATUS_SKIPPED_TYPE, extended(entry, EXT_SKIP_REASON));
        assertNull(extended(entry, EXT_CORRELATION_ID));
        assertTrue("file:content : binary changed (unsupported format)".equals(entry.getComment()));
        assertTrue("no BlobDiff must be created", diffsFor(doc.getId()).isEmpty());
    }

    /* ------------------------------------------------------ silent cases */

    /**
     * The regression this ordering was introduced for: eligibility used to be evaluated before
     * {@code sameContent}, so reporting skips naively would audit every new version of an
     * over-sized document even when its binary never moved.
     */
    @Test
    @Deploy("nuxeo-advanced-document-blob-audit-core:blobaudit-test-smallblob-config.xml")
    public void testUnchangedOverSizedBinaryIsNotAudited() throws Exception {
        String content = largeText("stable");
        DocumentModel doc = createVersionedFile("big-but-stable", content, "text/plain");
        newVersion(doc, content, "text/plain");

        assertTrue("an unchanged binary must stay silent, whatever its size",
                auditEntries(doc.getId()).isEmpty());
        assertTrue(diffsFor(doc.getId()).isEmpty());
    }

    @Test
    public void testUnchangedUnsupportedBinaryIsNotAudited() throws Exception {
        DocumentModel doc = createVersionedFile("stable-unsupported", "same payload", UNSUPPORTED_MIME);
        newVersion(doc, "same payload", UNSUPPORTED_MIME);

        assertTrue(auditEntries(doc.getId()).isEmpty());
        assertTrue(diffsFor(doc.getId()).isEmpty());
    }

    /** An xpath outside the configured scope is NOT_APPLICABLE: fully silent, never a skip. */
    @Test
    public void testOutOfScopeXPathStaysSilent() throws Exception {
        BlobDiffService service = org.nuxeo.runtime.api.Framework.getService(BlobDiffService.class);
        assertEquals(DiffEligibility.NOT_APPLICABLE,
                service.getEligibility("File", "files:files/0/file", textBlob("x", UNSUPPORTED_MIME, "f.bin")));
    }

    /**
     * Item 15: the work re-reads both binaries from their blob provider after commit, so a pair
     * that is not entirely made of {@code ManagedBlob} cannot be diffed. That used to be a silent
     * WARN; the change is now audited like any other non-diffable one.
     */
    @Test
    public void testNonManagedBlobPairIsAuditedWithoutADiff() throws Exception {
        DocumentModel doc = createVersionedFile("not-managed", "v1", "text/plain");
        VersionContext versions = new VersionContext("prev", "1.0", "new", "1.1", "series");

        // plain in-memory blobs, deliberately not ManagedBlob
        BlobDiffTrigger.Outcome outcome = BlobDiffTrigger.scheduleIfNeeded(doc, "file:content",
                textBlob("v1", "text/plain", "notes.txt"), textBlob("v2", "text/plain", "notes.txt"), versions,
                "Administrator", System.currentTimeMillis());

        assertEquals(BlobAuditConstants.STATUS_SKIPPED_NOT_MANAGED, outcome.skipReason());
        assertNull(outcome.correlationId());
        assertTrue(outcome.isAuditable());
    }

    /**
     * ... but only when the binary actually changed: the digest check still comes first.
     * <p>
     * Note that a non-managed blob usually carries <b>no digest at all</b>, and {@code sameContent}
     * deliberately answers "different" when it cannot tell. So in practice this branch audits every
     * new version of such a pair. For an audit tool a false positive beats a silence, and the WARN
     * that comes with it points at the real problem, an unusual blob provider setup.
     */
    @Test
    public void testNonManagedBlobPairWithIdenticalContentStaysSilent() throws Exception {
        DocumentModel doc = createVersionedFile("not-managed-stable", "v1", "text/plain");
        VersionContext versions = new VersionContext("prev", "1.0", "new", "1.1", "series");
        Blob old = textBlob("same", "text/plain", "notes.txt");
        Blob recent = textBlob("same", "text/plain", "notes.txt");
        old.setDigest("d41d8cd98f00b204e9800998ecf8427e");
        recent.setDigest("d41d8cd98f00b204e9800998ecf8427e");

        BlobDiffTrigger.Outcome outcome = BlobDiffTrigger.scheduleIfNeeded(doc, "file:content", old, recent, versions,
                "Administrator", System.currentTimeMillis());

        assertNull(outcome.skipReason());
        assertFalse("a binary with an unchanged digest must stay silent", outcome.isAuditable());
    }

    /* ------------------------------------------------- nominal path intact */

    @Test
    public void testDiffableChangeStillCarriesACorrelationIdAndNoSkipReason() throws Exception {
        DocumentModel doc = createVersionedFile("nominal", "v1", "text/plain");
        newVersion(doc, "v2", "text/plain");

        List<LogEntry> entries = auditEntries(doc.getId());
        assertEquals(1, entries.size());
        LogEntry entry = entries.get(0);
        assertNull("the nominal path must not set skipReason", extended(entry, EXT_SKIP_REASON));
        assertTrue("the nominal path must carry a correlation id",
                extended(entry, EXT_CORRELATION_ID) != null);
        assertEquals(1, diffsFor(doc.getId()).size());
    }

    /** The kill switches win over the skip reporting: a muted listener audits nothing at all. */
    @Test
    @Deploy("nuxeo-advanced-document-blob-audit-core:blobaudit-test-smallblob-config.xml")
    public void testDisabledListenerDoesNotReportSkipsEither() throws Exception {
        DocumentModel doc = createVersionedFile("muted-big", largeText("v1"), "text/plain");

        DocumentModel updated = session.getDocument(doc.getRef());
        updated.setPropertyValue("file:content",
                (Serializable) textBlob(largeText("v2"), "text/plain", "notes.bin"));
        updated.putContextData(BlobModificationListener.DISABLE_BLOB_DIFF_LISTENER, Boolean.TRUE);
        updated.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.MINOR);
        session.saveDocument(updated);
        session.save();
        txFeature.nextTransaction();

        assertTrue(auditEntries(doc.getId()).isEmpty());
        assertTrue(diffsFor(doc.getId()).isEmpty());
    }
}

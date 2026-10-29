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
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_NEW_MIME_TYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_OLD_MIME_TYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_SKIP_REASON;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_CONTENT_REPLACED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_FORMAT_CHANGED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_SIZE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_TYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.DOCX_MIME;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.XLSX_MIME;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.cells;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.customersJson;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.productsXml;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.textBlob;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.xlsx;

import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
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

    protected static final String PDF_MIME = "application/pdf";

    protected static final String XLS_MIME = "application/vnd.ms-excel";

    protected static final String JSON_MIME = "application/json";

    protected static final String XML_MIME = "application/xml";

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
        return createVersionedFile(name, textBlob(content, mimeType, "notes.bin"));
    }

    protected DocumentModel createVersionedFile(String name, Blob blob) throws Exception {
        DocumentModel doc = session.createDocumentModel("/", name, "File");
        doc.setPropertyValue("file:content", (Serializable) blob);
        doc = session.createDocument(doc);
        session.save();
        session.checkIn(doc.getRef(), VersioningOption.MAJOR, "initial");
        session.save();
        txFeature.nextTransaction();
        return session.getDocument(doc.getRef());
    }

    protected DocumentModel newVersion(DocumentModel doc, String content, String mimeType) throws Exception {
        return newVersion(doc, textBlob(content, mimeType, "notes.bin"));
    }

    protected DocumentModel newVersion(DocumentModel doc, Blob blob) throws Exception {
        DocumentModel updated = session.getDocument(doc.getRef());
        updated.setPropertyValue("file:content", (Serializable) blob);
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

    /* ------------------------------------------------------- format changes */

    /**
     * Two supported formats read by different extractor contributions cannot be compared: the
     * extractions do not cut the content the same way, and the diff would report everything as
     * removed and added. The skip is decided on the mime types alone, before any extraction, so the
     * payloads need not be real workbooks or documents.
     */
    @Test
    public void testASpreadsheetReplacedByAWordDocumentIsAuditedWithoutADiff() throws Exception {
        DocumentModel doc = createVersionedFile("xlsx-to-docx", "spreadsheet bytes", XLSX_MIME);
        newVersion(doc, "word bytes", DOCX_MIME);

        List<LogEntry> entries = auditEntries(doc.getId());
        assertEquals("exactly one audit entry", 1, entries.size());
        LogEntry entry = entries.get(0);
        assertEquals(STATUS_SKIPPED_FORMAT_CHANGED, extended(entry, EXT_SKIP_REASON));
        assertNull(extended(entry, EXT_CORRELATION_ID));
        assertEquals("file:content : binary changed (format changed)", entry.getComment());
        assertEquals(XLSX_MIME, extended(entry, EXT_OLD_MIME_TYPE));
        assertEquals(DOCX_MIME, extended(entry, EXT_NEW_MIME_TYPE));
        assertTrue("no BlobDiff must be created", diffsFor(doc.getId()).isEmpty());
    }

    /**
     * The case the {@code pdf} contribution exists for: Word and PDF are both read through the
     * converter, but a PDF comes out one line per rendered line and Word one line per paragraph.
     * Under a single contribution, a PDF rendition stored as a new version was diffed line by line.
     */
    @Test
    public void testAWordDocumentReplacedByItsPdfRenditionIsAuditedWithoutADiff() throws Exception {
        DocumentModel doc = createVersionedFile("docx-to-pdf", "word bytes", DOCX_MIME);
        newVersion(doc, "pdf bytes", PDF_MIME);

        List<LogEntry> entries = auditEntries(doc.getId());
        assertEquals(1, entries.size());
        assertEquals(STATUS_SKIPPED_FORMAT_CHANGED, extended(entries.get(0), EXT_SKIP_REASON));
        assertTrue(diffsFor(doc.getId()).isEmpty());
    }

    /** The digest check still comes first: the same bytes under another mime type are no change. */
    @Test
    public void testTheSameBytesUnderAnotherFormatAreNotAudited() throws Exception {
        DocumentModel doc = createVersionedFile("relabelled", "same payload", XLSX_MIME);
        newVersion(doc, "same payload", DOCX_MIME);

        assertTrue(auditEntries(doc.getId()).isEmpty());
        assertTrue(diffsFor(doc.getId()).isEmpty());
    }

    /**
     * A conversion within one contribution is still compared: an {@code .xls} saved as
     * {@code .xlsx} is the case where "converted, one cell changed" is exactly what the auditor needs.
     */
    @Test
    public void testAConversionWithinOneExtractorIsStillDiffed() throws Exception {
        DocumentModel doc = createVersionedFile("xls-to-xlsx", xls("A1", "hello", "B2", "100"));
        newVersion(doc, xlsx("Sheet1", cells("A1", "hello", "B2", "120"), "book.xlsx"));

        List<LogEntry> entries = auditEntries(doc.getId());
        assertEquals(1, entries.size());
        LogEntry entry = entries.get(0);
        assertNull(extended(entry, EXT_SKIP_REASON));
        assertEquals(XLS_MIME, extended(entry, EXT_OLD_MIME_TYPE));
        assertEquals(XLSX_MIME, extended(entry, EXT_NEW_MIME_TYPE));
        List<DocumentModel> diffs = diffsFor(doc.getId());
        assertEquals(1, diffs.size());
        DocumentModel diff = diffs.get(0);
        assertEquals(BlobAuditConstants.STATUS_OK, diff.getPropertyValue(BlobAuditConstants.XP_STATUS));
        assertEquals(Long.valueOf(1), diff.getPropertyValue(BlobAuditConstants.XP_CHANGED));
        assertEquals(Long.valueOf(0), diff.getPropertyValue(BlobAuditConstants.XP_ADDED));
        assertEquals(Long.valueOf(0), diff.getPropertyValue(BlobAuditConstants.XP_REMOVED));
    }

    /** A single-sheet binary {@code .xls}, from {@code "A1", value} pairs. */
    protected Blob xls(String... keyValuePairs) throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BlobAuditTestHelper.fillSheet(workbook.createSheet("Sheet1"), cells(keyValuePairs));
            workbook.write(out);
            return BlobAuditTestHelper.blob(out.toByteArray(), XLS_MIME, "book.xls");
        }
    }

    /* ------------------------------------------------- replaced text content */

    /**
     * Item 62: a {@code .json} replaced by an unrelated {@code .xml}. Both are read by
     * {@code plainText}, so it is no format change in the sense of item 61, and the diff would list
     * both files whole. It is filed with its counters and its score, without its body. Decided once
     * the diff is computed: the audit entry, written at check-in, is the nominal one.
     */
    @Test
    public void testATextFileReplacedByAnotherFormatIsKeptWithoutItsBody() throws Exception {
        DocumentModel doc = createVersionedFile("json-to-xml", textBlob(customersJson(10), JSON_MIME, "data.json"));
        newVersion(doc, textBlob(productsXml(10), XML_MIME, "data.xml"));

        List<LogEntry> entries = auditEntries(doc.getId());
        assertEquals(1, entries.size());
        LogEntry entry = entries.get(0);
        assertNull(extended(entry, EXT_SKIP_REASON));
        assertTrue(extended(entry, EXT_CORRELATION_ID) != null);
        assertEquals(JSON_MIME, extended(entry, EXT_OLD_MIME_TYPE));
        assertEquals(XML_MIME, extended(entry, EXT_NEW_MIME_TYPE));

        List<DocumentModel> diffs = diffsFor(doc.getId());
        assertEquals(1, diffs.size());
        DocumentModel diff = diffs.get(0);
        assertEquals(STATUS_SKIPPED_CONTENT_REPLACED, diff.getPropertyValue(BlobAuditConstants.XP_STATUS));
        assertEquals(0d, (Double) diff.getPropertyValue(BlobAuditConstants.XP_SIMILARITY), 0d);
        assertTrue((Long) diff.getPropertyValue(BlobAuditConstants.XP_CHANGED) > 0);
        assertNull("the body must not be stored", diff.getPropertyValue(BlobAuditConstants.XP_DIFF_BLOB));
        assertEquals(JSON_MIME, diff.getPropertyValue(BlobAuditConstants.XP_OLD_MIMETYPE));
        assertEquals(XML_MIME, diff.getPropertyValue(BlobAuditConstants.XP_MIMETYPE));
    }

    /** A {@code .txt} holding JSON, edited and saved as {@code .json}: a genuine diff. */
    @Test
    public void testATextFileEditedUnderAnotherFormatIsStillDiffed() throws Exception {
        DocumentModel doc = createVersionedFile("txt-to-json", textBlob(customersJson(10), "text/plain", "data.txt"));
        newVersion(doc, textBlob(customersJson(10).replace("Customer number 3", "Customer renamed"), JSON_MIME,
                "data.json"));

        DocumentModel diff = diffsFor(doc.getId()).get(0);
        assertEquals(BlobAuditConstants.STATUS_OK, diff.getPropertyValue(BlobAuditConstants.XP_STATUS));
        assertEquals(Long.valueOf(1), diff.getPropertyValue(BlobAuditConstants.XP_CHANGED));
        assertTrue((Double) diff.getPropertyValue(BlobAuditConstants.XP_SIMILARITY) > 0.9);
        assertTrue(diff.getPropertyValue(BlobAuditConstants.XP_DIFF_BLOB) != null);
    }

    /** The same rewrite without a format change is an author's edit, and always diffed. */
    @Test
    public void testATextFileRewrittenInTheSameFormatIsStillDiffed() throws Exception {
        DocumentModel doc = createVersionedFile("json-rewritten", textBlob(customersJson(10), JSON_MIME, "a.json"));
        newVersion(doc, textBlob(productsXml(10), JSON_MIME, "a.json"));

        DocumentModel diff = diffsFor(doc.getId()).get(0);
        assertEquals(BlobAuditConstants.STATUS_OK, diff.getPropertyValue(BlobAuditConstants.XP_STATUS));
        assertEquals(0d, (Double) diff.getPropertyValue(BlobAuditConstants.XP_SIMILARITY), 0d);
        assertTrue(diff.getPropertyValue(BlobAuditConstants.XP_DIFF_BLOB) != null);
    }

    @Test
    @Deploy("nuxeo-advanced-document-blob-audit-core:blobaudit-test-keepalldiffs-config.xml")
    public void testMinSimilarityZeroStoresTheReplacedContentWhole() throws Exception {
        DocumentModel doc = createVersionedFile("json-to-xml-kept", textBlob(customersJson(10), JSON_MIME, "d.json"));
        newVersion(doc, textBlob(productsXml(10), XML_MIME, "d.xml"));

        DocumentModel diff = diffsFor(doc.getId()).get(0);
        assertEquals(BlobAuditConstants.STATUS_OK, diff.getPropertyValue(BlobAuditConstants.XP_STATUS));
        assertTrue(diff.getPropertyValue(BlobAuditConstants.XP_DIFF_BLOB) != null);
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

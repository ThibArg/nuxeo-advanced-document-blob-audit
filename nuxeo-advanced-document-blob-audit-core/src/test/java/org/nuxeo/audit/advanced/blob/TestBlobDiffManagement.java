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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.textBlob;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.audit.advanced.blob.io.BlobDiffSourceEnricher;
import org.nuxeo.audit.advanced.blob.operations.BlobDiffDeleteOp;
import org.nuxeo.audit.advanced.blob.operations.BlobDiffPurgeOp;
import org.nuxeo.audit.advanced.blob.operations.BlobDiffRetryOp;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CloseableCoreSession;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentSecurityException;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.api.trash.TrashService;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.io.registry.MarshallerHelper;
import org.nuxeo.ecm.core.io.registry.context.RenderingContext;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;
import org.nuxeo.runtime.transaction.TransactionHelper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Step 2 server side: binary keys persisted on BlobDiff, Delete / Purge / Retry operations and the
 * {@code blobDiffSource} enricher used by Web UI.
 *
 * @since 1.2
 */
@RunWith(FeaturesRunner.class)
@Features(BlobAuditFeature.class)
@Deploy("org.nuxeo.ecm.automation.core")
@Deploy("org.nuxeo.ecm.core.io")
public class TestBlobDiffManagement {

    @Inject
    protected CoreSession session;

    @Inject
    protected CoreFeature coreFeature;

    @Inject
    protected BlobDiffService blobDiffService;

    @Inject
    protected AutomationService automationService;

    @Inject
    protected TransactionalFeature txFeature;

    /* ---------------------------------------------------------------- helpers */

    protected DocumentModel file(String name, Blob blob) {
        DocumentModel doc = session.createDocumentModel("/", name, "File");
        doc.setPropertyValue("file:content", (Serializable) blob);
        doc = session.createDocument(doc);
        session.save();
        return doc;
    }

    protected ManagedBlob storedBlob(DocumentModel doc) {
        return (ManagedBlob) session.getDocument(doc.getRef()).getPropertyValue("file:content");
    }

    protected DocumentModel diff(DocumentModel source, Date date, String status, FrozenBlobs keys) {
        DocumentModel diff = blobDiffService.createDiffDocument(session, source.getId(), source.getRepositoryName(),
                source.getTitle(), "file:content", null, null, keys, "jdoe", date, null, status,
                UUID.randomUUID().toString());
        session.save();
        return diff;
    }

    protected Date daysAgo(int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, -days);
        return calendar.getTime();
    }

    protected Object run(CoreSession s, String operation, Object input, java.util.Map<String, ?> params)
            throws Exception {
        try (OperationContext ctx = new OperationContext(s)) {
            if (input != null) {
                ctx.setInput(input);
            }
            return automationService.run(ctx, operation, params == null ? java.util.Map.of() : params);
        }
    }

    protected void assertDenied(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof DocumentSecurityException) {
                return;
            }
        }
        fail("expected a DocumentSecurityException, got " + e);
    }

    protected List<DocumentModel> diffsOf(String sourceId) {
        return session.query("SELECT * FROM BlobDiff WHERE bdiff:sourceId = '" + sourceId + "'");
    }

    /* ------------------------------------------------------------ binary keys */

    @Test
    public void testBinaryKeysArePersistedByTheWork() throws Exception {
        DocumentModel doc = file("keys", textBlob("v1", "text/plain", "notes.txt"));
        session.checkIn(doc.getRef(), VersioningOption.MAJOR, "v1");
        session.save();
        txFeature.nextTransaction();
        doc = session.getDocument(doc.getRef());
        doc.setPropertyValue("file:content", (Serializable) textBlob("v2", "text/plain", "notes.txt"));
        session.saveDocument(doc);
        session.checkIn(doc.getRef(), VersioningOption.MINOR, "v2");
        session.save();
        txFeature.nextTransaction();

        List<DocumentModel> diffs = diffsOf(doc.getId());
        assertEquals(1, diffs.size());
        DocumentModel diff = diffs.get(0);
        assertNotNull(diff.getPropertyValue(BlobAuditConstants.XP_OLD_BLOB_KEY));
        assertNotNull(diff.getPropertyValue(BlobAuditConstants.XP_NEW_BLOB_KEY));
        assertNotNull(diff.getPropertyValue(BlobAuditConstants.XP_OLD_BLOB_PROVIDER));
        assertNotNull(diff.getPropertyValue(BlobAuditConstants.XP_NEW_BLOB_PROVIDER));
        assertEquals("text/plain", diff.getPropertyValue(BlobAuditConstants.XP_OLD_MIMETYPE));
    }

    /* ----------------------------------------------------------------- delete */

    @Test
    public void testAdministratorCanDeleteDiffs() throws Exception {
        DocumentModel source = file("to-delete", textBlob("x", "text/plain", "x.txt"));
        DocumentModel d1 = diff(source, new Date(), BlobAuditConstants.STATUS_OK, FrozenBlobs.NONE);
        DocumentModel d2 = diff(source, new Date(), BlobAuditConstants.STATUS_OK, FrozenBlobs.NONE);

        run(session, BlobDiffDeleteOp.ID,
                new org.nuxeo.ecm.core.api.impl.DocumentModelListImpl(List.of(d1, d2)), null);
        assertTrue(diffsOf(source.getId()).isEmpty());
    }

    @Test
    public void testDeleteRefusesOtherDocumentTypes() throws Exception {
        DocumentModel source = file("not-a-diff", textBlob("x", "text/plain", "x.txt"));
        try {
            run(session, BlobDiffDeleteOp.ID, source, null);
            fail("deleting a non BlobDiff document must fail");
        } catch (Exception e) {
            // expected
        }
        assertTrue(session.exists(source.getRef()));
    }

    /**
     * A refused operation marks the current transaction rollback-only (automation behaviour), which
     * leaves the test without an active transaction afterwards. Each refused call therefore runs in
     * its own transaction, and a fresh one is started before the final checks.
     */
    @Test
    public void testNonAuditorCannotDeleteOrPurge() throws Exception {
        DocumentModel source = file("protected", textBlob("x", "text/plain", "x.txt"));
        DocumentModel d = diff(source, daysAgo(10), BlobAuditConstants.STATUS_OK, FrozenBlobs.NONE);
        txFeature.nextTransaction();

        assertRefusedForJdoe(BlobDiffPurgeOp.ID, null, java.util.Map.of("before", Calendar.getInstance()));
        // jdoe cannot even read the diff; the auditor check is performed before any access
        assertRefusedForJdoe(BlobDiffDeleteOp.ID, d, null);

        restartTransaction();
        assertTrue("a refused operation must not delete anything", session.exists(d.getRef()));
        assertTrue(session.exists(source.getRef()));
    }

    protected void assertRefusedForJdoe(String operationId, Object input, java.util.Map<String, ?> params) {
        restartTransaction();
        try (CloseableCoreSession jdoe = coreFeature.openCoreSession("jdoe")) {
            run(jdoe, operationId, input, params);
            fail("jdoe must not be allowed to run " + operationId);
        } catch (Exception e) {
            assertDenied(e);
        } finally {
            // rolls back the transaction marked rollback-only by the refused operation
            TransactionHelper.commitOrRollbackTransaction();
        }
    }

    /** Ends the current transaction (committing or rolling back) and starts a new one. */
    protected void restartTransaction() {
        if (TransactionHelper.isTransactionActiveOrMarkedRollback()) {
            TransactionHelper.commitOrRollbackTransaction();
        }
        TransactionHelper.startTransaction();
    }

    /*
     * The purge itself moved to TestBlobDiffPurgeAction: it is asynchronous since 2025.3 and needs
     * the Bulk Action Framework. Only the access check stays here, because it is enforced before
     * anything is submitted.
     */

    /* ------------------------------------------------------------------ retry */

    @Test
    public void testRetryReplaysAnErrorDiffAndReplacesIt() throws Exception {
        DocumentModel v1 = file("v1-holder", textBlob("alpha\nbeta", "text/plain", "notes.txt"));
        DocumentModel v2 = file("v2-holder", textBlob("alpha\nBETA", "text/plain", "notes.txt"));
        ManagedBlob b1 = storedBlob(v1);
        ManagedBlob b2 = storedBlob(v2);
        FrozenBlobs keys = new FrozenBlobs(b1.getProviderId(), b1.getKey(), "text/plain", b1.getLength(),
                b2.getProviderId(), b2.getKey(), "text/plain", b2.getLength());
        DocumentModel failed = diff(v2, new Date(), BlobAuditConstants.STATUS_ERROR, keys);
        String correlationId = (String) failed.getPropertyValue(BlobAuditConstants.XP_CORRELATION_ID);
        txFeature.nextTransaction();

        run(session, BlobDiffRetryOp.ID, session.getDocument(failed.getRef()), null);
        txFeature.nextTransaction();

        List<DocumentModel> diffs = diffsOf(v2.getId());
        assertEquals("the failed diff must be replaced, not duplicated", 1, diffs.size());
        DocumentModel replayed = diffs.get(0);
        assertEquals(BlobAuditConstants.STATUS_OK, replayed.getPropertyValue(BlobAuditConstants.XP_STATUS));
        assertEquals(correlationId, replayed.getPropertyValue(BlobAuditConstants.XP_CORRELATION_ID));
        assertEquals("jdoe", replayed.getPropertyValue(BlobAuditConstants.XP_USER));
        assertEquals(Long.valueOf(1), replayed.getPropertyValue(BlobAuditConstants.XP_CHANGED));
        assertFalse(session.exists(failed.getRef()));
    }

    @Test
    public void testRetryRequiresAnErrorDiffWithKeys() throws Exception {
        DocumentModel source = file("no-retry", textBlob("x", "text/plain", "x.txt"));
        DocumentModel okDiff = diff(source, new Date(), BlobAuditConstants.STATUS_OK, FrozenBlobs.NONE);
        DocumentModel noKeys = diff(source, new Date(), BlobAuditConstants.STATUS_ERROR, FrozenBlobs.NONE);
        for (DocumentModel d : List.of(okDiff, noKeys)) {
            try {
                run(session, BlobDiffRetryOp.ID, d, null);
                fail("retry must be refused for " + d.getPropertyValue(BlobAuditConstants.XP_STATUS));
            } catch (Exception e) {
                // expected
            }
        }
    }

    /**
     * COR-01, end to end: an extraction that <b>fails</b> must be filed as {@code error}, not as
     * {@code skippedUnsupportedType}.
     * <p>
     * The distinction is not cosmetic. {@code BlobDiff.Retry} only accepts {@code error}, so a
     * failure recorded as "unsupported format" could never be replayed: the audit trail kept a
     * permanent hole, and the auditor read a reason that was not the real one. The fixture is a
     * pair of .xlsx whose mime type has an extractor - so the check-in path finds the work
     * eligible - but whose bytes are not a zip, so the extractor blows up at work time.
     */
    @Test
    public void testAnExtractionFailureIsRetryable() throws Exception {
        DocumentModel doc = file("corrupted-xlsx", corruptXlsx("v1 is not a zip"));
        session.checkIn(doc.getRef(), VersioningOption.MAJOR, "v1");
        session.save();
        txFeature.nextTransaction();
        doc = session.getDocument(doc.getRef());
        doc.setPropertyValue("file:content", (Serializable) corruptXlsx("v2 is not a zip either"));
        session.saveDocument(doc);
        session.checkIn(doc.getRef(), VersioningOption.MINOR, "v2");
        session.save();
        txFeature.nextTransaction();

        List<DocumentModel> diffs = diffsOf(doc.getId());
        assertEquals("a changed binary with an extractor must still produce a BlobDiff", 1, diffs.size());
        DocumentModel failed = diffs.get(0);
        assertEquals("a failed extraction is an incident, not an unsupported format",
                BlobAuditConstants.STATUS_ERROR, failed.getPropertyValue(BlobAuditConstants.XP_STATUS));

        // ... and that status is exactly what makes the diff replayable
        run(session, BlobDiffRetryOp.ID, session.getDocument(failed.getRef()), null);
        txFeature.nextTransaction();

        List<DocumentModel> replayed = diffsOf(doc.getId());
        assertEquals("the retry must replace the failed diff, not duplicate it", 1, replayed.size());
        assertEquals(BlobAuditConstants.STATUS_ERROR,
                replayed.get(0).getPropertyValue(BlobAuditConstants.XP_STATUS));
    }

    /** An .xlsx by mime type and filename only: the extractor is found, then fails on the bytes. */
    protected Blob corruptXlsx(String content) throws Exception {
        return BlobAuditTestHelper.blob(content.getBytes(StandardCharsets.UTF_8), BlobAuditTestHelper.XLSX_MIME,
                "corrupt.xlsx");
    }

    /* --------------------------------------------------------------- enricher */

    protected JsonNode sourceInfo(DocumentModel diff) throws Exception {
        RenderingContext ctx = RenderingContext.CtxBuilder.enrichDoc(BlobDiffSourceEnricher.NAME)
                                                          .session(session)
                                                          .get();
        String json = MarshallerHelper.objectToJson(session.getDocument(diff.getRef()), ctx);
        return new ObjectMapper().readTree(json).get("contextParameters").get(BlobDiffSourceEnricher.NAME);
    }

    @Test
    public void testEnricherDescribesAnExistingSource() throws Exception {
        DocumentModel source = file("enriched", textBlob("x", "text/plain", "x.txt"));
        JsonNode info = sourceInfo(diff(source, new Date(), BlobAuditConstants.STATUS_OK, FrozenBlobs.NONE));
        assertTrue(info.get("exists").asBoolean());
        assertFalse(info.get("trashed").asBoolean());
        assertTrue(info.get("readable").asBoolean());
        assertEquals(source.getPathAsString(), info.get("path").asText());
        assertEquals(source.getId(), info.get("uid").asText());
    }

    @Test
    public void testEnricherFlagsATrashedSource() throws Exception {
        DocumentModel source = file("trashed", textBlob("x", "text/plain", "x.txt"));
        DocumentModel d = diff(source, new Date(), BlobAuditConstants.STATUS_OK, FrozenBlobs.NONE);
        Framework.getService(TrashService.class).trashDocument(session.getDocument(source.getRef()));
        session.save();
        JsonNode info = sourceInfo(d);
        assertTrue(info.get("exists").asBoolean());
        assertTrue(info.get("trashed").asBoolean());
    }

    @Test
    public void testEnricherFlagsADeletedSource() throws Exception {
        DocumentModel source = file("gone", textBlob("x", "text/plain", "x.txt"));
        DocumentModel d = diff(source, new Date(), BlobAuditConstants.STATUS_OK, FrozenBlobs.NONE);
        session.removeDocument(source.getRef());
        session.save();
        JsonNode info = sourceInfo(d);
        assertFalse(info.get("exists").asBoolean());
        assertFalse(info.get("readable").asBoolean());
        assertNull(info.get("path"));
        assertEquals(source.getId(), info.get("uid").asText());
    }

    /* ----------------------------------------------------------------- access */

    @Test
    public void testAccessRules() {
        assertTrue(BlobDiffAccess.isAuditor(session.getPrincipal()));
        assertTrue(BlobDiffAccess.isAuditor(
                new org.nuxeo.ecm.core.api.impl.UserPrincipal("u1", List.of("administrators"), false, false)));
        assertFalse(BlobDiffAccess.isAuditor(
                new org.nuxeo.ecm.core.api.impl.UserPrincipal("u2", List.of("members"), false, false)));
        assertFalse(BlobDiffAccess.isAuditor(null));
    }
}

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
import static org.junit.Assert.fail;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.textBlob;

import java.io.Serializable;
import java.time.Duration;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.audit.advanced.blob.bulk.BlobDiffPurgeAction;
import org.nuxeo.audit.advanced.blob.operations.BlobDiffCleanEmptyFoldersOp;
import org.nuxeo.audit.advanced.blob.operations.BlobDiffPurgeAbortOp;
import org.nuxeo.audit.advanced.blob.operations.BlobDiffPurgeOp;
import org.nuxeo.audit.advanced.blob.operations.BlobDiffPurgeStatusOp;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CloseableCoreSession;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentSecurityException;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.bulk.BulkService;
import org.nuxeo.ecm.core.bulk.CoreBulkFeature;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;
import org.nuxeo.runtime.transaction.TransactionHelper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Covers item 11: the retention purge runs on the Bulk Action Framework instead of looping inside
 * an Automation operation bound to the HTTP transaction.
 *
 * @since 2025.1
 */
@RunWith(FeaturesRunner.class)
@Features({ BlobAuditFeature.class, CoreBulkFeature.class })
@Deploy("org.nuxeo.ecm.automation.core")
@Deploy("org.nuxeo.ecm.core.io")
public class TestBlobDiffPurgeAction {

    protected static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Inject
    protected CoreSession session;

    @Inject
    protected CoreFeature coreFeature;

    @Inject
    protected BlobDiffService blobDiffService;

    @Inject
    protected AutomationService automationService;

    @Inject
    protected BulkService bulkService;

    @Inject
    protected TransactionalFeature txFeature;

    /* ==================== fixtures ==================== */

    protected DocumentModel file(String name, Blob blob) {
        DocumentModel doc = session.createDocumentModel("/", name, "File");
        doc.setPropertyValue("file:content", (Serializable) blob);
        doc = session.createDocument(doc);
        session.save();
        return doc;
    }

    protected DocumentModel diff(DocumentModel source, Date date, String status) {
        DocumentModel diff = blobDiffService.createDiffDocument(session, source.getId(), source.getRepositoryName(),
                source.getTitle(), "file:content", null, null, FrozenBlobs.NONE, "jdoe", date, null, status,
                UUID.randomUUID().toString());
        session.save();
        return diff;
    }

    protected Date daysAgo(int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, -days);
        return calendar.getTime();
    }

    protected Calendar beforeDaysAgo(int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, -days);
        return calendar;
    }

    protected JsonNode run(CoreSession s, String operation, Object input, Map<String, ?> params) throws Exception {
        try (OperationContext ctx = new OperationContext(s)) {
            if (input != null) {
                ctx.setInput(input);
            }
            Object result = automationService.run(ctx, operation, params == null ? Map.of() : params);
            return new ObjectMapper().readTree(((Blob) result).getString());
        }
    }

    protected List<DocumentModel> diffsOf(String sourceId) {
        return session.query("SELECT * FROM BlobDiff WHERE bdiff:sourceId = '" + sourceId + "'");
    }

    /** Submits a purge and waits for the bulk command to complete. Returns the final status. */
    protected JsonNode purgeAndWait(Map<String, ?> params) throws Exception {
        JsonNode submitted = run(session, BlobDiffPurgeOp.ID, null, params);
        String commandId = submitted.get("commandId").asText();
        assertEquals("SCHEDULED", submitted.get("state").asText());
        txFeature.nextTransaction();
        assertTrue("the purge did not complete in " + TIMEOUT, bulkService.await(commandId, TIMEOUT));
        txFeature.nextTransaction();
        return run(session, BlobDiffPurgeStatusOp.ID, null, Map.of("commandId", commandId));
    }

    /* ==================== purge ==================== */

    @Test
    public void testPurgeDeletesOlderDiffsOnly() throws Exception {
        DocumentModel source = file("purged", textBlob("x", "text/plain", "x.txt"));
        DocumentModel old1 = diff(source, daysAgo(40), BlobAuditConstants.STATUS_OK);
        DocumentModel old2 = diff(source, daysAgo(40), BlobAuditConstants.STATUS_ERROR);
        DocumentModel recent = diff(source, new Date(), BlobAuditConstants.STATUS_OK);
        txFeature.nextTransaction();

        JsonNode status = purgeAndWait(Map.of("before", beforeDaysAgo(30)));

        assertEquals("COMPLETED", status.get("state").asText());
        assertEquals(2, status.get("deleted").asInt());
        assertEquals(0, status.get("errorCount").asInt());
        assertFalse(session.exists(old1.getRef()));
        assertFalse(session.exists(old2.getRef()));
        assertTrue(session.exists(recent.getRef()));
    }

    @Test
    public void testPurgeCanBeRestrictedToAStatus() throws Exception {
        DocumentModel source = file("purged-status", textBlob("x", "text/plain", "x.txt"));
        DocumentModel ok = diff(source, daysAgo(40), BlobAuditConstants.STATUS_OK);
        DocumentModel error = diff(source, daysAgo(40), BlobAuditConstants.STATUS_ERROR);
        txFeature.nextTransaction();

        JsonNode status = purgeAndWait(
                Map.of("before", Calendar.getInstance(), "status", BlobAuditConstants.STATUS_ERROR));

        assertEquals(1, status.get("deleted").asInt());
        assertTrue(session.exists(ok.getRef()));
        assertFalse(session.exists(error.getRef()));
    }

    @Test
    public void testPurgeOnAnEmptySetCompletesWithoutDeletingAnything() throws Exception {
        DocumentModel source = file("nothing-to-purge", textBlob("x", "text/plain", "x.txt"));
        DocumentModel recent = diff(source, new Date(), BlobAuditConstants.STATUS_OK);
        txFeature.nextTransaction();

        JsonNode status = purgeAndWait(Map.of("before", beforeDaysAgo(30)));

        assertEquals("COMPLETED", status.get("state").asText());
        assertEquals(0, status.get("deleted").asInt());
        assertTrue(session.exists(recent.getRef()));
    }

    /**
     * bucketSize is 100 and batchSize 50, so this crosses several buckets and several transactions.
     * The whole point of item 11: none of this happens in the caller's transaction any more.
     */
    @Test
    public void testPurgeSpansSeveralBucketsAndBatches() throws Exception {
        DocumentModel source = file("bulk-purged", textBlob("x", "text/plain", "x.txt"));
        for (int i = 0; i < 250; i++) {
            diff(source, daysAgo(40), BlobAuditConstants.STATUS_OK);
        }
        txFeature.nextTransaction();
        assertEquals(250, diffsOf(source.getId()).size());

        JsonNode status = purgeAndWait(Map.of("before", beforeDaysAgo(30)));

        assertEquals(250, status.get("deleted").asInt());
        assertEquals(250, status.get("total").asInt());
        assertTrue(diffsOf(source.getId()).isEmpty());
    }

    /**
     * The type check in the computation is what keeps the action from ever becoming a generic
     * delete-by-NXQL, even when submitted by hand with an arbitrary query.
     */
    @Test
    public void testTheActionOnlyEverRemovesBlobDiffDocuments() throws Exception {
        DocumentModel source = file("must-survive", textBlob("x", "text/plain", "x.txt"));
        DocumentModel target = diff(source, daysAgo(40), BlobAuditConstants.STATUS_OK);
        txFeature.nextTransaction();

        String commandId = bulkService.submit(
                new org.nuxeo.ecm.core.bulk.message.BulkCommand.Builder(BlobDiffPurgeAction.ACTION_NAME,
                        "SELECT * FROM Document WHERE ecm:isVersion = 0", "Administrator").repository(
                                session.getRepositoryName()).build());
        assertTrue(bulkService.await(commandId, TIMEOUT));
        txFeature.nextTransaction();

        assertFalse("the BlobDiff must be purged", session.exists(target.getRef()));
        assertTrue("a File caught by the query must survive", session.exists(source.getRef()));
        assertTrue("the root container must survive",
                session.exists(new PathRef("/" + BlobAuditConstants.CONTAINER_NAME)));
    }

    /* ==================== empty folders ==================== */

    @Test
    public void testEmptyDatedFoldersAreRemoved() throws Exception {
        DocumentModel source = file("folder-cleanup", textBlob("x", "text/plain", "x.txt"));
        DocumentModel old = diff(source, daysAgo(400), BlobAuditConstants.STATUS_OK);
        DocumentModel recent = diff(source, new Date(), BlobAuditConstants.STATUS_OK);
        String oldFolder = session.getParentDocument(old.getRef()).getPathAsString();
        String recentFolder = session.getParentDocument(recent.getRef()).getPathAsString();
        txFeature.nextTransaction();

        purgeAndWait(Map.of("before", beforeDaysAgo(30)));
        JsonNode cleaned = run(session, BlobDiffCleanEmptyFoldersOp.ID, null, null);

        assertTrue(cleaned.get("folders").asInt() >= 1);
        assertFalse("the emptied dated folder must be removed", session.exists(new PathRef(oldFolder)));
        assertTrue("a folder still holding a diff must survive", session.exists(new PathRef(recentFolder)));
        assertTrue("the root container must never be removed",
                session.exists(new PathRef("/" + BlobAuditConstants.CONTAINER_NAME)));
    }

    @Test
    public void testCleaningEmptyFoldersIsIdempotent() throws Exception {
        DocumentModel source = file("idempotent-cleanup", textBlob("x", "text/plain", "x.txt"));
        diff(source, daysAgo(400), BlobAuditConstants.STATUS_OK);
        txFeature.nextTransaction();
        purgeAndWait(Map.of("before", beforeDaysAgo(30)));

        run(session, BlobDiffCleanEmptyFoldersOp.ID, null, null);
        JsonNode second = run(session, BlobDiffCleanEmptyFoldersOp.ID, null, null);

        assertEquals("nothing left to remove on the second run", 0, second.get("folders").asInt());
    }

    /* ==================== concurrency and abort ==================== */

    /**
     * The action is exclusive: a second purge must not run beside the first one. This is also what
     * pins {@code submit()} rather than {@code submitTransactional()} in the operation - the latter
     * defers the exclusivity check to commit time, where it can no longer be reported cleanly.
     */
    @Test
    public void testASecondPurgeIsRefusedWhileOneIsRunning() throws Exception {
        DocumentModel source = file("concurrent", textBlob("x", "text/plain", "x.txt"));
        for (int i = 0; i < 50; i++) {
            diff(source, daysAgo(40), BlobAuditConstants.STATUS_OK);
        }
        txFeature.nextTransaction();

        JsonNode first = run(session, BlobDiffPurgeOp.ID, null, Map.of("before", beforeDaysAgo(30)));
        String commandId = first.get("commandId").asText();
        try {
            run(session, BlobDiffPurgeOp.ID, null, Map.of("before", beforeDaysAgo(30)));
            fail("a second purge must be refused while one is running");
        } catch (Exception e) {
            assertTrue("expected an exclusivity error, got " + e, messageOf(e).contains("already running"));
        } finally {
            restartTransaction();
        }
        assertTrue(bulkService.await(commandId, TIMEOUT));

        // Once the first one is over, a new purge is accepted again.
        restartTransaction();
        JsonNode third = run(session, BlobDiffPurgeOp.ID, null, Map.of("before", beforeDaysAgo(30)));
        assertNotNull(third.get("commandId"));
        assertTrue(bulkService.await(third.get("commandId").asText(), TIMEOUT));
    }

    @Test
    public void testAPurgeCanBeAborted() throws Exception {
        DocumentModel source = file("abortable", textBlob("x", "text/plain", "x.txt"));
        diff(source, daysAgo(40), BlobAuditConstants.STATUS_OK);
        txFeature.nextTransaction();

        JsonNode submitted = run(session, BlobDiffPurgeOp.ID, null, Map.of("before", beforeDaysAgo(30)));
        String commandId = submitted.get("commandId").asText();

        JsonNode aborted = run(session, BlobDiffPurgeAbortOp.ID, null, Map.of("commandId", commandId));

        assertNotNull(aborted.get("state"));
        assertEquals("ABORTED", bulkService.getStatus(commandId).getState().name());
    }

    @Test
    public void testStatusOfAnUnknownCommandIsNotAnError() throws Exception {
        JsonNode status = run(session, BlobDiffPurgeStatusOp.ID, null,
                Map.of("commandId", UUID.randomUUID().toString()));

        assertEquals("UNKNOWN", status.get("state").asText());
        assertEquals(0, status.get("deleted").asInt());
    }

    /* ==================== access control ==================== */

    @Test
    public void testNonAuditorIsRefusedOnEveryPurgeOperation() throws Exception {
        assertRefusedForJdoe(BlobDiffPurgeOp.ID, Map.of("before", Calendar.getInstance()));
        assertRefusedForJdoe(BlobDiffPurgeStatusOp.ID, Map.of("commandId", "whatever"));
        assertRefusedForJdoe(BlobDiffPurgeAbortOp.ID, Map.of("commandId", "whatever"));
        assertRefusedForJdoe(BlobDiffCleanEmptyFoldersOp.ID, null);
        restartTransaction();
    }

    protected void assertRefusedForJdoe(String operationId, Map<String, ?> params) {
        restartTransaction();
        try (CloseableCoreSession jdoe = coreFeature.openCoreSession("jdoe")) {
            run(jdoe, operationId, null, params);
            fail("jdoe must not be allowed to run " + operationId);
        } catch (Exception e) {
            assertDenied(e);
        } finally {
            TransactionHelper.commitOrRollbackTransaction();
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

    protected String messageOf(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            sb.append(t.getMessage()).append(' ');
        }
        return sb.toString();
    }

    protected void restartTransaction() {
        if (TransactionHelper.isTransactionActiveOrMarkedRollback()) {
            TransactionHelper.commitOrRollbackTransaction();
        }
        TransactionHelper.startTransaction();
    }
}

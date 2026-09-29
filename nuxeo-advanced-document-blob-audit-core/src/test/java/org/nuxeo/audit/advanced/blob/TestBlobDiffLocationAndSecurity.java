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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.textBlob;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.audit.advanced.blob.work.BlobDiffWork;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CloseableCoreSession;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.DocumentSecurityException;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.api.impl.UserPrincipal;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.api.security.impl.ACLImpl;
import org.nuxeo.ecm.core.api.security.impl.ACPImpl;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.ecm.platform.query.api.PageProviderService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;
import org.nuxeo.runtime.transaction.TransactionHelper;

/**
 * Step 1 of the admin UI work: {@code BlobDiff} documents must land under {@code /change-diff} and
 * be visible to the auditors group only, <b>checked from a real non-privileged session</b>, not
 * merely by inspecting the ACL.
 *
 * @since 1.1
 */
@RunWith(FeaturesRunner.class)
@Features(BlobAuditFeature.class)
public class TestBlobDiffLocationAndSecurity {

    protected static final String CONTAINER_PATH = "/" + BlobAuditConstants.CONTAINER_NAME;

    @Inject
    protected CoreSession session;

    @Inject
    protected CoreFeature coreFeature;

    @Inject
    protected BlobDiffService blobDiffService;

    @Inject
    protected PageProviderService pageProviderService;

    @Inject
    protected TransactionalFeature txFeature;

    /* ---------------------------------------------------------------- helpers */

    /** Gives jdoe Everything on the whole repository: the diff container must still resist. */
    protected void grantEverythingToJdoeOnRoot() {
        DocumentModel root = session.getRootDocument();
        ACP acp = session.getACP(root.getRef());
        acp.addACE(ACL.LOCAL_ACL, new ACE("jdoe", SecurityConstants.EVERYTHING, true));
        session.setACP(root.getRef(), acp, true);
        session.save();
    }

    protected DocumentModel createFile(CoreSession s, String name, Blob blob) {
        DocumentModel doc = s.createDocumentModel("/", name, "File");
        doc.setPropertyValue("file:content", (Serializable) blob);
        doc = s.createDocument(doc);
        s.save();
        return doc;
    }

    protected List<DocumentModel> allDiffs(CoreSession s) {
        return s.query("SELECT * FROM BlobDiff");
    }

    protected List<DocumentModel> diffsForSource(CoreSession s, String sourceId) {
        Map<String, Serializable> props = new HashMap<>();
        props.put("coreSession", (Serializable) s);
        @SuppressWarnings("unchecked")
        List<DocumentModel> page = (List<DocumentModel>) pageProviderService.getPageProvider(
                "BLOB_DIFFS_FOR_DOCUMENT", null, null, null, props, sourceId).getCurrentPage();
        return page;
    }

    /** jdoe edits a text file end to end; returns the source document id. 
     * @throws IOException */
    protected String jdoeEditsAFile(String name) throws IOException {
        grantEverythingToJdoeOnRoot();
        txFeature.nextTransaction();
        String id;
        try (CloseableCoreSession jdoe = coreFeature.openCoreSession("jdoe")) {
            DocumentModel doc = createFile(jdoe, name, textBlob("v1", "text/plain", "notes.txt"));
            id = doc.getId();
            jdoe.checkIn(doc.getRef(), VersioningOption.MAJOR, "v1");
            jdoe.save();
        }
        txFeature.nextTransaction();
        try (CloseableCoreSession jdoe = coreFeature.openCoreSession("jdoe")) {
            DocumentModel doc = jdoe.getDocument(new org.nuxeo.ecm.core.api.IdRef(id));
            doc.setPropertyValue("file:content", (Serializable) textBlob("v2", "text/plain", "notes.txt"));
            jdoe.saveDocument(doc);
            jdoe.checkIn(doc.getRef(), VersioningOption.MINOR, "v2");
            jdoe.save();
        }
        // commits and waits for BlobDiffWork
        txFeature.nextTransaction();
        return id;
    }

    protected Date date(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month, day, 10, 0, 0);
        return calendar.getTime();
    }

    /* --------------------------------------------------------------- location */

    @Test
    public void testRootContainerExistsAtStartupEvenBeforeAnyDiff() {
        assertTrue("repository initialisation must create " + CONTAINER_PATH,
                session.exists(new PathRef(CONTAINER_PATH)));
        DocumentModel root = session.getDocument(new PathRef(CONTAINER_PATH));
        assertEquals("/", session.getParentDocument(root.getRef()).getPathAsString());
    }

    @Test
    public void testDiffLandsInTheDatedFolderUnderChangeDiff() throws Exception {
        String sourceId = jdoeEditsAFile("located");

        List<DocumentModel> diffs = diffsForSource(session, sourceId);
        assertEquals(1, diffs.size());
        DocumentModel diff = diffs.get(0);
        Calendar date = (Calendar) diff.getPropertyValue(BlobAuditConstants.XP_DATE);
        String expectedParent = String.format("%s/%tY/%tm/%td", CONTAINER_PATH, date, date, date);
        assertEquals(expectedParent, session.getParentDocument(diff.getRef()).getPathAsString());

        // every BlobDiff of the repository lives under the container
        assertEquals(allDiffs(session).size(),
                session.query("SELECT * FROM BlobDiff WHERE ecm:path STARTSWITH '" + CONTAINER_PATH + "'").size());
    }

    @Test
    public void testSingleContainerAtRoot() {
        blobDiffService.getOrCreateContainer(session, new Date());
        blobDiffService.getOrCreateContainer(session, new Date());
        DocumentModelList containers = session.query("SELECT * FROM Document WHERE ecm:parentId = '"
                + session.getRootDocument().getId() + "' AND ecm:name LIKE '" + BlobAuditConstants.CONTAINER_NAME
                + "%'");
        assertEquals(1, containers.size());
        assertEquals(BlobAuditConstants.CONTAINER_NAME, containers.get(0).getName());
    }

    /** Two blob xpaths saved at once: same source, same time, must still be two documents. */
    @Test
    public void testSameSourceAndTimeGetDistinctNames() throws Exception {
        DocumentModel source = createFile(session, "two-blobs", textBlob("x", "text/plain", "x.txt"));
        Date now = new Date();
        DocumentModel first = blobDiffService.createDiffDocument(session, source, "file:content", null, null,
                "jdoe", now, null, BlobAuditConstants.STATUS_OK, UUID.randomUUID().toString());
        DocumentModel second = blobDiffService.createDiffDocument(session, source, "files:files/0/file", null,
                null, "jdoe", now, null, BlobAuditConstants.STATUS_OK, UUID.randomUUID().toString());
        session.save();

        assertNotEquals(first.getName(), second.getName());
        assertTrue(first.getName().contains(
                (String) first.getPropertyValue(BlobAuditConstants.XP_CORRELATION_ID)));
        assertEquals(2, diffsForSource(session, source.getId()).size());
    }

    /**
     * Parallel works on the same day: whatever the interleaving, there is a single root container
     * and every dated folder lives under it, hence inherits its restricted ACL.
     */
    @Test
    public void testConcurrentContainerCreationStaysUnderASingleRoot() throws Exception {
        txFeature.nextTransaction();
        Date date = date(2031, Calendar.MARCH, 14);
        String repo = session.getRepositoryName();
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                String[] path = new String[1];
                TransactionHelper.runInTransaction(() -> CoreInstance.doPrivileged(repo,
                        (CoreSession s) -> { path[0] = blobDiffService.getOrCreateContainer(s, date).getPathAsString(); }));
                return path[0];
            }));
        }
        start.countDown();
        for (Future<String> future : futures) {
            assertTrue(future.get(30, TimeUnit.SECONDS).startsWith(CONTAINER_PATH + "/2031/03/14"));
        }
        executor.shutdown();
        txFeature.nextTransaction();

        DocumentModelList roots = session.query("SELECT * FROM Document WHERE ecm:parentId = '"
                + session.getRootDocument().getId() + "' AND ecm:name LIKE '" + BlobAuditConstants.CONTAINER_NAME
                + "%'");
        assertEquals(1, roots.size());
    }

    /* --------------------------------------------------------------- security */

    /** The decisive test: a user with Everything on the root sees nothing of the diffs. */
    @Test
    public void testNonAdministratorCannotSeeDiffs() throws Exception {
        String sourceId = jdoeEditsAFile("secret");
        List<DocumentModel> adminView = diffsForSource(session, sourceId);
        assertEquals("the administrator must see the diff", 1, adminView.size());
        DocumentModel diff = adminView.get(0);

        try (CloseableCoreSession jdoe = coreFeature.openCoreSession("jdoe")) {
            assertTrue(jdoe.query("SELECT * FROM BlobDiff").isEmpty());
            assertTrue(diffsForSource(jdoe, sourceId).isEmpty());
            assertFalse(jdoe.hasPermission(diff.getRef(), SecurityConstants.READ));
            try {
                jdoe.getDocument(diff.getRef());
                fail("jdoe must not read a BlobDiff");
            } catch (DocumentSecurityException e) {
                // expected
            }
            try {
                jdoe.getDocument(new PathRef(CONTAINER_PATH));
                fail("jdoe must not read the container");
            } catch (DocumentSecurityException e) {
                // expected
            }
            // and therefore cannot reach the diff blob either
            assertFalse(jdoe.hasPermission(diff.getRef(), SecurityConstants.READ_PROPERTIES));
            // nor delete anything
            assertFalse(jdoe.hasPermission(diff.getRef(), SecurityConstants.REMOVE));
        }
    }

    /** A member of the auditors group, not the built-in Administrator, can read and delete. */
    @Test
    public void testAuditorsGroupMemberCanReadAndDelete() throws Exception {
        String sourceId = jdoeEditsAFile("audited");
        String group = blobDiffService.getConfig().getAuditorsGroup();
        NuxeoPrincipal auditor = new UserPrincipal("auditor", List.of(group), false, false);
        try (CloseableCoreSession s = coreFeature.openCoreSession(auditor)) {
            List<DocumentModel> diffs = diffsForSource(s, sourceId);
            assertEquals(1, diffs.size());
            assertTrue(s.hasPermission(diffs.get(0).getRef(), SecurityConstants.REMOVE));
            s.removeDocument(diffs.get(0).getRef());
            s.save();
        }
        txFeature.nextTransaction();
        assertTrue(diffsForSource(session, sourceId).isEmpty());
    }

    /** A tampered ACL (older version, manual change) is restored by the next initialisation. */
    @Test
    public void testTamperedAclIsRepaired() {
        DocumentModel root = blobDiffService.ensureRootContainer(session);
        ACP open = new ACPImpl();
        ACL acl = new ACLImpl(ACL.LOCAL_ACL);
        acl.add(new ACE(SecurityConstants.EVERYONE, SecurityConstants.READ, true));
        open.addACL(acl);
        session.setACP(root.getRef(), open, true);
        session.save();

        assertTrue("a tampered ACL must be reported as repaired", blobDiffService.repairSecurity(session, root));
        assertFalse("a correct ACL must be left untouched", blobDiffService.repairSecurity(session, root));

        ACL local = session.getACP(root.getRef()).getACL(ACL.LOCAL_ACL);
        assertTrue(java.util.Arrays.stream(local.getACEs())
                                   .noneMatch(ace -> SecurityConstants.EVERYONE.equals(ace.getUsername())
                                           && ace.isGranted()));
    }

    /* ------------------------------------------------------- deleted source */

    /** Source removed before the work ran: an error BlobDiff is recorded, the work does not fail. */
    @Test
    public void testDeletedSourceProducesAnErrorDiff() {
        txFeature.nextTransaction();
        String missingId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        BlobDiffWork work = new BlobDiffWork(session.getRepositoryName(), missingId, "file:content", "default",
                null, "old.txt", "text/plain", null, 0, "default", null, "new.txt", "text/plain", null, 0, "jdoe",
                System.currentTimeMillis(), correlationId);
        Framework.getService(WorkManager.class).schedule(work);
        txFeature.nextTransaction();

        List<DocumentModel> diffs = diffsForSource(session, missingId);
        assertEquals(1, diffs.size());
        DocumentModel diff = diffs.get(0);
        assertEquals(BlobAuditConstants.STATUS_ERROR, diff.getPropertyValue(BlobAuditConstants.XP_STATUS));
        assertEquals(BlobAuditConstants.SUMMARY_SOURCE_MISSING, diff.getPropertyValue(BlobAuditConstants.XP_SUMMARY));
        assertEquals(correlationId, diff.getPropertyValue(BlobAuditConstants.XP_CORRELATION_ID));
        assertTrue(diff.getPathAsString().startsWith(CONTAINER_PATH + "/"));
    }
}

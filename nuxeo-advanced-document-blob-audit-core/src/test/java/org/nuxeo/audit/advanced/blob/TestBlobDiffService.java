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
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.cells;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.textBlob;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.xlsx;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

/**
 * Runtime tests for {@link BlobDiffService}: extractor selection, guardrails, container layout and
 * security, and persistence of the {@code BlobDiff} document.
 *
 * @since 1.0
 */
@RunWith(FeaturesRunner.class)
@Features(BlobAuditFeature.class)
public class TestBlobDiffService {

    @Inject
    protected CoreSession session;

    @Inject
    protected BlobDiffService blobDiffService;

    @Inject
    protected TransactionalFeature txFeature;

    /* ---------------------------------------------------------------- service */

    @Test
    public void testServiceIsRegistered() {
        assertNotNull(blobDiffService);
        assertTrue("test config must enable the feature", blobDiffService.getConfig().isEnabled());
    }

    @Test
    public void testSpreadsheetExtractorIsSelected() throws Exception {
        DiffableContent content = blobDiffService.extract(xlsx("Sheet1", cells("A1", "hello"), "f.xlsx"));

        assertNotNull(content);
        assertTrue("a spreadsheet must produce keyed content", content.keyed());
    }

    @Test
    public void testPlainTextExtractorIsSelected() throws Exception {
        DiffableContent content = blobDiffService.extract(textBlob("a\nb", "text/plain", "f.txt"));

        assertNotNull(content);
        assertFalse("text must produce positional content", content.keyed());
    }

    /** No extractor means no diff, and a {@code null} result the caller must handle. */
    @Test
    public void testUnsupportedMimeTypeYieldsNoExtractor() throws Exception {
        Blob blob = textBlob("binary-ish", "application/x-unknown-binary", "f.bin");

        assertNull(blobDiffService.extract(blob));
        assertNull(blobDiffService.diff(blob, blob));
        assertFalse(blobDiffService.isDiffable("File", "file:content", blob));
    }

    /** Extraction failures must be swallowed, never propagated to the listener or the work. */
    @Test
    public void testCorruptedSpreadsheetIsHandledGracefully() throws Exception {
        Blob corrupted = BlobAuditTestHelper.blob("not a zip at all".getBytes(),
                BlobAuditTestHelper.XLSX_MIME, "corrupt.xlsx");

        assertNull("a corrupted blob must not throw", blobDiffService.extract(corrupted));
    }

    /* ------------------------------------------------------------- guardrails */

    @Test
    @Deploy("nuxeo-advanced-document-blob-audit-core:blobaudit-test-smallblob-config.xml")
    public void testBlobLargerThanMaxSizeIsNotDiffable() throws Exception {
        // this override caps maxBlobSize at 512 bytes
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 2048) {
            sb.append("padding padding padding\n");
        }

        assertFalse(blobDiffService.isDiffable("File", "file:content",
                textBlob(sb.toString(), "text/plain", "big.txt")));
        assertTrue("a small blob must still pass",
                blobDiffService.isDiffable("File", "file:content", textBlob("tiny", "text/plain", "small.txt")));
    }

    @Test
    public void testXPathOutsideTheConfiguredListIsNotDiffable() throws Exception {
        Blob blob = textBlob("hello", "text/plain", "f.txt");

        assertTrue(blobDiffService.isDiffable("File", "file:content", blob));
        assertFalse(blobDiffService.isDiffable("File", "files:files/0/file", blob));
    }

    @Test
    public void testNullBlobIsNotDiffable() {
        assertFalse(blobDiffService.isDiffable("File", "file:content", null));
    }

    /* -------------------------------------------------------------- container */

    @Test
    public void testContainerIsPartitionedByDate() {
        DocumentModel container = blobDiffService.getOrCreateContainer(session, date(2026, Calendar.SEPTEMBER, 25));

        assertEquals("/" + BlobAuditConstants.CONTAINER_NAME + "/2026/09/25", container.getPathAsString());
        assertTrue(session.exists(new PathRef("/" + BlobAuditConstants.CONTAINER_NAME)));
    }

    /** Two diffs on the same day must land in the same folder, not create duplicates. */
    @Test
    public void testContainerCreationIsIdempotent() {
        Date date = date(2026, Calendar.SEPTEMBER, 25);

        DocumentModel first = blobDiffService.getOrCreateContainer(session, date);
        DocumentModel second = blobDiffService.getOrCreateContainer(session, date);

        assertEquals(first.getId(), second.getId());
    }

    @Test
    public void testDifferentDaysGetDifferentFolders() {
        DocumentModel sept = blobDiffService.getOrCreateContainer(session, date(2026, Calendar.SEPTEMBER, 25));
        DocumentModel oct = blobDiffService.getOrCreateContainer(session, date(2026, Calendar.OCTOBER, 1));

        assertEquals("/change-diff/2026/09/25", sept.getPathAsString());
        assertEquals("/change-diff/2026/10/01", oct.getPathAsString());
    }

    /**
     * The whole point of using a dedicated document rather than a facet on the source: the diffs get
     * their own, stricter ACL. Inheritance must be blocked, otherwise anyone able to read the source
     * document would read every past version of its content.
     */
    @Test
    public void testRootContainerBlocksInheritanceAndRestrictsRead() {
        blobDiffService.getOrCreateContainer(session, new Date());
        DocumentModel root = session.getDocument(new PathRef("/" + BlobAuditConstants.CONTAINER_NAME));

        ACP acp = session.getACP(root.getRef());
        ACL localAcl = acp.getACL(ACL.LOCAL_ACL);

        assertNotNull("a local ACL must be set on the diff container", localAcl);
        assertTrue("inheritance must be blocked",
                Arrays.stream(localAcl.getACEs())
                      .anyMatch(ace -> SecurityConstants.EVERYONE.equals(ace.getUsername())
                              && SecurityConstants.EVERYTHING.equals(ace.getPermission()) && !ace.isGranted()));
        String group = blobDiffService.getConfig().getAuditorsGroup();
        for (String permission : new String[] { SecurityConstants.READ, SecurityConstants.REMOVE,
                SecurityConstants.REMOVE_CHILDREN }) {
            assertTrue("auditors must be granted " + permission,
                    Arrays.stream(localAcl.getACEs())
                          .anyMatch(ace -> group.equals(ace.getUsername()) && permission.equals(ace.getPermission())
                                  && ace.isGranted()));
        }
    }

    @Test
    public void testContainerIsHiddenFromNavigation() {
        blobDiffService.getOrCreateContainer(session, new Date());
        DocumentModel root = session.getDocument(new PathRef("/" + BlobAuditConstants.CONTAINER_NAME));

        assertTrue(root.hasFacet("HiddenInNavigation"));
    }

    /* ------------------------------------------------------------ persistence */

    @Test
    public void testCreateDiffDocumentStoresSummaryAndBlob() throws Exception {
        DocumentModel source = session.createDocument(session.createDocumentModel("/", "source", "File"));
        session.save();

        Blob before = xlsx("Sheet1", cells("A1", "100"), "before.xlsx");
        Blob after = xlsx("Sheet1", cells("A1", "120"), "after.xlsx");
        DiffResult result = blobDiffService.diff(before, after);
        assertEquals(1, result.changed());

        DocumentModel diffDoc = blobDiffService.createDiffDocument(session, source, "file:content", before, after,
                "jdoe", new Date(), result, BlobAuditConstants.STATUS_OK, "corr-42");
        session.save();

        assertEquals(BlobAuditConstants.DIFF_DOCTYPE, diffDoc.getType());
        assertEquals(source.getId(), diffDoc.getPropertyValue(BlobAuditConstants.XP_SOURCE_ID));
        assertEquals("file:content", diffDoc.getPropertyValue(BlobAuditConstants.XP_XPATH));
        assertEquals("jdoe", diffDoc.getPropertyValue(BlobAuditConstants.XP_USER));
        assertEquals("corr-42", diffDoc.getPropertyValue(BlobAuditConstants.XP_CORRELATION_ID));
        assertEquals(BlobAuditConstants.STATUS_OK, diffDoc.getPropertyValue(BlobAuditConstants.XP_STATUS));
        assertEquals("1 modification", diffDoc.getPropertyValue(BlobAuditConstants.XP_SUMMARY));
        assertEquals(Long.valueOf(1), diffDoc.getPropertyValue(BlobAuditConstants.XP_CHANGED));
        assertEquals(Long.valueOf(0), diffDoc.getPropertyValue(BlobAuditConstants.XP_ADDED));

        // the diff itself is a blob, deliberately kept out of the document record and full-text
        Blob diffBlob = (Blob) diffDoc.getPropertyValue(BlobAuditConstants.XP_DIFF_BLOB);
        assertNotNull(diffBlob);
        assertTrue(diffBlob.getString().contains("Sheet1!A1"));

        assertTrue("diffs must be hidden", diffDoc.hasFacet("HiddenInNavigation"));
        assertTrue(diffDoc.getPathAsString().startsWith("/" + BlobAuditConstants.CONTAINER_NAME + "/"));
    }

    /** A skipped diff must still be traced, with a status explaining why nothing was compared. */
    @Test
    public void testCreateDiffDocumentWithSkippedStatusHasNoBlob() {
        DocumentModel source = session.createDocument(session.createDocumentModel("/", "source-skipped", "File"));
        session.save();

        DocumentModel diffDoc = blobDiffService.createDiffDocument(session, source, "file:content", null, null,
                "jdoe", new Date(), null, BlobAuditConstants.STATUS_SKIPPED_TYPE, "corr-43");
        session.save();

        assertEquals(BlobAuditConstants.STATUS_SKIPPED_TYPE,
                diffDoc.getPropertyValue(BlobAuditConstants.XP_STATUS));
        assertNull(diffDoc.getPropertyValue(BlobAuditConstants.XP_DIFF_BLOB));
    }

    /** An empty diff is recorded, but carries no blob: there is nothing to store. */
    @Test
    public void testIdenticalContentProducesNoDiffBlob() throws Exception {
        DocumentModel source = session.createDocument(session.createDocumentModel("/", "source-same", "File"));
        session.save();

        Blob blob = xlsx("Sheet1", cells("A1", "same"), "f.xlsx");
        DiffResult result = blobDiffService.diff(blob, blob);
        assertTrue(result.isEmpty());

        DocumentModel diffDoc = blobDiffService.createDiffDocument(session, source, "file:content", blob, blob,
                "jdoe", new Date(), result, BlobAuditConstants.STATUS_OK, "corr-44");
        session.save();

        assertNull(diffDoc.getPropertyValue(BlobAuditConstants.XP_DIFF_BLOB));
        assertEquals("No textual change detected", diffDoc.getPropertyValue(BlobAuditConstants.XP_SUMMARY));
    }

    /** Digests are stored as evidence and are what lets a rename be told apart from an edit. */
    @Test
    public void testDigestsArePersisted() throws Exception {
        DocumentModel source = session.createDocumentModel("/", "source-digest", "File");
        source.setPropertyValue("file:content", (Serializable) xlsx("Sheet1", cells("A1", "v1"), "v1.xlsx"));
        source = session.createDocument(source);
        session.save();
        txFeature.nextTransaction();

        Blob stored = (Blob) session.getDocument(source.getRef()).getPropertyValue("file:content");
        assertNotNull("a stored blob must expose a digest", stored.getDigest());

        DocumentModel diffDoc = blobDiffService.createDiffDocument(session, source, "file:content", stored, stored,
                "jdoe", new Date(), null, BlobAuditConstants.STATUS_OK, "corr-45");
        session.save();

        assertEquals(stored.getDigest(), diffDoc.getPropertyValue(BlobAuditConstants.XP_OLD_DIGEST));
        assertEquals(stored.getDigest(), diffDoc.getPropertyValue(BlobAuditConstants.XP_NEW_DIGEST));
    }

    protected Date date(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month, day, 10, 0, 0);
        return calendar.getTime();
    }
}

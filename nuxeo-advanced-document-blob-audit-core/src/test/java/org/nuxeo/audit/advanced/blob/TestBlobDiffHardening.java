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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.textBlob;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.audit.advanced.blob.work.BlobDiffWork;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.schema.FacetNames;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.core.schema.DocumentType;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

/**
 * Hardening of the asynchronous pipeline: fulltext exclusion, dedicated work queue, deterministic
 * work id, idempotent work and bounded version lookup.
 *
 * @since 2025.1
 */
@RunWith(FeaturesRunner.class)
@Features(BlobAuditFeature.class)
public class TestBlobDiffHardening {

    @Inject
    protected CoreSession session;

    @Inject
    protected TransactionalFeature txFeature;

    /* ----------------------------------------------------------------- fulltext */

    /**
     * bdiff:diff holds business content extracted from the source. Without this facet the platform
     * copies it into the fulltext index and into Elasticsearch, defeating the restricted container.
     */
    @Test
    public void testBlobDiffIsExcludedFromFulltext() {
        DocumentType type = Framework.getService(SchemaManager.class).getDocumentType(BlobAuditConstants.DIFF_DOCTYPE);
        assertTrue("BlobDiff must carry the NotFulltextIndexable facet",
                type.getFacets().contains(FacetNames.NOT_FULLTEXT_INDEXABLE));
    }

    /* -------------------------------------------------------------------- queue */

    /** The blobDiff category must not fall back to the shared "default" queue. */
    @Test
    public void testBlobDiffHasItsOwnWorkQueue() {
        String queueId = Framework.getService(WorkManager.class).getCategoryQueueId(BlobAuditConstants.WORK_CATEGORY);
        assertEquals(BlobAuditConstants.WORK_CATEGORY, queueId);
    }

    /* ------------------------------------------------------------------ work id */

    protected BlobDiffWork work(String oldDigest, String newDigest, VersionContext versions) {
        return new BlobDiffWork("test", "doc-1", "Title", "file:content", "p", "oldKey", "old.txt", "text/plain",
                oldDigest, 10, "p", "newKey", "new.txt", "text/plain", newDigest, 12, "jdoe",
                System.currentTimeMillis(), UUID.randomUUID().toString(), versions);
    }

    /**
     * The id is what IF_NOT_RUNNING_OR_SCHEDULED deduplicates on. Two works describing the same
     * version pair must collide, whatever their (random) correlation id.
     */
    @Test
    public void testWorkIdIsDeterministicOnTheVersionPair() {
        VersionContext versions = new VersionContext("v1", "1.0", "v2", "2.0", "series");
        assertEquals(work("d1", "d2", versions).getId(), work("d1", "d2", versions).getId());
        // A different correlation id must not create a new work identity
        assertEquals(work("other", "digests", versions).getId(), work("d1", "d2", versions).getId());
    }

    /** Without a version pair, the digest pair is the identity. */
    @Test
    public void testWorkIdFallsBackOnDigests() {
        assertEquals(work("d1", "d2", VersionContext.NONE).getId(), work("d1", "d2", VersionContext.NONE).getId());
        assertNotEquals(work("d1", "d2", VersionContext.NONE).getId(),
                work("d1", "d3", VersionContext.NONE).getId());
    }

    @Test
    public void testDifferentVersionPairsGiveDifferentWorkIds() {
        assertNotEquals(work("d1", "d2", new VersionContext("v1", "1.0", "v2", "2.0", "s")).getId(),
                work("d1", "d2", new VersionContext("v2", "2.0", "v3", "3.0", "s")).getId());
    }

    /* -------------------------------------------------------------- idempotence */

    protected DocumentModel newVersionWithBlob(DocumentModel doc, String content) throws Exception {
        doc = session.getDocument(doc.getRef());
        doc.setPropertyValue("file:content", (Serializable) textBlob(content, "text/plain", "notes.txt"));
        doc = session.saveDocument(doc);
        session.save();
        DocumentRef ref = session.checkIn(doc.getRef(), VersioningOption.MINOR, "test");
        session.save();
        txFeature.nextTransaction();
        return session.getDocument(ref);
    }

    /**
     * The WorkManager is at-least-once: running the very same work twice must not leave two
     * indistinguishable BlobDiff documents behind.
     */
    @Test
    public void testRunningTheSameWorkTwiceCreatesASingleDiff() throws Exception {
        DocumentModel doc = session.createDocumentModel("/", "idempotence", "File");
        doc.setPropertyValue("file:content", (Serializable) textBlob("line 1", "text/plain", "notes.txt"));
        doc = session.createDocument(doc);
        session.save();
        newVersionWithBlob(doc, "line 1");
        newVersionWithBlob(doc, "line 1 modified");

        List<DocumentModel> diffs = session.query(
                "SELECT * FROM BlobDiff WHERE bdiff:sourceId = '" + doc.getId() + "'");
        assertEquals(1, diffs.size());
        String correlationId = (String) diffs.get(0).getPropertyValue(BlobAuditConstants.XP_CORRELATION_ID);

        // Replay the exact same work, as a redelivery would
        Blob old = textBlob("line 1", "text/plain", "notes.txt");
        Blob current = textBlob("line 1 modified", "text/plain", "notes.txt");
        BlobDiffWork replay = new BlobDiffWork(session.getRepositoryName(), doc.getId(), doc.getTitle(),
                "file:content", "test", "oldKey", "notes.txt", "text/plain", old.getDigest(), old.getLength(),
                "test", "newKey", "notes.txt", "text/plain", current.getDigest(), current.getLength(), "Administrator",
                System.currentTimeMillis(), correlationId, VersionContext.NONE);
        Framework.getService(WorkManager.class).schedule(replay, true);
        txFeature.nextTransaction();

        assertEquals("The replayed work must not duplicate the BlobDiff", 1, session
                .query("SELECT * FROM BlobDiff WHERE bdiff:sourceId = '" + doc.getId() + "'").size());
    }

    /* ----------------------------------------------------- bounded version query */

    /**
     * The listener must stay cheap whatever the length of the version history: only the two most
     * recent versions are ever loaded, and the diff always compares the last pair.
     */
    @Test
    public void testManyVersionsStillDiffTheLastPairOnly() throws Exception {
        DocumentModel doc = session.createDocumentModel("/", "many-versions", "File");
        doc.setPropertyValue("file:content", (Serializable) textBlob("v0", "text/plain", "notes.txt"));
        doc = session.createDocument(doc);
        session.save();
        for (int i = 1; i <= 6; i++) {
            newVersionWithBlob(doc, "v" + i);
        }

        List<DocumentModel> diffs = session.query("SELECT * FROM BlobDiff WHERE bdiff:sourceId = '" + doc.getId()
                + "' ORDER BY bdiff:date");
        // 6 versions, so 5 consecutive pairs: the first version has no predecessor to compare with,
        // and no cross pair is ever produced.
        assertEquals(5, diffs.size());
        DocumentModel last = diffs.get(diffs.size() - 1);
        assertEquals(BlobAuditConstants.STATUS_OK, last.getPropertyValue(BlobAuditConstants.XP_STATUS));
    }
}

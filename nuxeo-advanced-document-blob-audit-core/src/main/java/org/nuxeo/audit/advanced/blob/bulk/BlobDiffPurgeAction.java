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
package org.nuxeo.audit.advanced.blob.bulk;

import static org.nuxeo.ecm.core.bulk.BulkServiceImpl.STATUS_STREAM;
import static org.nuxeo.lib.stream.computation.AbstractComputation.INPUT_1;
import static org.nuxeo.lib.stream.computation.AbstractComputation.OUTPUT_1;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.audit.advanced.blob.BlobAuditConstants;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentNotFoundException;
import org.nuxeo.ecm.core.api.DocumentSecurityException;
import org.nuxeo.ecm.core.bulk.action.computation.AbstractBulkComputation;
import org.nuxeo.ecm.core.bulk.message.BulkStatus;
import org.nuxeo.lib.stream.computation.ComputationContext;
import org.nuxeo.lib.stream.computation.Topology;
import org.nuxeo.runtime.stream.StreamProcessorTopology;

/**
 * Retention purge of {@code BlobDiff} documents, on the Bulk Action Framework.
 * <p>
 * Replaces the unbounded {@code while (true) { ...; nextTransaction(); }} loop that used to run
 * inside the {@code BlobDiff.Purge} Automation operation, while the {@code @Context CoreSession}
 * was bound to the HTTP transaction: an HTTP timeout was guaranteed at volume.
 * <p>
 * <b>The action is deliberately not {@code httpEnabled}.</b> It is unreachable through
 * {@code Bulk.RunAction} and through the {@code /search/bulk/{action}} REST endpoint, for anyone
 * but an administrator. The only supported entry point is the {@code BlobDiff.Purge} operation,
 * which enforces {@code BlobDiffAccess.checkAuditor} and builds the query itself. Turning
 * {@code httpEnabled} on would publish a delete-by-NXQL API to every authenticated user; keeping it
 * off while routing the UI through {@code Bulk.RunAction} would reserve the purge to
 * administrators, which takes the feature away from the auditors it was built for.
 *
 * @since 2025.1
 */
public class BlobDiffPurgeAction implements StreamProcessorTopology {

    public static final String ACTION_NAME = "blobDiffPurge";

    public static final String ACTION_FULL_NAME = "bulk/" + ACTION_NAME;

    /** Key of the exact deletion count in {@link BulkStatus#getResult()}. */
    public static final String RESULT_DELETED = "deleted";

    @Override
    public Topology getTopology(Map<String, String> options) {
        return Topology.builder()
                       .addComputation(BlobDiffPurgeComputation::new,
                               List.of(INPUT_1 + ":" + ACTION_FULL_NAME, OUTPUT_1 + ":" + STATUS_STREAM))
                       .build();
    }

    public static class BlobDiffPurgeComputation extends AbstractBulkComputation {

        private static final Logger log = LogManager.getLogger(BlobDiffPurgeComputation.class);

        protected long deleted;

        protected long skipped;

        public BlobDiffPurgeComputation() {
            super(ACTION_FULL_NAME);
        }

        @Override
        public void startBucket(String bucketKey) {
            super.startBucket(bucketKey);
            deleted = 0;
            skipped = 0;
        }

        /**
         * Only {@code BlobDiff} documents are removed, whatever the query the command was built
         * with. The type check is what makes this action harmless by construction: it can never be
         * turned into a generic delete-by-NXQL, not even by an administrator submitting it by hand.
         * <p>
         * Removal goes through {@code session.removeDocument}, not through the core
         * {@code deletion} action: events, ACLs and blob dereferencing all matter here, and
         * {@code BlobDiff} documents are flat leaves, so there is no parent/child ordering to worry
         * about.
         */
        @Override
        protected void compute(CoreSession session, List<String> ids, Map<String, Serializable> properties) {
            for (DocumentModel doc : loadDocuments(session, ids)) {
                if (!BlobAuditConstants.DIFF_DOCTYPE.equals(doc.getType())) {
                    log.warn("Skipping {} of type {}: the purge only removes {} documents",
                            doc::getPathAsString, doc::getType, () -> BlobAuditConstants.DIFF_DOCTYPE);
                    skipped++;
                    continue;
                }
                try {
                    session.removeDocument(doc.getRef());
                    deleted++;
                } catch (DocumentNotFoundException e) {
                    // Already removed, by a concurrent batch or a manual deletion: nothing to do.
                    log.debug("BlobDiff {} was already removed", doc.getId());
                } catch (DocumentSecurityException e) {
                    log.debug("Cannot remove BlobDiff {}: {}", doc.getId(), e.getMessage());
                    skipped++;
                }
            }
        }

        /**
         * {@code processed} counts the ids handed to the computation, not the documents actually
         * removed. The purge dialog reports "N content diffs purged", so the exact count is
         * published in the status result, where {@code BulkStatus} sums the numeric values of every
         * bucket.
         */
        @Override
        public void endBucket(ComputationContext context, BulkStatus delta) {
            delta.setSkipCount(skipped);
            delta.mergeResult(Map.of(RESULT_DELETED, Long.valueOf(deleted)));
            super.endBucket(context, delta);
        }
    }
}

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
package org.nuxeo.audit.advanced.blob.operations;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.audit.advanced.blob.BlobAuditConstants;
import org.nuxeo.audit.advanced.blob.BlobDiffAccess;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.runtime.transaction.TransactionHelper;

/**
 * Retention purge: permanently deletes every {@code BlobDiff} dated strictly before {@code before},
 * optionally restricted to one status, then removes the dated folders left empty.
 * <p>
 * Works by batches of {@value #BATCH_SIZE}, committing between batches so a large purge neither
 * holds a huge transaction nor times out. Returns {@code {"deleted": n, "folders": m}}.
 *
 * @since 1.2
 */
@Operation(id = BlobDiffPurgeOp.ID, category = "Audit", label = "BlobDiff: Purge",
        description = "Permanently deletes BlobDiff documents older than a date. Reserved to administrators and auditors.")
public class BlobDiffPurgeOp {

    public static final String ID = "BlobDiff.Purge";

    public static final int BATCH_SIZE = 100;

    private static final Logger log = LogManager.getLogger(BlobDiffPurgeOp.class);

    @Context
    protected CoreSession session;

    @Param(name = "before")
    protected Calendar before;

    @Param(name = "status", required = false)
    protected String status;

    @OperationMethod
    public Blob run() {
        BlobDiffAccess.checkAuditor(session.getPrincipal());
        String nxql = query();
        long deleted = 0;
        while (true) {
            List<DocumentModel> batch = session.query(nxql, null, BATCH_SIZE, 0, false);
            if (batch.isEmpty()) {
                break;
            }
            session.removeDocuments(batch.stream().map(DocumentModel::getRef).toArray(DocumentRef[]::new));
            session.save();
            deleted += batch.size();
            nextTransaction();
        }
        long folders = removeEmptyFolders();
        session.save();
        log.info("BlobDiff purge by {}: {} diffs and {} empty folders removed (before {}, status {})",
                session.getPrincipal().getName(), deleted, folders, before.getTime(), status);
        return Blobs.createJSONBlob("{\"deleted\":" + deleted + ",\"folders\":" + folders + "}");
    }

    protected String query() {
        String day = new SimpleDateFormat("yyyy-MM-dd").format(before.getTime());
        StringBuilder sb = new StringBuilder("SELECT * FROM ").append(BlobAuditConstants.DIFF_DOCTYPE)
                                                              .append(" WHERE ")
                                                              .append(BlobAuditConstants.XP_DATE)
                                                              .append(" < DATE '")
                                                              .append(day)
                                                              .append("'");
        if (status != null && !status.isBlank()) {
            sb.append(" AND ").append(BlobAuditConstants.XP_STATUS).append(" = ").append(NXQL.escapeString(status));
        }
        return sb.toString();
    }

    protected void nextTransaction() {
        if (TransactionHelper.isTransactionActive()) {
            TransactionHelper.commitOrRollbackTransaction();
            TransactionHelper.startTransaction();
        }
    }

    /** Removes empty day, then month, then year folders under the container. */
    protected long removeEmptyFolders() {
        PathRef rootRef = new PathRef("/" + BlobAuditConstants.CONTAINER_NAME);
        if (!session.exists(rootRef)) {
            return 0;
        }
        return removeEmptyChildren(rootRef, 3);
    }

    protected long removeEmptyChildren(DocumentRef parent, int depth) {
        long removed = 0;
        for (DocumentModel child : session.getChildren(parent, BlobAuditConstants.CONTAINER_TYPE)) {
            if (depth > 1) {
                removed += removeEmptyChildren(child.getRef(), depth - 1);
            }
            if (!session.hasChildren(child.getRef())) {
                session.removeDocument(child.getRef());
                removed++;
            }
        }
        return removed;
    }
}

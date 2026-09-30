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

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.audit.advanced.blob.BlobAuditConstants;
import org.nuxeo.audit.advanced.blob.BlobDiffAccess;
import org.nuxeo.audit.advanced.blob.bulk.BlobDiffPurgeAction;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.ConcurrentUpdateException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.bulk.BulkService;
import org.nuxeo.ecm.core.bulk.message.BulkCommand;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.runtime.api.Framework;

/**
 * Retention purge: schedules the permanent deletion of every {@code BlobDiff} dated strictly before
 * {@code before}, optionally restricted to one status.
 * <p>
 * <b>Asynchronous since 2025.3.</b> This operation used to delete by batches in an unbounded loop,
 * committing between batches while the {@code @Context CoreSession} was bound to the HTTP
 * transaction - an HTTP timeout was guaranteed at volume. It now submits a
 * {@link BlobDiffPurgeAction} bulk command and returns immediately with its id. Progress is read
 * through {@code BlobDiff.PurgeStatus}, the command can be stopped with {@code BlobDiff.PurgeAbort},
 * and the dated folders left empty are removed by {@code BlobDiff.CleanEmptyFolders}.
 * <p>
 * Returns {@code {"commandId": "...", "state": "SCHEDULED"}}. Callers written against the previous
 * synchronous {@code {"deleted": n, "folders": m}} payload must be adapted.
 * <p>
 * The action is {@code exclusive}: submitting a second purge while one is running raises a readable
 * error rather than letting two commands fight over the same documents.
 * <p>
 * The command is submitted under the calling user, not {@code system}: the deletion stays subject
 * to the ACL of {@code /change-diff}, like every other operation of this plugin. This operation is
 * also the <b>only</b> supported way in: the bulk action itself is not {@code httpEnabled}.
 *
 * @since 1.2
 */
@Operation(id = BlobDiffPurgeOp.ID, category = "Audit", label = "BlobDiff: Purge",
        description = "Schedules the permanent deletion of BlobDiff documents older than a date. "
                + "Asynchronous: returns a bulk command id, to be followed with BlobDiff.PurgeStatus. "
                + "Reserved to administrators and auditors.")
public class BlobDiffPurgeOp {

    public static final String ID = "BlobDiff.Purge";

    private static final Logger log = LogManager.getLogger(BlobDiffPurgeOp.class);

    /**
     * Boundary of the retention query, always in UTC - the same zone {@code BlobDiffComponent}
     * uses to build the dated containers. Formatting it in the JVM default zone would shift the
     * boundary by a day, and two nodes of a cluster in different zones would not purge the same
     * set for a single "before" parameter.
     */
    protected static final DateTimeFormatter BOUNDARY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                                                                                .withZone(ZoneOffset.UTC);

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
        String user = session.getPrincipal().getName();
        BulkCommand command = new BulkCommand.Builder(BlobDiffPurgeAction.ACTION_NAME, nxql, user).repository(
                session.getRepositoryName()).build();
        String commandId;
        try {
            // submit(), not submitTransactional(): the latter defers the real submission - and with
            // it the exclusivity check and the parameter validation - to beforeCompletion, so a
            // second concurrent purge would surface as a commit failure instead of the readable
            // error below, and the returned id would not be queryable until commit. This operation
            // writes nothing to the repository, so there is no work to undo if it does not commit.
            commandId = Framework.getService(BulkService.class).submit(command);
        } catch (ConcurrentUpdateException e) {
            throw new NuxeoException("A BlobDiff purge is already running on this repository", e);
        }
        log.info("BlobDiff purge scheduled by {}: command {} (before {}, status {})", user, commandId,
                before.getTime(), status);
        return Blobs.createJSONBlob("{\"commandId\":\"" + commandId + "\",\"state\":\"SCHEDULED\"}");
    }

    protected String query() {
        String day = BOUNDARY_FORMAT.format(before.toInstant());
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
}

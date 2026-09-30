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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.audit.advanced.blob.BlobAuditConstants;
import org.nuxeo.audit.advanced.blob.BlobDiffAccess;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.PathRef;

/**
 * Removes the dated {@code /change-diff/yyyy/MM/dd} folders left empty, day then month then year.
 * <p>
 * Split out of {@code BlobDiff.Purge} when the purge became asynchronous: the folders can only be
 * removed once the bulk command has finished emptying them. The Web UI calls this as soon as the
 * purge reaches {@code COMPLETED}; it is idempotent and can be run on its own.
 * <p>
 * <b>Why this one does not need the Bulk Action Framework.</b> The fan-out is bounded by the
 * calendar: the root holds a handful of years, a year holds twelve months, a month holds thirty-one
 * days. The longest list ever loaded is about thirty entries, whatever the number of diffs. It
 * still uses {@code getChildrenIterator} rather than {@code getChildren}, so that a container
 * polluted by concurrent-creation duplicates cannot materialise a large list either.
 *
 * @since 2025.3
 */
@Operation(id = BlobDiffCleanEmptyFoldersOp.ID, category = "Audit", label = "BlobDiff: Clean Empty Folders",
        description = "Removes the dated folders left empty under /change-diff. "
                + "Reserved to administrators and auditors.")
public class BlobDiffCleanEmptyFoldersOp {

    public static final String ID = "BlobDiff.CleanEmptyFolders";

    /** Depth of the dated hierarchy under the root container: year, month, day. */
    protected static final int DATED_DEPTH = 3;

    private static final Logger log = LogManager.getLogger(BlobDiffCleanEmptyFoldersOp.class);

    @Context
    protected CoreSession session;

    @OperationMethod
    public Blob run() {
        BlobDiffAccess.checkAuditor(session.getPrincipal());
        long folders = removeEmptyFolders();
        session.save();
        log.info("BlobDiff empty folder cleanup by {}: {} folders removed", session.getPrincipal().getName(),
                folders);
        return Blobs.createJSONBlob("{\"folders\":" + folders + "}");
    }

    protected long removeEmptyFolders() {
        PathRef rootRef = new PathRef("/" + BlobAuditConstants.CONTAINER_NAME);
        if (!session.exists(rootRef)) {
            return 0;
        }
        return removeEmptyChildren(rootRef, DATED_DEPTH);
    }

    protected long removeEmptyChildren(DocumentRef parent, int depth) {
        long removed = 0;
        for (DocumentModel child : session.getChildrenIterator(parent, BlobAuditConstants.CONTAINER_TYPE)) {
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

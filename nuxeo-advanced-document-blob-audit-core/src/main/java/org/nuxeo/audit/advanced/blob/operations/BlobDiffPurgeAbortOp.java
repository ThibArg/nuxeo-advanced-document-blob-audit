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
import org.nuxeo.audit.advanced.blob.BlobDiffAccess;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.bulk.BulkService;
import org.nuxeo.runtime.api.Framework;

/**
 * Stops a purge scheduled by {@code BlobDiff.Purge}.
 * <p>
 * Aborting leaves the purge <b>partially applied</b>: the diffs already removed are gone for good.
 * This is harmless - a purge is idempotent, running it again finishes the job - but the UI says so
 * before asking for confirmation.
 *
 * @since 2025.1
 */
@Operation(id = BlobDiffPurgeAbortOp.ID, category = "Audit", label = "BlobDiff: Abort Purge",
        description = "Stops a running purge. Already deleted diffs are not restored. "
                + "Reserved to administrators and auditors.")
public class BlobDiffPurgeAbortOp {

    public static final String ID = "BlobDiff.PurgeAbort";

    private static final Logger log = LogManager.getLogger(BlobDiffPurgeAbortOp.class);

    @Context
    protected CoreSession session;

    @Param(name = "commandId")
    protected String commandId;

    @OperationMethod
    public Blob run() {
        BlobDiffAccess.checkAuditor(session.getPrincipal());
        var status = Framework.getService(BulkService.class).abort(commandId);
        log.info("BlobDiff purge {} aborted by {}, {} diffs had already been deleted", commandId,
                session.getPrincipal().getName(), status.getProcessed());
        return Blobs.createJSONBlob(BlobDiffPurgeStatusJson.of(status));
    }
}

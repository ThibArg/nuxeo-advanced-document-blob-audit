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
 * Progress of a purge scheduled by {@code BlobDiff.Purge}.
 * <p>
 * Goes through an operation rather than the platform {@code GET /api/v1/bulk/{commandId}} endpoint
 * so that the auditors check applies here too, and so that the Web UI keeps talking to the server
 * through {@code nuxeo-operation} like the rest of this plugin.
 * <p>
 * An unknown or expired command id yields {@code state: UNKNOWN} rather than an error: that is also
 * what a still-uncommitted {@code submitTransactional} returns.
 *
 * @since 2025.1
 */
@Operation(id = BlobDiffPurgeStatusOp.ID, category = "Audit", label = "BlobDiff: Purge Status",
        description = "Returns the progress of a purge scheduled by BlobDiff.Purge. "
                + "Reserved to administrators and auditors.")
public class BlobDiffPurgeStatusOp {

    public static final String ID = "BlobDiff.PurgeStatus";

    @Context
    protected CoreSession session;

    @Param(name = "commandId")
    protected String commandId;

    @OperationMethod
    public Blob run() {
        BlobDiffAccess.checkAuditor(session.getPrincipal());
        return Blobs.createJSONBlob(
                BlobDiffPurgeStatusJson.of(Framework.getService(BulkService.class).getStatus(commandId)));
    }
}

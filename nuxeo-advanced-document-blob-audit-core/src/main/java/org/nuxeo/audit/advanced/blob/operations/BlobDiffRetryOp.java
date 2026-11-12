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

import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.CONTAINER_NAME;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.DIFF_DOCTYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_ERROR;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_STATUS;

import org.nuxeo.audit.advanced.blob.BlobDiffAccess;
import org.nuxeo.audit.advanced.blob.DiffRequest;
import org.nuxeo.audit.advanced.blob.work.BlobDiffWork;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.work.api.Work;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.runtime.api.Framework;

/**
 * Replays a {@code BlobDiff} in {@code error}, using the storage keys persisted on it. The new diff
 * keeps the original user, date and correlation id (so the audit entry still points to it) and
 * replaces the failed one once created.
 * <p>
 * A second retry is refused while the WorkManager still holds the first one. A replay lost with a
 * restarted node leaves the diff in error, so it can be retried again.
 *
 * @since 2025.1
 */
@Operation(id = BlobDiffRetryOp.ID, category = "Audit", label = "BlobDiff: Retry",
        description = "Schedules again the computation of a BlobDiff in error."
                + " Reserved to administrators and auditors.")
public class BlobDiffRetryOp {

    public static final String ID = "BlobDiff.Retry";

    /** Every {@code BlobDiff} the plugin creates lives under this path, and nothing else does. */
    protected static final String CONTAINER_PATH_PREFIX = "/" + CONTAINER_NAME + "/";

    @Context
    protected CoreSession session;

    @OperationMethod
    public DocumentModel run(DocumentModel input) {
        BlobDiffAccess.checkAuditor(session.getPrincipal());
        // Nothing but the id of the input is used: see checkProvenance.
        DocumentModel diff = reloadStored(input);
        checkProvenance(diff);
        if (!DIFF_DOCTYPE.equals(diff.getType())) {
            throw new NuxeoException("Not a BlobDiff document: " + diff.getId(), 400);
        }
        if (!STATUS_ERROR.equals(diff.getPropertyValue(XP_STATUS))) {
            throw new NuxeoException("Only a BlobDiff in error can be retried", 400);
        }
        // Read only now: a stored model under /change-diff is what makes the keys trustworthy.
        DiffRequest request = DiffRequest.readFrom(diff);
        if (request.oldBlob() == null || request.newBlob() == null) {
            throw new NuxeoException("This BlobDiff has no stored binary keys, it cannot be retried", 400);
        }
        BlobDiffWork work = new BlobDiffWork(request).withReplaceDiffId(diff.getId());
        WorkManager workManager = Framework.getService(WorkManager.class);
        if (isPending(workManager.getWorkState(work.getId()))) {
            throw new NuxeoException("A retry of this BlobDiff is already pending", 409);
        }
        workManager.schedule(work, true);
        return diff;
    }

    /**
     * Returns the document as stored, read with the caller's session (so Read is still enforced),
     * discarding whatever the input model holds in memory.
     *
     * @throws NuxeoException (400) when the input was never stored, so has no id to reload
     * @since 2025.1
     */
    protected DocumentModel reloadStored(DocumentModel input) {
        String id;
        try {
            id = input == null ? null : input.getId();
        } catch (UnsupportedOperationException e) {
            // SimpleDocumentModel, what a JSON document entity without uid becomes
            id = null;
        }
        if (id == null) {
            throw new NuxeoException("Not a stored BlobDiff", 400);
        }
        return session.getDocument(new IdRef(id));
    }

    /**
     * A {@code null} state is not proof that nothing runs: the stream WorkManager only knows states
     * when it stores them. Two replays are then harmless, though, as they run one after the other on
     * the same partition and the second finds the diff of the first.
     */
    protected static boolean isPending(Work.State state) {
        return state == Work.State.SCHEDULED || state == Work.State.RUNNING;
    }

    /**
     * Refuses anything that does not come from {@code /change-diff}. It must be given the model
     * {@link #reloadStored(DocumentModel)} returns, <b>never the input of the operation</b>, and run
     * before any other property of it is read.
     * <p>
     * The operation replays a diff from the provider id and the storage key persisted on the
     * document, and {@link org.nuxeo.audit.advanced.blob.work.BlobDiffWork} re-reads them through
     * {@code provider.readBlob(info)} in a <b>system session</b>. A blob provider is a store keyed
     * by an opaque string: {@code readBlob} enforces no document ACL whatsoever. The extracted
     * content then lands in {@code bdiff:diff} under {@code /change-diff}, where the caller can read
     * it.
     * <p>
     * The document type and the {@code bdiff:status} were the only checks, and both are attacker
     * controlled: {@code AbstractSession.createDocument} calls {@code parent.addChild(name, type)}
     * without consulting the allowed subtypes - that is a {@code TypeManager} concern, so a UI one.
     * An auditor, explicitly <b>not</b> an administrator, could therefore create a {@code BlobDiff}
     * in their own workspace, set {@code bdiff:status=error} plus the keys of their choice, and have
     * the platform decrypt any binary of the repository for them.
     * <p>
     * The path is the provenance: the plugin creates diffs nowhere else, and {@code /change-diff} is
     * ACL-restricted to the auditors group, so a document sitting there was put there by the plugin.
     * That holds of the <b>stored</b> document only. Over REST, a JSON document entity given as input
     * is loaded by uid, then gets the properties of the request applied in memory under
     * {@code doPrivileged} ({@code DocumentModelJsonReader#readEntity}): Read on a genuine diff keeps
     * its genuine path while the status and the keys are the caller's.
     *
     * @since 2025.1
     */
    protected void checkProvenance(DocumentModel diff) {
        String path = diff == null ? null : diff.getPathAsString();
        if (path == null || !path.startsWith(CONTAINER_PATH_PREFIX)) {
            throw new NuxeoException("Not a managed BlobDiff: " + path, 400);
        }
    }
}

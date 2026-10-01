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
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_CORRELATION_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_DATE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_MIMETYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_NEW_BLOB_KEY;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_NEW_BLOB_PROVIDER;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_NEW_DIGEST;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_NEW_FILENAME;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_NEW_LENGTH;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_NEW_VERSION_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_NEW_VERSION_LABEL;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_OLD_BLOB_KEY;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_OLD_BLOB_PROVIDER;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_OLD_DIGEST;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_OLD_FILENAME;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_OLD_LENGTH;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_OLD_MIMETYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_PREVIOUS_VERSION_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_PREVIOUS_VERSION_LABEL;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_SOURCE_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_SOURCE_REPO;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_STATUS;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_USER;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_VERSION_SERIES_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_XPATH;

import java.util.Calendar;

import org.nuxeo.audit.advanced.blob.BlobDiffAccess;
import org.nuxeo.audit.advanced.blob.VersionContext;
import org.nuxeo.audit.advanced.blob.work.BlobDiffWork;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.runtime.api.Framework;

/**
 * Replays a {@code BlobDiff} in {@code error}, using the storage keys persisted on it. The new diff
 * keeps the original user, date and correlation id (so the audit entry still points to it) and
 * replaces the failed one once created.
 *
 * @since 1.2
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
    public DocumentModel run(DocumentModel diff) {
        BlobDiffAccess.checkAuditor(session.getPrincipal());
        checkProvenance(diff);
        if (!DIFF_DOCTYPE.equals(diff.getType())) {
            throw new NuxeoException("Not a BlobDiff document: " + diff.getId(), 400);
        }
        if (!STATUS_ERROR.equals(diff.getPropertyValue(XP_STATUS))) {
            throw new NuxeoException("Only a BlobDiff in error can be retried", 400);
        }
        String oldKey = str(diff, XP_OLD_BLOB_KEY);
        String newKey = str(diff, XP_NEW_BLOB_KEY);
        String oldProvider = str(diff, XP_OLD_BLOB_PROVIDER);
        String newProvider = str(diff, XP_NEW_BLOB_PROVIDER);
        if (oldKey == null || newKey == null || oldProvider == null || newProvider == null) {
            throw new NuxeoException(
                    "This BlobDiff has no stored binary keys (created before 1.2), it cannot be retried", 400);
        }
        Calendar date = (Calendar) diff.getPropertyValue(XP_DATE);
        String newMime = str(diff, XP_MIMETYPE);
        String oldMime = str(diff, XP_OLD_MIMETYPE);
        VersionContext versions = new VersionContext(
                str(diff, XP_PREVIOUS_VERSION_ID),
                str(diff, XP_PREVIOUS_VERSION_LABEL),
                str(diff, XP_NEW_VERSION_ID),
                str(diff, XP_NEW_VERSION_LABEL),
                str(diff, XP_VERSION_SERIES_ID));
        BlobDiffWork work = new BlobDiffWork(repo(diff), str(diff, XP_SOURCE_ID), diff.getTitle(), str(diff, XP_XPATH),
                oldProvider, oldKey, str(diff, XP_OLD_FILENAME), oldMime == null ? newMime : oldMime,
                str(diff, XP_OLD_DIGEST), length(diff, XP_OLD_LENGTH), newProvider, newKey,
                str(diff, XP_NEW_FILENAME), newMime, str(diff, XP_NEW_DIGEST), length(diff, XP_NEW_LENGTH),
                str(diff, XP_USER), date == null ? System.currentTimeMillis() : date.getTimeInMillis(),
                str(diff, XP_CORRELATION_ID), versions);
        work.withReplaceDiffId(diff.getId());
        Framework.getService(WorkManager.class).schedule(work, true);
        return diff;
    }

    /**
     * Refuses anything that does not come from {@code /change-diff}, <b>before a single property of
     * the input is read</b>.
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
     *
     * @since 2025.4
     */
    protected void checkProvenance(DocumentModel diff) {
        String path = diff == null ? null : diff.getPathAsString();
        if (path == null || !path.startsWith(CONTAINER_PATH_PREFIX)) {
            throw new NuxeoException("Not a managed BlobDiff: " + path, 400);
        }
    }

    protected String repo(DocumentModel diff) {
        String repo = str(diff, XP_SOURCE_REPO);
        return repo == null ? session.getRepositoryName() : repo;
    }

    protected static String str(DocumentModel doc, String xpath) {
        Object value = doc.getPropertyValue(xpath);
        return value == null ? null : value.toString();
    }

    protected static long length(DocumentModel doc, String xpath) {
        Object value = doc.getPropertyValue(xpath);
        return value instanceof Number number ? number.longValue() : -1;
    }
}

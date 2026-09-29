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

import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.*;

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
        description = "Schedules again the computation of a BlobDiff in error. Reserved to administrators and auditors.")
public class BlobDiffRetryOp {

    public static final String ID = "BlobDiff.Retry";

    @Context
    protected CoreSession session;

    @OperationMethod
    public DocumentModel run(DocumentModel diff) {
        BlobDiffAccess.checkAuditor(session.getPrincipal());
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
            throw new NuxeoException("This BlobDiff has no stored binary keys (created before 1.2), it cannot be retried",
                    400);
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

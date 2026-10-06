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

import java.util.ArrayList;
import java.util.List;

import org.nuxeo.audit.advanced.blob.BlobAuditConstants;
import org.nuxeo.audit.advanced.blob.BlobDiffAccess;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.impl.DocumentModelListImpl;

/**
 * Permanently deletes {@code BlobDiff} documents. No trash: a trashed audit trail makes no sense.
 * <p>
 * Runs with the caller's session, so the ACL of {@code /change-diff} is enforced on top of the
 * auditor check.
 *
 * @since 2025.1
 */
@Operation(id = BlobDiffDeleteOp.ID, category = "Audit", label = "BlobDiff: Delete permanently",
        description = "Permanently deletes the input BlobDiff documents. Reserved to administrators and auditors.")
public class BlobDiffDeleteOp {

    public static final String ID = "BlobDiff.Delete";

    @Context
    protected CoreSession session;

    @OperationMethod
    public void run(DocumentModel doc) {
        run(new DocumentModelListImpl(List.of(doc)));
    }

    @OperationMethod
    public void run(DocumentModelList docs) {
        BlobDiffAccess.checkAuditor(session.getPrincipal());
        List<DocumentRef> refs = new ArrayList<>(docs.size());
        for (DocumentModel doc : docs) {
            if (!BlobAuditConstants.DIFF_DOCTYPE.equals(doc.getType())) {
                throw new NuxeoException("Not a BlobDiff document: " + doc.getId(), 400);
            }
            refs.add(doc.getRef());
        }
        session.removeDocuments(refs.toArray(DocumentRef[]::new));
        session.save();
    }
}

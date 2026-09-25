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
package org.nuxeo.audit.advanced.blob;

import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.AUDITORS_GROUP;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.CONTAINER_NAME;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.CONTAINER_TITLE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.CONTAINER_TYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.DIFF_DOCTYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_OK;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_ADDED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_CHANGED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_CORRELATION_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_DATE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_DIFF_BLOB;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_MIMETYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_NEW_DIGEST;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_NEW_FILENAME;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_OLD_DIGEST;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_OLD_FILENAME;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_REMOVED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_SOURCE_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_SOURCE_REPO;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_STATUS;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_SUMMARY;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_TRUNCATED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_USER;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_XPATH;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.api.security.impl.ACLImpl;
import org.nuxeo.ecm.core.api.security.impl.ACPImpl;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.DefaultComponent;

/**
 * Registry of {@link BlobTextExtractor} implementations plus the {@link BlobDiffService}
 * implementation.
 *
 * @since 1.0
 */
public class BlobDiffComponent extends DefaultComponent implements BlobDiffService {

    private static final Logger log = LogManager.getLogger(BlobDiffComponent.class);

    public static final String XP_EXTRACTORS = "extractors";

    public static final String XP_CONFIG = "config";

    protected List<ResolvedExtractor> extractors;

    protected record ResolvedExtractor(ExtractorDescriptor descriptor, BlobTextExtractor extractor) {
    }

    @Override
    public void start(ComponentContext context) {
        super.start(context);
        List<ResolvedExtractor> resolved = new ArrayList<>();
        for (ExtractorDescriptor descriptor : this.<ExtractorDescriptor> getDescriptors(XP_EXTRACTORS)) {
            if (!descriptor.isEnabled()) {
                continue;
            }
            try {
                BlobTextExtractor extractor = descriptor.getKlass().getDeclaredConstructor().newInstance();
                extractor.init(descriptor.getProperties());
                resolved.add(new ResolvedExtractor(descriptor, extractor));
            } catch (ReflectiveOperationException e) {
                log.error("Cannot instantiate blob diff extractor: {}", descriptor.getId(), e);
            }
        }
        resolved.sort(Comparator.comparingInt(r -> r.descriptor().getOrder()));
        extractors = resolved;
    }

    @Override
    public void stop(ComponentContext context) throws InterruptedException {
        super.stop(context);
        extractors = null;
    }

    @Override
    public BlobDiffConfigDescriptor getConfig() {
        BlobDiffConfigDescriptor config = getDescriptor(XP_CONFIG, BlobDiffConfigDescriptor.DEFAULT_ID);
        return config == null ? new BlobDiffConfigDescriptor() : config;
    }

    @Override
    public boolean isDiffable(String docType, String xpath, Blob blob) {
        BlobDiffConfigDescriptor config = getConfig();
        if (!config.isEnabled() || blob == null) {
            return false;
        }
        if (!config.acceptsDocType(docType) || !config.acceptsXPath(xpath)) {
            return false;
        }
        if (blob.getLength() > config.getMaxBlobSize()) {
            return false;
        }
        return findExtractor(blob.getMimeType()) != null;
    }

    protected BlobTextExtractor findExtractor(String mimeType) {
        if (extractors == null) {
            return null;
        }
        for (ResolvedExtractor resolved : extractors) {
            if (resolved.descriptor().accepts(mimeType)) {
                return resolved.extractor();
            }
        }
        return null;
    }

    @Override
    public DiffableContent extract(Blob blob) {
        if (blob == null) {
            return DiffableContent.positional(List.of(), false);
        }
        BlobTextExtractor extractor = findExtractor(blob.getMimeType());
        if (extractor == null) {
            return null;
        }
        try {
            return extractor.extract(blob, getConfig().getMaxLines());
        } catch (Exception e) { // NOSONAR - extraction must never break the caller
            log.warn("Blob content extraction failed for mime type {}", blob.getMimeType(), e);
            return null;
        }
    }

    @Override
    public DiffResult diff(Blob oldBlob, Blob newBlob) {
        DiffableContent oldContent = extract(oldBlob);
        DiffableContent newContent = extract(newBlob);
        if (oldContent == null || newContent == null) {
            return null;
        }
        return new TextDiffer(getConfig().getMaxDiffEntries()).diff(oldContent, newContent);
    }

    /* ------------------------------------------------------------- persistence */

    @Override
    public DocumentModel getOrCreateContainer(CoreSession session, Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        DocumentModel parent = getOrCreateFolder(session, session.getRootDocument().getPathAsString(),
                CONTAINER_NAME, CONTAINER_TITLE, true);
        String year = String.valueOf(calendar.get(Calendar.YEAR));
        parent = getOrCreateFolder(session, parent.getPathAsString(), year, year, false);
        String month = new SimpleDateFormat("MM").format(date);
        parent = getOrCreateFolder(session, parent.getPathAsString(), month, month, false);
        String day = new SimpleDateFormat("dd").format(date);
        return getOrCreateFolder(session, parent.getPathAsString(), day, day, false);
    }

    protected DocumentModel getOrCreateFolder(CoreSession session, String parentPath, String name, String title,
            boolean secured) {
        PathRef ref = new PathRef(parentPath + (parentPath.endsWith("/") ? "" : "/") + name);
        if (session.exists(ref)) {
            return session.getDocument(ref);
        }
        DocumentModel folder = session.createDocumentModel(parentPath, name, CONTAINER_TYPE);
        folder.setPropertyValue("dc:title", title);
        folder.addFacet("HiddenInNavigation");
        folder = session.createDocument(folder);
        if (secured) {
            applyRestrictedAcl(session, folder);
        }
        return folder;
    }

    /**
     * Breaks inheritance and grants Read to the auditors group only.
     * <p>
     * This is the whole point of using a dedicated document rather than a facet on the source: the
     * diffs contain business content and must not inherit the source document permissions.
     */
    protected void applyRestrictedAcl(CoreSession session, DocumentModel folder) {
        ACP acp = new ACPImpl();
        ACL acl = new ACLImpl(ACL.LOCAL_ACL);
        acl.add(new ACE(AUDITORS_GROUP, SecurityConstants.READ, true));
        acl.add(ACE.BLOCK);
        acp.addACL(acl);
        session.setACP(folder.getRef(), acp, true);
    }

    @Override
    public DocumentModel createDiffDocument(CoreSession session, DocumentModel source, String xpath, Blob oldBlob,
            Blob newBlob, String user, Date date, DiffResult result, String status, String correlationId) {
        DocumentModel container = getOrCreateContainer(session, date);
        String name = source.getId() + "-" + date.getTime();
        DocumentModel diffDoc = session.createDocumentModel(container.getPathAsString(), name, DIFF_DOCTYPE);
        diffDoc.setPropertyValue("dc:title", source.getTitle() + " - " + xpath);
        diffDoc.setPropertyValue(XP_SOURCE_ID, source.getId());
        diffDoc.setPropertyValue(XP_SOURCE_REPO, source.getRepositoryName());
        diffDoc.setPropertyValue(XP_XPATH, xpath);
        diffDoc.setPropertyValue(XP_CORRELATION_ID, correlationId);
        diffDoc.setPropertyValue(XP_USER, user);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        diffDoc.setPropertyValue(XP_DATE, calendar);
        diffDoc.setPropertyValue(XP_MIMETYPE, newBlob != null ? newBlob.getMimeType() : null);
        diffDoc.setPropertyValue(XP_OLD_FILENAME, oldBlob != null ? oldBlob.getFilename() : null);
        diffDoc.setPropertyValue(XP_NEW_FILENAME, newBlob != null ? newBlob.getFilename() : null);
        diffDoc.setPropertyValue(XP_OLD_DIGEST, oldBlob != null ? oldBlob.getDigest() : null);
        diffDoc.setPropertyValue(XP_NEW_DIGEST, newBlob != null ? newBlob.getDigest() : null);
        diffDoc.setPropertyValue(XP_STATUS, status);
        if (result != null) {
            diffDoc.setPropertyValue(XP_SUMMARY, result.summary());
            diffDoc.setPropertyValue(XP_ADDED, Long.valueOf(result.added()));
            diffDoc.setPropertyValue(XP_REMOVED, Long.valueOf(result.removed()));
            diffDoc.setPropertyValue(XP_CHANGED, Long.valueOf(result.changed()));
            diffDoc.setPropertyValue(XP_TRUNCATED, Boolean.valueOf(result.truncated()));
            if (STATUS_OK.equals(status) && !result.isEmpty()) {
                // A blob, not a string: it stays out of the SQL/Mongo record and out of full-text.
                Blob diffBlob = Blobs.createBlob(result.unified(), "text/plain", StandardCharsets.UTF_8.name(),
                        name + ".diff.txt");
                diffDoc.setPropertyValue(XP_DIFF_BLOB, (Serializable) diffBlob);
            }
        }
        return session.createDocument(diffDoc);
    }
}

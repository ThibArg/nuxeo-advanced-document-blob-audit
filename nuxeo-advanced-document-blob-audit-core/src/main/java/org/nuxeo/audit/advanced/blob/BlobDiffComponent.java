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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.audit.advanced.blob.io.MaterializedBlob;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
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

    /**
     * Image inventory extractors, used when {@code imageAnalysisLevel > 0}. A separate point on
     * purpose: both an image extractor and a text extractor run on the same blob, so they cannot
     * compete for the same mime type in a single, order-based selection.
     *
     * @since 2025.3
     */
    public static final String XP_IMAGE_EXTRACTORS = "imageExtractors";

    public static final String XP_CONFIG = "config";

    protected List<ResolvedExtractor> extractors;

    protected List<ResolvedExtractor> imageExtractors;

    protected record ResolvedExtractor(ExtractorDescriptor descriptor, BlobTextExtractor extractor) {
    }

    @Override
    public void start(ComponentContext context) {
        super.start(context);
        BlobDiffConfigDescriptor config = getConfig();
        int configuredImageAnalysisLevel = config.getImageAnalysisLevel();
        int imageAnalysisLevel = config.normalizeImageAnalysisLevel();
        if (configuredImageAnalysisLevel != imageAnalysisLevel) {
            log.warn("Invalid imageAnalysisLevel {}: using supported value {} (range {}..{})",
                    configuredImageAnalysisLevel, imageAnalysisLevel,
                    BlobDiffConfigDescriptor.MIN_IMAGE_ANALYSIS_LEVEL,
                    BlobDiffConfigDescriptor.MAX_IMAGE_ANALYSIS_LEVEL);
        }
        extractors = resolve(XP_EXTRACTORS);
        imageExtractors = resolve(XP_IMAGE_EXTRACTORS);
    }

    protected List<ResolvedExtractor> resolve(String extensionPoint) {
        List<ResolvedExtractor> resolved = new ArrayList<>();
        for (ExtractorDescriptor descriptor : this.<ExtractorDescriptor> getDescriptors(extensionPoint)) {
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
        return resolved;
    }

    @Override
    public void stop(ComponentContext context) throws InterruptedException {
        super.stop(context);
        extractors = null;
        imageExtractors = null;
    }

    @Override
    public BlobDiffConfigDescriptor getConfig() {
        BlobDiffConfigDescriptor config = getDescriptor(XP_CONFIG, BlobDiffConfigDescriptor.DEFAULT_ID);
        return config == null ? new BlobDiffConfigDescriptor() : config;
    }

    @Override
    public DiffEligibility getEligibility(String docType, String xpath, Blob blob) {
        BlobDiffConfigDescriptor config = getConfig();
        if (!config.isEnabled() || blob == null) {
            return DiffEligibility.NOT_APPLICABLE;
        }
        if (!config.acceptsDocType(docType) || !config.acceptsXPath(xpath)) {
            return DiffEligibility.NOT_APPLICABLE;
        }
        if (blob.getLength() > config.getMaxBlobSize()) {
            return DiffEligibility.TOO_LARGE;
        }
        if (findExtractor(blob.getMimeType()) == null) {
            return DiffEligibility.UNSUPPORTED_TYPE;
        }
        return DiffEligibility.ELIGIBLE;
    }

    protected BlobTextExtractor findExtractor(String mimeType) {
        return findExtractor(extractors, mimeType);
    }

    /** @since 2025.3 */
    protected BlobTextExtractor findImageExtractor(String mimeType) {
        return findExtractor(imageExtractors, mimeType);
    }

    protected BlobTextExtractor findExtractor(List<ResolvedExtractor> candidates, String mimeType) {
        if (candidates == null) {
            return null;
        }
        for (ResolvedExtractor resolved : candidates) {
            if (resolved.descriptor().accepts(mimeType)) {
                return resolved.extractor();
            }
        }
        return null;
    }

    /**
     * {@code null} means <b>no extractor is registered</b> for the mime type, and nothing else.
     * <p>
     * A failing extractor used to be reported the same way, so {@code BlobDiffWork} filed a real
     * incident as {@code skippedUnsupportedType} - a status {@code BlobDiff.Retry} refuses, which
     * made the failure permanent and invisible. Raising is what lets the work record {@code error}
     * instead, through the {@code catch (RuntimeException)} it already has.
     */
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
        } catch (Exception e) { // NOSONAR - checked exceptions of the extractor SPI
            throw new NuxeoException("Blob content extraction failed for mime type " + blob.getMimeType(), e);
        }
    }

    /**
     * Both binaries are materialised locally <b>once</b> and every extraction pass reads the local
     * copy. Without this, a pair of S3-backed blobs meant one download per pass: four for two files
     * as soon as {@code imageAnalysisLevel > 0}.
     */
    @Override
    public DiffResult diff(Blob oldBlob, Blob newBlob) {
        try (MaterializedBlob oldLocal = MaterializedBlob.of(oldBlob);
                MaterializedBlob newLocal = MaterializedBlob.of(newBlob)) {
            return diffLocal(oldLocal.blob(), newLocal.blob());
        }
    }

    protected DiffResult diffLocal(Blob oldBlob, Blob newBlob) {
        DiffableContent oldContent = extract(oldBlob);
        DiffableContent newContent = extract(newBlob);
        if (oldContent == null || newContent == null) {
            return null;
        }
        BlobDiffConfigDescriptor config = getConfig();
        TextDiffer differ = new TextDiffer(config.getMaxDiffEntries(), config.getMaxDiffChars(),
                config.getMaxValueLength());
        DiffResult textResult = differ.diff(oldContent, newContent);
        if (config.normalizeImageAnalysisLevel() == 0) {
            return textResult;
        }
        BlobTextExtractor imageExtractor = newBlob == null ? null : findImageExtractor(newBlob.getMimeType());
        if (imageExtractor == null) {
            return textResult;
        }
        try {
            int maxLines = config.getMaxLines();
            DiffResult imageResult = differ.diff(extractImages(imageExtractor, oldBlob, maxLines),
                    extractImages(imageExtractor, newBlob, maxLines));
            return differ.merge(textResult, imageResult);
        } catch (Exception e) { // NOSONAR - the image inventory must never lose the text diff
            // Deliberately asymmetric with extract(): a failing *text* extraction raises, because
            // there is nothing left to record, while a failing image inventory only costs a
            // section of the report. Do not "harmonize" the two.
            log.warn("Image inventory extraction failed for mime type {}", newBlob.getMimeType(), e);
            return textResult;
        }
    }

    /** A missing side (first version, blob added or removed) has an empty inventory, not none. */
    protected DiffableContent extractImages(BlobTextExtractor extractor, Blob blob, int maxLines) throws Exception {
        return blob == null ? DiffableContent.keyed(List.of(), false) : extractor.extract(blob, maxLines);
    }

    /* ------------------------------------------------------------- persistence */

    /**
     * In-JVM guard around container creation, so that parallel works on the same node do not both
     * create the same dated folder in the common case.
     * <p>
     * Folders are created <b>in the caller's session and transaction</b>: creating them in a separate
     * transaction made them invisible to the caller's session (VCS caches / isolation), and every
     * following {@code getDocument} failed with {@code DocumentNotFoundException}.
     * <p>
     * A residual race (another node, or a transaction not yet committed) can only produce a
     * renamed <i>dated</i> sibling (for instance {@code 14.1727...}). It still lives under the
     * restricted root and inherits its ACL, so it is harmless for security; the root itself is
     * created at repository initialisation, before any work runs.
     */
    protected static final ReentrantLock CONTAINER_LOCK = new ReentrantLock();

    /**
     * Dated path of a container, always in UTC.
     * <p>
     * The previous implementation built three {@code SimpleDateFormat} per call in the JVM default
     * time zone. Two nodes of the same cluster in different zones would then write the diffs of a
     * single instant into two different dated folders, and a purge "before yyyy-MM-dd" would not
     * mean the same thing depending on which node answered.
     */
    protected static final DateTimeFormatter CONTAINER_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd")
                                                                                      .withZone(ZoneOffset.UTC);

    @Override
    public DocumentModel getOrCreateContainer(CoreSession session, Date date) {
        String[] parts = CONTAINER_PATH_FORMAT.format(date.toInstant()).split("/");
        String year = parts[0];
        String month = parts[1];
        String day = parts[2];
        PathRef dayRef = new PathRef("/" + CONTAINER_NAME + "/" + year + "/" + month + "/" + day);
        if (session.exists(dayRef)) {
            return session.getDocument(dayRef);
        }
        CONTAINER_LOCK.lock();
        try {
            DocumentModel root = ensureRootContainer(session);
            DocumentModel parent = getOrCreateFolder(session, root.getPathAsString(), year);
            parent = getOrCreateFolder(session, parent.getPathAsString(), month);
            DocumentModel folder = getOrCreateFolder(session, parent.getPathAsString(), day);
            session.save();
            return folder;
        } finally {
            CONTAINER_LOCK.unlock();
        }
    }

    /**
     * Number of times {@link #ensureRootContainer} retries after losing a creation race.
     * <p>
     * The retry used to be an unbounded recursion: a repository that renames every creation - a
     * misconfigured name generator, a permanent conflict - would recurse until the stack blew, on
     * a code path that runs inside the diff work.
     */
    protected static final int ROOT_CREATION_ATTEMPTS = 3;

    @Override
    public DocumentModel ensureRootContainer(CoreSession session) {
        PathRef ref = new PathRef("/" + CONTAINER_NAME);
        DocumentModel root = null;
        for (int attempt = 1; attempt <= ROOT_CREATION_ATTEMPTS && root == null; attempt++) {
            if (session.exists(ref)) {
                root = session.getDocument(ref);
                break;
            }
            DocumentModel created = session.createDocumentModel("/", CONTAINER_NAME, CONTAINER_TYPE);
            created.setPropertyValue("dc:title", CONTAINER_TITLE);
            created.addFacet("HiddenInNavigation");
            created = session.createDocument(created);
            if (CONTAINER_NAME.equals(created.getName())) {
                root = created;
            } else {
                // Lost a race against another node: the core renamed our sibling. Never leave an
                // unrestricted duplicate around, and retry to pick up the canonical one.
                log.warn("Duplicate diff container {} created concurrently, removing it (attempt {}/{})",
                        created.getPathAsString(), attempt, ROOT_CREATION_ATTEMPTS);
                session.removeDocument(created.getRef());
            }
        }
        if (root == null) {
            throw new NuxeoException("Cannot create the diff container /" + CONTAINER_NAME + " after "
                    + ROOT_CREATION_ATTEMPTS + " attempts");
        }
        repairSecurity(session, root);
        return root;
    }

    /**
     * Makes sure the root container carries exactly the expected local ACL: auditors group granted
     * Read, Remove and RemoveChildren, inheritance blocked. Repairs it (and logs a WARN) when it
     * was created by an older version, changed by hand, or the auditors group was reconfigured.
     *
     * @return {@code true} if the ACL had to be repaired
     */
    @Override
    public boolean repairSecurity(CoreSession session, DocumentModel root) {
        ACP current = session.getACP(root.getRef());
        ACL local = current == null ? null : current.getACL(ACL.LOCAL_ACL);
        List<ACE> expected = expectedAces();
        List<ACE> actual = local == null ? List.of() : Arrays.asList(local.getACEs());
        boolean otherAcls = current != null && Arrays.stream(current.getACLs())
                                                       .anyMatch(acl -> !ACL.LOCAL_ACL.equals(acl.getName())
                                                               && !ACL.INHERITED_ACL.equals(acl.getName())
                                                               && acl.getACEs().length > 0);
        if (sameAces(expected, actual) && !otherAcls) {
            return false;
        }
        if (local != null || otherAcls) {
            log.warn("Repairing ACL of diff container {}: was {}", root.getPathAsString(), actual);
        }
        ACP acp = new ACPImpl();
        ACL acl = new ACLImpl(ACL.LOCAL_ACL);
        expected.forEach(acl::add);
        acp.addACL(acl);
        session.setACP(root.getRef(), acp, true);
        return true;
    }

    protected List<ACE> expectedAces() {
        String group = getConfig().getAuditorsGroup();
        return List.of(new ACE(group, SecurityConstants.READ, true),
                new ACE(group, SecurityConstants.REMOVE, true),
                new ACE(group, SecurityConstants.REMOVE_CHILDREN, true), ACE.BLOCK);
    }

    protected boolean sameAces(List<ACE> expected, List<ACE> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            ACE e = expected.get(i);
            ACE a = actual.get(i);
            if (!e.getUsername().equals(a.getUsername()) || !e.getPermission().equals(a.getPermission())
                    || e.isGranted() != a.isGranted()) {
                return false;
            }
        }
        return true;
    }

    /** Dated sub-folders inherit the root ACL: no local ACL of their own. */
    protected DocumentModel getOrCreateFolder(CoreSession session, String parentPath, String name) {
        PathRef ref = new PathRef(parentPath + (parentPath.endsWith("/") ? "" : "/") + name);
        if (session.exists(ref)) {
            return session.getDocument(ref);
        }
        DocumentModel folder = session.createDocumentModel(parentPath, name, CONTAINER_TYPE);
        folder.setPropertyValue("dc:title", name);
        folder.addFacet("HiddenInNavigation");
        folder = session.createDocument(folder);
        if (!name.equals(folder.getName())) {
            // Renamed by the core because a sibling appeared concurrently. Harmless: it inherits
            // the restricted ACL of the root container.
            log.debug("Dated diff folder created as {} (concurrent creation)", folder.getPathAsString());
        }
        return folder;
    }

    @Override
    public DocumentModel createDiffDocument(CoreSession session, DocumentModel source, String xpath, Blob oldBlob,
            Blob newBlob, String user, Date date, DiffResult result, String status, String correlationId) {
        return createDiffDocument(session, source.getId(), source.getRepositoryName(), source.getTitle(), xpath,
                oldBlob, newBlob, user, date, result, status, correlationId);
    }

    @Override
    public DocumentModel createDiffDocument(CoreSession session, String sourceId, String sourceRepository,
            String sourceTitle, String xpath, Blob oldBlob, Blob newBlob, String user, Date date, DiffResult result,
            String status, String correlationId) {
        return createDiffDocument(session, sourceId, sourceRepository, sourceTitle, xpath, oldBlob, newBlob,
                FrozenBlobs.of(oldBlob, newBlob), user, date, result, status, correlationId);
    }

    @Override
    public DocumentModel createDiffDocument(CoreSession session, String sourceId, String sourceRepository,
            String sourceTitle, String xpath, Blob oldBlob, Blob newBlob, FrozenBlobs frozen, String user, Date date,
            DiffResult result, String status, String correlationId) {
        return createDiffDocument(session, sourceId, sourceRepository, sourceTitle, xpath, oldBlob, newBlob, frozen,
                VersionContext.NONE, user, date, result, status, correlationId);
    }

    @Override
    public DocumentModel createDiffDocument(CoreSession session, String sourceId, String sourceRepository,
            String sourceTitle, String xpath, Blob oldBlob, Blob newBlob, FrozenBlobs frozen,
            VersionContext versions, String user, Date date, DiffResult result, String status, String correlationId) {
        DocumentModel container = getOrCreateContainer(session, date);
        String name = diffDocumentName(sourceId, date, correlationId);
        DocumentModel diffDoc = session.createDocumentModel(container.getPathAsString(), name, DIFF_DOCTYPE);
        diffDoc.setPropertyValue("dc:title", (sourceTitle == null ? sourceId : sourceTitle) + " - " + xpath);
        diffDoc.setPropertyValue(XP_SOURCE_ID, sourceId);
        diffDoc.setPropertyValue(XP_SOURCE_REPO, sourceRepository);
        diffDoc.setPropertyValue(XP_XPATH, xpath);
        VersionContext versionContext = versions == null ? VersionContext.NONE : versions;
        diffDoc.setPropertyValue(BlobAuditConstants.XP_PREVIOUS_VERSION_ID, versionContext.previousVersionId());
        diffDoc.setPropertyValue(BlobAuditConstants.XP_PREVIOUS_VERSION_LABEL, versionContext.previousVersionLabel());
        diffDoc.setPropertyValue(BlobAuditConstants.XP_NEW_VERSION_ID, versionContext.newVersionId());
        diffDoc.setPropertyValue(BlobAuditConstants.XP_NEW_VERSION_LABEL, versionContext.newVersionLabel());
        diffDoc.setPropertyValue(BlobAuditConstants.XP_VERSION_SERIES_ID, versionContext.versionSeriesId());
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
        FrozenBlobs keys = frozen == null ? FrozenBlobs.NONE : frozen;
        diffDoc.setPropertyValue(BlobAuditConstants.XP_OLD_BLOB_PROVIDER, keys.oldProviderId());
        diffDoc.setPropertyValue(BlobAuditConstants.XP_OLD_BLOB_KEY, keys.oldKey());
        diffDoc.setPropertyValue(BlobAuditConstants.XP_OLD_MIMETYPE, keys.oldMimeType());
        diffDoc.setPropertyValue(BlobAuditConstants.XP_OLD_LENGTH, keys.oldLength() < 0 ? null : keys.oldLength());
        diffDoc.setPropertyValue(BlobAuditConstants.XP_NEW_BLOB_PROVIDER, keys.newProviderId());
        diffDoc.setPropertyValue(BlobAuditConstants.XP_NEW_BLOB_KEY, keys.newKey());
        diffDoc.setPropertyValue(BlobAuditConstants.XP_NEW_LENGTH, keys.newLength() < 0 ? null : keys.newLength());
        if (newBlob == null && keys.newMimeType() != null) {
            diffDoc.setPropertyValue(XP_MIMETYPE, keys.newMimeType());
        }
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

    /**
     * {@code <sourceId>-<eventTime>-<correlationId>}: one version touching two blob xpaths produces two
     * diffs with the same source and time, so the correlation id is what keeps names unique.
     */
    protected String diffDocumentName(String sourceId, Date date, String correlationId) {
        return sourceId + "-" + date.getTime() + (correlationId == null ? "" : "-" + correlationId);
    }
}

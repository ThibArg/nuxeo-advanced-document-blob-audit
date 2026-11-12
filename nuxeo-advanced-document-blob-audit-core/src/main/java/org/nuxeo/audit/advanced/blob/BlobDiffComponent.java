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
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EVENT_BLOB_MODIFIED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EVENT_TYPES_DIRECTORY;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.LABEL_EVENT_BLOB_MODIFIED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_OK;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_ADDED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_CHANGED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_DIFF_BLOB;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_REMOVED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_STATUS;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_SUMMARY;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_TRUNCATED;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
import org.nuxeo.ecm.core.repository.RepositoryInitializationHandler;
import org.nuxeo.ecm.directory.Session;
import org.nuxeo.ecm.directory.api.DirectoryService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.DefaultComponent;
import org.nuxeo.runtime.transaction.TransactionHelper;

/**
 * Registry of {@link BlobTextExtractor} implementations plus the {@link BlobDiffService}
 * implementation. Also registers {@link BlobDiffRepositoryInit} and the {@code blobContentModified}
 * audit event type.
 *
 * @since 2025.1
 */
public class BlobDiffComponent extends DefaultComponent implements BlobDiffService {

    private static final Logger log = LogManager.getLogger(BlobDiffComponent.class);

    public static final String XP_EXTRACTORS = "extractors";

    /**
     * Image inventory extractors, used when {@code imageInventory} is on. A separate point on
     * purpose: both an image extractor and a text extractor run on the same blob, so they cannot
     * compete for the same mime type in a single, order-based selection.
     *
     * @since 2025.1
     */
    public static final String XP_IMAGE_EXTRACTORS = "imageExtractors";

    public static final String XP_CONFIG = "config";

    protected List<ResolvedExtractor> extractors;

    protected List<ResolvedExtractor> imageExtractors;

    /** @since 2025.1 */
    protected RepositoryInitializationHandler repositoryInitHandler;

    /**
     * One enabled contribution, instantiated. {@code minSimilarity} and {@code minSimilarityLines}
     * are its {@link ExtractorDescriptor#MIN_SIMILARITY} rule, parsed once; {@code 0} disables it.
     */
    protected record ResolvedExtractor(ExtractorDescriptor descriptor, BlobTextExtractor extractor,
            double minSimilarity, int minSimilarityLines) {
    }

    @Override
    public void start(ComponentContext context) {
        super.start(context);
        extractors = resolve(XP_EXTRACTORS);
        imageExtractors = resolve(XP_IMAGE_EXTRACTORS);
        registerAuditEventType();
        repositoryInitHandler = new BlobDiffRepositoryInit();
        repositoryInitHandler.install();
    }

    /**
     * Adds {@code blobContentModified} to the platform {@code eventTypes} vocabulary.
     * <p>
     * Done programmatically on purpose. A declarative {@code <dataFile>} contribution does not add to
     * the platform's data, it <b>replaces</b> it: {@code dataFileName} is single-valued,
     * {@code BaseDirectoryDescriptor#merge} overwrites it, and {@code DirectoryRegistry} restarts the
     * effective descriptor from the template for every contribution carrying {@code extends}. A
     * greenfield instance would end up with this single row and lose the platform's 51.
     * <p>
     * A runtime with no {@code eventTypes} directory is skipped in silence, because the directory is
     * genuinely absent rather than merely late: {@code DirectoryServiceImpl} starts at order 97 and
     * this component at the default 1000, so the registry is populated and initialized by the time
     * this runs, and {@code getDirectory} answers {@code null} instead of throwing. That happens on a
     * distribution without {@code org.nuxeo.ecm.default.config}, which is where the
     * {@code template-vocabulary} the platform's {@code eventTypes} extends comes from. The only
     * consequence is a missing label in the audit filter, which is not worth an error.
     *
     * @since 2025.1
     */
    protected void registerAuditEventType() {
        DirectoryService ds = Framework.getService(DirectoryService.class);
        if (ds == null || ds.getDirectory(EVENT_TYPES_DIRECTORY) == null) {
            log.debug("No {} directory on this runtime; {} will not appear in the audit filter",
                    EVENT_TYPES_DIRECTORY, EVENT_BLOB_MODIFIED);
            return;
        }
        try {
            TransactionHelper.runInTransaction(() -> Framework.doPrivileged(() -> {
                try (Session session = ds.open(EVENT_TYPES_DIRECTORY)) {
                    if (session.hasEntry(EVENT_BLOB_MODIFIED)) {
                        return;
                    }
                    session.createEntry(Map.of("id", EVENT_BLOB_MODIFIED, //
                            "label", LABEL_EVENT_BLOB_MODIFIED, //
                            "obsolete", 0L, //
                            "ordering", 10_000_000L));
                }
            }));
        } catch (RuntimeException e) { // NOSONAR - never break startup over a vocabulary row
            log.error("Cannot register the {} event type; it will not appear in the audit filter",
                    EVENT_BLOB_MODIFIED, e);
        }
    }

    protected List<ResolvedExtractor> resolve(String extensionPoint) {
        List<ResolvedExtractor> resolved = new ArrayList<>();
        for (ExtractorDescriptor descriptor : this.<ExtractorDescriptor> getDescriptors(extensionPoint)) {
            if (!descriptor.isEnabled()) {
                continue;
            }
            try {
                BlobTextExtractor extractor = descriptor.getKlass().getDeclaredConstructor().newInstance();
                Map<String, String> properties = new HashMap<>(descriptor.getProperties());
                properties.putIfAbsent(BlobTextExtractor.MAX_VALUE_LENGTH,
                        String.valueOf(getConfig().getMaxValueLength()));
                extractor.init(properties);
                resolved.add(new ResolvedExtractor(descriptor, extractor, minSimilarity(descriptor),
                        minSimilarityLines(descriptor)));
            } catch (ReflectiveOperationException | RuntimeException e) {
                // RuntimeException included on purpose: init() parses contributed properties -
                // ConverterTextExtractor does Integer.parseInt on minLineLength - so a typo in an
                // XML contribution used to escape here and fail the whole component start, which
                // is exactly what logging and skipping this one extractor is meant to prevent.
                log.error("Cannot instantiate blob diff extractor: {}", descriptor.getId(), e);
            }
        }
        resolved.sort(Comparator.comparingInt(r -> r.descriptor().getOrder()));
        return resolved;
    }

    /**
     * Parsed apart from {@code init()} and leniently: a typo in this property must cost the rule,
     * not the extractor, whose formats would otherwise all become unsupported.
     */
    protected static double minSimilarity(ExtractorDescriptor descriptor) {
        String value = descriptor.getProperties().get(ExtractorDescriptor.MIN_SIMILARITY);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            if (parsed >= 0 && parsed <= 1) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // logged below
        }
        log.error("Invalid {} '{}' on blob diff extractor {}: expected a number between 0 and 1, rule disabled",
                ExtractorDescriptor.MIN_SIMILARITY, value, descriptor.getId());
        return 0;
    }

    protected static int minSimilarityLines(ExtractorDescriptor descriptor) {
        String value = descriptor.getProperties().get(ExtractorDescriptor.MIN_SIMILARITY_LINES);
        if (value == null || value.isBlank()) {
            return ExtractorDescriptor.DEFAULT_MIN_SIMILARITY_LINES;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= 0) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // logged below
        }
        log.error("Invalid {} '{}' on blob diff extractor {}: expected a positive integer, using {}",
                ExtractorDescriptor.MIN_SIMILARITY_LINES, value, descriptor.getId(),
                ExtractorDescriptor.DEFAULT_MIN_SIMILARITY_LINES);
        return ExtractorDescriptor.DEFAULT_MIN_SIMILARITY_LINES;
    }

    /**
     * The extractor lists are kept: a work still running past the WorkManager drain needs them
     * (item 49). Only the blob xpath templates of {@link VersionBlobPairs} go, a hot reload can
     * redefine the schemas they are derived from, and the repository initialization handler is
     * uninstalled.
     */
    @Override
    public void stop(ComponentContext context) throws InterruptedException {
        super.stop(context);
        VersionBlobPairs.clearCache();
        if (repositoryInitHandler != null) {
            repositoryInitHandler.uninstall();
            repositoryInitHandler = null;
        }
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

    /**
     * Extractor instances are compared by identity, on one snapshot of the list: there is one instance
     * per contribution, and a concurrent {@link #start} replacing the list must not make two lookups
     * of the same contribution look different.
     */
    @Override
    public DiffEligibility getEligibility(String docType, String xpath, Blob oldBlob, Blob newBlob) {
        List<ResolvedExtractor> candidates = extractors;
        DiffEligibility eligibility = DiffEligibility.combine(getEligibility(docType, xpath, oldBlob),
                getEligibility(docType, xpath, newBlob));
        if (eligibility != DiffEligibility.ELIGIBLE) {
            return eligibility;
        }
        BlobTextExtractor oldExtractor = findExtractor(candidates, oldBlob.getMimeType());
        BlobTextExtractor newExtractor = findExtractor(candidates, newBlob.getMimeType());
        return oldExtractor == newExtractor ? DiffEligibility.ELIGIBLE : DiffEligibility.FORMAT_CHANGED;
    }

    protected BlobTextExtractor findExtractor(String mimeType) {
        return findExtractor(extractors, mimeType);
    }

    /** @since 2025.1 */
    protected BlobTextExtractor findImageExtractor(String mimeType) {
        return findExtractor(imageExtractors, mimeType);
    }

    protected BlobTextExtractor findExtractor(List<ResolvedExtractor> candidates, String mimeType) {
        ResolvedExtractor resolved = findResolved(candidates, mimeType);
        return resolved == null ? null : resolved.extractor();
    }

    /** @since 2025.1 */
    protected ResolvedExtractor findResolved(List<ResolvedExtractor> candidates, String mimeType) {
        if (candidates == null) {
            return null;
        }
        for (ResolvedExtractor resolved : candidates) {
            if (resolved.descriptor().accepts(mimeType)) {
                return resolved;
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
        BlobTextExtractor extractor = findExtractor(blob.getMimeType());
        if (extractor == null) {
            return null;
        }
        try {
            return extractor.extract(blob, getConfig().getMaxLines());
        } catch (IOException e) {
            throw new NuxeoException("Blob content extraction failed for mime type " + blob.getMimeType(), e);
        }
    }

    /**
     * Both binaries are materialised locally <b>once</b> and every extraction pass reads the local
     * copy. Without this, a pair of S3-backed blobs meant one download per pass: four for two files
     * as soon as the image inventory is on.
     */
    @Override
    public DiffResult diff(Blob oldBlob, Blob newBlob) {
        try (MaterializedBlob oldLocal = MaterializedBlob.of(oldBlob);
                MaterializedBlob newLocal = MaterializedBlob.of(newBlob)) {
            return diffLocal(oldLocal.blob(), newLocal.blob());
        }
    }

    protected DiffResult diffLocal(Blob oldBlob, Blob newBlob) {
        List<ResolvedExtractor> candidates = extractors;
        DiffableContent oldContent = extract(oldBlob);
        DiffableContent newContent = extract(newBlob);
        if (oldContent == null || newContent == null) {
            return null;
        }
        BlobDiffConfigDescriptor config = getConfig();
        TextDiffer differ = new TextDiffer(config.getMaxDiffEntries(), config.getMaxDiffChars(),
                config.getMaxValueLength());
        DiffResult textResult = differ.diff(oldContent, newContent);
        if (isReplaced(candidates, oldBlob, newBlob, oldContent, newContent, textResult)) {
            textResult = textResult.asReplaced();
        }
        if (!config.isImageInventory()) {
            return textResult;
        }
        BlobTextExtractor imageExtractor = findImageExtractor(newBlob.getMimeType());
        if (imageExtractor == null) {
            return textResult;
        }
        try {
            int maxLines = config.getMaxLines();
            DiffResult imageResult = differ.diff(imageExtractor.extract(oldBlob, maxLines),
                    imageExtractor.extract(newBlob, maxLines));
            return differ.merge(textResult, imageResult);
        } catch (IOException | RuntimeException e) {
            // Deliberately asymmetric with extract(): a failing *text* extraction raises, because
            // there is nothing left to record, while a failing image inventory only costs a
            // section of the report. Do not "harmonize" the two.
            log.warn("Image inventory extraction failed for mime type {}", newBlob.getMimeType(), e);
            return textResult;
        }
    }

    /**
     * Whether the pair changed mime type and shares too little to be worth a diff (item 62).
     * <p>
     * Only a pair read by one contribution reaches this point - two contributions are
     * {@code FORMAT_CHANGED} - so the rule of the new side's contribution is the rule of both. A
     * rewrite within one mime type is always diffed: the rule is about a file replaced by another
     * one, not about how much an author changed.
     */
    protected boolean isReplaced(List<ResolvedExtractor> candidates, Blob oldBlob, Blob newBlob,
            DiffableContent oldContent, DiffableContent newContent, DiffResult result) {
        if (result.similarity() == null) {
            return false;
        }
        if (Objects.equals(ExtractorDescriptor.normalizeMimeType(oldBlob.getMimeType()),
                ExtractorDescriptor.normalizeMimeType(newBlob.getMimeType()))) {
            return false;
        }
        ResolvedExtractor resolved = findResolved(candidates, newBlob.getMimeType());
        if (resolved == null || resolved.minSimilarity() <= 0) {
            return false;
        }
        if (Math.min(oldContent.size(), newContent.size()) < resolved.minSimilarityLines()) {
            return false;
        }
        return result.similarity() < resolved.minSimilarity();
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
     * Dated path of a container, always in UTC and always in {@link Locale#ROOT}.
     * <p>
     * The previous implementation built three {@code SimpleDateFormat} per call in the JVM default
     * time zone. Two nodes of the same cluster in different zones would then write the diffs of a
     * single instant into two different dated folders, and a purge "before yyyy-MM-dd" would not
     * mean the same thing depending on which node answered.
     * <p>
     * The locale matters for the same reason the zone does, and was the half of it left implicit:
     * {@code ofPattern(String)} takes {@code Locale.getDefault(FORMAT)} and with it a
     * {@code DecimalStyle}, so under a locale with a non-Latin numbering system the year, month and
     * day would render as non-ASCII digits - inside a repository <b>path</b>.
     */
    protected static final DateTimeFormatter CONTAINER_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd",
            Locale.ROOT).withZone(ZoneOffset.UTC);

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
        String group = BlobDiffAccess.auditorsGroup();
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
    public DocumentModel createDiffDocument(CoreSession session, DiffRequest request, String status,
            DiffResult result) {
        Date date = new Date(request.eventTime());
        DocumentModel container = getOrCreateContainer(session, date);
        String name = diffDocumentName(request.sourceId(), date, request.correlationId());
        DocumentModel diffDoc = session.createDocumentModel(container.getPathAsString(), name, DIFF_DOCTYPE);
        request.writeTo(diffDoc);
        diffDoc.setPropertyValue(XP_STATUS, status);
        if (result != null) {
            diffDoc.setPropertyValue(XP_SUMMARY, result.summary());
            diffDoc.setPropertyValue(XP_ADDED, Long.valueOf(result.added()));
            diffDoc.setPropertyValue(XP_REMOVED, Long.valueOf(result.removed()));
            diffDoc.setPropertyValue(XP_CHANGED, Long.valueOf(result.changed()));
            diffDoc.setPropertyValue(XP_TRUNCATED, Boolean.valueOf(result.truncated()));
            diffDoc.setPropertyValue(BlobAuditConstants.XP_SIMILARITY, result.similarity());
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

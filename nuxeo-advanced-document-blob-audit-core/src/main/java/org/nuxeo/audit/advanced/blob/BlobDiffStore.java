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
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_DIFF_BLOB;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_REMOVED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_STATUS;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_SUMMARY;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_TRUNCATED;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

/**
 * The restricted /change-diff container and the BlobDiff documents in it. The session must be
 * privileged.
 *
 * @since 2025.1
 */
public final class BlobDiffStore {

    private static final Logger log = LogManager.getLogger(BlobDiffStore.class);

    /**
     * In-JVM guard around container creation, so that parallel works on the same node do not both
     * create the same dated folder in the common case.
     * <p>
     * Folders are created <b>in the caller's session and transaction</b>: created in a separate
     * transaction, they would be invisible to the caller's session (VCS caches / isolation), and
     * every following {@code getDocument} would fail with {@code DocumentNotFoundException}.
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
     * In the JVM default time zone, two nodes of the same cluster in different zones would write the
     * diffs of a single instant into two different dated folders, and a purge "before yyyy-MM-dd"
     * would not mean the same thing depending on which node answered.
     * <p>
     * The locale matters for the same reason: {@code ofPattern(String)} takes
     * {@code Locale.getDefault(FORMAT)} and with it a {@code DecimalStyle}, so under a locale with a
     * non-Latin numbering system the year, month and day would render as non-ASCII digits - inside a
     * repository <b>path</b>.
     */
    protected static final DateTimeFormatter CONTAINER_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd",
            Locale.ROOT).withZone(ZoneOffset.UTC);

    /**
     * Number of times {@link #ensureRootContainer} retries after losing a creation race.
     * <p>
     * Bounded on purpose: a repository that renames every creation - a misconfigured name generator,
     * a permanent conflict - would otherwise retry for ever, on a code path that runs inside the diff
     * work.
     */
    protected static final int ROOT_CREATION_ATTEMPTS = 3;

    private BlobDiffStore() {
        // utility class
    }

    /** Returns (creating it if needed) the dated container for the given date. */
    public static DocumentModel getOrCreateContainer(CoreSession session, Date date) {
        String[] parts = CONTAINER_PATH_FORMAT.format(date.toInstant()).split("/");
        String year = parts[0];
        String month = parts[1];
        String day = parts[2];
        var dayRef = new PathRef("/" + CONTAINER_NAME + "/" + year + "/" + month + "/" + day);
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
     * Returns (creating it if needed) the root {@code /change-diff} container and makes sure its
     * ACL is the expected one. The session must be privileged.
     */
    public static DocumentModel ensureRootContainer(CoreSession session) {
        var ref = new PathRef("/" + CONTAINER_NAME);
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
     * Read, Remove and RemoveChildren, inheritance blocked. Repairs it (and logs a WARN) when it was
     * changed by hand or the auditors group was reconfigured.
     *
     * @return {@code true} if the ACL had to be repaired
     */
    public static boolean repairSecurity(CoreSession session, DocumentModel root) {
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

    protected static List<ACE> expectedAces() {
        String group = BlobDiffAccess.auditorsGroup();
        return List.of(new ACE(group, SecurityConstants.READ, true),
                new ACE(group, SecurityConstants.REMOVE, true),
                new ACE(group, SecurityConstants.REMOVE_CHILDREN, true), ACE.BLOCK);
    }

    protected static boolean sameAces(List<ACE> expected, List<ACE> actual) {
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

    /**
     * Returns (creating it if needed) a dated sub-folder. Dated sub-folders inherit the root ACL: no
     * local ACL of their own.
     */
    protected static DocumentModel getOrCreateFolder(CoreSession session, String parentPath, String name) {
        var ref = new PathRef(parentPath + (parentPath.endsWith("/") ? "" : "/") + name);
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

    /**
     * Creates the {@code BlobDiff} document recording the request and its result, inside the dated
     * container of the request time. The session must be privileged.
     *
     * @param result the comparison, {@code null} when none was computed
     */
    public static DocumentModel createDiffDocument(CoreSession session, DiffRequest request, String status,
            DiffResult result) {
        var date = new Date(request.eventTime());
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
     * Returns the name of a diff, {@code <sourceId>-<eventTime>-<correlationId>}: one version touching
     * two blob xpaths produces two diffs with the same source and time, so the correlation id is what
     * keeps names unique.
     */
    protected static String diffDocumentName(String sourceId, Date date, String correlationId) {
        return sourceId + "-" + date.getTime() + (correlationId == null ? "" : "-" + correlationId);
    }
}
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

import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.AUDIT_CATEGORY;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EVENT_BLOB_MODIFIED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_CORRELATION_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_NEW_FILENAME;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_OLD_FILENAME;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_SKIP_REASON;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_XPATH;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_LIST_CHANGED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_NOT_MANAGED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_SIZE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_TYPE;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.event.DocumentEventTypes;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.core.schema.TypeConstants;
import org.nuxeo.ecm.core.schema.types.ComplexType;
import org.nuxeo.ecm.core.schema.types.Field;
import org.nuxeo.ecm.core.schema.types.ListType;
import org.nuxeo.ecm.core.schema.types.Schema;
import org.nuxeo.ecm.core.schema.types.Type;
import org.nuxeo.runtime.api.Framework;

/**
 * Creates one content audit per pair of successive Nuxeo versions. Ordinary saves, versions and
 * proxies are deliberately ignored.
 * <p>
 * <b>How the version pair is established.</b> The listener hooks the two events of a check-in, both
 * fired on the <em>live</em> document. On {@code ABOUT_TO_CHECKIN}, fired inline just before the
 * new version exists, it records the current last version. On {@code DOCUMENT_CHECKEDIN} it reads
 * the new version from the platform's {@code checkedInVersionRef} property and compares the two.
 * <p>
 * Capturing the previous version <em>before</em> the fact is what makes the pair exact. Earlier
 * versions of this listener hooked {@code documentCreated}, filtered on {@code isVersion()}, walked
 * back up to the live document and then re-derived the previous version from
 * {@code ORDER BY uid:major_version DESC, uid:minor_version DESC} - an assumption about how labels
 * are numbered, which had to bail out whenever it could not be trusted. Nothing is assumed any
 * more; {@link #previousVersionRefFallback} only exists for the two core call sites that pass no
 * option map, and it orders by {@code ecm:versionCreated}, a fact rather than a convention.
 * <p>
 * <b>Disabling.</b> See {@link #DISABLE_BLOB_DIFF_LISTENER} and {@link #runDisabled(Runnable)}.
 */
public class BlobModificationListener implements EventListener {

    private static final Logger log = LogManager.getLogger(BlobModificationListener.class);

    /**
     * Event property holding the {@code DocumentRef} of the version that precedes the one being
     * created. Set by this listener on {@code ABOUT_TO_CHECKIN}, read back on
     * {@code DOCUMENT_CHECKEDIN}.
     */
    protected static final String PREVIOUS_VERSION_REF = "blobDiffPreviousVersionRef";

    /**
     * Platform property carrying the new version, set by {@code AbstractSession#notifyCheckedInVersion}.
     * There is no constant for it in the core API; the platform's own listeners use the raw string too.
     */
    protected static final String CHECKED_IN_VERSION_REF = "checkedInVersionRef";

    /** Guards against a complex type that refers to itself while walking a schema. */
    protected static final int MAX_SCHEMA_DEPTH = 10;

    /**
     * Blob xpath templates per schema name. Cleared when {@link BlobDiffComponent} stops, which a
     * hot reload does, since schemas can then be redefined.
     */
    protected static final Map<String, List<String>> BLOB_XPATH_TEMPLATES = new ConcurrentHashMap<>();

    /** @since 2025.1 */
    public static void clearBlobXPathTemplates() {
        BLOB_XPATH_TEMPLATES.clear();
    }

    /**
     * Context data / event property disabling this listener for a single operation, following the
     * platform convention ({@code DublinCoreListener#DISABLE_DUBLINCORE_LISTENER},
     * {@code CoreSession#DISABLE_AUDIT_LOGGER}, {@code VersioningService#DISABLE_AUTO_CHECKOUT}).
     * <p>
     * Set it on the <b>live document</b> before the save that creates the version:
     *
     * <pre>
     * doc.putContextData(BlobModificationListener.DISABLE_BLOB_DIFF_LISTENER, Boolean.TRUE);
     * doc.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.MINOR);
     * session.saveDocument(doc);
     * </pre>
     *
     * {@code CoreSession#saveDocument} copies the document context data into its option map, and
     * passes that map to the {@code aboutToCheckIn} and {@code documentCheckedIn} events it fires on
     * the live document for the version <b>it creates itself</b>, which are the two events this
     * listener reads.
     * <p>
     * <b>Scope.</b> That covers manual versioning only, a {@code VERSIONING_OPTION} set on the
     * document. Every other path creating a version builds its own option map, so the flag never
     * reaches the listener and the version is audited and diffed regardless:
     * <ul>
     * <li>an automatic versioning policy applied <i>during</i> the same {@code saveDocument} -
     * {@code collaborative-save}, shipped and active by default, snapshots a document before a user
     * other than its last contributor updates it, and {@code note-as-wiki} versions every note. Both
     * go through {@code DocumentModel#checkIn}, that is the path below;</li>
     * <li>{@code CoreSession#checkIn(DocumentRef, VersioningOption, String)}, which builds a
     * <i>fresh, empty</i> option map;</li>
     * <li>{@code CoreSession#restoreToVersion}, which snapshots the live document first, and
     * {@code CoreSession#publishDocument}, both of which pass no option map at all.</li>
     * </ul>
     * Anything that checks in explicitly, restores, publishes, or saves a document that a policy may
     * version - an importer or a migration re-saving documents last edited by someone else, typically
     * - must use {@link #runDisabled(Runnable)} instead.
     *
     * @since 2025.1
     */
    public static final String DISABLE_BLOB_DIFF_LISTENER = "disableBlobDiffListener";

    /**
     * Thread-scoped kill switch, covering the cases where no event property can be passed - an
     * explicit {@code session.checkIn(...)}, an automatic versioning policy, a restore, a
     * publication, a migration script, a bulk importer.
     */
    protected static final ThreadLocal<Boolean> DISABLED = new ThreadLocal<>();

    /**
     * Runs the given code with this listener disabled on the current thread, whatever the API used
     * to create versions.
     * <p>
     * The previous state is restored in a {@code finally} block, so nesting is safe and an exception
     * can never leave the listener disabled for the rest of the thread - which, on a pooled request
     * thread, would silently stop auditing the whole instance.
     *
     * @since 2025.1
     */
    public static void runDisabled(Runnable runnable) {
        runDisabled(() -> {
            runnable.run();
            return null;
        });
    }

    /**
     * Value-returning variant of {@link #runDisabled(Runnable)}.
     *
     * @since 2025.1
     */
    public static <T> T runDisabled(Supplier<T> supplier) {
        Boolean previous = DISABLED.get();
        DISABLED.set(Boolean.TRUE);
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                DISABLED.remove();
            } else {
                DISABLED.set(previous);
            }
        }
    }

    /**
     * {@code true} when the thread-scoped switch is currently on.
     *
     * @since 2025.1
     */
    public static boolean isDisabledForThread() {
        return Boolean.TRUE.equals(DISABLED.get());
    }

    @Override
    public void handleEvent(Event event) {
        if (!(event.getContext() instanceof DocumentEventContext context)) {
            return;
        }
        boolean aboutToCheckIn = DocumentEventTypes.ABOUT_TO_CHECKIN.equals(event.getName());
        if (!aboutToCheckIn && !DocumentEventTypes.DOCUMENT_CHECKEDIN.equals(event.getName())) {
            return;
        }
        if (isDisabledForThread() || Boolean.TRUE.equals(context.getProperty(DISABLE_BLOB_DIFF_LISTENER))) {
            // Explicitly muted by the caller: no audit entry, no work, no BlobDiff.
            return;
        }
        // Both events are fired on the live document, never on the version.
        DocumentModel liveDoc = context.getSourceDocument();
        if (liveDoc == null || liveDoc.isProxy() || liveDoc.isVersion()) {
            return;
        }
        BlobDiffService service = Framework.getService(BlobDiffService.class);
        if (service == null) {
            return;
        }
        BlobDiffConfigDescriptor config = service.getConfig();
        if (!config.isEnabled() || !config.acceptsDocType(liveDoc.getType())) {
            return;
        }
        CoreSession session = liveDoc.getCoreSession();
        if (session == null) {
            return;
        }
        if (aboutToCheckIn) {
            capturePreviousVersion(session, liveDoc, context);
        } else {
            scheduleDiffs(session, liveDoc, context, config, event.getTime());
        }
    }

    /**
     * Records, <b>before</b> the check-in happens, which version is the current last one.
     * <p>
     * This is what removes any need to guess afterwards. {@code ABOUT_TO_CHECKIN} is fired inline on
     * the live document just before the new version is created, so
     * {@code getLastDocumentVersionRef} returns exactly the version the new one will succeed - no
     * ordering assumption, no heuristic.
     * <p>
     * The value is stored in the event properties because {@code AbstractSession} passes the very
     * same option map to {@code notifyCheckedInVersion}, which copies it into the
     * {@code DOCUMENT_CHECKEDIN} properties. Two call sites pass {@code null} options there, so the
     * value can be missing; {@link #scheduleDiffs} falls back to a query in that case.
     */
    protected void capturePreviousVersion(CoreSession session, DocumentModel liveDoc, DocumentEventContext context) {
        // Privileged: a version the caller cannot read must not silently look like "no previous
        // version", which would turn a real modification into an unaudited first version.
        Function<CoreSession, DocumentRef> lookup = s -> s.getLastDocumentVersionRef(liveDoc.getRef());
        DocumentRef previous = CoreInstance.doPrivileged(session, lookup);
        if (previous != null) {
            context.setProperty(PREVIOUS_VERSION_REF, previous);
        }
    }

    protected void scheduleDiffs(CoreSession session, DocumentModel liveDoc, DocumentEventContext context,
            BlobDiffConfigDescriptor config, long eventTime) {
        if (!(context.getProperty(CHECKED_IN_VERSION_REF) instanceof DocumentRef newRef)) {
            log.warn("No {} on the {} event of {}, skipping blob diff", CHECKED_IN_VERSION_REF,
                    DocumentEventTypes.DOCUMENT_CHECKEDIN, liveDoc.getId());
            return;
        }
        DocumentRef previousRef = context.getProperty(PREVIOUS_VERSION_REF) instanceof DocumentRef captured ? captured
                : previousVersionRefFallback(session, liveDoc.getId(), newRef);
        if (previousRef == null) {
            log.debug("Document {} has no previous version, nothing to diff", liveDoc.getId());
            return;
        }

        Function<CoreSession, DocumentModel[]> load = s -> new DocumentModel[] {
                s.exists(previousRef) ? s.getDocument(previousRef) : null,
                s.exists(newRef) ? s.getDocument(newRef) : null };
        DocumentModel[] pair = CoreInstance.doPrivileged(session, load);
        DocumentModel previousVersion = pair[0];
        DocumentModel newVersion = pair[1];
        if (previousVersion == null || newVersion == null) {
            log.warn("Cannot load the version pair ({}, {}) of {}, skipping blob diff", previousRef, newRef,
                    liveDoc.getId());
            return;
        }

        VersionContext versionContext = new VersionContext(previousVersion.getId(), previousVersion.getVersionLabel(),
                newVersion.getId(), newVersion.getVersionLabel(), newVersion.getVersionSeriesId());
        EventService eventService = Framework.getService(EventService.class);
        String principal = session.getPrincipal().getName();
        for (BlobPair blobs : blobPairs(previousVersion, newVersion, config)) {
            BlobDiffTrigger.Outcome outcome = blobs.unattributed()
                    ? BlobDiffTrigger.Outcome.skipped(STATUS_SKIPPED_LIST_CHANGED)
                    : BlobDiffTrigger.scheduleIfNeeded(liveDoc, blobs.xpath(), blobs.oldBlob(), blobs.newBlob(),
                            versionContext, principal, eventTime);
            if (outcome.isAuditable()) {
                eventService.fireEvent(
                        buildAuditEvent(liveDoc, session, blobs.xpath(), blobs.oldBlob(), blobs.newBlob(), outcome));
            }
        }
    }

    /**
     * Previous version when {@code ABOUT_TO_CHECKIN} could not hand it over.
     * <p>
     * Ordered by {@code ecm:versionCreated}, which is a fact about when the version was created,
     * not an assumption about how its label was numbered. Two rows are fetched because the newest
     * one is the version that was just created.
     */
    protected DocumentRef previousVersionRefFallback(CoreSession session, String liveDocId, DocumentRef newRef) {
        String nxql = "SELECT * FROM Document WHERE ecm:versionVersionableId = " + NXQL.escapeString(liveDocId)
                + " AND ecm:isVersion = 1 ORDER BY ecm:versionCreated DESC";
        Function<CoreSession, DocumentModelList> query = s -> s.query(nxql, null, 2, 0, false);
        for (DocumentModel version : CoreInstance.doPrivileged(session, query)) {
            if (!version.getRef().equals(newRef)) {
                return version.getRef();
            }
        }
        return null;
    }

    /**
     * The blobs to compare between the two versions.
     * <p>
     * Unless {@code <xpaths>} lists {@code *}, the configured paths - {@code file:content} by
     * default - are compared verbatim: a path holding no blob simply yields {@code null} in
     * {@link #safeGetBlob} and is skipped downstream, so there is nothing to pre-filter.
     * <p>
     * With {@code *}, the paths are derived from the <b>schemas the new version carries</b>, those of
     * facets added to the document included, and cached per schema. The previous implementation
     * walked every schema and every property of both version documents and called
     * {@code property.getValue()} on each, forcing the load of every complex and list property
     * inside the user transaction, on every single check-in. Blobs inside a list are paired by
     * {@link #pairListEntries}.
     *
     * @since 2025.1
     */
    protected List<BlobPair> blobPairs(DocumentModel previous, DocumentModel current,
            BlobDiffConfigDescriptor config) {
        List<BlobPair> pairs = new ArrayList<>();
        if (!config.isEveryXPath()) {
            for (String xpath : config.getXPaths()) {
                pairs.add(new BlobPair(xpath, safeGetBlob(previous, xpath), safeGetBlob(current, xpath), false));
            }
            return pairs;
        }
        for (String schema : current.getSchemas()) {
            for (String template : BLOB_XPATH_TEMPLATES.computeIfAbsent(schema,
                    BlobModificationListener::computeBlobXPathTemplates)) {
                if (template.indexOf('*') < 0) {
                    pairs.add(new BlobPair(template, safeGetBlob(previous, template),
                            safeGetBlob(current, template), false));
                } else {
                    pairListEntries(template, previous, current, pairs);
                }
            }
        }
        return pairs;
    }

    /**
     * Pairs the blobs of a list by content, never by index alone.
     * <p>
     * An index is not an identity: once an entry is removed, inserted or moved, the same index holds
     * another file on each side, and comparing them reported a change between two unrelated files.
     * A blob present on both sides, wherever it sits, is unchanged. A new blob is compared with the
     * one it replaces only when the list kept its shape and that one is gone from the new version;
     * otherwise, if blobs also disappeared, it is audited without a diff. A new blob with nothing
     * gone is an addition, which is not audited, like any added blob.
     */
    protected void pairListEntries(String template, DocumentModel previous, DocumentModel current,
            List<BlobPair> pairs) {
        Map<String, Blob> before = blobsAt(template, previous);
        Map<String, Blob> after = blobsAt(template, current);
        Set<String> digestsBefore = digests(before.values());
        Set<String> digestsAfter = digests(after.values());
        boolean somethingGone = before.values().stream().anyMatch(blob -> !holds(digestsAfter, blob));
        boolean sameShape = before.keySet().equals(after.keySet());
        for (Map.Entry<String, Blob> entry : after.entrySet()) {
            Blob newBlob = entry.getValue();
            if (holds(digestsBefore, newBlob)) {
                continue;
            }
            Blob replaced = sameShape ? before.get(entry.getKey()) : null;
            if (replaced != null && !holds(digestsAfter, replaced)) {
                pairs.add(new BlobPair(entry.getKey(), replaced, newBlob, false));
            } else if (somethingGone) {
                pairs.add(new BlobPair(entry.getKey(), null, newBlob, true));
            }
        }
    }

    protected Map<String, Blob> blobsAt(String template, DocumentModel doc) {
        List<String> xpaths = new ArrayList<>();
        expandTemplate(template, doc, xpaths);
        Map<String, Blob> blobs = new LinkedHashMap<>();
        for (String xpath : xpaths) {
            Blob blob = safeGetBlob(doc, xpath);
            if (blob != null) {
                blobs.put(xpath, blob);
            }
        }
        return blobs;
    }

    protected static Set<String> digests(Iterable<Blob> blobs) {
        Set<String> digests = new HashSet<>();
        for (Blob blob : blobs) {
            if (blob.getDigest() != null) {
                digests.add(blob.getDigest());
            }
        }
        return digests;
    }

    /** A blob with no digest is never found on the other side: nothing proves it unchanged. */
    protected static boolean holds(Set<String> digests, Blob blob) {
        return blob.getDigest() != null && digests.contains(blob.getDigest());
    }

    /**
     * Two blobs to compare at one xpath.
     *
     * @param unattributed the new blob of a list whose previous file cannot be told: audited
     *            without a diff
     * @since 2025.1
     */
    protected record BlobPair(String xpath, Blob oldBlob, Blob newBlob, boolean unattributed) {
    }

    /**
     * Blob xpaths of a schema, as <b>templates</b>: a {@code *} stands for the index of a list entry,
     * which only a document can resolve.
     */
    protected static List<String> computeBlobXPathTemplates(String schemaName) {
        SchemaManager schemaManager = Framework.getService(SchemaManager.class);
        Schema schema = schemaManager == null ? null : schemaManager.getSchema(schemaName);
        if (schema == null) {
            return List.of();
        }
        Set<String> xpaths = new LinkedHashSet<>();
        String prefix = schema.getNamespace().hasPrefix() ? schema.getNamespace().prefix : schema.getName();
        for (Field field : schema.getFields()) {
            collectBlobFields(prefix + ":" + field.getName().getLocalName(), field.getType(), xpaths, 0);
        }
        return List.copyOf(xpaths);
    }

    /**
     * Walks a field type, appending the path of every blob found underneath.
     * <p>
     * The depth is bounded: a complex type referring to itself would otherwise recurse for ever.
     */
    protected static void collectBlobFields(String path, Type type, Set<String> xpaths, int depth) {
        if (depth > MAX_SCHEMA_DEPTH) {
            log.warn("Giving up on blob xpath {} beyond depth {}", path, MAX_SCHEMA_DEPTH);
            return;
        }
        if (TypeConstants.isContentType(type)) {
            xpaths.add(path);
            return;
        }
        if (type instanceof ListType list) {
            collectBlobFields(path + "/*", list.getFieldType(), xpaths, depth + 1);
        } else if (type instanceof ComplexType complex) {
            for (Field field : complex.getFields()) {
                collectBlobFields(path + "/" + field.getName().getLocalName(), field.getType(), xpaths, depth + 1);
            }
        }
    }

    /**
     * Resolves the {@code *} of a list template against the actual entries of a document.
     * <p>
     * Only the list properties that really lead to a blob are read, instead of every property of
     * every schema.
     */
    protected void expandTemplate(String template, DocumentModel doc, List<String> xpaths) {
        int star = template.indexOf('*');
        String listPath = template.substring(0, star - 1);
        int size;
        try {
            Object value = doc.getPropertyValue(listPath);
            size = value instanceof List<?> list ? list.size() : 0;
        } catch (RuntimeException e) { // NOSONAR - an unreadable list is simply not expanded
            // The xpath template comes from the schema, the document may not carry the schema.
            log.debug("Cannot read list {} on {} to expand template {}", listPath, doc.getId(), template, e);
            return;
        }
        for (int i = 0; i < size; i++) {
            String resolved = template.substring(0, star) + i + template.substring(star + 1);
            if (resolved.indexOf('*') < 0) {
                xpaths.add(resolved);
            } else {
                expandTemplate(resolved, doc, xpaths);
            }
        }
    }

    protected Blob safeGetBlob(DocumentModel doc, String xpath) {
        try {
            Object value = doc.getPropertyValue(xpath);
            return value instanceof Blob blob ? blob : null;
        } catch (RuntimeException e) { // NOSONAR - an unreadable property is not a binary change
            log.debug("Cannot read property {} on {}", xpath, doc.getId(), e);
            return null;
        }
    }

    protected Event buildAuditEvent(DocumentModel liveDoc, CoreSession session, String xpath, Blob oldBlob,
            Blob newBlob, BlobDiffTrigger.Outcome outcome) {
        DocumentEventContext ctx = new DocumentEventContext(session, session.getPrincipal(), liveDoc);
        ctx.setCategory(AUDIT_CATEGORY);
        ctx.setComment(xpath + " : " + commentSuffix(outcome));
        ctx.setProperty(EXT_XPATH, xpath);
        ctx.setProperty(EXT_OLD_FILENAME, filenameOf(oldBlob));
        ctx.setProperty(EXT_NEW_FILENAME, filenameOf(newBlob));
        if (outcome.skipReason() != null) {
            ctx.setProperty(EXT_SKIP_REASON, outcome.skipReason());
        } else {
            ctx.setProperty(EXT_CORRELATION_ID, outcome.correlationId());
        }
        return ctx.newEvent(EVENT_BLOB_MODIFIED);
    }

    protected String commentSuffix(BlobDiffTrigger.Outcome outcome) {
        if (STATUS_SKIPPED_SIZE.equals(outcome.skipReason())) {
            return "binary changed (too large)";
        }
        if (STATUS_SKIPPED_TYPE.equals(outcome.skipReason())) {
            return "binary changed (unsupported format)";
        }
        if (STATUS_SKIPPED_NOT_MANAGED.equals(outcome.skipReason())) {
            return "binary changed (blob not managed by a provider)";
        }
        if (STATUS_SKIPPED_LIST_CHANGED.equals(outcome.skipReason())) {
            return "binary changed (list entries also removed or moved, previous file unknown)";
        }
        return "binary content modified between versions";
    }

    protected String filenameOf(Blob blob) {
        return blob != null && blob.getFilename() != null ? blob.getFilename() : "";
    }

}

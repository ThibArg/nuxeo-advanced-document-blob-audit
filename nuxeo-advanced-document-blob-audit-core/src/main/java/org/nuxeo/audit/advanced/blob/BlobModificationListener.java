/*
 * (C) Copyright 2026 Nuxeo SA (http://nuxeo.com/) and others.
 * Licensed under the Apache License, Version 2.0.
 */
package org.nuxeo.audit.advanced.blob;

import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.AUDIT_CATEGORY;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EVENT_BLOB_MODIFIED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_CORRELATION_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_NEW_FILENAME;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_OLD_FILENAME;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_SKIP_REASON;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_XPATH;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_NOT_MANAGED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_SIZE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_TYPE;

import java.util.ArrayList;
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
import org.nuxeo.ecm.core.schema.DocumentType;
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
     * Blob xpath templates per document type. A document type is immutable for the life of the
     * runtime, so this never needs invalidating.
     */
    protected static final Map<String, List<String>> BLOB_XPATH_TEMPLATES = new ConcurrentHashMap<>();

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
     * {@code CoreSession#saveDocument} copies the document context data into the event options, and
     * {@code notifyCheckedInVersion} forwards them to the {@code documentCreated} event fired on the
     * new version, which is what this listener reads.
     * <p>
     * <b>Limitation.</b> {@code CoreSession#checkIn(DocumentRef, VersioningOption, String)} builds a
     * <i>fresh, empty</i> option map, so context data set on the document is <b>not</b> propagated on
     * that path. Use {@link #runDisabled(Runnable)} when you check in explicitly.
     *
     * @since 2025.2
     */
    public static final String DISABLE_BLOB_DIFF_LISTENER = "disableBlobDiffListener";

    /**
     * Thread-scoped kill switch, covering the cases where no event property can be passed - an
     * explicit {@code session.checkIn(...)}, a migration script, a bulk importer.
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
     * @since 2025.2
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
     * @since 2025.2
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
     * @since 2025.2
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
        for (String xpath : blobXPaths(liveDoc, newVersion, config)) {
            Blob oldBlob = safeGetBlob(previousVersion, xpath);
            Blob newBlob = safeGetBlob(newVersion, xpath);
            BlobDiffTrigger.Outcome outcome = BlobDiffTrigger.scheduleIfNeeded(liveDoc, xpath, oldBlob, newBlob,
                    versionContext, principal, eventTime);
            if (outcome.isAuditable()) {
                eventService.fireEvent(buildAuditEvent(liveDoc, session, xpath, oldBlob, newBlob, outcome));
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
     * The blob xpaths to inspect on this document type.
     * <p>
     * When {@code <xpaths>} is configured - the shipped default is {@code file:content} - it is
     * used verbatim: a path holding no blob simply yields {@code null} in {@link #safeGetBlob} and
     * is skipped downstream, so there is nothing to pre-filter.
     * <p>
     * Otherwise the paths are derived from the <b>document type</b> and cached. The previous
     * implementation walked every schema and every property of both version documents and called
     * {@code property.getValue()} on each, forcing the load of every complex and list property
     * inside the user transaction, on every single check-in. A document type does not change
     * between two check-ins, so this is computed once per type and per JVM.
     */
    protected List<String> blobXPaths(DocumentModel liveDoc, DocumentModel version,
            BlobDiffConfigDescriptor config) {
        if (!config.getXPaths().isEmpty()) {
            return config.getXPaths();
        }
        List<String> templates = BLOB_XPATH_TEMPLATES.computeIfAbsent(liveDoc.getType(),
                BlobModificationListener::computeBlobXPathTemplates);
        return expandTemplates(templates, version);
    }

    /**
     * Blob xpaths of a document type, as <b>templates</b>: a {@code *} stands for the index of a
     * list entry, which only a document can resolve.
     */
    protected static List<String> computeBlobXPathTemplates(String docType) {
        SchemaManager schemaManager = Framework.getService(SchemaManager.class);
        DocumentType type = schemaManager == null ? null : schemaManager.getDocumentType(docType);
        if (type == null) {
            return List.of();
        }
        Set<String> xpaths = new LinkedHashSet<>();
        for (Schema schema : type.getSchemas()) {
            String prefix = schema.getNamespace().hasPrefix() ? schema.getNamespace().prefix : schema.getName();
            for (Field field : schema.getFields()) {
                collectBlobFields(prefix + ":" + field.getName().getLocalName(), field.getType(), xpaths, 0);
            }
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
     * Resolves the {@code *} of list templates against the actual entries of a document.
     * <p>
     * Only the list properties that really lead to a blob are read, instead of every property of
     * every schema.
     */
    protected List<String> expandTemplates(List<String> templates, DocumentModel doc) {
        List<String> xpaths = new ArrayList<>(templates.size());
        for (String template : templates) {
            if (template.indexOf('*') < 0) {
                xpaths.add(template);
            } else {
                expandTemplate(template, doc, xpaths);
            }
        }
        return xpaths;
    }

    protected void expandTemplate(String template, DocumentModel doc, List<String> xpaths) {
        int star = template.indexOf('*');
        String listPath = template.substring(0, star - 1);
        int size;
        try {
            Object value = doc.getPropertyValue(listPath);
            size = value instanceof List<?> list ? list.size() : 0;
        } catch (RuntimeException e) {
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
        } catch (RuntimeException e) {
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
        return "binary content modified between versions";
    }

    protected String filenameOf(Blob blob) {
        return blob != null && blob.getFilename() != null ? blob.getFilename() : "";
    }

}

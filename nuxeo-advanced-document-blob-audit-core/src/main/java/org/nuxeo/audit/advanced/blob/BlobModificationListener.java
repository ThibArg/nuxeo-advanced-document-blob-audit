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
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.event.DocumentEventTypes;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.core.schema.types.SimpleTypeImpl;
import org.nuxeo.ecm.core.schema.types.Type;
import org.nuxeo.ecm.core.schema.types.primitives.BinaryType;
import org.nuxeo.runtime.api.Framework;

/**
 * Creates one content audit per pair of successive, normally ordered Nuxeo versions.
 * Ordinary saves, live documents and proxies are deliberately ignored.
 * <p>
 * <b>Assumption on version ordering.</b> The previous version is looked up with
 * {@code ORDER BY uid:major_version DESC, uid:minor_version DESC}, which assumes the usual, expected
 * Nuxeo behaviour: versions are created one after the other and each new version is greater than the
 * previous one. When that is not true - typically after a bulk rewrite of version numbers straight
 * in the database - the listener detects that the newest ordered version is not the one just
 * created, logs a WARN and skips the diff rather than comparing an ambiguous pair.
 * <p>
 * <b>Disabling.</b> See {@link #DISABLE_BLOB_DIFF_LISTENER} and {@link #runDisabled(Runnable)}.
 */
public class BlobModificationListener implements EventListener {

    private static final Logger log = LogManager.getLogger(BlobModificationListener.class);

    protected static final int MAX_SOURCE_HOPS = 5;

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
        if (!DocumentEventTypes.DOCUMENT_CREATED.equals(event.getName())
                || !(event.getContext() instanceof DocumentEventContext context)) {
            return;
        }
        if (isDisabledForThread() || Boolean.TRUE.equals(context.getProperty(DISABLE_BLOB_DIFF_LISTENER))) {
            // Explicitly muted by the caller: no audit entry, no work, no BlobDiff.
            return;
        }
        DocumentModel receivedVersion = context.getSourceDocument();
        if (receivedVersion == null || receivedVersion.isProxy() || !receivedVersion.isVersion()) {
            return;
        }
        BlobDiffService service = Framework.getService(BlobDiffService.class);
        if (service == null || !service.getConfig().isEnabled()) {
            return;
        }
        CoreSession session = receivedVersion.getCoreSession();
        if (session == null) {
            return;
        }

        DocumentModel liveDoc = findLiveDocument(session, receivedVersion);
        if (liveDoc == null) {
            log.warn("Cannot resolve the live document for version {}, skipping blob diff", receivedVersion.getId());
            return;
        }

        DocumentModelList versions = orderedVersions(session, liveDoc.getId());
        if (versions.isEmpty()) {
            log.warn("No version returned for live document {} immediately after creating version {}",
                    liveDoc.getId(), receivedVersion.getId());
            return;
        }
        DocumentModel currentVersion = versions.get(0);
        if (!receivedVersion.getId().equals(currentVersion.getId())) {
            // The algorithm assumes normal, monotonically increasing Nuxeo version numbers.
            // Never compare an ambiguous pair silently.
            log.warn("Newest ordered version {} is not the documentCreated version {} for live document {}; "
                    + "version numbers may not be monotonically increasing, skipping blob diff",
                    currentVersion.getId(), receivedVersion.getId(), liveDoc.getId());
            return;
        }
        if (versions.size() < 2) {
            log.debug("Version {} is the first version of {}, no previous version to diff",
                    currentVersion.getVersionLabel(), liveDoc.getId());
            return;
        }

        DocumentModel previousVersion = versions.get(1);
        VersionContext versionContext = new VersionContext(previousVersion.getId(), previousVersion.getVersionLabel(),
                currentVersion.getId(), currentVersion.getVersionLabel(), currentVersion.getVersionSeriesId());
        EventService eventService = Framework.getService(EventService.class);
        String principal = session.getPrincipal().getName();
        for (String xpath : collectBlobXPaths(previousVersion, currentVersion, service)) {
            Blob oldBlob = safeGetBlob(previousVersion, xpath);
            Blob newBlob = safeGetBlob(currentVersion, xpath);
            BlobDiffTrigger.Outcome outcome = BlobDiffTrigger.scheduleIfNeeded(liveDoc, xpath, oldBlob, newBlob,
                    versionContext, principal, event.getTime());
            if (outcome.isAuditable()) {
                eventService.fireEvent(buildAuditEvent(liveDoc, session, xpath, oldBlob, newBlob, outcome));
            }
        }
    }

    protected DocumentModel findLiveDocument(CoreSession session, DocumentModel version) {
        DocumentModel doc = version;
        for (int i = 0; i < MAX_SOURCE_HOPS && doc != null && !isLive(doc); i++) {
            doc = session.getSourceDocument(doc.getRef());
        }
        return doc != null && isLive(doc) ? doc : null;
    }

    protected boolean isLive(DocumentModel doc) {
        return !doc.isVersion() && !doc.isProxy();
    }

    /**
     * The two most recent versions of the series, newest first.
     * <p>
     * Two deliberate choices here.
     * <p>
     * <b>Bounded.</b> Only two rows are fetched. The previous implementation selected every version
     * of the series at each check-in, so a document with 500 versions loaded 500 document models
     * inside the user transaction - a cost growing quadratically over the life of the document.
     * <p>
     * <b>Privileged.</b> The query runs unfiltered. With the user session, a version the caller
     * cannot read is silently dropped from the result and the second row is <em>not</em> the real
     * previous version: the diff would then compare a wrong pair without any way to notice. Nothing
     * leaks, since only ids, labels and blobs of the two versions are used, and the resulting
     * BlobDiff lives under the restricted container.
     */
    protected DocumentModelList orderedVersions(CoreSession session, String liveDocId) {
        String nxql = "SELECT * FROM Document WHERE ecm:versionVersionableId = "
                + NXQL.escapeString(liveDocId)
                + " AND ecm:isVersion = 1 ORDER BY uid:major_version DESC, uid:minor_version DESC";
        // An explicit Function is required: an inline lambda is both Function- and
        // Consumer-compatible, which makes the doPrivileged overload ambiguous.
        Function<CoreSession, DocumentModelList> query = s -> s.query(nxql, null, 2, 0, false);
        return CoreInstance.doPrivileged(session, query);
    }

    protected List<String> collectBlobXPaths(DocumentModel previous, DocumentModel current,
            BlobDiffService service) {
        BlobDiffConfigDescriptor config = service.getConfig();
        if (!config.acceptsDocType(current.getType())) {
            return List.of();
        }
        if (!config.getXPaths().isEmpty()) {
            return config.getXPaths().stream()
                    .filter(xpath -> isBlob(previous, xpath) || isBlob(current, xpath))
                    .toList();
        }
        Set<String> xpaths = new LinkedHashSet<>();
        collectBlobXPaths(previous, xpaths);
        collectBlobXPaths(current, xpaths);
        return new ArrayList<>(xpaths);
    }

    protected void collectBlobXPaths(DocumentModel doc, Set<String> xpaths) {
        for (String schema : doc.getSchemas()) {
            for (Property property : rootPropertiesOf(doc, schema)) {
                collectBlobs(property, xpaths);
            }
        }
    }

    protected Collection<Property> rootPropertiesOf(DocumentModel doc, String schema) {
        return doc.getPropertyObjects(schema);
    }

    protected void collectBlobs(Property property, Set<String> xpaths) {
        if (isBlobProperty(property)) {
            xpaths.add(normalize(property.getXPath()));
            return;
        }
        if (property.isComplex() || property.isList()) {
            for (Property child : property.getChildren()) {
                collectBlobs(child, xpaths);
            }
        }
    }

    protected boolean isBlob(DocumentModel doc, String xpath) {
        try {
            return isBlobProperty(doc.getProperty(xpath));
        } catch (RuntimeException e) {
            return false;
        }
    }

    protected boolean isBlobProperty(Property property) {
        Type type = property.getType();
        if (type instanceof SimpleTypeImpl simple) {
            type = simple.getPrimitiveType();
        }
        return type instanceof BinaryType || property.getValue() instanceof Blob;
    }

    protected Blob safeGetBlob(DocumentModel doc, String xpath) {
        try {
            Object value = doc.getPropertyValue(xpath);
            return value instanceof Blob blob ? blob : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Builds the {@code blobContentModified} entry, for a scheduled diff or for a binary change
     * that cannot be diffed.
     * <p>
     * The skip case carries {@code skipReason} and <b>no</b> {@code diffCorrelationId}: no
     * {@code BlobDiff} will ever be created, and a correlation id pointing at a document that does
     * not exist would be worse than no id at all.
     * <p>
     * The comment is a plain English sentence, persisted as is, consistent with the nominal path.
     */
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

    protected String normalize(String xpath) {
        return xpath != null && xpath.startsWith("/") ? xpath.substring(1) : xpath;
    }
}

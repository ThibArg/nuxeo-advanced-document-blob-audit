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

import static java.lang.Boolean.TRUE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.AUDIT_CATEGORY;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EVENT_BLOB_MODIFIED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_CORRELATION_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_NEW_FILENAME;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_NEW_MIME_TYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_OLD_FILENAME;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_OLD_MIME_TYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_SKIP_REASON;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_XPATH;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.STATUS_SKIPPED_LIST_CHANGED;

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
 * Capturing the previous version <em>before</em> the fact is what makes the pair exact: nothing is
 * assumed about how version labels are numbered. {@link #previousVersionRefFallback} only exists for
 * the two core call sites that pass no option map, and it orders by {@code ecm:versionCreated}, a
 * fact rather than a convention.
 * <p>
 * <b>Disabling.</b> See {@link #DISABLE_BLOB_DIFF_LISTENER} and {@link #runDisabled(Runnable)}.
 *
 * @since 2025.1
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
     * Runs the given code with this listener disabled on the current thread, and returns its result,
     * like {@link #runDisabled(Runnable)}.
     *
     * @since 2025.1
     */
    public static <T> T runDisabled(Supplier<T> supplier) {
        Boolean previous = DISABLED.get();
        DISABLED.set(TRUE);
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
     * Returns whether the thread-scoped switch is currently on.
     *
     * @since 2025.1
     */
    public static boolean isDisabledForThread() {
        return TRUE.equals(DISABLED.get());
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
        if (isDisabledForThread() || TRUE.equals(context.getProperty(DISABLE_BLOB_DIFF_LISTENER))) {
            // Explicitly muted by the caller: no audit entry, no work, no BlobDiff.
            return;
        }
        // Both events are fired on the live document, never on the version.
        DocumentModel liveDoc = context.getSourceDocument();
        if (liveDoc == null || liveDoc.isProxy() || liveDoc.isVersion()) {
            return;
        }
        var service = Framework.getService(BlobDiffService.class);
        if (service == null) {
            return;
        }
        BlobDiffConfigDescriptor config = service.getConfig();
        if (!config.isEnabled() || !config.acceptsDocType(liveDoc.getType())) {
            return;
        }
        // The new version holds what the live document holds: with no blob there, nothing can be
        // compared, and the version lookups would cost queries in the user's transaction for nothing.
        if (!VersionBlobPairs.holdsBlob(liveDoc, config)) {
            return;
        }
        CoreSession session = liveDoc.getCoreSession();
        if (session == null) {
            return;
        }
        if (aboutToCheckIn) {
            capturePreviousVersion(session, liveDoc, context);
        } else {
            scheduleDiffs(service, session, liveDoc, context, config, event.getTime());
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

    protected void scheduleDiffs(BlobDiffService service, CoreSession session, DocumentModel liveDoc,
            DocumentEventContext context, BlobDiffConfigDescriptor config, long eventTime) {
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

        var versionContext = new VersionContext(previousVersion.getId(), previousVersion.getVersionLabel(),
                newVersion.getId(), newVersion.getVersionLabel(), newVersion.getVersionSeriesId());
        var eventService = Framework.getService(EventService.class);
        String principal = session.getPrincipal().getName();
        for (VersionBlobPairs.BlobPair blobs : VersionBlobPairs.of(previousVersion, newVersion, config)) {
            BlobDiffTrigger.Outcome outcome = blobs.unattributed()
                    ? BlobDiffTrigger.Outcome.skipped(STATUS_SKIPPED_LIST_CHANGED)
                    : BlobDiffTrigger.scheduleIfNeeded(service, liveDoc, blobs.xpath(), blobs.oldBlob(),
                            blobs.newBlob(), versionContext, principal, eventTime);
            if (outcome.isAuditable()) {
                eventService.fireEvent(
                        buildAuditEvent(liveDoc, session, blobs.xpath(), blobs.oldBlob(), blobs.newBlob(), outcome));
            }
        }
    }

    /**
     * Returns the previous version when {@code ABOUT_TO_CHECKIN} could not hand it over, or
     * {@code null} when there is none.
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

    protected Event buildAuditEvent(DocumentModel liveDoc, CoreSession session, String xpath, Blob oldBlob,
            Blob newBlob, BlobDiffTrigger.Outcome outcome) {
        var ctx = new DocumentEventContext(session, session.getPrincipal(), liveDoc);
        ctx.setCategory(AUDIT_CATEGORY);
        ctx.setComment(xpath + " : " + commentSuffix(outcome));
        ctx.setProperty(EXT_XPATH, xpath);
        ctx.setProperty(EXT_OLD_FILENAME, filenameOf(oldBlob));
        ctx.setProperty(EXT_NEW_FILENAME, filenameOf(newBlob));
        ctx.setProperty(EXT_OLD_MIME_TYPE, mimeTypeOf(oldBlob));
        ctx.setProperty(EXT_NEW_MIME_TYPE, mimeTypeOf(newBlob));
        if (outcome.skipReason() != null) {
            ctx.setProperty(EXT_SKIP_REASON, outcome.skipReason());
        } else {
            ctx.setProperty(EXT_CORRELATION_ID, outcome.correlationId());
        }
        return ctx.newEvent(EVENT_BLOB_MODIFIED);
    }

    protected String commentSuffix(BlobDiffTrigger.Outcome outcome) {
        return (outcome.skipReason() == null ? DiffStatus.OK : DiffStatus.of(outcome.skipReason())).auditComment();
    }

    protected String filenameOf(Blob blob) {
        return blob != null && blob.getFilename() != null ? blob.getFilename() : "";
    }

    /**
     * Returns the mime type of the blob, or an empty string.
     *
     * @since 2025.1
     */
    protected String mimeTypeOf(Blob blob) {
        return blob != null && blob.getMimeType() != null ? blob.getMimeType() : "";
    }

}

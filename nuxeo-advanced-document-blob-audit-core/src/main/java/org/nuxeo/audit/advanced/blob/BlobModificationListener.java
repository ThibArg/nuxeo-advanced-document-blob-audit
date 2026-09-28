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
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_XPATH;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.LifeCycleConstants;
import org.nuxeo.ecm.core.api.event.DocumentEventTypes;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.core.schema.types.SimpleTypeImpl;
import org.nuxeo.ecm.core.schema.types.Type;
import org.nuxeo.ecm.core.schema.types.primitives.BinaryType;
import org.nuxeo.runtime.api.Framework;

/**
 * Detects binary content changes and records them.
 * <p>
 * This listener is deliberately <b>standalone</b>: it does not extend, wrap or depend on
 * {@code nuxeo-advanced-document-audit}. That plugin is published as an example meant to be forked
 * and tuned, so depending on it would mean depending on code the integrator is expected to modify.
 * The small amount of duplication (walking dirty properties) is the price of that independence.
 * <p>
 * Scope is the mirror image of the scalar-field audit plugin: this one looks <b>only</b> at
 * blob-valued properties, and ignores everything else.
 * <p>
 * The listener itself stays cheap. It compares digests through {@link BlobDiffTrigger} and writes
 * one small audit entry; extraction and comparison happen later, in an asynchronous work.
 * <p>
 * <b>LTS 2025 audit API (2025.16+).</b> The listener does <b>not</b> write audit entries itself:
 * {@code AuditBackend#addLogEntries} is deprecated since 2025.16. Instead it fires a
 * {@code blobContentModified} Nuxeo event; that event is declared in a route of the
 * {@code routes} extension point, so the platform audit pipeline (StreamAuditEventListener,
 * AuditRouter, audit/audit stream, StreamAuditWriter) builds the {@code LogEntry} and writes it to
 * every backend whose route matches. The extended infos are mapped from the event context
 * properties through the {@code extendedInfo} extension point (see blobaudit-audit-contrib.xml).
 * <p>
 * Side benefit: the event is bundled with the user transaction, so a rolled-back save produces no
 * audit entry.
 *
 * @since 1.0
 */
public class BlobModificationListener implements EventListener {

    private static final Logger log = LogManager.getLogger(BlobModificationListener.class);

    @Override
    public void handleEvent(Event event) {
        if (!DocumentEventTypes.BEFORE_DOC_UPDATE.equals(event.getName())) {
            return;
        }
        if (!(event.getContext() instanceof DocumentEventContext context)) {
            return;
        }
        DocumentModel doc = context.getSourceDocument();
        if (doc == null || doc.isProxy() || doc.isVersion()) {
            return;
        }
        // Lifecycle transitions and restores re-save the whole document; they are not user edits.
        if (Boolean.TRUE.equals(doc.getContextData(LifeCycleConstants.INITIAL_LIFECYCLE_STATE_OPTION_NAME))) {
            return;
        }

        BlobDiffService service = Framework.getService(BlobDiffService.class);
        if (service == null || !service.getConfig().isEnabled()) {
            return;
        }

        CoreSession session = doc.getCoreSession();
        if (session == null) {
            return;
        }

        // The event fires before the change is flushed, so reading the document back from storage
        // yields the previous state. This is what gives us the "before" blobs.
        DocumentModel previous;
        try {
            previous = session.getDocument(doc.getRef());
        } catch (RuntimeException e) {
            log.debug("Cannot load previous state of {}", doc.getId(), e);
            return;
        }

        EventService eventService = Framework.getService(EventService.class);
        for (String xpath : collectDirtyBlobXPaths(doc, service)) {
            Blob newBlob = safeGetBlob(doc, xpath);
            Blob oldBlob = safeGetBlob(previous, xpath);

            String correlationId = BlobDiffTrigger.scheduleIfNeeded(doc, xpath, oldBlob, newBlob,
                    session.getPrincipal().getName(), event.getTime());
            if (correlationId == null) {
                // Nothing worth diffing: unchanged bytes, rename only, unsupported type, too large.
                continue;
            }
            eventService.fireEvent(buildAuditEvent(doc, session, xpath, oldBlob, newBlob, correlationId));
        }
    }

    /** Returns the xpaths of blob properties that were modified and are in scope. */
    protected List<String> collectDirtyBlobXPaths(DocumentModel doc, BlobDiffService service) {
        List<String> xpaths = new ArrayList<>();
        BlobDiffConfigDescriptor config = service.getConfig();
        if (!config.acceptsDocType(doc.getType())) {
            return xpaths;
        }
        // When xpaths are configured explicitly, check those only: far cheaper than walking every
        // schema of the document, and that is the recommended production setup anyway.
        if (!config.getXPaths().isEmpty()) {
            for (String xpath : config.getXPaths()) {
                if (isDirtyBlob(doc, xpath)) {
                    xpaths.add(xpath);
                }
            }
            return xpaths;
        }
        for (String schema : doc.getSchemas()) {
            for (Property property : rootPropertiesOf(doc, schema)) {
                collectDirtyBlobs(property, xpaths);
            }
        }
        return xpaths;
    }

    /**
     * Returns the root properties of one schema of the document.
     * <p>
     * <b>Single point of adaptation.</b> {@code DocumentModel} has no {@code getPart(String)}: the
     * {@code DocumentPart} API has been deprecated for years in favour of the property accessors.
     * If {@code getPropertyObjects(String)} is not available in your target version, the equivalent
     * is to iterate the schema fields and resolve each one:
     *
     * <pre>
     * List&lt;Property&gt; roots = new ArrayList&lt;&gt;();
     * for (Field field : Framework.getService(SchemaManager.class).getSchema(schema).getFields()) {
     *     roots.add(doc.getProperty(schema + ':' + field.getName().getLocalName()));
     * }
     * return roots;
     * </pre>
     */
    protected Collection<Property> rootPropertiesOf(DocumentModel doc, String schema) {
        return doc.getPropertyObjects(schema);
    }

    protected void collectDirtyBlobs(Property property, List<String> xpaths) {
        if (!property.isDirty()) {
            return;
        }
        if (isBlobProperty(property)) {
            xpaths.add(normalize(property.getXPath()));
            return;
        }
        if (property.isComplex() || property.isList()) {
            for (Property child : property.getChildren()) {
                collectDirtyBlobs(child, xpaths);
            }
        }
    }

    protected boolean isBlobProperty(Property property) {
        Type type = property.getType();
        if (type instanceof SimpleTypeImpl simple) {
            type = simple.getPrimitiveType();
        }
        return type instanceof BinaryType || property.getValue() instanceof Blob;
    }

    protected boolean isDirtyBlob(DocumentModel doc, String xpath) {
        try {
            Property property = doc.getProperty(xpath);
            return property.isDirty() && isBlobProperty(property);
        } catch (RuntimeException e) {
            // property does not exist on this document type
            return false;
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

    /**
     * Builds the {@code blobContentModified} event from which the audit pipeline derives the log
     * entry. It carries <b>no business content</b>: only the xpath, the filenames and the correlation
     * id pointing at the {@code BlobDiff} document created asynchronously.
     * <p>
     * Standard fields (docUUID, docPath, docType, docLifeCycle, principalName, repositoryId,
     * eventDate) are filled by the platform from the {@link DocumentEventContext}; category and
     * comment come from the {@code category} / {@code comment} context properties. The property
     * keys below are the ones read by the {@code extendedInfo} contributions.
     */
    protected Event buildAuditEvent(DocumentModel doc, CoreSession session, String xpath, Blob oldBlob,
            Blob newBlob, String correlationId) {
        DocumentEventContext ctx = new DocumentEventContext(session, session.getPrincipal(), doc);
        ctx.setCategory(AUDIT_CATEGORY);
        ctx.setComment(xpath + " : binary content modified");
        ctx.setProperty(EXT_XPATH, xpath);
        ctx.setProperty(EXT_OLD_FILENAME, filenameOf(oldBlob));
        ctx.setProperty(EXT_NEW_FILENAME, filenameOf(newBlob));
        ctx.setProperty(EXT_CORRELATION_ID, correlationId);
        return ctx.newEvent(EVENT_BLOB_MODIFIED);
    }

    /** Empty string rather than null: keeps the EL mapping of the extended infos null-safe. */
    protected String filenameOf(Blob blob) {
        return blob != null && blob.getFilename() != null ? blob.getFilename() : "";
    }

    /** Strips the leading slash that {@code Property#getXPath()} returns for top level properties. */
    protected String normalize(String xpath) {
        return xpath != null && xpath.startsWith("/") ? xpath.substring(1) : xpath;
    }
}

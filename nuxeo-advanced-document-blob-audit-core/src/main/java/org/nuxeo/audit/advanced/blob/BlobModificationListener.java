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
import java.util.Date;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.audit.api.LogEntry;
import org.nuxeo.audit.service.AuditBackend;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.LifeCycleConstants;
import org.nuxeo.ecm.core.api.event.DocumentEventTypes;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventListener;
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
 * <b>LTS 2025 audit API.</b> Entries are written through {@link AuditBackend}. There is no
 * {@code AuditLogger} in the {@code org.nuxeo.audit.api} package: {@code AuditAdmin},
 * {@code AuditLogger}, {@code AuditReader} and {@code Logs} were all replaced by
 * {@code AuditBackend}.
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

        List<LogEntry> entries = new ArrayList<>();
        for (String xpath : collectDirtyBlobXPaths(doc, service)) {
            Blob newBlob = safeGetBlob(doc, xpath);
            Blob oldBlob = safeGetBlob(previous, xpath);

            String correlationId = BlobDiffTrigger.scheduleIfNeeded(doc, xpath, oldBlob, newBlob,
                    session.getPrincipal().getName(), event.getTime());
            if (correlationId == null) {
                // Nothing worth diffing: unchanged bytes, rename only, unsupported type, too large.
                continue;
            }
            entries.add(buildEntry(doc, session, xpath, oldBlob, newBlob, event.getTime(), correlationId));
        }

        if (!entries.isEmpty()) {
            Framework.getService(AuditBackend.class).addLogEntries(entries);
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
     * Builds the audit entry. It carries <b>no business content</b>: only the xpath, the filenames
     * and the correlation id pointing at the {@code BlobDiff} document created asynchronously.
     */
    protected LogEntry buildEntry(DocumentModel doc, CoreSession session, String xpath, Blob oldBlob, Blob newBlob,
            long eventTime, String correlationId) {
        String oldFilename = oldBlob != null ? oldBlob.getFilename() : null;
        String newFilename = newBlob != null ? newBlob.getFilename() : null;
        return LogEntry.builder(EVENT_BLOB_MODIFIED, new Date(eventTime))
                       .category(AUDIT_CATEGORY)
                       .docUUID(doc.getId())
                       .docPath(doc.getPathAsString())
                       .docType(doc.getType())
                       .docLifeCycle(doc.getCurrentLifeCycleState())
                       .principalName(session.getPrincipal().getName())
                       .repositoryId(doc.getRepositoryName())
                       .comment(xpath + " : binary content modified")
                       .extended(EXT_XPATH, xpath)
                       .extended(EXT_OLD_FILENAME, oldFilename)
                       .extended(EXT_NEW_FILENAME, newFilename)
                       .extended(EXT_CORRELATION_ID, correlationId)
                       .build();
    }

    /** Strips the leading slash that {@code Property#getXPath()} returns for top level properties. */
    protected String normalize(String xpath) {
        return xpath != null && xpath.startsWith("/") ? xpath.substring(1) : xpath;
    }
}

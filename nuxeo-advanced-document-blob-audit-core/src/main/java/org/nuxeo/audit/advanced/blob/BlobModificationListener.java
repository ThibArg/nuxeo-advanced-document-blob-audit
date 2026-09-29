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
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EXT_XPATH;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.Blob;
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
 */
public class BlobModificationListener implements EventListener {

    private static final Logger log = LogManager.getLogger(BlobModificationListener.class);

    protected static final int MAX_SOURCE_HOPS = 5;

    @Override
    public void handleEvent(Event event) {
        if (!DocumentEventTypes.DOCUMENT_CREATED.equals(event.getName())
                || !(event.getContext() instanceof DocumentEventContext context)) {
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
            String correlationId = BlobDiffTrigger.scheduleIfNeeded(liveDoc, xpath, oldBlob, newBlob, versionContext,
                    principal, event.getTime());
            if (correlationId != null) {
                eventService.fireEvent(buildAuditEvent(liveDoc, session, xpath, oldBlob, newBlob, correlationId));
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

    protected DocumentModelList orderedVersions(CoreSession session, String liveDocId) {
        String nxql = "SELECT * FROM Document WHERE ecm:versionVersionableId = "
                + NXQL.escapeString(liveDocId)
                + " AND ecm:isVersion = 1 ORDER BY uid:major_version DESC, uid:minor_version DESC";
        return session.query(nxql);
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

    protected Event buildAuditEvent(DocumentModel liveDoc, CoreSession session, String xpath, Blob oldBlob,
            Blob newBlob, String correlationId) {
        DocumentEventContext ctx = new DocumentEventContext(session, session.getPrincipal(), liveDoc);
        ctx.setCategory(AUDIT_CATEGORY);
        ctx.setComment(xpath + " : binary content modified between versions");
        ctx.setProperty(EXT_XPATH, xpath);
        ctx.setProperty(EXT_OLD_FILENAME, filenameOf(oldBlob));
        ctx.setProperty(EXT_NEW_FILENAME, filenameOf(newBlob));
        ctx.setProperty(EXT_CORRELATION_ID, correlationId);
        return ctx.newEvent(EVENT_BLOB_MODIFIED);
    }

    protected String filenameOf(Blob blob) {
        return blob != null && blob.getFilename() != null ? blob.getFilename() : "";
    }

    protected String normalize(String xpath) {
        return xpath != null && xpath.startsWith("/") ? xpath.substring(1) : xpath;
    }
}

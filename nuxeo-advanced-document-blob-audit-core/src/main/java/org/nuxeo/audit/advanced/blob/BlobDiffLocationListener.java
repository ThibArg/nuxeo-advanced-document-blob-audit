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
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.DIFF_DOCTYPE;

import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentSecurityException;
import org.nuxeo.ecm.core.api.event.CoreEventConstants;
import org.nuxeo.ecm.core.api.event.DocumentEventTypes;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;

/**
 * Keeps every {@code BlobDiff} under {@code /change-diff}, and everything under {@code /change-diff}
 * inside it.
 * <p>
 * {@code AbstractSession#createDocument} does not check allowed subtypes - a {@code TypeManager},
 * hence UI, concern - so without this guard any user able to add children somewhere could create a
 * {@code BlobDiff} with fabricated content, attributed to anyone, and have it listed next to the
 * genuine ones. Moves and copies are covered as well: the auditors group holds {@code Remove} on the
 * container, which is all {@code CoreSession#move} needs to take a diff out of it.
 * <p>
 * Applies to every principal, system included: the plugin itself never writes a diff anywhere else.
 *
 * @since 2025.1
 */
public class BlobDiffLocationListener implements EventListener {

    /** Name of the listener as contributed, for {@code EventServiceAdmin#setListenerEnabledFlag}. */
    public static final String NAME = "blobDiffLocationListener";

    protected static final String CONTAINER_PATH = "/" + CONTAINER_NAME;

    @Override
    public void handleEvent(Event event) {
        if (!(event.getContext() instanceof DocumentEventContext context)) {
            return;
        }
        DocumentModel doc = context.getSourceDocument();
        if (doc == null) {
            return;
        }
        boolean fromContainer = !DocumentEventTypes.ABOUT_TO_CREATE.equals(event.getName())
                && isInContainer(doc.getPathAsString());
        if (!fromContainer && !DIFF_DOCTYPE.equals(doc.getType())) {
            return;
        }
        Object destination = context.getProperty(CoreEventConstants.DESTINATION_PATH);
        if (destination instanceof String path && isInContainer(path)) {
            return;
        }
        event.markBubbleException();
        throw new DocumentSecurityException("Content diffs must stay under " + CONTAINER_PATH + ": refusing "
                + event.getName() + " of " + doc.getPathAsString() + " to " + destination);
    }

    protected static boolean isInContainer(String path) {
        return path != null && (path.equals(CONTAINER_PATH) || path.startsWith(CONTAINER_PATH + "/"));
    }
}

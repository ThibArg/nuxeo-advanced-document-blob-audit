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

import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EVENT_BLOB_MODIFIED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EVENT_TYPES_DIRECTORY;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.LABEL_EVENT_BLOB_MODIFIED;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.repository.RepositoryInitializationHandler;
import org.nuxeo.ecm.directory.Session;
import org.nuxeo.ecm.directory.api.DirectoryService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.DefaultComponent;
import org.nuxeo.runtime.transaction.TransactionHelper;

/**
 * Registers {@link BlobDiffRepositoryInit} and the {@code blobContentModified} audit event type.
 *
 * @since 1.0
 */
public class BlobDiffInitComponent extends DefaultComponent {

    private static final Logger log = LogManager.getLogger(BlobDiffInitComponent.class);

    protected RepositoryInitializationHandler handler;

    @Override
    public void start(ComponentContext context) {
        handler = new BlobDiffRepositoryInit();
        handler.install();
        registerAuditEventType();
    }

    /**
     * Adds {@code blobContentModified} to the platform {@code eventTypes} vocabulary.
     * <p>
     * Done programmatically on purpose. A declarative {@code <dataFile>} contribution does not add to
     * the platform's data, it <b>replaces</b> it: {@code dataFileName} is single-valued,
     * {@code BaseDirectoryDescriptor#merge} overwrites it, and {@code DirectoryRegistry} restarts the
     * effective descriptor from the template for every contribution carrying {@code extends}. A
     * greenfield instance would end up with this single row and lose the platform's 51.
     *
     * @since 2025.2
     */
    protected void registerAuditEventType() {
        DirectoryService ds = Framework.getService(DirectoryService.class);
        if (ds == null) {
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

    @Override
    public void stop(ComponentContext context) throws InterruptedException {
        if (handler != null) {
            handler.uninstall();
            handler = null;
        }
    }
}

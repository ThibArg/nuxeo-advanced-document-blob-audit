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

import static org.junit.Assert.assertTrue;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EVENT_BLOB_MODIFIED;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.EVENT_TYPES_DIRECTORY;

import java.util.Map;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.directory.Session;
import org.nuxeo.ecm.directory.api.DirectoryService;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

/**
 * The {@code blobContentModified} row must be <b>added</b> to the platform {@code eventTypes}
 * vocabulary, never substituted for it.
 * <p>
 * The plugin used to contribute the row through a {@code <directory extends="template-vocabulary">}
 * carrying a {@code <dataFile>}. That does not merge: {@code dataFileName} is single-valued,
 * {@code BaseDirectoryDescriptor#merge} overwrites it, and {@code DirectoryRegistry} recomputes the
 * effective descriptor from the template for every contribution carrying {@code extends}. On an
 * instance whose directory storage was empty, the vocabulary ended up holding this single row and
 * the platform's 51 were gone. The row is now created at startup by
 * {@link BlobDiffInitComponent#registerAuditEventType()}.
 * <p>
 * {@code AuditCoreFeature} brings both {@code DirectoryFeature} and the
 * {@code directories-contrib.xml} of {@code org.nuxeo.ecm.platform.audit}, so the platform rows are
 * genuinely loaded here; without them the size assertion below would pass vacuously. The
 * {@code template-vocabulary} those directories extend ships in {@code org.nuxeo.ecm.default.config},
 * which no feature in this stack deploys, hence the test contribution. Reaching this row at all also
 * proves the {@code Long} typing of {@code obsolete} and {@code ordering}: a mistyped value makes
 * {@code createEntry} throw, and the throw is swallowed on purpose so that a vocabulary row never
 * breaks startup.
 *
 * @since 2025.2
 */
@RunWith(FeaturesRunner.class)
@Features(BlobAuditFeature.class)
@Deploy("nuxeo-advanced-document-blob-audit-core:blobaudit-test-vocabulary-template-contrib.xml")
public class TestAuditEventTypeRegistration {

    @Inject
    protected DirectoryService directoryService;

    @Test
    public void testThePlatformEventTypesAreStillThere() {
        try (Session session = directoryService.open(EVENT_TYPES_DIRECTORY)) {
            assertTrue("the plugin must not replace the platform vocabulary", session.query(Map.of()).size() > 1);
            assertTrue(session.hasEntry(EVENT_BLOB_MODIFIED));
            assertTrue(session.hasEntry("documentCreated"));
        }
    }
}

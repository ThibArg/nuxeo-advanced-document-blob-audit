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

import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.RunnerFeature;

/**
 * Runtime feature for the blob audit tests.
 * <p>
 * Wraps the platform in-memory audit backend and deploys this bundle. The feature ships disabled in
 * production, so the test config contribution turns it on.
 *
 * @since 1.0
 */
@Features({ org.nuxeo.audit.test.AuditFeature.class , org.nuxeo.ecm.core.test.CoreFeature.class})
@Deploy("org.nuxeo.ecm.platform.query.api")
@Deploy("nuxeo-advanced-document-blob-audit-core")
@Deploy("nuxeo-advanced-document-blob-audit-core:blobaudit-test-config.xml")
@Deploy("nuxeo-advanced-document-blob-audit-core:blobaudit-test-pageprovider-contrib.xml")
public class BlobAuditFeature implements RunnerFeature {
}

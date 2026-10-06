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

import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.repository.RepositoryInitializationHandler;
import org.nuxeo.runtime.api.Framework;

/**
 * Creates the restricted diff container once, at repository initialisation, rather than lazily from
 * the listener. Guarantees the ACL is in place before the first diff is ever written.
 *
 * @since 2025.1
 */
public class BlobDiffRepositoryInit extends RepositoryInitializationHandler {

    @Override
    public void doInitializeRepository(CoreSession session) {
        BlobDiffService service = Framework.getService(BlobDiffService.class);
        // Always, even when the feature is disabled: the restricted container then exists, with its
        // ACL, before the first diff is ever written, and a tampered ACL is repaired at each start.
        if (service != null) {
            service.ensureRootContainer(session);
        }
    }
}

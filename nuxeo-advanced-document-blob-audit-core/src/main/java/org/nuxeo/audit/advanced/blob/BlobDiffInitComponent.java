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

import org.nuxeo.ecm.core.repository.RepositoryInitializationHandler;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.DefaultComponent;

/**
 * Registers {@link BlobDiffRepositoryInit}.
 *
 * @since 1.0
 */
public class BlobDiffInitComponent extends DefaultComponent {

    protected RepositoryInitializationHandler handler;

    @Override
    public void start(ComponentContext context) {
        handler = new BlobDiffRepositoryInit();
        handler.install();
    }

    @Override
    public void stop(ComponentContext context) throws InterruptedException {
        if (handler != null) {
            handler.uninstall();
            handler = null;
        }
    }
}

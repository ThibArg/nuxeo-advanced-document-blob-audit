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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;

/**
 * Holds the first removal of a {@code BlobDiff} once armed, until released, so that a test can act
 * while a purge is known to be running. Contributed by {@code blobaudit-test-purgegate-contrib.xml}.
 *
 * @since 2025.1
 */
public class PurgeGateListener implements EventListener {

    protected static volatile CountDownLatch held;

    protected static volatile CountDownLatch release;

    public static void arm() {
        held = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    /** Waits for the purge to reach its first removal. */
    public static boolean awaitHeld() throws InterruptedException {
        return held.await(30, TimeUnit.SECONDS);
    }

    public static void release() {
        release.countDown();
    }

    @Override
    public void handleEvent(Event event) {
        CountDownLatch gate = release;
        if (gate == null || gate.getCount() == 0 || !(event.getContext() instanceof DocumentEventContext context)) {
            return;
        }
        DocumentModel doc = context.getSourceDocument();
        if (doc == null || !BlobAuditConstants.DIFF_DOCTYPE.equals(doc.getType()) || held.getCount() == 0) {
            return;
        }
        held.countDown();
        try {
            gate.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

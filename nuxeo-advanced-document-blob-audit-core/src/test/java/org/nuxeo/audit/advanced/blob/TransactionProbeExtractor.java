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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.nuxeo.audit.advanced.blob.extractor.PlainTextExtractor;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.runtime.transaction.TransactionHelper;

/**
 * Plain text extractor recording whether a transaction is open when it runs, contributed by
 * {@code blobaudit-test-txprobe-contrib.xml}. It can also run a hook during its first extraction,
 * to act while the work is between its two transactions.
 *
 * @since 2025.1
 */
public class TransactionProbeExtractor extends PlainTextExtractor {

    protected static final List<Boolean> TRANSACTION_ACTIVE = new CopyOnWriteArrayList<>();

    protected static volatile Runnable duringExtraction;

    public static void reset(Runnable hook) {
        TRANSACTION_ACTIVE.clear();
        duringExtraction = hook;
    }

    /** One entry per extraction, in order. */
    public static List<Boolean> transactionActive() {
        return List.copyOf(TRANSACTION_ACTIVE);
    }

    @Override
    public DiffableContent extract(Blob blob, int maxLines) throws IOException {
        TRANSACTION_ACTIVE.add(TransactionHelper.isTransactionActiveOrMarkedRollback());
        Runnable hook = duringExtraction;
        duringExtraction = null;
        if (hook != null) {
            hook.run();
        }
        return super.extract(blob, maxLines);
    }
}

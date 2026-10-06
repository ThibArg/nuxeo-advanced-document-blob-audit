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
package org.nuxeo.audit.advanced.blob.operations;

import java.io.Serializable;

import org.nuxeo.audit.advanced.blob.bulk.BlobDiffPurgeAction;
import org.nuxeo.ecm.core.bulk.message.BulkStatus;

/**
 * JSON rendering of a {@link BulkStatus}, shared by {@code BlobDiff.PurgeStatus} and
 * {@code BlobDiff.PurgeAbort} so that the purge dialog gets the same payload either way.
 *
 * @since 2025.1
 */
public class BlobDiffPurgeStatusJson {

    private BlobDiffPurgeStatusJson() {
        // utility class
    }

    /**
     * {@code deleted} is the exact number of removed documents, published by the action in the
     * status result; {@code processed} counts the ids handed to the computation, which is not the
     * same thing as soon as the query matched something the purge refuses to touch.
     */
    public static String of(BulkStatus status) {
        Serializable deleted = status.getResult().get(BlobDiffPurgeAction.RESULT_DELETED);
        long deletedCount = deleted instanceof Number number ? number.longValue() : 0L;
        return "{\"state\":\"" + status.getState().name() + "\"" //
                + ",\"deleted\":" + deletedCount //
                + ",\"processed\":" + status.getProcessed() //
                + ",\"total\":" + status.getTotal() //
                + ",\"errorCount\":" + status.getErrorCount() //
                + ",\"skipCount\":" + status.getSkipCount() //
                + ",\"queryLimitReached\":" + status.isQueryLimitReached() //
                + "}";
    }
}

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

/**
 * The statuses of a diff: the value persisted as {@code bdiff:status} or recorded as the
 * {@code skipReason} of the audit entry, and the comment of the {@code blobContentModified} audit
 * entry that reports it.
 *
 * @since 2025.1
 */
public enum DiffStatus {

    OK(BlobAuditConstants.STATUS_OK, "binary content modified between versions"),

    SKIPPED_TOO_LARGE(BlobAuditConstants.STATUS_SKIPPED_SIZE, "binary changed (too large)"),

    SKIPPED_UNSUPPORTED_TYPE(BlobAuditConstants.STATUS_SKIPPED_TYPE, "binary changed (unsupported format)"),

    SKIPPED_FORMAT_CHANGED(BlobAuditConstants.STATUS_SKIPPED_FORMAT_CHANGED, "binary changed (format changed)"),

    SKIPPED_NOT_MANAGED(BlobAuditConstants.STATUS_SKIPPED_NOT_MANAGED,
            "binary changed (blob not managed by a provider)"),

    SKIPPED_LIST_CHANGED(BlobAuditConstants.STATUS_SKIPPED_LIST_CHANGED,
            "binary changed (list entries also removed or moved, previous file unknown)"),

    /** Decided by the work once the diff is computed: never a skip reason of the audit entry. */
    SKIPPED_CONTENT_REPLACED(BlobAuditConstants.STATUS_SKIPPED_CONTENT_REPLACED, null),

    /** Decided by the work: the audit entry is the nominal one. */
    ERROR(BlobAuditConstants.STATUS_ERROR, null);

    private final String value;

    private final String auditComment;

    DiffStatus(String value, String auditComment) {
        this.value = value;
        this.auditComment = auditComment;
    }

    /** Returns the persisted value, one of the {@code BlobAuditConstants.STATUS_*} constants. */
    public String value() {
        return value;
    }

    /**
     * Returns the comment of the audit entry reporting this status, or {@code null} when the status
     * never appears in an audit comment.
     */
    public String auditComment() {
        return auditComment;
    }

    /**
     * Returns the status persisted as the given value.
     *
     * @throws IllegalArgumentException if no status has that value
     */
    public static DiffStatus of(String value) {
        for (DiffStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown diff status: " + value);
    }
}

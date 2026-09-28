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
 * Shared constants.
 *
 * @since 1.0
 */
public class BlobAuditConstants {

    private BlobAuditConstants() {
        // utility class
    }

    /* Document model */
    public static final String DIFF_DOCTYPE = "BlobDiff";

    public static final String DIFF_SCHEMA = "blobdiff";

    public static final String XP_SOURCE_ID = "bdiff:sourceId";

    public static final String XP_SOURCE_REPO = "bdiff:sourceRepository";

    public static final String XP_XPATH = "bdiff:xpath";

    public static final String XP_CORRELATION_ID = "bdiff:correlationId";

    public static final String XP_USER = "bdiff:user";

    public static final String XP_DATE = "bdiff:date";

    public static final String XP_MIMETYPE = "bdiff:mimeType";

    public static final String XP_OLD_FILENAME = "bdiff:oldFilename";

    public static final String XP_NEW_FILENAME = "bdiff:newFilename";

    public static final String XP_OLD_DIGEST = "bdiff:oldDigest";

    public static final String XP_NEW_DIGEST = "bdiff:newDigest";

    public static final String XP_SUMMARY = "bdiff:summary";

    public static final String XP_ADDED = "bdiff:added";

    public static final String XP_REMOVED = "bdiff:removed";

    public static final String XP_CHANGED = "bdiff:changed";

    public static final String XP_TRUNCATED = "bdiff:truncated";

    public static final String XP_STATUS = "bdiff:status";

    public static final String XP_DIFF_BLOB = "bdiff:diff";

    /* Container */
    public static final String CONTAINER_NAME = "change-diff";

    public static final String CONTAINER_TITLE = "Change Diff";

    public static final String CONTAINER_TYPE = "Folder";

    /**
     * Default group granted access on the diff container, overridable through the
     * {@code auditorsGroup} element of the {@code config} extension point. The diffs contain business
     * content extracted from the source documents: choose the audience deliberately.
     */
    public static final String DEFAULT_AUDITORS_GROUP = "administrators";

    /** @deprecated use {@link BlobDiffConfigDescriptor#getAuditorsGroup()} */
    @Deprecated
    public static final String AUDITORS_GROUP = DEFAULT_AUDITORS_GROUP;

    /* Audit */
    public static final String EVENT_BLOB_MODIFIED = "blobContentModified";

    /**
     * Audit category. Reusing Nuxeo's default document category means the entries show up in the Web
     * UI audit table and under the existing "Document" filter without any extra contribution.
     */
    public static final String AUDIT_CATEGORY = "eventDocumentCategory";

    public static final String EXT_XPATH = "xpath";

    public static final String EXT_OLD_FILENAME = "oldFilename";

    public static final String EXT_NEW_FILENAME = "newFilename";

    public static final String EXT_CORRELATION_ID = "diffCorrelationId";

    /* Status values */
    public static final String STATUS_OK = "ok";

    public static final String STATUS_SKIPPED_SIZE = "skippedTooLarge";

    public static final String STATUS_SKIPPED_TYPE = "skippedUnsupportedType";

    public static final String STATUS_ERROR = "error";

    /** Summary of an error BlobDiff whose source was deleted before the diff could run. */
    public static final String SUMMARY_SOURCE_MISSING = "Source document no longer exists";

    /* Work */
    public static final String WORK_CATEGORY = "blobDiff";
}

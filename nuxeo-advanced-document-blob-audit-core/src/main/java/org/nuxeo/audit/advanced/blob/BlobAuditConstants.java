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
 * @since 2025.1
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
    public static final String XP_PREVIOUS_VERSION_ID = "bdiff:previousVersionId";

    public static final String XP_PREVIOUS_VERSION_LABEL = "bdiff:previousVersionLabel";

    public static final String XP_NEW_VERSION_ID = "bdiff:newVersionId";

    public static final String XP_NEW_VERSION_LABEL = "bdiff:newVersionLabel";

    public static final String XP_VERSION_SERIES_ID = "bdiff:versionSeriesId";

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

    /* Storage identity of the compared binaries, needed to replay a diff (since 1.2) */
    public static final String XP_OLD_BLOB_PROVIDER = "bdiff:oldBlobProvider";

    public static final String XP_OLD_BLOB_KEY = "bdiff:oldBlobKey";

    public static final String XP_OLD_MIMETYPE = "bdiff:oldMimeType";

    public static final String XP_OLD_LENGTH = "bdiff:oldLength";

    public static final String XP_NEW_BLOB_PROVIDER = "bdiff:newBlobProvider";

    public static final String XP_NEW_BLOB_KEY = "bdiff:newBlobKey";

    public static final String XP_NEW_LENGTH = "bdiff:newLength";

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

    /**
     * Configuration property holding the auditors group. Its {@code org.nuxeo.web.ui.} prefix makes
     * Web UI expose it as {@code Nuxeo.UI.config.blobaudit.auditorsGroup}, so the server and the UI
     * share a single setting.
     */
    public static final String AUDITORS_GROUP_PROPERTY = "org.nuxeo.web.ui.blobaudit.auditorsGroup";

    /** @deprecated use {@link BlobDiffConfigDescriptor#getAuditorsGroup()} */
    @Deprecated
    public static final String AUDITORS_GROUP = DEFAULT_AUDITORS_GROUP;

    /* Audit */
    public static final String EVENT_BLOB_MODIFIED = "blobContentModified";

    /**
     * Platform vocabulary listing the audit event ids. Fed by {@code nuxeo-platform-audit-core}; the
     * plugin only adds its own row to it, at runtime.
     *
     * @since 2025.1
     */
    public static final String EVENT_TYPES_DIRECTORY = "eventTypes";

    /**
     * Label of the {@link #EVENT_BLOB_MODIFIED} row. Resolved server side against the
     * {@code messages} bundle, not by Web UI, hence the properties files in {@code OSGI-INF/l10n}.
     *
     * @since 2025.1
     */
    public static final String LABEL_EVENT_BLOB_MODIFIED = "label.blobaudit.event.blobContentModified";

    /**
     * Audit category. Reusing Nuxeo's default document category means the entries show up in the Web
     * UI audit table and under the existing "Document" filter without any extra contribution.
     */
    public static final String AUDIT_CATEGORY = "eventDocumentCategory";

    public static final String EXT_XPATH = "xpath";

    public static final String EXT_OLD_FILENAME = "oldFilename";

    public static final String EXT_NEW_FILENAME = "newFilename";

    public static final String EXT_CORRELATION_ID = "diffCorrelationId";

    /**
     * Extended info carrying the mime type of the previous blob. Metadata only, like the filenames,
     * and what makes a {@link #STATUS_SKIPPED_FORMAT_CHANGED} entry self-explanatory.
     *
     * @since 2025.1
     */
    public static final String EXT_OLD_MIME_TYPE = "oldMimeType";

    /**
     * Extended info carrying the mime type of the new blob.
     *
     * @since 2025.1
     */
    public static final String EXT_NEW_MIME_TYPE = "newMimeType";

    /**
     * Extended info carrying why a binary change was audited without a diff. Holds one of the
     * {@code STATUS_SKIPPED_*} values; absent on the nominal path, where {@link #EXT_CORRELATION_ID}
     * is set instead. The two are mutually exclusive.
     *
     * @since 2025.1
     */
    public static final String EXT_SKIP_REASON = "skipReason";

    /* Status values */
    public static final String STATUS_OK = "ok";

    public static final String STATUS_SKIPPED_SIZE = "skippedTooLarge";

    public static final String STATUS_SKIPPED_TYPE = "skippedUnsupportedType";

    /**
     * Both blobs are supported, but by different extractor contributions: a {@code .xlsx} replaced by
     * a {@code .docx}, a Word document replaced by its PDF rendition. The two extractions do not cut
     * the content the same way, so their diff would report everything as removed and added. Reported
     * like the other skips, and reachable as a {@code bdiff:status} when the work re-evaluates the
     * pair.
     *
     * @since 2025.1
     */
    public static final String STATUS_SKIPPED_FORMAT_CHANGED = "skippedFormatChanged";

    /**
     * The two version blobs are not both {@code ManagedBlob}, so the pair cannot be frozen and
     * re-read by the asynchronous work. Reported like the other skips; never a {@code bdiff:status},
     * since no {@code BlobDiff} is created. In practice this means an unusual blob provider setup.
     *
     * @since 2025.1
     */
    public static final String STATUS_SKIPPED_NOT_MANAGED = "skippedNotManaged";

    /**
     * A blob inside a list changed, but entries were also removed or moved, so the previous file it
     * replaces cannot be told. Reported like the other skips; never a {@code bdiff:status}.
     *
     * @since 2025.1
     */
    public static final String STATUS_SKIPPED_LIST_CHANGED = "skippedListChanged";

    public static final String STATUS_ERROR = "error";

    /**
     * An {@code error} diff whose replay {@code BlobDiff.Retry} has scheduled. Put back to
     * {@link #STATUS_ERROR} if the replay fails for good; the diff is replaced otherwise.
     *
     * @since 2025.1
     */
    public static final String STATUS_RETRYING = "retrying";

    /** Summary of an error BlobDiff whose source was deleted before the diff could run. */
    public static final String SUMMARY_SOURCE_MISSING = "Source document no longer exists";

    /* Work */
    public static final String WORK_CATEGORY = "blobDiff";
}

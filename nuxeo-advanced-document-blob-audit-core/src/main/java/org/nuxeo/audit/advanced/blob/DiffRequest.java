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

import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_CORRELATION_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_DATE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_NEW_BLOB_KEY;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_NEW_BLOB_PROVIDER;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_NEW_DIGEST;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_NEW_FILENAME;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_NEW_LENGTH;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_NEW_MIMETYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_NEW_VERSION_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_NEW_VERSION_LABEL;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_OLD_BLOB_KEY;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_OLD_BLOB_PROVIDER;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_OLD_DIGEST;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_OLD_FILENAME;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_OLD_LENGTH;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_OLD_MIMETYPE;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_PREVIOUS_VERSION_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_PREVIOUS_VERSION_LABEL;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_SOURCE_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_SOURCE_REPO;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_USER;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_VERSION_SERIES_ID;
import static org.nuxeo.audit.advanced.blob.BlobAuditConstants.XP_XPATH;

import java.io.Serializable;
import java.util.Calendar;
import java.util.Objects;

import org.nuxeo.ecm.core.api.DocumentModel;

/**
 * What a BlobDiffWork compares and what its BlobDiff records about it: built at check-in, carried by
 * the work, written on the BlobDiff, read back by BlobDiff.Retry.
 *
 * @param repository the repository of the source document
 * @param sourceId the id of the live source document
 * @param sourceTitle the title of the source, {@code null} when unknown
 * @param xpath the blob xpath compared
 * @param oldBlob the binary of the previous version, {@code null} when not stored
 * @param newBlob the binary of the new version, {@code null} when not stored
 * @param versions the version pair, mandatory
 * @param user the principal who created the new version
 * @param eventTime the time of the check-in, in milliseconds
 * @param correlationId the id shared with the audit entry
 * @since 2025.1
 */
public record DiffRequest(String repository, String sourceId, String sourceTitle, String xpath,
        FrozenBlob oldBlob, FrozenBlob newBlob, VersionContext versions, String user, long eventTime,
        String correlationId) implements Serializable {

    private static final long serialVersionUID = 1L;

    public DiffRequest {
        Objects.requireNonNull(versions, "versions");
    }

    /**
     * Returns the id of the work comparing this pair: a stable, readable name built from the source,
     * the xpath and the version pair.
     * <p>
     * It is <b>not</b> a deduplication key: LTS 2025 ignores {@code IF_NOT_RUNNING_OR_SCHEDULED}.
     * Duplicates are prevented by {@code BlobDiffWork#alreadyRecorded}, on the correlation id.
     */
    public String workId() {
        return repository + ":" + sourceId + ":" + xpath + ":" + versions.previousVersionId() + ">"
                + versions.newVersionId() + ":blobDiff";
    }

    /** Returns a copy of this request with another source title. */
    public DiffRequest withSourceTitle(String title) {
        return new DiffRequest(repository, sourceId, title, xpath, oldBlob, newBlob, versions, user, eventTime,
                correlationId);
    }

    /**
     * Writes this request on a {@code BlobDiff}: everything but the status and the result. A
     * {@code null} side writes nothing for that side.
     */
    public void writeTo(DocumentModel diff) {
        diff.setPropertyValue("dc:title", (sourceTitle == null ? sourceId : sourceTitle) + " - " + xpath);
        diff.setPropertyValue(XP_SOURCE_ID, sourceId);
        diff.setPropertyValue(XP_SOURCE_REPO, repository);
        diff.setPropertyValue(XP_XPATH, xpath);
        diff.setPropertyValue(XP_PREVIOUS_VERSION_ID, versions.previousVersionId());
        diff.setPropertyValue(XP_PREVIOUS_VERSION_LABEL, versions.previousVersionLabel());
        diff.setPropertyValue(XP_NEW_VERSION_ID, versions.newVersionId());
        diff.setPropertyValue(XP_NEW_VERSION_LABEL, versions.newVersionLabel());
        diff.setPropertyValue(XP_VERSION_SERIES_ID, versions.versionSeriesId());
        diff.setPropertyValue(XP_CORRELATION_ID, correlationId);
        diff.setPropertyValue(XP_USER, user);
        Calendar date = Calendar.getInstance();
        date.setTimeInMillis(eventTime);
        diff.setPropertyValue(XP_DATE, date);
        Side.OLD.write(diff, oldBlob);
        Side.NEW.write(diff, newBlob);
    }

    /**
     * Reads back the request a {@code BlobDiff} was written from, the reverse of
     * {@link #writeTo(DocumentModel)}. A side whose provider id or key is missing is returned as
     * {@code null}.
     * <p>
     * Trusts every property it reads: call it only on a model loaded from storage
     * ({@code CoreSession#getDocument}), never on one a caller handed in, and only once its path was
     * checked (BlobDiffRetryOp#checkProvenance). A caller-supplied model can carry in-memory values
     * its stored document does not hold.
     */
    public static DiffRequest readFrom(DocumentModel diff) {
        String repository = string(diff, XP_SOURCE_REPO);
        String xpath = string(diff, XP_XPATH);
        VersionContext versions = new VersionContext(string(diff, XP_PREVIOUS_VERSION_ID),
                string(diff, XP_PREVIOUS_VERSION_LABEL), string(diff, XP_NEW_VERSION_ID),
                string(diff, XP_NEW_VERSION_LABEL), string(diff, XP_VERSION_SERIES_ID));
        Calendar date = (Calendar) diff.getPropertyValue(XP_DATE);
        return new DiffRequest(repository == null ? diff.getRepositoryName() : repository,
                string(diff, XP_SOURCE_ID), sourceTitle(diff, xpath), xpath, Side.OLD.read(diff),
                Side.NEW.read(diff), versions, string(diff, XP_USER),
                date == null ? System.currentTimeMillis() : date.getTimeInMillis(), string(diff, XP_CORRELATION_ID));
    }

    /** The diff is titled {@code <source title> - <xpath>}; passed whole, every retry would append the xpath again. */
    private static String sourceTitle(DocumentModel diff, String xpath) {
        String title = diff.getTitle();
        String suffix = " - " + xpath;
        return title != null && title.endsWith(suffix) ? title.substring(0, title.length() - suffix.length()) : null;
    }

    private static String string(DocumentModel doc, String xpath) {
        Object value = doc.getPropertyValue(xpath);
        return value == null ? null : value.toString();
    }

    /** The six properties of one side of the pair, so that old and new go through the same lines. */
    private record Side(String provider, String key, String filename, String mimeType, String digest,
            String length) {

        static final Side OLD = new Side(XP_OLD_BLOB_PROVIDER, XP_OLD_BLOB_KEY, XP_OLD_FILENAME, XP_OLD_MIMETYPE,
                XP_OLD_DIGEST, XP_OLD_LENGTH);

        static final Side NEW = new Side(XP_NEW_BLOB_PROVIDER, XP_NEW_BLOB_KEY, XP_NEW_FILENAME, XP_NEW_MIMETYPE,
                XP_NEW_DIGEST, XP_NEW_LENGTH);

        void write(DocumentModel diff, FrozenBlob blob) {
            if (blob == null) {
                return;
            }
            diff.setPropertyValue(provider, blob.providerId());
            diff.setPropertyValue(key, blob.key());
            diff.setPropertyValue(filename, blob.filename());
            diff.setPropertyValue(mimeType, blob.mimeType());
            diff.setPropertyValue(digest, blob.digest());
            diff.setPropertyValue(length, blob.length() < 0 ? null : Long.valueOf(blob.length()));
        }

        FrozenBlob read(DocumentModel diff) {
            String providerId = string(diff, provider);
            String storageKey = string(diff, key);
            if (providerId == null || storageKey == null) {
                return null;
            }
            Object size = diff.getPropertyValue(length);
            return new FrozenBlob(providerId, storageKey, string(diff, filename), string(diff, mimeType),
                    string(diff, digest), size instanceof Number number ? number.longValue() : -1);
        }
    }
}

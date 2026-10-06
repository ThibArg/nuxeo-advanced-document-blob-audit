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

import java.util.ArrayList;
import java.util.List;

import org.nuxeo.common.xmap.annotation.XNode;
import org.nuxeo.common.xmap.annotation.XNodeList;
import org.nuxeo.common.xmap.annotation.XObject;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.Descriptor;
import org.nuxeo.runtime.services.config.ConfigurationService;

/**
 * Global configuration. Everything is opt-in and bounded on purpose: diffing binaries is expensive
 * and copies business content outside of the source document.
 *
 * @since 2025.1
 */
@XObject("config")
public class BlobDiffConfigDescriptor implements Descriptor {

    public static final String DEFAULT_ID = "default";

    @XNode("@enabled")
    protected boolean enabled = false;

    /** Blobs bigger than this (bytes) are never extracted. */
    @XNode("maxBlobSize")
    protected long maxBlobSize = 10L * 1024 * 1024;

    /**
     * Hard cap on extracted units.
     * <p>
     * This bounds <b>running time</b>, which stays O(n*m) even with Hirschberg. Measured on JDK 17
     * with one paragraph modified: 304 ms at 4 900 lines, ~1.1 s at 10 000, ~4.6 s at 20 000. A
     * 200-page Word document extracts to roughly 5 000 paragraphs, so 10 000 leaves a 2x margin
     * while keeping the worst case near one second per work.
     */
    @XNode("maxLines")
    protected int maxLines = 10000;

    /** Hard cap on reported differences. */
    @XNode("maxDiffEntries")
    protected int maxDiffEntries = 5000;

    /**
     * Hard cap on the size of the produced unified diff, in characters.
     * <p>
     * {@code maxDiffEntries} bounds how many differences are reported, never how long they are. An
     * Excel cell holds up to 32 767 characters and a Word paragraph is unbounded, so the two caps
     * together are what keeps the diff body - built in a {@code StringBuilder}, copied by
     * {@code toString()}, copied again into a blob - proportional to something known in advance.
     *
     * @since 2025.1
     */
    @XNode("maxDiffChars")
    protected int maxDiffChars = TextDiffer.DEFAULT_MAX_DIFF_CHARS;

    /**
     * A single extracted unit longer than this is elided in the diff body.
     *
     * @since 2025.1
     */
    @XNode("maxValueLength")
    protected int maxValueLength = TextDiffer.DEFAULT_MAX_VALUE_LENGTH;

    /**
     * Image analysis level. Level 0 disables image processing; level 1 enables digest-based image
     * inventory. Higher levels are reserved for future, more expensive analysis modes.
     */
    @XNode("imageAnalysisLevel")
    protected int imageAnalysisLevel = 0;

    public static final int MIN_IMAGE_ANALYSIS_LEVEL = 0;

    public static final int MAX_IMAGE_ANALYSIS_LEVEL = 1;

    /**
     * Group granted access to the {@code /change-diff} container: Read, Remove and RemoveChildren,
     * so it can list, open and delete diffs (retention, admin UI). Members of the platform
     * administrators groups bypass ACLs anyway; this setting matters when a dedicated group (for
     * instance {@code auditors}) is the intended audience.
     */
    @XNode("auditorsGroup")
    protected String auditorsGroup;

    /** Empty means "every document type". */
    @XNodeList(value = "docTypes/docType", type = ArrayList.class, componentType = String.class)
    protected List<String> docTypes = new ArrayList<>();

    /** Empty means "every blob xpath". */
    @XNodeList(value = "xpaths/xpath", type = ArrayList.class, componentType = String.class)
    protected List<String> xpaths = new ArrayList<>();

    @Override
    public String getId() {
        return DEFAULT_ID;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getMaxBlobSize() {
        return maxBlobSize;
    }

    public int getMaxLines() {
        return maxLines;
    }

    public int getMaxDiffEntries() {
        return maxDiffEntries;
    }

    /** @since 2025.1 */
    public int getMaxDiffChars() {
        return maxDiffChars;
    }

    /** @since 2025.1 */
    public int getMaxValueLength() {
        return maxValueLength;
    }

    public int getImageAnalysisLevel() {
        return imageAnalysisLevel;
    }

    /**
     * Clamps the configured image analysis level to the range supported by this version.
     * <p>
     * <b>Pure.</b> It used to assign the clamped value back to {@link #imageAnalysisLevel}, which
     * made a query method write a non-{@code volatile} field of the descriptor instance the whole
     * runtime shares through {@code getDescriptor(XP_CONFIG, ...)} - and it is called both from
     * {@code BlobDiffComponent#start} and from {@code diffLocal}, i.e. concurrently from the
     * {@code blobDiff} worker threads, with no happens-before edge. The write was idempotent so the
     * outcome was benign, but it was a data race on a registry object, and it silently made the
     * "configured value differs from the normalized one" warning in {@code start()} a one-shot.
     */
    public int normalizeImageAnalysisLevel() {
        return Math.max(MIN_IMAGE_ANALYSIS_LEVEL, Math.min(MAX_IMAGE_ANALYSIS_LEVEL, imageAnalysisLevel));
    }

    public List<String> getDocTypes() {
        return docTypes;
    }

    /**
     * Resolution order: {@code <auditorsGroup>} of this descriptor, then the configuration property
     * {@value BlobAuditConstants#AUDITORS_GROUP_PROPERTY} (also exposed to Web UI as
     * {@code Nuxeo.UI.config.blobaudit.auditorsGroup}, which is why it is the recommended place),
     * then {@code administrators}.
     */
    public String getAuditorsGroup() {
        if (auditorsGroup != null && !auditorsGroup.isBlank()) {
            return auditorsGroup.trim();
        }
        String property = null;
        if (Framework.isInitialized()) {
            ConfigurationService configuration = Framework.getService(ConfigurationService.class);
            if (configuration != null) {
                property = configuration.getString(BlobAuditConstants.AUDITORS_GROUP_PROPERTY).orElse(null);
            }
        }
        return property == null || property.isBlank() ? BlobAuditConstants.DEFAULT_AUDITORS_GROUP : property.trim();
    }

    public List<String> getXPaths() {
        return xpaths;
    }

    public boolean acceptsDocType(String docType) {
        return docTypes.isEmpty() || docTypes.contains(docType);
    }

    public boolean acceptsXPath(String xpath) {
        return xpaths.isEmpty() || xpaths.contains(xpath);
    }
}

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
import org.nuxeo.runtime.model.Descriptor;

/**
 * Global configuration. Everything is opt-in and bounded on purpose: diffing binaries is expensive
 * and copies business content outside of the source document.
 *
 * @since 1.0
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

    public List<String> getDocTypes() {
        return docTypes;
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

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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.nuxeo.common.xmap.annotation.XNode;
import org.nuxeo.common.xmap.annotation.XNodeList;
import org.nuxeo.common.xmap.annotation.XNodeMap;
import org.nuxeo.common.xmap.annotation.XObject;
import org.nuxeo.runtime.model.Descriptor;

/**
 * Registration of a {@link BlobTextExtractor} for a set of mime types.
 *
 * @since 2025.1
 */
@XObject("extractor")
public class ExtractorDescriptor implements Descriptor {

    @XNode("@name")
    protected String name;

    @XNode("@class")
    protected Class<? extends BlobTextExtractor> klass;

    @XNode("@enabled")
    protected boolean enabled = true;

    /** Lower runs first; lets a specialised extractor win over a catch-all one. */
    @XNode("@order")
    protected int order = 100;

    @XNodeList(value = "mimeTypes/mimeType", type = ArrayList.class, componentType = String.class)
    protected List<String> mimeTypes = new ArrayList<>();

    @XNodeMap(value = "property", key = "@name", type = HashMap.class, componentType = String.class)
    protected Map<String, String> properties = new HashMap<>();

    @Override
    public String getId() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getOrder() {
        return order;
    }

    public List<String> getMimeTypes() {
        return mimeTypes;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public Class<? extends BlobTextExtractor> getKlass() {
        return klass;
    }

    /** {@code true} if this extractor handles the mime type, {@code *} acting as a catch-all. */
    public boolean accepts(String mimeType) {
        if (mimeType == null) {
            return mimeTypes.contains("*");
        }
        String normalized = mimeType.toLowerCase(Locale.ROOT);
        int idx = normalized.indexOf(';');
        if (idx > 0) {
            normalized = normalized.substring(0, idx).trim();
        }
        for (String candidate : mimeTypes) {
            if ("*".equals(candidate) || candidate.equalsIgnoreCase(normalized)) {
                return true;
            }
            if (candidate.endsWith("/*")
                    && normalized.startsWith(candidate.substring(0, candidate.length() - 1))) {
                return true;
            }
        }
        return false;
    }
}

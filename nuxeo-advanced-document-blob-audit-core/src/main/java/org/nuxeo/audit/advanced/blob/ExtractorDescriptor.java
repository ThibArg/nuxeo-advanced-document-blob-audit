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
 * <p>
 * Contributions with the same name <b>merge</b>: a later one only overrides what it sets, its
 * properties are added to the earlier ones, and its mime types replace them only when it lists some.
 *
 * @since 2025.1
 */
@XObject("extractor")
public class ExtractorDescriptor implements Descriptor {

    /** @since 2025.1 */
    public static final int DEFAULT_ORDER = 100;

    @XNode("@name")
    protected String name;

    @XNode("@class")
    protected Class<? extends BlobTextExtractor> klass;

    @XNode("@enabled")
    protected Boolean enabled;

    /** Lower runs first; lets a specialised extractor win over a catch-all one. */
    @XNode("@order")
    protected Integer order;

    @XNodeList(value = "mimeTypes/mimeType", type = ArrayList.class, componentType = String.class)
    protected List<String> mimeTypes = new ArrayList<>();

    @XNodeMap(value = "property", key = "@name", type = HashMap.class, componentType = String.class)
    protected Map<String, String> properties = new HashMap<>();

    @Override
    public String getId() {
        return name;
    }

    /**
     * Overlays a later contribution on this one. The default merge returned the later contribution
     * alone, so an override re-declaring a single property lost the class, and the format became
     * unsupported.
     *
     * @since 2025.1
     */
    @Override
    public Descriptor merge(Descriptor o) {
        ExtractorDescriptor other = (ExtractorDescriptor) o;
        ExtractorDescriptor merged = new ExtractorDescriptor();
        merged.name = either(other.name, name);
        merged.klass = either(other.klass, klass);
        merged.enabled = either(other.enabled, enabled);
        merged.order = either(other.order, order);
        merged.mimeTypes = Descriptor.getIfEmpty(other.mimeTypes, mimeTypes);
        merged.properties = new HashMap<>(properties);
        merged.properties.putAll(other.properties);
        return merged;
    }

    protected static <T> T either(T value, T fallback) {
        return value != null ? value : fallback;
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public int getOrder() {
        return order == null ? DEFAULT_ORDER : order;
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

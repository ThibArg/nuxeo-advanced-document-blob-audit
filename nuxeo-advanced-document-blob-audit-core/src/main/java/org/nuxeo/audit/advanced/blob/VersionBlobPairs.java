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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.model.PropertyNotFoundException;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.core.schema.TypeConstants;
import org.nuxeo.ecm.core.schema.types.ComplexType;
import org.nuxeo.ecm.core.schema.types.Field;
import org.nuxeo.ecm.core.schema.types.ListType;
import org.nuxeo.ecm.core.schema.types.Schema;
import org.nuxeo.ecm.core.schema.types.Type;
import org.nuxeo.runtime.api.Framework;

/**
 * Which blobs to compare between two versions: the configured xpaths, or with {@code <xpath>*</xpath>}
 * every blob xpath of the schemas the new version carries. Blobs inside a list are paired by content
 * (digest), never by index alone.
 *
 * @since 2025.1
 */
public class VersionBlobPairs {

    private static final Logger log = LogManager.getLogger(VersionBlobPairs.class);

    /** Guards against a complex type that refers to itself while walking a schema. */
    protected static final int MAX_SCHEMA_DEPTH = 10;

    /**
     * Blob xpath templates per schema name. Cleared when {@link BlobDiffComponent} stops, which a
     * hot reload does, since schemas can then be redefined.
     */
    protected static final Map<String, List<String>> BLOB_XPATH_TEMPLATES = new ConcurrentHashMap<>();

    private VersionBlobPairs() {
        // utility class
    }

    /**
     * Clears the blob xpath templates, so that they are computed again from the current schemas.
     *
     * @since 2025.1
     */
    public static void clearCache() {
        BLOB_XPATH_TEMPLATES.clear();
    }

    /**
     * Two blobs to compare at one xpath.
     *
     * @param unattributed the new blob of a list whose previous file cannot be told: audited
     *            without a diff
     * @since 2025.1
     */
    public record BlobPair(String xpath, Blob oldBlob, Blob newBlob, boolean unattributed) {
    }

    /**
     * Returns the blobs to compare between the two versions.
     * <p>
     * Unless {@code <xpaths>} lists {@code *}, the configured paths - {@code file:content} by
     * default - are compared verbatim: a path holding no blob simply yields {@code null} in
     * {@link #safeGetBlob} and is skipped downstream, so there is nothing to pre-filter.
     * <p>
     * With {@code *}, the paths are derived from the <b>schemas the new version carries</b>, those of
     * facets added to the document included, and cached per schema, so that a check-in never walks
     * every property of both versions: calling {@code property.getValue()} on each would force the
     * load of every complex and list property inside the user transaction. Blobs inside a list are
     * paired by {@link #pairListEntries}.
     *
     * @since 2025.1
     */
    public static List<BlobPair> of(DocumentModel previous, DocumentModel current, BlobDiffConfigDescriptor config) {
        List<BlobPair> pairs = new ArrayList<>();
        if (!config.isEveryXPath()) {
            for (String xpath : config.getXPaths()) {
                pairs.add(new BlobPair(xpath, safeGetBlob(previous, xpath), safeGetBlob(current, xpath), false));
            }
            return pairs;
        }
        for (String schema : current.getSchemas()) {
            for (String template : templates(schema)) {
                if (template.indexOf('*') < 0) {
                    pairs.add(new BlobPair(template, safeGetBlob(previous, template),
                            safeGetBlob(current, template), false));
                } else {
                    pairListEntries(template, previous, current, pairs);
                }
            }
        }
        return pairs;
    }

    /**
     * Returns whether the document holds a blob at one of the xpaths {@link #of} would read on it.
     * <p>
     * A check-in versions the live document as it stands, so a live document holding none gives a
     * new version holding none, and no pair with a new blob to compare: the listener then skips the
     * version lookups altogether. Reads the properties of the document only, no query.
     *
     * @since 2025.1
     */
    public static boolean holdsBlob(DocumentModel doc, BlobDiffConfigDescriptor config) {
        if (!config.isEveryXPath()) {
            return config.getXPaths().stream().anyMatch(xpath -> safeGetBlob(doc, xpath) != null);
        }
        for (String schema : doc.getSchemas()) {
            for (String template : templates(schema)) {
                boolean found = template.indexOf('*') < 0 ? safeGetBlob(doc, template) != null
                        : !blobsAt(template, doc).isEmpty();
                if (found) {
                    return true;
                }
            }
        }
        return false;
    }

    protected static List<String> templates(String schema) {
        return BLOB_XPATH_TEMPLATES.computeIfAbsent(schema, VersionBlobPairs::computeBlobXPathTemplates);
    }

    /**
     * Pairs the blobs of a list by content, never by index alone.
     * <p>
     * An index is not an identity: once an entry is removed, inserted or moved, the same index holds
     * another file on each side, and comparing them would report a change between two unrelated
     * files. A blob present on both sides, wherever it sits, is unchanged. A new blob is compared with
     * the one it replaces only when the list kept its shape and that one is gone from the new version;
     * otherwise, if blobs also disappeared, it is audited without a diff. A new blob with nothing gone
     * is an addition, which is not audited, like any added blob.
     */
    protected static void pairListEntries(String template, DocumentModel previous, DocumentModel current,
            List<BlobPair> pairs) {
        Map<String, Blob> before = blobsAt(template, previous);
        Map<String, Blob> after = blobsAt(template, current);
        Set<String> digestsBefore = digests(before.values());
        Set<String> digestsAfter = digests(after.values());
        boolean somethingGone = before.values().stream().anyMatch(blob -> !holds(digestsAfter, blob));
        boolean sameShape = before.keySet().equals(after.keySet());
        for (Map.Entry<String, Blob> entry : after.entrySet()) {
            Blob newBlob = entry.getValue();
            if (holds(digestsBefore, newBlob)) {
                continue;
            }
            Blob replaced = sameShape ? before.get(entry.getKey()) : null;
            if (replaced != null && !holds(digestsAfter, replaced)) {
                pairs.add(new BlobPair(entry.getKey(), replaced, newBlob, false));
            } else if (somethingGone) {
                pairs.add(new BlobPair(entry.getKey(), null, newBlob, true));
            }
        }
    }

    protected static Map<String, Blob> blobsAt(String template, DocumentModel doc) {
        List<String> xpaths = new ArrayList<>();
        expandTemplate(template, doc, xpaths);
        Map<String, Blob> blobs = new LinkedHashMap<>();
        for (String xpath : xpaths) {
            Blob blob = safeGetBlob(doc, xpath);
            if (blob != null) {
                blobs.put(xpath, blob);
            }
        }
        return blobs;
    }

    protected static Set<String> digests(Iterable<Blob> blobs) {
        Set<String> digests = new HashSet<>();
        for (Blob blob : blobs) {
            if (blob.getDigest() != null) {
                digests.add(blob.getDigest());
            }
        }
        return digests;
    }

    /**
     * Returns whether the digest of the blob is among the given ones. A blob with no digest is never
     * found on the other side: nothing proves it unchanged.
     */
    protected static boolean holds(Set<String> digests, Blob blob) {
        return blob.getDigest() != null && digests.contains(blob.getDigest());
    }

    /**
     * Returns the blob xpaths of a schema, as <b>templates</b>: a {@code *} stands for the index of a
     * list entry, which only a document can resolve.
     */
    protected static List<String> computeBlobXPathTemplates(String schemaName) {
        var schemaManager = Framework.getService(SchemaManager.class);
        Schema schema = schemaManager == null ? null : schemaManager.getSchema(schemaName);
        if (schema == null) {
            return List.of();
        }
        Set<String> xpaths = new LinkedHashSet<>();
        String prefix = schema.getNamespace().hasPrefix() ? schema.getNamespace().prefix : schema.getName();
        for (Field field : schema.getFields()) {
            collectBlobFields(prefix + ":" + field.getName().getLocalName(), field.getType(), xpaths, 0);
        }
        return List.copyOf(xpaths);
    }

    /**
     * Walks a field type, appending the path of every blob found underneath.
     * <p>
     * The depth is bounded: a complex type referring to itself would otherwise recurse for ever.
     */
    protected static void collectBlobFields(String path, Type type, Set<String> xpaths, int depth) {
        if (depth > MAX_SCHEMA_DEPTH) {
            log.warn("Giving up on blob xpath {} beyond depth {}", path, MAX_SCHEMA_DEPTH);
            return;
        }
        if (TypeConstants.isContentType(type)) {
            xpaths.add(path);
            return;
        }
        if (type instanceof ListType list) {
            collectBlobFields(path + "/*", list.getFieldType(), xpaths, depth + 1);
        } else if (type instanceof ComplexType complex) {
            for (Field field : complex.getFields()) {
                collectBlobFields(path + "/" + field.getName().getLocalName(), field.getType(), xpaths, depth + 1);
            }
        }
    }

    /**
     * Resolves the {@code *} of a list template against the actual entries of a document.
     * <p>
     * Only the list properties that really lead to a blob are read, instead of every property of
     * every schema. A list the document does not carry is not expanded; any other failure to read it
     * propagates, see {@link #safeGetBlob}.
     */
    protected static void expandTemplate(String template, DocumentModel doc, List<String> xpaths) {
        int star = template.indexOf('*');
        String listPath = template.substring(0, star - 1);
        int size;
        try {
            Object value = doc.getPropertyValue(listPath);
            size = value instanceof List<?> list ? list.size() : 0;
        } catch (PropertyNotFoundException e) {
            // The template comes from the schemas of the new version: the previous one may not
            // carry the schema of a facet added since.
            log.debug("No list {} on {} to expand template {}", listPath, doc.getId(), template);
            return;
        }
        for (int i = 0; i < size; i++) {
            String resolved = template.substring(0, star) + i + template.substring(star + 1);
            if (resolved.indexOf('*') < 0) {
                xpaths.add(resolved);
            } else {
                expandTemplate(resolved, doc, xpaths);
            }
        }
    }

    /**
     * Returns the blob at the xpath, or {@code null} when the document holds none there - no such
     * schema on the document, no such list entry, or a value that is not a blob.
     * <p>
     * Only {@link PropertyNotFoundException} means "no blob". Any other failure propagates: answered
     * {@code null}, a binary that changed would leave no audit entry at all, and nothing above
     * {@code DEBUG} would tell. Raised from the synchronous listener, the platform logs it at
     * {@code ERROR} and goes on with the check-in.
     */
    protected static Blob safeGetBlob(DocumentModel doc, String xpath) {
        try {
            Object value = doc.getPropertyValue(xpath);
            return value instanceof Blob blob ? blob : null;
        } catch (PropertyNotFoundException e) {
            log.debug("No property {} on {}", xpath, doc.getId());
            return null;
        }
    }
}

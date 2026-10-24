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
package org.nuxeo.audit.advanced.blob.io;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.exceptions.InvalidOperationException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.util.XMLHelper;
import org.nuxeo.ecm.core.api.Blob;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * Opens OOXML packages and streams their XML parts, for the extractors that read a bounded part of
 * a document and must therefore not let POI's usermodel build the model of all of it.
 * <p>
 * A package is opened read only from the local file when there is one, so its parts are inflated
 * on demand. The caller releases it with {@link OPCPackage#revert()}: {@code close()} on a read-only
 * package logs a warning and then does exactly that.
 *
 * @since 2025.1
 */
public final class OoxmlPackages {

    /**
     * Ends a parse once the handler has what it needs.
     *
     * @since 2025.1
     */
    public static final class Stop extends SAXException {

        private static final long serialVersionUID = 1L;

        public Stop() {
            super("parse stopped by its handler");
        }
    }

    private OoxmlPackages() {
        // utility class
    }

    /**
     * Opens the package of the blob, from its local file when it has one.
     *
     * @since 2025.1
     */
    public static OPCPackage open(Blob blob) throws IOException {
        File file = blob.getFile();
        if (file != null) {
            return open(file);
        }
        try (InputStream in = blob.getStream()) {
            return open(in);
        }
    }

    /**
     * Opens the package read only, in random access.
     *
     * @since 2025.1
     */
    public static OPCPackage open(File file) throws IOException {
        try {
            return OPCPackage.open(file, PackageAccess.READ);
        } catch (InvalidFormatException e) {
            throw new IOException("Not a readable OOXML package: " + file.getName(), e);
        }
    }

    /**
     * Buffers the whole package: only the fallback for a blob that could not be materialised.
     *
     * @since 2025.1
     */
    public static OPCPackage open(InputStream in) throws IOException {
        try {
            return OPCPackage.open(in);
        } catch (InvalidFormatException e) {
            throw new IOException("Not a readable OOXML package", e);
        }
    }

    /**
     * Parses a part with POI's hardened SAX reader, which accepts no DTD and resolves no external
     * entity.
     *
     * @since 2025.1
     */
    public static void parse(PackagePart part, ContentHandler handler) throws IOException, SAXException {
        XMLReader reader;
        try {
            reader = XMLHelper.newXMLReader();
        } catch (ParserConfigurationException e) {
            throw new IOException("No SAX parser available", e);
        }
        reader.setContentHandler(handler);
        try (InputStream in = part.getInputStream()) {
            reader.parse(new InputSource(in));
        }
    }

    /**
     * Returns the part an internal relationship of {@code source} points to, or {@code null} when the
     * relationship is unknown, external, or points to nothing.
     *
     * @since 2025.1
     */
    public static PackagePart related(PackagePart source, String relationshipId) {
        try {
            PackageRelationship relationship = source.getRelationship(relationshipId);
            return relationship == null ? null : related(source, relationship);
        } catch (InvalidOperationException e) {
            return null;
        }
    }

    /**
     * Returns the parts {@code source} points to through relationships of the given type, in
     * relationship id order.
     *
     * @since 2025.1
     */
    public static List<PackagePart> relatedOfType(PackagePart source, String relationshipType) throws IOException {
        List<PackagePart> parts = new ArrayList<>();
        try {
            for (PackageRelationship relationship : source.getRelationshipsByType(relationshipType)) {
                PackagePart part = related(source, relationship);
                if (part != null) {
                    parts.add(part);
                }
            }
        } catch (InvalidFormatException e) {
            throw new IOException("Unreadable relationships of " + source.getPartName(), e);
        }
        return parts;
    }

    /**
     * Returns the value of the relationship attribute ({@code r:id}, {@code r:embed}...) with the given
     * local name, whatever the prefix and the namespace version, or {@code null}.
     *
     * @since 2025.1
     */
    public static String relationshipAttribute(Attributes attributes, String localName) {
        for (int i = 0; i < attributes.getLength(); i++) {
            if (localName.equals(attributes.getLocalName(i)) && !attributes.getURI(i).isEmpty()) {
                return attributes.getValue(i);
            }
        }
        return null;
    }

    /**
     * Unwraps an {@code IOException} a handler had to tunnel through SAX.
     *
     * @since 2025.1
     */
    public static IOException unreadable(SAXException e, String what) {
        return e.getException() instanceof IOException io ? io : new IOException("Not a readable " + what, e);
    }

    private static PackagePart related(PackagePart source, PackageRelationship relationship) {
        if (relationship.getTargetMode() == TargetMode.EXTERNAL) {
            return null;
        }
        try {
            return source.getRelatedPart(relationship);
        } catch (InvalidFormatException | IllegalArgumentException e) {
            return null;
        }
    }
}

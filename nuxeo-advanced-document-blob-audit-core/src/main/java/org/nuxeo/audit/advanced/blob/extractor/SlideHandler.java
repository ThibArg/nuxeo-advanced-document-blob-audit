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
package org.nuxeo.audit.advanced.blob.extractor;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.PackageRelationshipTypes;
import org.apache.poi.xslf.usermodel.XSLFRelation;
import org.nuxeo.audit.advanced.blob.io.OoxmlPackages;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Streams the shape tree of a slide or notes part, in drawing order, as POI's usermodel walks it
 * ({@code XSLFSheet#buildShapes}) but without building it.
 * <p>
 * Group shapes are walked at their position. Of an alternate content block, only the first element
 * of its first {@code mc:Choice} is read, as a shape in place of the block: that is what the usermodel
 * does, and it never reads the {@code mc:Fallback}. Text shapes report their paragraphs, tables their
 * rows, pictures the relationship of their embedded image. Subclasses keep what they need, and throw
 * {@link OoxmlPackages.Stop} to end the parse.
 *
 * @since 2025.1
 */
public class SlideHandler extends DefaultHandler {

    public static final String TABLE_URI = "http://schemas.openxmlformats.org/drawingml/2006/table";

    protected static final String MARKUP_COMPATIBILITY = "http://schemas.openxmlformats.org/markup-compatibility/2006";

    protected enum Role {
        OUTSIDE, CONTAINER, ALTERNATE, CHOICE, SHAPE, SHAPE_PROPERTIES, APPLICATION_PROPERTIES, TEXT_BODY,
        PARAGRAPH, RUN, TEXT, FRAME, GRAPHIC, TABLE_DATA, TABLE, ROW, CELL, PICTURE, BLIP_FILL, SKIP
    }

    protected static final class Frame {

        protected final Role role;

        protected final boolean topLevel;

        /** Set once the only child this element is read for has been seen. */
        protected boolean taken;

        protected Frame(Role role, boolean topLevel) {
            this.role = role;
            this.topLevel = topLevel;
        }
    }

    protected static final Frame SKIP = new Frame(Role.SKIP, false);

    protected final Deque<Frame> stack = new ArrayDeque<>();

    protected boolean shapeTopLevel;

    protected String placeholder;

    protected StringBuilder paragraph;

    protected StringBuilder cell;

    protected List<String> row;

    protected String blip;

    /**
     * Returns the slides of a presentation package, in presentation order, like
     * {@code XMLSlideShow#getSlides()}: an entry of the slide list that leads to no slide is skipped.
     *
     * @since 2025.1
     */
    public static List<PackagePart> slides(OPCPackage pkg) throws IOException, SAXException {
        PackagePart presentation = mainPart(pkg);
        List<String> ids = new ArrayList<>();
        OoxmlPackages.parse(presentation, new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName, Attributes attributes) {
                String id = "sldId".equals(localName) ? OoxmlPackages.relationshipAttribute(attributes, "id") : null;
                if (id != null) {
                    ids.add(id);
                }
            }
        });
        List<PackagePart> slides = new ArrayList<>();
        for (String id : ids) {
            PackageRelationship relationship = presentation.getRelationship(id);
            if (relationship != null && XSLFRelation.SLIDE.getRelation().equals(relationship.getRelationshipType())) {
                PackagePart slide = OoxmlPackages.related(presentation, id);
                if (slide != null) {
                    slides.add(slide);
                }
            }
        }
        return slides;
    }

    /**
     * Returns the notes of a slide, or {@code null}. The last one if there are several, like
     * {@code XSLFSlide#getNotes()}.
     *
     * @since 2025.1
     */
    public static PackagePart notes(PackagePart slide) throws IOException {
        List<PackagePart> notes = OoxmlPackages.relatedOfType(slide, XSLFRelation.NOTES.getRelation());
        return notes.isEmpty() ? null : notes.get(notes.size() - 1);
    }

    protected static PackagePart mainPart(OPCPackage pkg) throws IOException {
        for (String type : List.of(PackageRelationshipTypes.CORE_DOCUMENT,
                PackageRelationshipTypes.STRICT_CORE_DOCUMENT)) {
            PackageRelationship relationship = pkg.getRelationshipsByType(type).getRelationship(0);
            PackagePart part = relationship == null ? null : pkg.getPart(relationship);
            if (part != null) {
                return part;
            }
        }
        throw new IOException("Not a readable OOXML presentation: no main part");
    }

    /**
     * Whether the paragraphs of this text shape are read; {@code false} skips them unparsed.
     *
     * @param topLevel whether the shape is in the shape tree itself rather than in a group
     * @param placeholder the {@code type} of the placeholder of the shape, {@code null} if not given
     */
    protected boolean readsText(boolean topLevel, String placeholder) {
        return true;
    }

    /** Whether tables are read; {@code false} skips them unparsed. */
    protected boolean readsTables() {
        return true;
    }

    /**
     * A paragraph of a text shape, as {@code XSLFTextParagraph#getText()} renders it.
     *
     * @param topLevel whether the shape is in the shape tree itself rather than in a group
     * @param placeholder the {@code type} of the placeholder of the shape, {@code null} if not given
     */
    protected void paragraph(boolean topLevel, String placeholder, String text) throws SAXException {
        // for subclasses
    }

    /** The end of a text shape, after its last paragraph. */
    protected void textShapeEnd(boolean topLevel, String placeholder) throws SAXException {
        // for subclasses
    }

    /** A table row, one entry per cell, each as {@code XSLFTableCell#getText()} renders it. */
    protected void tableRow(List<String> cells) throws SAXException {
        // for subclasses
    }

    /** An embedded picture, by the id of its relationship from the part being parsed. */
    protected void picture(String relationshipId) throws SAXException {
        // for subclasses
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
        Frame parent = stack.peek();
        stack.push(parent == null ? new Frame(Role.OUTSIDE, true) : child(parent, uri, localName, attributes));
    }

    protected Frame child(Frame parent, String uri, String name, Attributes attributes) {
        boolean top = parent.topLevel;
        switch (parent.role) {
            case OUTSIDE:
                return new Frame("spTree".equals(name) ? Role.CONTAINER : Role.OUTSIDE, true);
            case CONTAINER:
                return shape(name, top);
            case ALTERNATE:
                return MARKUP_COMPATIBILITY.equals(uri) && "Choice".equals(name) ? first(parent, Role.CHOICE) : SKIP;
            case CHOICE:
                if (parent.taken) {
                    return SKIP;
                }
                parent.taken = true;
                return shape(name, top);
            case SHAPE:
                if ("nvSpPr".equals(name)) {
                    return new Frame(Role.SHAPE_PROPERTIES, top);
                }
                return "txBody".equals(name) && readsText(shapeTopLevel, placeholder) ? first(parent, Role.TEXT_BODY)
                        : SKIP;
            case SHAPE_PROPERTIES:
                return "nvPr".equals(name) ? new Frame(Role.APPLICATION_PROPERTIES, top) : SKIP;
            case APPLICATION_PROPERTIES:
                if ("ph".equals(name)) {
                    placeholder = attributes.getValue("", "type");
                }
                return SKIP;
            case TEXT_BODY:
                if ("p".equals(name)) {
                    paragraph = new StringBuilder();
                    return new Frame(Role.PARAGRAPH, top);
                }
                return SKIP;
            case PARAGRAPH:
                if ("r".equals(name) || "fld".equals(name)) {
                    return new Frame(Role.RUN, top);
                }
                if ("br".equals(name)) {
                    paragraph.append('\n');
                }
                return SKIP;
            case RUN:
                return "t".equals(name) ? first(parent, Role.TEXT) : SKIP;
            case FRAME:
                return "graphic".equals(name) ? new Frame(Role.GRAPHIC, top) : SKIP;
            case GRAPHIC:
                return "graphicData".equals(name) && TABLE_URI.equals(attributes.getValue("", "uri"))
                        ? new Frame(Role.TABLE_DATA, top)
                        : SKIP;
            case TABLE_DATA:
                return "tbl".equals(name) ? first(parent, Role.TABLE) : SKIP;
            case TABLE:
                if ("tr".equals(name)) {
                    row = new ArrayList<>();
                    return new Frame(Role.ROW, top);
                }
                return SKIP;
            case ROW:
                if ("tc".equals(name)) {
                    cell = new StringBuilder();
                    return new Frame(Role.CELL, top);
                }
                return SKIP;
            case CELL:
                return "txBody".equals(name) ? first(parent, Role.TEXT_BODY) : SKIP;
            case PICTURE:
                return "blipFill".equals(name) ? new Frame(Role.BLIP_FILL, top) : SKIP;
            case BLIP_FILL:
                if ("blip".equals(name)) {
                    blip = OoxmlPackages.relationshipAttribute(attributes, "embed");
                }
                return SKIP;
            default:
                return SKIP;
        }
    }

    /** A child of the shape tree or of a group, read as the usermodel types it. */
    protected Frame shape(String name, boolean top) {
        switch (name) {
            case "sp":
                shapeTopLevel = top;
                placeholder = null;
                return new Frame(Role.SHAPE, top);
            case "grpSp":
                return new Frame(Role.CONTAINER, false);
            case "graphicFrame":
                return readsTables() ? new Frame(Role.FRAME, top) : SKIP;
            case "pic":
                blip = null;
                return new Frame(Role.PICTURE, top);
            case "cxnSp":
                return SKIP;
            default:
                // Anything the shape tree does not declare is read as alternate content: only a
                // mc:Choice child can make it count, and the declared non-shapes have none.
                return new Frame(Role.ALTERNATE, top);
        }
    }

    protected Frame first(Frame parent, Role role) {
        if (parent.taken) {
            return SKIP;
        }
        parent.taken = true;
        return new Frame(role, parent.topLevel);
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        Frame current = stack.peek();
        if (current != null && current.role == Role.TEXT) {
            paragraph.append(ch, start, length);
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        switch (stack.pop().role) {
            case PARAGRAPH -> endParagraph();
            case SHAPE -> textShapeEnd(shapeTopLevel, placeholder);
            case CELL -> {
                row.add(cell.toString());
                cell = null;
            }
            case ROW -> tableRow(row);
            case PICTURE -> {
                if (blip != null && !blip.isEmpty()) {
                    picture(blip);
                }
            }
            default -> {
                // nothing to report
            }
        }
    }

    protected void endParagraph() throws SAXException {
        String text = paragraph.toString();
        paragraph = null;
        if (cell == null) {
            paragraph(shapeTopLevel, placeholder, text);
        } else {
            // Joined like XSLFTextShape#getText, which only separates once something was written.
            if (!cell.isEmpty()) {
                cell.append('\n');
            }
            cell.append(text);
        }
    }
}

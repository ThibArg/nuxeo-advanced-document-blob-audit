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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.nuxeo.audit.advanced.blob.BlobContentExtractor;
import org.nuxeo.audit.advanced.blob.ContentLine;
import org.nuxeo.audit.advanced.blob.DiffableContent;
import org.nuxeo.audit.advanced.blob.TextDiffer;
import org.nuxeo.audit.advanced.blob.io.MaterializedBlob;
import org.nuxeo.audit.advanced.blob.io.OoxmlPackages;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

/**
 * Paragraph-level extractor for PowerPoint OOXML files ({@code .pptx}, {@code .pptm}, {@code .ppsx},
 * {@code .potx}), streamed with SAX the way Apache POI XSLF reads them.
 * <p>
 * Produces <b>positional</b> content. Each slide starts with a {@code ## Slide} marker line carrying
 * its title (never its number: numbering would make a slide insertion shift, and therefore
 * "modify", every following slide). Then come the text paragraphs of every shape in drawing order,
 * table cells as {@code [table] cell | cell | ...} rows, and optionally speaker notes prefixed with
 * {@code [notes]}. Group shapes are walked recursively.
 * <p>
 * Positional alignment (Hirschberg) is the right fit: inserting, deleting or moving a slide yields a
 * compact diff instead of the cascade a keyed "slide N" identity would produce.
 * <p>
 * Legacy binary {@code .ppt} needs poi-scratchpad (HSLF) and is left to the generic
 * {@link ConverterTextExtractor} ({@code any2text}).
 *
 * @since 2025.1
 */
public class PresentationExtractor implements BlobContentExtractor {

    public static final String SLIDE_MARKER = "## Slide";

    public static final String NOTES_PREFIX = "[notes] ";

    public static final String TABLE_PREFIX = "[table] ";

    protected boolean includeNotes = true;

    protected int maxValueLength = TextDiffer.DEFAULT_MAX_VALUE_LENGTH;

    @Override
    public void init(Map<String, String> properties) {
        includeNotes = !"false".equalsIgnoreCase(properties.get("includeNotes"));
        maxValueLength = BlobContentExtractor.maxValueLength(properties);
    }

    /**
     * Streams the slides one by one, and stops at {@code maxLines}.
     * <p>
     * {@code XMLSlideShow} parses every slide, layout, master and notes page into an XMLBeans tree
     * when it is opened, so its footprint would follow the size of the deck rather than what is
     * extracted - from a file as well, which only spares buffering the compressed package. Each
     * slide is read once with SAX; its lines are held until its title, which the marker line needs
     * before anything else, has been read.
     */
    @Override
    public DiffableContent extract(Blob blob, int maxLines) throws IOException {
        var collector = new Collector(maxLines, maxValueLength);
        try (MaterializedBlob local = MaterializedBlob.of(blob)) {
            OPCPackage pkg = OoxmlPackages.open(local.file());
            try {
                for (PackagePart slide : SlideHandler.slides(pkg)) {
                    if (!collectSlide(slide, collector)) {
                        break;
                    }
                }
            } catch (SAXException e) {
                throw OoxmlPackages.unreadable(e, "OOXML presentation: " + blob.getFilename());
            } finally {
                pkg.revert();
            }
        }
        return DiffableContent.positional(collector.lines, collector.truncated);
    }

    /**
     * Collects the lines of a slide, then those of its notes.
     *
     * @return {@code false} once maxLines has been reached
     */
    protected boolean collectSlide(PackagePart slide, Collector collector) throws IOException, SAXException {
        if (collector.remaining() <= 0) {
            collector.truncated = true;
            return false;
        }
        var content = new SlideText(collector, false);
        try {
            OoxmlPackages.parse(slide, content);
        } catch (OoxmlPackages.Stop e) {
            // the slide overflows and its title is known: the adds below reach maxLines
        }
        if (!collector.addCompacted(content.marker == null ? SLIDE_MARKER : content.marker)) {
            return false;
        }
        for (String line : content.lines) {
            if (!collector.addCompacted(line)) {
                return false;
            }
        }
        try {
            PackagePart notes = includeNotes ? SlideHandler.notes(slide) : null;
            if (notes != null) {
                OoxmlPackages.parse(notes, new SlideText(collector, true));
            }
        } catch (OoxmlPackages.Stop e) {
            return false;
        }
        return true;
    }

    /**
     * Returns whether a text shape is the title of its slide, like {@code XSLFSlide#getTitle()}: a
     * top-level shape whose placeholder type is {@code title}.
     */
    protected static boolean isTitle(boolean topLevel, String placeholder) {
        return topLevel && "title".equals(placeholder);
    }

    /**
     * The lines of a slide, or of its notes page. Notes pages also hold the slide thumbnail and the
     * page number: only their body placeholder is kept.
     * <p>
     * Notes lines go straight to the collector. Slide lines are held until the slide has been read:
     * the marker line, which comes first, carries the title, and the title shape can sit anywhere in
     * the slide. Its paragraphs are captured on the way, in a {@link TitleText}. At most the room left
     * in the collector is held, each line compacted as it arrives, so that a slide holds what the
     * collector would; past that room, the rest of the slide is read the way the title alone needs -
     * other text and tables skipped unread - and the parse stops as soon as the title is known.
     */
    protected static class SlideText extends SlideHandler {

        protected final Collector collector;

        protected final boolean notes;

        /** Slide mode: the lines of the slide, compacted, at most {@link #capacity} of them. */
        protected final List<String> lines = new ArrayList<>();

        /** Slide mode: the room left in the collector when the slide starts, marker included. */
        protected final int capacity;

        /** Slide mode: set once a line arrived with {@link #capacity} lines already held. */
        protected boolean overflowing;

        /** Slide mode: the paragraphs of the title shape read so far. */
        protected final TitleText titleText;

        /** Slide mode: the marker line, compacted, {@code null} until the title shape has been read. */
        protected String marker;

        /**
         * The table row being read, as its line: {@code TABLE_PREFIX} then its cells, whitespace
         * collapsed, joined with {@code " | "}.
         */
        protected final BoundedText row;

        /** Whether the row being read has a cell already. */
        protected boolean rowStarted;

        /** Whether a cell of the row being read holds text: a row of empty cells is no line. */
        protected boolean rowHasText;

        protected SlideText(Collector collector, boolean notes) {
            super(collector.maxValueLength);
            this.collector = collector;
            this.notes = notes;
            this.capacity = collector.remaining();
            this.titleText = new TitleText(collector.maxValueLength);
            this.row = new BoundedText(collector.maxValueLength);
        }

        @Override
        protected boolean readsText(boolean topLevel, String placeholder) {
            if (notes) {
                return topLevel && "body".equals(placeholder);
            }
            // Once the slide overflows, only its title is still wanted: the rest is skipped unread.
            return !overflowing || isTitle(topLevel, placeholder);
        }

        @Override
        protected boolean readsTables() {
            return !notes && !overflowing;
        }

        /** Skips the rest of the shape or table being read when the slide overflows, unless it is the title. */
        @Override
        protected Frame child(Frame parent, String uri, String name, Attributes attributes) {
            if (overflowing && !(cell == null && isTitle(shapeTopLevel, placeholder))
                    && (parent.role == Role.TEXT_BODY || parent.role == Role.TABLE || parent.role == Role.ROW)) {
                return SKIP;
            }
            return super.child(parent, uri, name, attributes);
        }

        @Override
        protected void paragraph(boolean topLevel, String placeholder, String text) throws SAXException {
            // Blank paragraphs belong to the title too.
            if (!notes && marker == null && isTitle(topLevel, placeholder)) {
                titleText.add(text);
            }
            if (!text.isBlank()) {
                add((notes ? NOTES_PREFIX : "") + text.trim());
            }
        }

        @Override
        protected void textShapeEnd(boolean topLevel, String placeholder) throws SAXException {
            if (!notes && marker == null && isTitle(topLevel, placeholder)) {
                marker = titleText.marker();
                if (overflowing) {
                    throw new OoxmlPackages.Stop();
                }
            }
        }

        @Override
        protected void tableCell(String text) {
            String normalized = text.replaceAll("\\s+", " ").trim();
            row.append(rowStarted ? " | " : TABLE_PREFIX).append(normalized);
            rowStarted = true;
            rowHasText |= !normalized.isEmpty();
        }

        /**
         * Adds the row as {@code TABLE_PREFIX + String.join(" | ", cells)}: whole, or already what
         * {@link ContentLine#compact(String, int)} makes of it when it is longer than
         * {@link BoundedText#HELD_LENGTH}.
         */
        @Override
        protected void tableRowEnd() throws SAXException {
            String line = row.toString();
            boolean hasText = rowHasText;
            row.clear();
            rowStarted = false;
            rowHasText = false;
            if (hasText) {
                add(line);
            }
        }

        protected void add(String line) throws SAXException {
            if (notes) {
                if (!collector.add(line)) {
                    throw new OoxmlPackages.Stop();
                }
            } else if (lines.size() < capacity) {
                lines.add(ContentLine.compact(line, collector.maxValueLength));
            } else {
                // Discarded: the slide is cut anyway, but its title has to be read before the parse ends.
                overflowing = true;
                if (marker != null) {
                    throw new OoxmlPackages.Stop();
                }
            }
        }
    }

    /**
     * The title of a slide, read paragraph by paragraph, holding no more of it than the marker line
     * needs: its first {@code maxValueLength} characters, its length and its SHA-256.
     * <p>
     * {@link #marker()} is exactly what {@link ContentLine#compact(String, int)} makes of
     * {@code SLIDE_MARKER + ": " + title.trim()}, {@code title} being the paragraphs joined with
     * {@code \n}, or {@code SLIDE_MARKER} alone for a blank title. Characters up to U+0020 before the
     * first other one are skipped, and the digest is copied before the ones that follow the last other
     * one, so that both ends are left out the way {@code trim()} leaves them out.
     * <p>
     * Pieces are only ever split next to a character up to U+0020, never inside a surrogate pair, so
     * encoding them one by one gives the bytes of the whole.
     *
     * @since 2025.1
     */
    protected static class TitleText {

        protected static final String PREFIX = SLIDE_MARKER + ": ";

        protected final int maxValueLength;

        /** The beginning of the marker, trailing characters up to U+0020 included. */
        protected final StringBuilder head = new StringBuilder(PREFIX);

        protected final MessageDigest digest = ContentLine.sha256();

        /** The length of the marker, trailing characters up to U+0020 included. */
        protected long size = PREFIX.length();

        /** Nothing appended yet: no separator before the next paragraph, like the joined title. */
        protected boolean empty = true;

        /** Whether every character so far is whitespace, as {@code String#isBlank()} tells it. */
        protected boolean blank = true;

        /** Whether a character over U+0020 has been read: the ones up to it are trimmed. */
        protected boolean started;

        /** The digest and the length of the marker up to its last character over U+0020, once more follow. */
        protected MessageDigest trimmedDigest;

        protected long trimmedSize;

        protected TitleText(int maxValueLength) {
            this.maxValueLength = maxValueLength;
            digest.update(PREFIX.getBytes(UTF_8));
        }

        protected void add(String paragraph) {
            if (!empty) {
                append("\n");
            }
            append(paragraph);
        }

        protected void append(String text) {
            empty &= text.isEmpty();
            for (int i = 0; blank && i < text.length(); i++) {
                blank = Character.isWhitespace(text.charAt(i));
            }
            int start = 0;
            if (!started) {
                while (start < text.length() && text.charAt(start) <= ' ') {
                    start++;
                }
                started = start < text.length();
            }
            int end = text.length();
            while (end > start && text.charAt(end - 1) <= ' ') {
                end--;
            }
            if (end > start) {
                feed(text, start, end);
                trimmedDigest = null;
            }
            if (started && end < text.length()) {
                if (trimmedDigest == null) {
                    trimmedDigest = copy(digest);
                    trimmedSize = size;
                }
                feed(text, end, text.length());
            }
        }

        protected void feed(String text, int start, int end) {
            size += end - start;
            int room = maxValueLength - head.length();
            if (room > 0) {
                head.append(text, start, Math.min(end, start + room));
            }
            digest.update(text.substring(start, end).getBytes(UTF_8));
        }

        /** Returns how many characters of the title are held: at most {@code maxValueLength}, prefix included. */
        public int length() {
            return head.length();
        }

        /** Returns the marker line, compacted. Called once: it completes the digest. */
        protected String marker() {
            if (blank) {
                return SLIDE_MARKER;
            }
            MessageDigest whole = trimmedDigest == null ? digest : trimmedDigest;
            long length = trimmedDigest == null ? size : trimmedSize;
            if (length <= maxValueLength) {
                return head.substring(0, (int) length);
            }
            return ContentLine.compacted(head, length, whole.digest(), maxValueLength);
        }

        protected static MessageDigest copy(MessageDigest digest) {
            try {
                return (MessageDigest) digest.clone();
            } catch (CloneNotSupportedException e) {
                throw new NuxeoException("SHA-256 digests cannot be copied on this JVM", e);
            }
        }
    }

    protected static class Collector {
        protected final int maxLines;

        protected final int maxValueLength;

        protected final List<ContentLine> lines = new ArrayList<>();

        protected boolean truncated;

        protected Collector(int maxLines, int maxValueLength) {
            this.maxLines = maxLines;
            this.maxValueLength = maxValueLength;
        }

        /** Returns how many more lines can be added. */
        protected int remaining() {
            return maxLines - lines.size();
        }

        protected boolean add(String value) {
            return addCompacted(ContentLine.compact(value, maxValueLength));
        }

        /** Adds a value {@link ContentLine#compact(String, int)} has already bounded. */
        protected boolean addCompacted(String value) {
            if (lines.size() >= maxLines) {
                truncated = true;
                return false;
            }
            lines.add(ContentLine.of(value));
            return true;
        }
    }
}

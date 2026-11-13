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

import java.nio.CharBuffer;
import java.security.MessageDigest;

import org.nuxeo.audit.advanced.blob.ContentLine;
import org.nuxeo.ecm.core.api.NuxeoException;

/**
 * Text read piece by piece, such as the character data of an XML element, held whole up to
 * {@link #HELD_LENGTH} characters and reduced past that to what {@link ContentLine#compact(String, int)}
 * keeps of it: its beginning, its length and its SHA-256.
 * <p>
 * A SAX handler only knows how long a text is once its element ends, and every budget of an
 * extraction ({@code maxValueLength}, {@code maxLines}, the shared strings table) applies to a
 * finished value. POI's {@code ZipSecureFile} lets a part inflate up to 100 times its compressed
 * size, so a single text node of a file {@code maxBlobSize} accepts can reach a billion characters:
 * held in a {@code StringBuilder}, it would exhaust the heap before any budget is consulted.
 * <p>
 * Up to {@code HELD_LENGTH} characters - {@code maxValueLength} if larger - the text is held whole and
 * {@link #toString()} returns it, so the extractor decodes, trims, parses and compacts it as it would
 * any value. Past that, only its beginning is held, the rest is counted and digested as it arrives,
 * and {@link #toString()} returns exactly {@code ContentLine.compact(whole, maxValueLength)}, the whole
 * never being held: such a text is compared on its raw content, before any decoding or trimming.
 *
 * @since 2025.1
 */
public class BoundedText {

    /**
     * How many characters are held whole: the most an Excel cell holds. A cell, a formula or a
     * shared string Excel writes is therefore always read whole.
     */
    public static final int HELD_LENGTH = 32_767;

    /** Encoded bytes gathered before each digest update. */
    protected static final int BUFFER_SIZE = 8192;

    protected final int maxValueLength;

    protected final int held;

    /** The text, or its first {@link #held} characters once it is longer. */
    protected final StringBuilder head = new StringBuilder();

    protected long length;

    /** The SHA-256 of the text so far, {@code null} while it is held whole. */
    protected MessageDigest digest;

    /** UTF-8 bytes not yet handed to {@link #digest}. */
    protected byte[] buffer;

    protected int buffered;

    /** A high surrogate whose low one may come with the next piece; {@code 0} for none. */
    protected char pendingHigh;

    /** What {@link #toString()} returned for the text as it stands, {@code null} once it changed. */
    protected String compacted;

    /**
     * @param maxValueLength the length a text past {@link #HELD_LENGTH} characters is compacted to
     */
    public BoundedText(int maxValueLength) {
        this.maxValueLength = maxValueLength;
        this.held = Math.max(HELD_LENGTH, maxValueLength);
    }

    public BoundedText append(char[] chars, int start, int count) {
        return append(CharBuffer.wrap(chars, start, count));
    }

    public BoundedText append(char c) {
        return append(String.valueOf(c));
    }

    public BoundedText append(CharSequence text) {
        int count = text.length();
        if (digest == null && length + count <= held) {
            head.append(text);
            length += count;
            return this;
        }
        if (digest == null) {
            digest = ContentLine.sha256();
            if (buffer == null) {
                buffer = new byte[BUFFER_SIZE];
            }
            feed(head);
        }
        int room = held - head.length();
        if (room > 0) {
            head.append(text, 0, Math.min(count, room));
        }
        feed(text);
        length += count;
        compacted = null;
        return this;
    }

    /** Returns the length of the whole text, held or not. */
    public long length() {
        return length;
    }

    public boolean isEmpty() {
        return length == 0;
    }

    /** Empties the text, to read another one. */
    public void clear() {
        head.setLength(0);
        length = 0;
        digest = null;
        buffered = 0;
        pendingHigh = 0;
        compacted = null;
    }

    /**
     * Returns the text if it is held whole, otherwise {@code ContentLine.compact(whole, maxValueLength)}.
     */
    @Override
    public String toString() {
        if (digest == null) {
            return head.toString();
        }
        if (compacted == null) {
            flush();
            MessageDigest whole = copy(digest);
            if (pendingHigh != 0) {
                // Unpaired at the end, like String#getBytes encodes it.
                whole.update((byte) '?');
            }
            compacted = ContentLine.compacted(head, length, whole.digest(), maxValueLength);
        }
        return compacted;
    }

    /**
     * Digests the text encoded in UTF-8 exactly as {@code String#getBytes(UTF_8)} encodes the whole,
     * whatever the pieces: a surrogate pair split between two of them is joined, an unpaired surrogate
     * becomes {@code ?}.
     */
    protected void feed(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (pendingHigh != 0) {
                char high = pendingHigh;
                pendingHigh = 0;
                if (Character.isLowSurrogate(c)) {
                    encode(Character.toCodePoint(high, c));
                    continue;
                }
                encode('?');
            }
            if (Character.isHighSurrogate(c)) {
                pendingHigh = c;
            } else {
                encode(Character.isLowSurrogate(c) ? '?' : c);
            }
        }
    }

    protected void encode(int codePoint) {
        if (buffered > buffer.length - 4) {
            flush();
        }
        if (codePoint < 0x80) {
            buffer[buffered++] = (byte) codePoint;
        } else if (codePoint < 0x800) {
            buffer[buffered++] = (byte) (0xC0 | (codePoint >> 6));
            buffer[buffered++] = (byte) (0x80 | (codePoint & 0x3F));
        } else if (codePoint < 0x10000) {
            buffer[buffered++] = (byte) (0xE0 | (codePoint >> 12));
            buffer[buffered++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
            buffer[buffered++] = (byte) (0x80 | (codePoint & 0x3F));
        } else {
            buffer[buffered++] = (byte) (0xF0 | (codePoint >> 18));
            buffer[buffered++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
            buffer[buffered++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
            buffer[buffered++] = (byte) (0x80 | (codePoint & 0x3F));
        }
    }

    protected void flush() {
        digest.update(buffer, 0, buffered);
        buffered = 0;
    }

    protected static MessageDigest copy(MessageDigest digest) {
        try {
            return (MessageDigest) digest.clone();
        } catch (CloneNotSupportedException e) {
            throw new NuxeoException("SHA-256 digests cannot be copied on this JVM", e);
        }
    }
}

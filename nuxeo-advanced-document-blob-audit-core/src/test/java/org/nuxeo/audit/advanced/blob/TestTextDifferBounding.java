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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * The size of the produced diff is bounded by construction, not by the size of the input.
 * <p>
 * {@code maxDiffEntries} only ever bounded <b>how many</b> differences were written; nothing
 * bounded their length. An Excel cell accepts 32 767 characters and a Word paragraph is unbounded,
 * so a few thousand entries could reach hundreds of megabytes - accumulated in a
 * {@code StringBuilder}, copied by {@code toString()}, copied again into an in-memory blob, then
 * encoded to bytes by the blob provider. Two caps close that: {@code maxChars} on the whole body,
 * {@code maxValueLength} on a single unit.
 * <p>
 * No Nuxeo runtime: {@link TextDiffer} has no platform dependency, which is what lets these cases
 * assert the exact bound rather than approximate it. See item 23 in {@code AGENTS.md}.
 *
 * @since 2025.4
 */
public class TestTextDifferBounding {

    /** The real maximum of a single Excel cell. */
    protected static final int EXCEL_CELL_MAX = 32_767;

    protected static final int MAX_CHARS = 64 * 1024;

    protected static final int MAX_VALUE_LENGTH = 1024;

    protected final TextDiffer differ = new TextDiffer(5000, MAX_CHARS, MAX_VALUE_LENGTH);

    protected String repeat(char c, int length) {
        return String.valueOf(c).repeat(length);
    }

    /** {@code count} keyed entries whose value is {@code length} identical characters. */
    protected DiffableContent hugeKeyed(int count, int length, char filler) {
        List<ContentLine> content = new ArrayList<>(count);
        String value = repeat(filler, length);
        for (int i = 0; i < count; i++) {
            content.add(ContentLine.of("Sheet1!A" + i, value));
        }
        return DiffableContent.keyed(content, false);
    }

    /**
     * The headline case: 5 000 modified cells of 32 767 characters each. Unbounded, that renders to
     * roughly 320 MB. The cap must hold <b>exactly</b> - the budget is checked against the rendered
     * entry, not before building it, so the result cannot overshoot by one entry.
     */
    @Test
    public void testAVeryLargeKeyedDiffStaysUnderTheCharacterCap() {
        DiffResult result = differ.diff(hugeKeyed(5000, EXCEL_CELL_MAX, 'a'),
                hugeKeyed(5000, EXCEL_CELL_MAX, 'b'));

        assertTrue("unified() must not exceed maxChars, was " + result.unified().length(),
                result.unified().length() <= MAX_CHARS);
        // Guards against a vacuous pass: the budget must actually have been spent, not skipped.
        assertTrue("the budget must be used, not bypassed", result.unified().length() > MAX_CHARS / 2);
        assertTrue(result.truncated());
        // Counting never stops at the budget: every cell is still reported as modified.
        assertEquals(5000, result.changed());
        assertEquals(0, result.added());
        assertEquals(0, result.removed());
    }

    /** Same guarantee on the positional side, which has its own emission loop. */
    @Test
    public void testAVeryLargePositionalDiffStaysUnderTheCharacterCap() {
        List<ContentLine> before = new ArrayList<>();
        List<ContentLine> after = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            before.add(ContentLine.of(repeat('a', 5000) + i));
            after.add(ContentLine.of(repeat('b', 5000) + i));
        }

        DiffResult result = differ.diff(DiffableContent.positional(before, false),
                DiffableContent.positional(after, false));

        assertTrue("unified() must not exceed maxChars, was " + result.unified().length(),
                result.unified().length() <= MAX_CHARS);
        assertTrue("the budget must be used, not bypassed", result.unified().length() > MAX_CHARS / 2);
        assertTrue(result.truncated());
        assertEquals(2000, result.added() + result.changed());
        assertEquals(2000, result.removed() + result.changed());
    }

    /**
     * A single over-long unit is elided in the body, and the counters stay exact.
     * <p>
     * One entry only, so the character cap is never reached: what is asserted here is the second
     * cap, the per-value one, in isolation.
     */
    @Test
    public void testAnOverLongValueIsElidedWhileCountersStayExact() {
        String longValue = repeat('x', 10_000);
        DiffResult result = differ.diff(BlobAuditTestHelper.keyed("Sheet1!A1", "short"),
                BlobAuditTestHelper.keyed("Sheet1!A1", longValue));

        assertEquals(1, result.changed());
        assertEquals(0, result.added());
        assertEquals(0, result.removed());
        // A single entry never trips the character cap, so this truncation is not reported: the
        // diff is complete in terms of *differences*, only one rendered value was shortened.
        assertFalse(result.truncated());
        assertTrue(result.unified().contains("[10000 chars]"));
        assertFalse("the full value must not reach the diff body", result.unified().contains(longValue));
        assertTrue("the elided prefix must be kept", result.unified().contains(repeat('x', MAX_VALUE_LENGTH)));
        assertTrue(result.unified().length() < MAX_VALUE_LENGTH + 200);
    }

    /** A value exactly at the limit is left alone: the cap is inclusive. */
    @Test
    public void testAValueExactlyAtTheLimitIsNotElided() {
        String atLimit = repeat('x', MAX_VALUE_LENGTH);
        DiffResult result = differ.diff(BlobAuditTestHelper.keyed("Sheet1!A1", "short"),
                BlobAuditTestHelper.keyed("Sheet1!A1", atLimit));

        assertTrue(result.unified().contains(atLimit));
        assertFalse(result.unified().contains("chars]"));
    }

    /**
     * The key is elided too. Without it the per-entry size would stay unbounded: spreadsheet keys
     * are short, but a keyed extractor is free to use JSON pointers, which are not.
     */
    @Test
    public void testAnOverLongKeyIsElidedToo() {
        String longKey = repeat('k', 10_000);
        DiffResult result = differ.diff(BlobAuditTestHelper.keyed(longKey, "before"),
                BlobAuditTestHelper.keyed(longKey, "after"));

        assertEquals(1, result.changed());
        assertFalse("the full key must not reach the diff body", result.unified().contains(longKey));
        assertTrue(result.unified().contains("[10000 chars]"));
    }

    /**
     * {@code merge} applies the same cap to the concatenation of the text diff and the image
     * inventory. Each side is bounded on its own, so without this the merged body could reach twice
     * the cap and the image inventory would quietly undo the bound.
     */
    @Test
    public void testMergeKeepsTheConcatenationUnderTheCap() {
        TextDiffer small = new TextDiffer(5000, 4096, MAX_VALUE_LENGTH);
        DiffResult text = new DiffResult(1, 0, 0, false, ("+ text line\n").repeat(300));
        DiffResult images = new DiffResult(0, 1, 0, false, ("- image line\n").repeat(300));

        DiffResult merged = small.merge(text, images);

        assertTrue("merged body must not exceed maxChars, was " + merged.unified().length(),
                merged.unified().length() <= 4096);
        assertTrue(merged.truncated());
        // Counters are summed whatever the rendering had room for.
        assertEquals(1, merged.added());
        assertEquals(1, merged.removed());
        // The cut falls back to a line boundary: the tail is never a half-rendered entry.
        assertTrue(merged.unified().endsWith("\n"));
    }

    /** Under the cap, merge must stay a plain concatenation and flag nothing. */
    @Test
    public void testMergeLeavesASmallResultUntouched() {
        DiffResult text = new DiffResult(1, 0, 0, false, "+ text line\n");
        DiffResult images = new DiffResult(0, 1, 0, false, "- image line\n");

        DiffResult merged = differ.merge(text, images);

        assertFalse(merged.truncated());
        assertEquals("+ text line\n\n# Images\n- image line\n", merged.unified());
    }
}

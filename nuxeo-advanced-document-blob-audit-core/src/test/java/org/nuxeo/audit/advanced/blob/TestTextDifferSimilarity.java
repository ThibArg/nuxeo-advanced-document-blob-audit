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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Item 62: {@link DiffResult#similarity()}, the share of the smaller side kept unchanged in the
 * other, in characters. It is what tells a file replaced by another one from a file edited.
 *
 * @since 2025.1
 */
public class TestTextDifferSimilarity {

    protected static final double DELTA = 1e-9;

    protected final TextDiffer differ = new TextDiffer();

    protected static DiffableContent text(List<String> lines) {
        List<ContentLine> content = new ArrayList<>();
        for (String line : lines) {
            content.add(ContentLine.of(line));
        }
        return DiffableContent.positional(content, false);
    }

    /** Fixed-width numbering, so that every line of every call has the same length. */
    protected static List<String> lines(String prefix, int count) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lines.add(String.format("%s line number %03d", prefix, i));
        }
        return lines;
    }

    @Test
    public void testAnUnchangedContentScoresOne() {
        List<String> lines = lines("same", 20);

        assertEquals(1d, differ.diff(text(lines), text(lines)).similarity(), DELTA);
    }

    @Test
    public void testAContentRewrittenFromEndToEndScoresZero() {
        DiffResult result = differ.diff(text(lines("before", 30)), text(lines("after", 30)));

        assertEquals(0d, result.similarity(), DELTA);
    }

    /** The smaller side is the reference: what was there is all still there. */
    @Test
    public void testALargeAdditionStillScoresOne() {
        List<String> before = lines("kept", 20);
        List<String> after = new ArrayList<>(before);
        after.addAll(lines("added", 500));

        assertEquals(1d, differ.diff(text(before), text(after)).similarity(), DELTA);
    }

    @Test
    public void testAMassDeletionStillScoresOne() {
        List<String> before = lines("kept", 500);
        List<String> after = new ArrayList<>(before.subList(0, 20));

        assertEquals(1d, differ.diff(text(before), text(after)).similarity(), DELTA);
    }

    /** Lines of the same length, so the share in characters is the share in lines. */
    @Test
    public void testAHeavyEditKeepingAThirdScoresAThird() {
        List<String> before = lines("aaaa", 30);
        List<String> after = new ArrayList<>(before.subList(0, 10));
        after.addAll(lines("bbbb", 20));

        assertEquals(1d / 3, differ.diff(text(before), text(after)).similarity(), DELTA);
    }

    /**
     * Two unrelated JSON documents share every brace line, which is half of their lines. Counted in
     * characters, those lines weigh next to nothing.
     */
    @Test
    public void testSharedPunctuationWeighsNextToNothing() {
        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            before.addAll(List.of("{", "\"id\": " + i + ",", "\"name\": \"customer number " + i + "\"", "},"));
            after.addAll(List.of("{", "\"code\": \"X-" + i + "\",", "\"label\": \"product reference " + i + "\"", "},"));
        }

        DiffResult result = differ.diff(text(before), text(after));

        assertTrue("half of the lines are shared, yet the score is " + result.similarity(),
                result.similarity() < 0.1);
    }

    @Test
    public void testAnEmptySideIsNotMeasured() {
        assertNull(differ.diff(text(List.of()), text(lines("added", 10))).similarity());
        assertNull(differ.diff(text(lines("removed", 10)), text(List.of())).similarity());
        assertNull(differ.diff(text(List.of()), text(List.of())).similarity());
    }

    /** A truncated window is measured on what was extracted, like the counters. */
    @Test
    public void testATruncatedWindowIsMeasuredOnWhatWasExtracted() {
        List<ContentLine> before = new ArrayList<>();
        List<ContentLine> after = new ArrayList<>();
        for (String line : lines("same", 10)) {
            before.add(ContentLine.of(line));
            after.add(ContentLine.of(line));
        }

        DiffResult result = differ.diff(DiffableContent.positional(before, true),
                DiffableContent.positional(after, true));

        assertEquals(1d, result.similarity(), DELTA);
    }

    @Test
    public void testKeyedContentCountsTheEntriesKeptWithTheirValue() {
        DiffableContent before = DiffableContent.keyed(List.of(ContentLine.of("A1", "1111"),
                ContentLine.of("A2", "2222"), ContentLine.of("A3", "3333"), ContentLine.of("A4", "4444")), false);
        DiffableContent after = DiffableContent.keyed(List.of(ContentLine.of("A1", "1111"),
                ContentLine.of("A2", "2222"), ContentLine.of("A3", "XXXX"), ContentLine.of("B4", "4444")), false);

        assertEquals(0.5, differ.diff(before, after).similarity(), DELTA);
    }

    /** The image inventory has a section of its own in the body, but the score stays the text's. */
    @Test
    public void testMergingTheImageInventoryKeepsTheTextScore() {
        DiffResult textResult = differ.diff(text(lines("aaaa", 30)), text(lines("aaaa", 10)));
        DiffResult images = new DiffResult(1, 0, 0, false, "+ image\n");

        DiffResult merged = differ.merge(textResult.asReplaced(), images);

        assertEquals(textResult.similarity(), merged.similarity());
        assertTrue(merged.replaced());
    }
}

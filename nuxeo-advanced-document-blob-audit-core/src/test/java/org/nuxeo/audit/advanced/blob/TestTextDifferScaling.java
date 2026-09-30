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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

/**
 * Scaling tests for the positional diff, guarding the property that motivated the use of
 * Hirschberg's algorithm: memory must stay linear, not quadratic.
 * <p>
 * The reference workload is a real one: a 200-page Word document extracts to roughly 5 000
 * paragraphs. A textbook LCS table would allocate ~92 MB for that single comparison, leaving no
 * headroom before an OutOfMemoryError under concurrent works.
 *
 * @since 1.0
 */
public class TestTextDifferScaling {

    /** Representative of the largest documents in the target corpus. */
    protected static final int REAL_WORLD_LINES = 4900;

    protected final TextDiffer differ = new TextDiffer(Integer.MAX_VALUE);

    protected DiffableContent lines(List<String> values) {
        List<ContentLine> content = new ArrayList<>(values.size());
        for (String value : values) {
            content.add(ContentLine.of(value));
        }
        return DiffableContent.positional(content, false);
    }

    protected List<String> paragraphs(int count, String suffix) {
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add("Paragraph " + i + " of the contract" + suffix);
        }
        return values;
    }

    /**
     * The headline case: two 4 900 line documents differing by a single paragraph. A quadratic
     * implementation would allocate ~92 MB here; this must run in a few tens of kilobytes of
     * working memory.
     */
    @Test
    public void testRealWorldSizedDocumentStaysWithinMemoryBudget() {
        List<String> before = paragraphs(REAL_WORLD_LINES, "");
        List<String> after = new ArrayList<>(before);
        after.set(2500, "Paragraph 2500 of the contract AMENDED");

        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long usedBefore = runtime.totalMemory() - runtime.freeMemory();

        DiffResult result = differ.diff(lines(before), lines(after));

        long allocated = (runtime.totalMemory() - runtime.freeMemory()) - usedBefore;

        assertEquals(1, result.changed());
        assertEquals(0, result.added());
        assertEquals(0, result.removed());
        // Generous bound: a quadratic implementation needs ~92 MB for this exact input, so any
        // regression to a full matrix would blow past 32 MB immediately.
        assertTrue("positional diff must not allocate a quadratic matrix, allocated=" + allocated,
                allocated < 32L * 1024 * 1024);
    }

    /**
     * A 200-page document entirely rewritten: the worst case for the alignment, and a reminder that
     * the counters are not an exact edit script. With no common subsequence at all, the recursion
     * emits every removal before every addition, so the coalescing pass finds a single
     * REMOVE-then-ADD boundary: 4 899 removals, 4 899 additions and 1 modification rather than the
     * 4 900 modifications a reader might expect.
     * <p>
     * The totals stay honest - every line is accounted for exactly once on each side - and the
     * unified output is complete.
     */
    @Test
    public void testFullyRewrittenLargeDocument() {
        DiffResult result = differ.diff(lines(paragraphs(REAL_WORLD_LINES, "")),
                lines(paragraphs(REAL_WORLD_LINES, " REWRITTEN")));

        assertEquals(REAL_WORLD_LINES - 1, result.removed());
        assertEquals(REAL_WORLD_LINES - 1, result.added());
        assertEquals(1, result.changed());
        // every line of each side is reported exactly once
        assertEquals(REAL_WORLD_LINES, result.removed() + result.changed());
        assertEquals(REAL_WORLD_LINES, result.added() + result.changed());
    }

    /** Asymmetric input: a large document compared against an empty one. */
    @Test
    public void testLargeVersusEmpty() {
        DiffResult result = differ.diff(lines(paragraphs(REAL_WORLD_LINES, "")), lines(List.of()));

        assertEquals(REAL_WORLD_LINES, result.removed());
        assertEquals(0, result.added());
        assertEquals(0, result.changed());
    }

    /**
     * The point of the prefix/suffix trimming: the dominant real case is no longer quadratic.
     * <p>
     * 40 000 paragraphs - four times the shipped {@code maxLines} cap - with a single amended
     * paragraph. Without trimming the alignment would run 1.6 billion cell comparisons, about 18 s
     * on the reference machine. With it, the middle is one line and the cost collapses to the two
     * linear scans. The bound is deliberately generous: it is there to catch a regression to the
     * quadratic path, not to measure the machine.
     */
    @Test
    public void testSingleChangeInAVeryLargeDocumentIsNearLinear() {
        int size = 40_000;
        List<String> before = paragraphs(size, "");
        List<String> after = new ArrayList<>(before);
        after.set(size / 2, "Paragraph " + (size / 2) + " of the contract AMENDED");

        long start = System.nanoTime();
        DiffResult result = differ.diff(lines(before), lines(after));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, result.changed());
        assertEquals(0, result.added());
        assertEquals(0, result.removed());
        assertTrue("a single change in a " + size + " line document must not trigger a quadratic "
                + "alignment, took " + elapsedMs + " ms", elapsedMs < 2_000);
    }

    /**
     * Trimming must not turn the worst case into a wrong answer: nothing is shared here, so the
     * full alignment still runs and the counters must be exactly what they were before.
     */
    @Test
    public void testTrimmingDoesNotChangeTheFullyRewrittenCase() {
        DiffResult trimmed = differ.diff(lines(paragraphs(200, "")), lines(paragraphs(200, " REWRITTEN")));

        assertEquals(199, trimmed.removed());
        assertEquals(199, trimmed.added());
        assertEquals(1, trimmed.changed());
    }

    /**
     * Hirschberg must produce an <em>optimal</em> alignment, not merely a plausible one. The
     * invariant checked here is the defining property of an LCS-based diff: the number of preserved
     * lines equals the length of the longest common subsequence.
     */
    @Test
    public void testAlignmentIsOptimalOnRandomInputs() {
        Random random = new Random(1234);
        for (int iteration = 0; iteration < 500; iteration++) {
            List<String> a = randomSequence(random, 12);
            List<String> b = randomSequence(random, 12);

            DiffResult result = differ.diff(lines(a), lines(b));

            // a preserved line is one that is neither removed nor part of a modification
            int preserved = a.size() - result.removed() - result.changed();
            assertEquals("suboptimal alignment for a=" + a + " b=" + b, lcsLength(a, b), preserved);
        }
    }

    /** Both sides must always balance, whatever the alignment chosen among equally optimal ones. */
    @Test
    public void testCountingInvariantHolds() {
        Random random = new Random(99);
        for (int iteration = 0; iteration < 500; iteration++) {
            List<String> a = randomSequence(random, 15);
            List<String> b = randomSequence(random, 15);

            DiffResult result = differ.diff(lines(a), lines(b));

            int keptFromA = a.size() - result.removed() - result.changed();
            int keptFromB = b.size() - result.added() - result.changed();
            assertEquals(keptFromA, keptFromB);
            assertTrue(keptFromA >= 0);
        }
    }

    protected List<String> randomSequence(Random random, int maxLength) {
        int length = random.nextInt(maxLength);
        List<String> values = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            values.add(String.valueOf((char) ('a' + random.nextInt(4))));
        }
        return values;
    }

    /** Straightforward O(n*m) LCS length, used only as a test oracle. */
    protected int lcsLength(List<String> a, List<String> b) {
        int[][] table = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++) {
                table[i][j] = a.get(i - 1).equals(b.get(j - 1)) ? table[i - 1][j - 1] + 1
                        : Math.max(table[i - 1][j], table[i][j - 1]);
            }
        }
        return table[a.size()][b.size()];
    }
}

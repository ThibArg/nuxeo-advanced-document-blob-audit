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
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.keyed;
import static org.nuxeo.audit.advanced.blob.BlobAuditTestHelper.positional;

import org.junit.Test;

/**
 * Unit tests for the diff engine. No Nuxeo runtime involved: {@link TextDiffer} is deliberately
 * free of any platform dependency so it can be tested and reasoned about in isolation.
 *
 * @since 1.0
 */
public class TestTextDiffer {

    protected final TextDiffer differ = new TextDiffer();

    /* ------------------------------------------------------------- positional */

    @Test
    public void testPositionalIdenticalYieldsNoDiff() {
        DiffResult result = differ.diff(positional("L1", "L2", "L3"), positional("L1", "L2", "L3"));

        assertTrue(result.isEmpty());
        assertEquals(0, result.added());
        assertEquals(0, result.removed());
        assertEquals(0, result.changed());
        assertEquals("", result.unified());
        assertEquals("No textual change detected", result.summary());
    }

    /**
     * A removal immediately followed by an addition must be reported as a single modification, not
     * as one removal plus one addition. This is the whole point of the coalescing pass.
     */
    @Test
    public void testPositionalModificationIsCoalesced() {
        DiffResult result = differ.diff(positional("L1", "L2", "L3"), positional("L1", "L2-modified", "L3"));

        assertEquals(0, result.added());
        assertEquals(0, result.removed());
        assertEquals(1, result.changed());
        assertTrue(result.unified().contains("- L2"));
        assertTrue(result.unified().contains("+ L2-modified"));
        assertEquals("1 modification", result.summary());
    }

    @Test
    public void testPositionalPureAddition() {
        DiffResult result = differ.diff(positional("L1", "L2"), positional("L1", "L2", "L3"));

        assertEquals(1, result.added());
        assertEquals(0, result.removed());
        assertEquals(0, result.changed());
        assertTrue(result.unified().contains("+ L3"));
        assertEquals("1 addition", result.summary());
    }

    @Test
    public void testPositionalPureRemoval() {
        DiffResult result = differ.diff(positional("L1", "L2", "L3"), positional("L1", "L3"));

        assertEquals(0, result.added());
        assertEquals(1, result.removed());
        assertEquals(0, result.changed());
        assertTrue(result.unified().contains("- L2"));
        assertEquals("1 deletion", result.summary());
    }

    /** Inserting at the top must not be reported as "everything changed". */
    @Test
    public void testPositionalInsertionAtTopIsASingleAddition() {
        DiffResult result = differ.diff(positional("L1", "L2"), positional("L0", "L1", "L2"));

        assertEquals(1, result.added());
        assertEquals(0, result.removed());
        assertEquals(0, result.changed());
    }

    @Test
    public void testPositionalMixedChangeAndAddition() {
        DiffResult result = differ.diff(positional("L1", "L2", "L3"), positional("L1", "L2-mod", "L3", "L4"));

        assertEquals(1, result.added());
        assertEquals(0, result.removed());
        assertEquals(1, result.changed());
        assertEquals("1 modification, 1 addition", result.summary());
    }

    @Test
    public void testPositionalFromEmpty() {
        DiffResult result = differ.diff(positional(), positional("A", "B"));

        assertEquals(2, result.added());
        assertEquals(0, result.removed());
        assertEquals(0, result.changed());
        assertEquals("2 additions", result.summary());
    }

    @Test
    public void testPositionalToEmpty() {
        DiffResult result = differ.diff(positional("A", "B"), positional());

        assertEquals(0, result.added());
        assertEquals(2, result.removed());
        assertEquals(0, result.changed());
        assertEquals("2 deletions", result.summary());
    }

    /**
     * Documents a known artefact of the coalescing pass: two fully unrelated blocks of equal size
     * are reported as REMOVE + CHANGE + ADD rather than as 2 removals and 2 additions. The totals
     * remain honest and the unified output is complete, but the counters should not be read as an
     * exact edit script.
     */
    @Test
    public void testPositionalFullyDifferentBlocksCoalescePartially() {
        DiffResult result = differ.diff(positional("A", "B"), positional("C", "D"));

        assertEquals(1, result.added());
        assertEquals(1, result.removed());
        assertEquals(1, result.changed());
        assertFalse(result.isEmpty());
    }

    /* ------------------------------------------------- prefix/suffix trimming */

    /**
     * The prefix and the suffix scans must not overlap. When one sequence is entirely contained in
     * the other, a naive suffix scan would count the same lines twice and produce crossed sublist
     * bounds.
     */
    @Test
    public void testSequenceFullyContainedInTheOther() {
        DiffResult result = differ.diff(positional("A", "B"), positional("A", "B", "C"));

        assertEquals(1, result.added());
        assertEquals(0, result.removed());
        assertEquals(0, result.changed());
        assertTrue(result.unified().contains("+ C"));
    }

    @Test
    public void testSequenceFullyContainedInTheOtherReversed() {
        DiffResult result = differ.diff(positional("A", "B", "C"), positional("A", "B"));

        assertEquals(0, result.added());
        assertEquals(1, result.removed());
        assertEquals(0, result.changed());
    }

    /** Everything is common prefix: the alignment must never be reached. */
    @Test
    public void testIdenticalSequencesAreFullyTrimmed() {
        DiffResult result = differ.diff(positional("A", "B", "C"), positional("A", "B", "C"));

        assertTrue(result.isEmpty());
    }

    /** A change on the very first line leaves no common prefix, only a suffix. */
    @Test
    public void testChangeOnTheFirstLine() {
        DiffResult result = differ.diff(positional("A", "B", "C"), positional("A-modified", "B", "C"));

        assertEquals(1, result.changed());
        assertEquals(0, result.added());
        assertEquals(0, result.removed());
    }

    /** ... and symmetrically on the very last one. */
    @Test
    public void testChangeOnTheLastLine() {
        DiffResult result = differ.diff(positional("A", "B", "C"), positional("A", "B", "C-modified"));

        assertEquals(1, result.changed());
        assertEquals(0, result.added());
        assertEquals(0, result.removed());
    }

    /**
     * Repeated lines are the classic trap of a trimming pass: the prefix scan stops at the first
     * difference, so identical lines further down must still be aligned by the LCS, not silently
     * consumed.
     */
    @Test
    public void testRepeatedLinesAroundTheChange() {
        DiffResult result = differ.diff(positional("A", "A", "A", "B", "A", "A"),
                positional("A", "A", "A", "B-modified", "A", "A"));

        assertEquals(1, result.changed());
        assertEquals(0, result.added());
        assertEquals(0, result.removed());
    }

    @Test
    public void testPositionalTruncationIsReported() {
        TextDiffer limited = new TextDiffer(2);

        DiffResult result = limited.diff(positional("A", "B", "C", "D", "E"), positional());

        // every removal is counted, only the first two are rendered
        assertEquals(5, result.removed());
        assertTrue(result.truncated());
        assertEquals(2, result.unified().lines().count());
        assertTrue(result.summary().endsWith("(truncated)"));
    }

    /* ------------------------------------------------------------------ keyed */

    @Test
    public void testKeyedIdenticalYieldsNoDiff() {
        DiffResult result = differ.diff(keyed("S!A1", "x", "S!B1", "y"), keyed("S!A1", "x", "S!B1", "y"));

        assertTrue(result.isEmpty());
    }

    @Test
    public void testKeyedReportsAddedRemovedAndChanged() {
        DiffResult result = differ.diff(keyed("S!A1", "keep", "S!B1", "old", "S!C1", "gone"),
                keyed("S!A1", "keep", "S!B1", "new", "S!D1", "fresh"));

        assertEquals(1, result.added());
        assertEquals(1, result.removed());
        assertEquals(1, result.changed());
        assertTrue(result.unified().contains("~ S!B1 : old -> new"));
        assertTrue(result.unified().contains("- S!C1 = gone"));
        assertTrue(result.unified().contains("+ S!D1 = fresh"));
    }

    /**
     * The real benefit of keyed content: the comparison is a map comparison, so the order in which
     * units are extracted is irrelevant.
     */
    @Test
    public void testKeyedIsInsensitiveToExtractionOrder() {
        DiffResult result = differ.diff(keyed("S!A1", "x", "S!B1", "y", "S!C1", "z"),
                keyed("S!C1", "z", "S!A1", "x", "S!B1", "y"));

        assertTrue(result.isEmpty());
    }

    /** Mixing modes must fall back to the positional strategy rather than collapse on null keys. */
    @Test
    public void testMixedModesFallBackToPositional() {
        DiffResult result = differ.diff(keyed("S!A1", "x"), positional("S!A1 = x"));

        // ContentLine.render() emits "key = value", so both sides align
        assertTrue(result.isEmpty());
    }
}

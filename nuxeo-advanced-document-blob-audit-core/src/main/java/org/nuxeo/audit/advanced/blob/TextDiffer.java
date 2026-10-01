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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The single diff engine of the plugin, shared by every format.
 * <p>
 * Two strategies, selected from {@link DiffableContent#keyed()}:
 * <ul>
 * <li><b>keyed</b> - units are identified (spreadsheet cells, JSON pointers). Comparison is a map
 * comparison: O(n+m) in both time and memory, exact {@code added} / {@code removed} /
 * {@code changed} semantics, insensitive to extraction order.</li>
 * <li><b>positional</b> - units are a sequence (text lines, Word paragraphs). Comparison is an LCS
 * alignment computed with <b>Hirschberg's algorithm</b>; a removal immediately followed by an
 * addition is reported as a modification.</li>
 * </ul>
 * <p>
 * <b>Why Hirschberg rather than the textbook DP table.</b> The naive LCS allocates an
 * {@code int[n+1][m+1]} matrix, i.e. roughly {@code 4*n*m} bytes. At 5 000 lines that is ~92 MB
 * <em>per concurrent work</em>, and it grows quadratically: 10 000 lines costs ~380 MB, 20 000
 * lines ~1.5 GB. Real documents reach those sizes (a 200-page Word document extracts to about
 * 5 000 paragraphs), so the naive version forces a low {@code maxLines} cap and still risks an
 * OutOfMemoryError under a burst of edits.
 * <p>
 * Hirschberg computes the same optimal alignment in O(min(n,m)) memory - two working rows, a few
 * tens of kilobytes even at 200 000 lines - by recursively splitting the problem in half and
 * keeping only the last row of each half-matrix. Measured cost of that trade-off is a 1.4x to 1.6x
 * slowdown, irrelevant here since the whole comparison already runs asynchronously.
 * <p>
 * <b>Time is quadratic only on what actually differs.</b> Hirschberg fixes memory, not running
 * time, which stays O(n*m) on the sequences it is given. The positional diff therefore strips the
 * common prefix and suffix first (see {@link #positionalEdits}), and only the differing middle
 * reaches the alignment. In the dominant real case - a handful of paragraphs edited in a long
 * document - that middle is a few lines and the cost stops growing with the document: measured on
 * one modified paragraph, 139 ms to 4.7 ms at 5 000 lines, 336 ms to 2.4 ms at 10 000, 1.45 s to
 * 2.4 ms at 20 000.
 * <p>
 * The worst case - a document rewritten from end to end - shares nothing, trims nothing and stays
 * quadratic: ~140 ms at 10 000 lines, ~340 ms at 20 000. This is why {@code maxLines} still
 * matters, see {@link BlobDiffConfigDescriptor#getMaxLines()}.
 * <p>
 * The alignment itself runs over interned line ids rather than strings, so the {@code n*m} inner
 * loop compares {@code int}s instead of calling {@code String.equals}. That alone cuts the worst
 * case by about 2.6x at 20 000 lines, since paragraphs of a document typically share a long common
 * prefix and make {@code String.equals} walk most of their characters before failing.
 * <p>
 * No third-party diff library is required.
 *
 * @since 1.0
 */
public class TextDiffer {

    public static final int DEFAULT_MAX_DIFF_ENTRIES = 5000;

    /** @since 2025.4 */
    public static final int DEFAULT_MAX_DIFF_CHARS = 4 * 1024 * 1024;

    /** @since 2025.4 */
    public static final int DEFAULT_MAX_VALUE_LENGTH = 4096;

    /** Suffix appended to an elided value, before the character count. */
    protected static final String ELLIPSIS = "… [";

    protected final int maxEntries;

    /** @since 2025.4 */
    protected final int maxChars;

    /** @since 2025.4 */
    protected final int maxValueLength;

    public TextDiffer() {
        this(DEFAULT_MAX_DIFF_ENTRIES);
    }

    public TextDiffer(int maxEntries) {
        this(maxEntries, DEFAULT_MAX_DIFF_CHARS, DEFAULT_MAX_VALUE_LENGTH);
    }

    /**
     * @param maxEntries how many entries may be written to the diff body
     * @param maxChars hard cap on the length of the produced unified diff
     * @param maxValueLength a single extracted unit longer than this is elided in the diff body
     * @since 2025.4
     */
    public TextDiffer(int maxEntries, int maxChars, int maxValueLength) {
        this.maxEntries = maxEntries;
        this.maxChars = maxChars;
        this.maxValueLength = maxValueLength;
    }

    /**
     * Truncates a single unit so that one entry can never, on its own, blow the budget.
     * <p>
     * An Excel cell holds up to 32 767 characters and a Word paragraph is unbounded, so without
     * this a diff of a few thousand entries reaches hundreds of megabytes - held in a
     * {@code StringBuilder}, copied by {@code toString()}, copied again into a blob.
     * <p>
     * <b>Known limitation.</b> When both sides of a modification are longer than
     * {@code maxValueLength} and only differ past it, the rendered entry shows two identical
     * prefixes ({@code ~ key : X… -> X…}). The counters stay exact and {@code bdiff:changed} still
     * reports the modification; only the rendering is uninformative. Resolving it - a digest, or
     * the offset of the first difference - was judged not worth the complexity for a rare case.
     *
     * @since 2025.4
     */
    protected String elide(String value) {
        return value == null || value.length() <= maxValueLength ? value
                : value.substring(0, maxValueLength) + ELLIPSIS + value.length() + " chars]";
    }

    public DiffResult diff(DiffableContent oldContent, DiffableContent newContent) {
        boolean keyed = oldContent.keyed() && newContent.keyed();
        boolean truncated = oldContent.truncated() || newContent.truncated();
        return keyed ? diffKeyed(oldContent, newContent, truncated)
                : diffPositional(oldContent, newContent, truncated);
    }

    /**
     * Concatenates a text diff and an image inventory diff, under the same {@code maxChars} cap.
     * <p>
     * Each side is already bounded on its own, so without this the merged body could reach twice
     * the cap - the image inventory would quietly undo the bound. The cut falls back to the last
     * complete line, so the tail is never a half-rendered entry, and it flags the result as
     * truncated.
     *
     * @since 2025.4
     */
    public DiffResult merge(DiffResult text, DiffResult images) {
        String unified = text.unified();
        if (!images.unified().isEmpty()) {
            unified += (unified.isEmpty() ? "" : "\n") + "# Images\n" + images.unified();
        }
        boolean cut = false;
        if (unified.length() > maxChars) {
            int lastNewline = unified.lastIndexOf('\n', maxChars);
            unified = lastNewline < 0 ? "" : unified.substring(0, lastNewline + 1);
            cut = true;
        }
        return new DiffResult(text.added() + images.added(), text.removed() + images.removed(),
                text.changed() + images.changed(), text.truncated() || images.truncated() || cut, unified);
    }

    /* ------------------------------------------------------------------ keyed */

    protected DiffResult diffKeyed(DiffableContent oldContent, DiffableContent newContent, boolean truncated) {
        Map<String, String> oldMap = toMap(oldContent);
        Map<String, String> newMap = toMap(newContent);

        Set<String> keys = new LinkedHashSet<>(oldMap.keySet());
        keys.addAll(newMap.keySet());

        int added = 0;
        int removed = 0;
        int changed = 0;
        int emitted = 0;
        boolean cut = false;
        StringBuilder sb = new StringBuilder();

        for (String key : keys) {
            String oldValue = oldMap.get(key);
            String newValue = newMap.get(key);
            if (Objects.equals(oldValue, newValue)) {
                continue;
            }
            if (oldValue == null) {
                added++;
            } else if (newValue == null) {
                removed++;
            } else {
                changed++;
            }
            if (cut || emitted >= maxEntries) {
                // Counting continues past the budget on purpose: added/removed/changed must stay
                // exact whatever the rendering had room for. Once cut, stay cut - letting a later,
                // shorter entry through would produce a non-contiguous diff body.
                cut = true;
                continue;
            }
            String entry;
            if (oldValue == null) {
                entry = "+ " + elide(key) + " = " + elide(newValue) + "\n";
            } else if (newValue == null) {
                entry = "- " + elide(key) + " = " + elide(oldValue) + "\n";
            } else {
                entry = "~ " + elide(key) + " : " + elide(oldValue) + " -> " + elide(newValue) + "\n";
            }
            // Checked against the rendered entry, not before building it: testing sb.length()
            // alone would let the result overshoot maxChars by one whole entry.
            if (sb.length() + entry.length() > maxChars) {
                cut = true;
                continue;
            }
            sb.append(entry);
            emitted++;
        }
        return new DiffResult(added, removed, changed, truncated || cut, sb.toString());
    }

    protected Map<String, String> toMap(DiffableContent content) {
        Map<String, String> map = new LinkedHashMap<>();
        for (ContentLine line : content.lines()) {
            map.put(line.key(), line.value());
        }
        return map;
    }

    /* ------------------------------------------------------------- positional */

    protected DiffResult diffPositional(DiffableContent oldContent, DiffableContent newContent, boolean truncated) {
        List<String> a = render(oldContent);
        List<String> b = render(newContent);
        List<Edit> edits = coalesce(positionalEdits(a, b));

        int added = 0;
        int removed = 0;
        int changed = 0;
        int emitted = 0;
        boolean cut = false;
        StringBuilder sb = new StringBuilder();

        for (Edit edit : edits) {
            switch (edit.type) {
            case ADD -> added++;
            case REMOVE -> removed++;
            case CHANGE -> changed++;
            }
            if (cut || emitted >= maxEntries) {
                // See diffKeyed: counters stay exact past the budget, and the cut is monotone.
                cut = true;
                continue;
            }
            String entry = switch (edit.type) {
                case ADD -> "+ " + elide(edit.newValue) + "\n";
                case REMOVE -> "- " + elide(edit.oldValue) + "\n";
                case CHANGE -> "- " + elide(edit.oldValue) + "\n+ " + elide(edit.newValue) + "\n";
            };
            if (sb.length() + entry.length() > maxChars) {
                cut = true;
                continue;
            }
            sb.append(entry);
            emitted++;
        }
        return new DiffResult(added, removed, changed, truncated || cut, sb.toString());
    }

    protected List<String> render(DiffableContent content) {
        List<String> rendered = new ArrayList<>(content.size());
        for (ContentLine line : content.lines()) {
            rendered.add(line.render());
        }
        return rendered;
    }

    protected enum EditType {
        ADD, REMOVE, CHANGE
    }

    protected static class Edit {

        protected final EditType type;

        protected final String oldValue;

        protected final String newValue;

        protected Edit(EditType type, String oldValue, String newValue) {
            this.type = type;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }
    }

    /**
     * Strips the common prefix and the common suffix, then aligns only what is left.
     * <p>
     * This is what makes the dominant real case cheap. A 5 000 paragraph contract with one amended
     * paragraph shares 2 500 leading and 2 499 trailing paragraphs with its previous version: the
     * quadratic alignment then runs on 1x1 instead of 5000x5000, 139 ms down to 4.7 ms. A fully
     * rewritten document shares nothing, trims nothing and costs what it costed before - the
     * optimisation is free, never a pessimisation.
     * <p>
     * <b>Why this is safe.</b> Identical lines produce no {@link Edit} at all, so removing them
     * cannot change the output. And when {@code a[0].equals(b[0])} there is always an optimal
     * alignment that pairs those two lines, so {@code LCS(a, b) = prefix + LCS(middle) + suffix}:
     * trimming preserves optimality, which {@code TestTextDifferScaling} asserts against a
     * brute-force oracle.
     */
    protected List<Edit> positionalEdits(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int max = Math.min(n, m);

        int prefix = 0;
        while (prefix < max && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        // The two scans must not overlap: a sequence fully contained in the other would otherwise
        // have the same lines counted twice, and the sublist bounds would cross.
        int suffix = 0;
        while (suffix < max - prefix && a.get(n - 1 - suffix).equals(b.get(m - 1 - suffix))) {
            suffix++;
        }

        if (prefix == 0 && suffix == 0) {
            return lcsEdits(a, b);
        }
        return lcsEdits(a.subList(prefix, n - suffix), b.subList(prefix, m - suffix));
    }

    /**
     * Computes the edit script between two sequences using Hirschberg's algorithm.
     * <p>
     * Memory is O(min(n,m)); time stays O(n*m). The produced alignment is optimal, i.e. it preserves
     * exactly as many lines as the longest common subsequence.
     */
    protected List<Edit> lcsEdits(List<String> a, List<String> b) {
        List<Edit> edits = new ArrayList<>();
        new Alignment(a, b, edits).align(0, a.size(), 0, b.size());
        return edits;
    }

    /**
     * One positional alignment, over interned line ids rather than strings.
     * <p>
     * The inner loop of the LCS runs {@code n*m} comparisons. Comparing {@code int} identities
     * instead of calling {@code String.equals} removes the length check, the char-by-char walk and
     * the cache misses on the string data, which matters on paragraphs of a few hundred characters.
     * Interning is a single O(n+m) pass, and equal ids mean equal strings by construction.
     */
    protected static final class Alignment {

        protected final List<String> aValues;

        protected final List<String> bValues;

        protected final int[] a;

        protected final int[] b;

        protected final List<Edit> out;

        protected Alignment(List<String> aValues, List<String> bValues, List<Edit> out) {
            this.aValues = aValues;
            this.bValues = bValues;
            this.out = out;
            Map<String, Integer> ids = new HashMap<>();
            this.a = intern(aValues, ids);
            this.b = intern(bValues, ids);
        }

        protected static int[] intern(List<String> values, Map<String, Integer> ids) {
            int[] result = new int[values.size()];
            for (int i = 0; i < result.length; i++) {
                Integer id = ids.get(values.get(i));
                if (id == null) {
                    id = ids.size();
                    ids.put(values.get(i), id);
                }
                result[i] = id;
            }
            return result;
        }

        /**
         * Recursively aligns {@code a[aStart, aEnd)} against {@code b[bStart, bEnd)}, appending the
         * resulting edits to {@code out}.
         * <p>
         * Recursion halves the first sequence at each level, so the stack depth is O(log n): 18
         * frames for a 200 000 line document. No risk of stack overflow.
         */
        protected void align(int aStart, int aEnd, int bStart, int bEnd) {
            int n = aEnd - aStart;
            int m = bEnd - bStart;

            if (n == 0) {
                for (int j = bStart; j < bEnd; j++) {
                    out.add(new Edit(EditType.ADD, null, bValues.get(j)));
                }
                return;
            }
            if (m == 0) {
                for (int i = aStart; i < aEnd; i++) {
                    out.add(new Edit(EditType.REMOVE, aValues.get(i), null));
                }
                return;
            }
            if (n == 1) {
                // Base case: align the single line on its first occurrence, which matches the
                // tie-breaking of the classic quadratic implementation.
                int single = a[aStart];
                int match = -1;
                for (int j = bStart; j < bEnd; j++) {
                    if (single == b[j]) {
                        match = j;
                        break;
                    }
                }
                if (match < 0) {
                    out.add(new Edit(EditType.REMOVE, aValues.get(aStart), null));
                    for (int j = bStart; j < bEnd; j++) {
                        out.add(new Edit(EditType.ADD, null, bValues.get(j)));
                    }
                } else {
                    for (int j = bStart; j < match; j++) {
                        out.add(new Edit(EditType.ADD, null, bValues.get(j)));
                    }
                    for (int j = match + 1; j < bEnd; j++) {
                        out.add(new Edit(EditType.ADD, null, bValues.get(j)));
                    }
                }
                return;
            }

            // Split a in half, then find the column where the two half-alignments meet optimally.
            int mid = aStart + n / 2;
            int[] left = lastRow(aStart, mid, bStart, bEnd, false);
            int[] right = lastRow(mid, aEnd, bStart, bEnd, true);

            int bestJ = 0;
            int bestValue = -1;
            for (int j = 0; j <= m; j++) {
                int value = left[j] + right[m - j];
                if (value > bestValue) {
                    bestValue = value;
                    bestJ = j;
                }
            }

            align(aStart, mid, bStart, bStart + bestJ);
            align(mid, aEnd, bStart + bestJ, bEnd);
        }

        /**
         * Returns the last row of the LCS matrix between {@code a[aStart, aEnd)} and
         * {@code b[bStart, bEnd)}, using two rows of working memory only.
         *
         * @param reversed when {@code true}, both ranges are walked backwards, which is what lets
         *            the caller compute the suffix half of the alignment
         */
        protected int[] lastRow(int aStart, int aEnd, int bStart, int bEnd, boolean reversed) {
            int n = aEnd - aStart;
            int m = bEnd - bStart;
            int[] previous = new int[m + 1];
            int[] current = new int[m + 1];

            for (int i = 0; i < n; i++) {
                int ai = reversed ? a[aEnd - 1 - i] : a[aStart + i];
                current[0] = 0;
                for (int j = 0; j < m; j++) {
                    int bj = reversed ? b[bEnd - 1 - j] : b[bStart + j];
                    current[j + 1] = ai == bj ? previous[j] + 1 : Math.max(current[j], previous[j + 1]);
                }
                int[] swap = previous;
                previous = current;
                current = swap;
            }
            return previous;
        }
    }

    /** Turns a REMOVE immediately followed by an ADD into a single CHANGE. */
    protected List<Edit> coalesce(List<Edit> edits) {
        List<Edit> result = new ArrayList<>(edits.size());
        for (int i = 0; i < edits.size(); i++) {
            Edit current = edits.get(i);
            if (current.type == EditType.REMOVE && i + 1 < edits.size()
                    && edits.get(i + 1).type == EditType.ADD) {
                result.add(new Edit(EditType.CHANGE, current.oldValue, edits.get(i + 1).newValue));
                i++;
            } else {
                result.add(current);
            }
        }
        return result;
    }
}

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

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
 * @since 2025.1
 */
public class TextDiffer {

    public static final int DEFAULT_MAX_DIFF_ENTRIES = 5000;

    /**
     * Default cap on the length of the diff body, in characters.
     *
     * @since 2025.1
     */
    public static final int DEFAULT_MAX_DIFF_CHARS = 4 * 1024 * 1024;

    /**
     * Default length past which a single unit is elided.
     *
     * @since 2025.1
     */
    public static final int DEFAULT_MAX_VALUE_LENGTH = 4096;

    /** Suffix appended to an elided value, before the character count. */
    protected static final String ELLIPSIS = "… [";

    /**
     * Every line break {@code \R} matches, {@code \r\n} counting as one: what {@link #renderValue}
     * escapes.
     */
    protected static final Pattern LINE_BREAK = Pattern.compile("\\R");

    protected final int maxEntries;

    /**
     * Cap on the length of the diff body, in characters.
     *
     * @since 2025.1
     */
    protected final int maxChars;

    /**
     * Length past which a single unit is elided in the diff body.
     *
     * @since 2025.1
     */
    protected final int maxValueLength;

    public TextDiffer() {
        this(DEFAULT_MAX_DIFF_ENTRIES);
    }

    public TextDiffer(int maxEntries) {
        this(maxEntries, DEFAULT_MAX_DIFF_CHARS, DEFAULT_MAX_VALUE_LENGTH);
    }

    /**
     * Creates a differ whose diff body is bounded by the given caps.
     *
     * @param maxEntries how many entries may be written to the diff body
     * @param maxChars hard cap on the length of the produced unified diff
     * @param maxValueLength a single extracted unit longer than this is elided in the diff body
     * @since 2025.1
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
     * <b>Long values that differ past the kept prefix.</b> The built-in extractors compact long values
     * with a digest of the whole ({@link ContentLine#compact(String, int)}), short enough to pass
     * through here unchanged, so two such values still render differently. Only an extractor that
     * does not compact its values can show two identical prefixes ({@code ~ key : X… -> X…}); the
     * counters and {@code bdiff:changed} stay exact either way.
     *
     * @since 2025.1
     */
    protected String elide(String value) {
        return value == null || value.length() <= maxValueLength ? value
                : value.substring(0, maxValueLength) + ELLIPSIS + value.length() + " chars]";
    }

    /**
     * Renders a key or a value inside an entry: elided by {@link #elide(String)}, then each of its
     * line breaks written as the two characters {@code \n}.
     * <p>
     * The body is a line-oriented record: the viewer classifies each line by its {@code +}, {@code -},
     * {@code ~} or {@code #} prefix. A raw break inside an Excel cell (Alt+Enter), a PowerPoint
     * paragraph (Shift+Enter) or a table cell would split one entry into several lines, the next ones
     * unprefixed or, typed on purpose, prefixed like entries that never happened. The break is
     * escaped here, when the entry is rendered, so the extracted values, their comparison and the
     * similarity stay unchanged.
     *
     * @since 2025.1
     */
    protected String renderValue(String value) {
        String elided = elide(value);
        return elided == null ? null : LINE_BREAK.matcher(elided).replaceAll("\\\\n");
    }

    /**
     * Compares two extractions.
     * <p>
     * Each side may be cut at {@code maxLines}, independently. Past the last unit the two windows
     * share, a unit missing from a truncated side may simply lie beyond its cut: it is neither
     * reported nor counted, rather than reported as an addition or a removal that never happened.
     * Before that point, both windows cover the same part of the document, so the facts hold.
     * <p>
     * The result carries a {@link DiffResult#similarity()}: the characters of the units kept
     * unchanged, divided by the characters of the smaller side. Characters rather than units, so
     * that the closing braces, brackets and tags two unrelated JSON or XML files share weigh next
     * to nothing; the smaller side, so that a large addition or a mass deletion still scores
     * {@code 1}. It is measured on the extracted windows, like the counters.
     */
    public DiffResult diff(DiffableContent oldContent, DiffableContent newContent) {
        boolean keyed = oldContent.keyed() && newContent.keyed();
        return keyed ? diffKeyed(oldContent, newContent) : diffPositional(oldContent, newContent);
    }

    /**
     * Concatenates a text diff and an image inventory diff, under the same {@code maxChars} cap.
     * <p>
     * Each side is already bounded on its own, so without this the merged body could reach twice
     * the cap - the image inventory would quietly undo the bound. The cut falls back to the last
     * complete line, so the tail is never a half-rendered entry, and it flags the result as
     * truncated.
     *
     * @since 2025.1
     */
    public DiffResult merge(DiffResult text, DiffResult images) {
        String unified = text.unified();
        if (!images.unified().isEmpty()) {
            unified += (unified.isEmpty() ? "" : "\n") + "# Images\n" + images.unified();
        }
        boolean cut = false;
        if (unified.length() > maxChars) {
            // maxChars - 1: the kept prefix ends with the newline and must fit within maxChars
            int lastNewline = unified.lastIndexOf('\n', maxChars - 1);
            unified = lastNewline < 0 ? "" : unified.substring(0, lastNewline + 1);
            cut = true;
        }
        return new DiffResult(text.added() + images.added(), text.removed() + images.removed(),
                text.changed() + images.changed(), text.truncated() || images.truncated() || cut, unified,
                text.similarity(), text.replaced());
    }

    /**
     * Returns {@code kept / min(oldChars, newChars)}, or {@code null} when a side has no content.
     *
     * @since 2025.1
     */
    protected static Double similarity(long kept, long oldChars, long newChars) {
        long smaller = Math.min(oldChars, newChars);
        return smaller <= 0 ? null : Double.valueOf(Math.min(1d, (double) kept / smaller));
    }

    /**
     * Diff body within maxEntries and maxChars. The budget is checked on the rendered entry, and once
     * the body is cut it stays cut, so the body is always a contiguous prefix of the full diff.
     *
     * @since 2025.1
     */
    protected class Body {

        protected final StringBuilder text = new StringBuilder();

        protected int emitted;

        protected boolean cut;

        /** Appends the entry if it fits; past the budget, the entry is not even rendered. */
        protected void offer(Supplier<String> entry) {
            if (cut || emitted >= maxEntries) {
                cut = true;
                return;
            }
            String rendered = entry.get();
            // Checked on the rendered entry: testing text.length() alone would let the body
            // overshoot maxChars by one whole entry.
            if (text.length() + rendered.length() > maxChars) {
                cut = true;
                return;
            }
            text.append(rendered);
            emitted++;
        }

        protected boolean isCut() {
            return cut;
        }

        protected String text() {
            return text.toString();
        }
    }

    /* ------------------------------------------------------------------ keyed */

    protected DiffResult diffKeyed(DiffableContent oldContent, DiffableContent newContent) {
        boolean truncated = oldContent.truncated() || newContent.truncated();
        Map<String, String> oldMap = toMap(oldContent);
        Map<String, String> newMap = toMap(newContent);
        Set<String> oldUnknown = newContent.truncated() ? pastLastShared(oldMap, newMap) : Set.of();
        Set<String> newUnknown = oldContent.truncated() ? pastLastShared(newMap, oldMap) : Set.of();

        Set<String> keys = new LinkedHashSet<>(oldMap.keySet());
        keys.addAll(newMap.keySet());

        long kept = 0;
        int added = 0;
        int removed = 0;
        int changed = 0;
        var body = new Body();

        for (String key : keys) {
            String oldValue = oldMap.get(key);
            String newValue = newMap.get(key);
            if (oldValue != null && oldValue.equals(newValue)) {
                kept += keyedLength(key, oldValue);
            }
            if (Objects.equals(oldValue, newValue) || oldUnknown.contains(key) || newUnknown.contains(key)) {
                continue;
            }
            // Counted before the budget check: added/removed/changed stay exact whatever the body
            // had room for.
            if (oldValue == null) {
                added++;
            } else if (newValue == null) {
                removed++;
            } else {
                changed++;
            }
            body.offer(() -> keyedEntry(key, oldValue, newValue));
        }
        return new DiffResult(added, removed, changed, truncated || body.isCut(), body.text(),
                similarity(kept, keyedLength(oldMap), keyedLength(newMap)), false);
    }

    /**
     * Renders one keyed entry: {@code +} for an added key, {@code -} for a removed one, {@code ~} for
     * a changed value.
     *
     * @since 2025.1
     */
    protected String keyedEntry(String key, String oldValue, String newValue) {
        if (oldValue == null) {
            return "+ " + renderValue(key) + " = " + renderValue(newValue) + "\n";
        }
        if (newValue == null) {
            return "- " + renderValue(key) + " = " + renderValue(oldValue) + "\n";
        }
        return "~ " + renderValue(key) + " : " + renderValue(oldValue) + " -> " + renderValue(newValue) + "\n";
    }

    protected static long keyedLength(String key, String value) {
        return (key == null ? 0 : key.length()) + (value == null ? 0 : value.length());
    }

    protected static long keyedLength(Map<String, String> map) {
        long length = 0;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            length += keyedLength(entry.getKey(), entry.getValue());
        }
        return length;
    }

    protected Map<String, String> toMap(DiffableContent content) {
        Map<String, String> map = new LinkedHashMap<>();
        for (ContentLine line : content.lines()) {
            map.put(line.key(), line.value());
        }
        return map;
    }

    /**
     * Returns the keys of {@code side} that come, in extraction order, after the last one
     * {@code other} also holds, and that {@code other} does not hold. Both windows follow the order of
     * the document, so a key before a shared one would have been extracted on both sides.
     */
    protected static Set<String> pastLastShared(Map<String, String> side, Map<String, String> other) {
        Set<String> past = new HashSet<>();
        for (String key : side.keySet()) {
            if (other.containsKey(key)) {
                past.clear();
            } else {
                past.add(key);
            }
        }
        return past;
    }

    /* ------------------------------------------------------------- positional */

    protected DiffResult diffPositional(DiffableContent oldContent, DiffableContent newContent) {
        boolean truncated = oldContent.truncated() || newContent.truncated();
        List<String> a = render(oldContent);
        List<String> b = render(newContent);
        Comparison comparison = positionalEdits(a, b, oldContent.truncated(), newContent.truncated());
        List<Edit> edits = coalesce(comparison.edits());

        int added = 0;
        int removed = 0;
        int changed = 0;
        var body = new Body();

        for (Edit edit : edits) {
            // See diffKeyed: counted before the budget check.
            switch (edit.type()) {
            case ADD -> added++;
            case REMOVE -> removed++;
            case CHANGE -> changed++;
            }
            body.offer(() -> positionalEntry(edit));
        }
        return new DiffResult(added, removed, changed, truncated || body.isCut(), body.text(),
                similarity(comparison.keptChars(), length(a), length(b)), false);
    }

    /**
     * Renders one positional edit: {@code +} for an added line, {@code -} for a removed one, both for
     * a changed one.
     *
     * @since 2025.1
     */
    protected String positionalEntry(Edit edit) {
        return switch (edit.type()) {
            case ADD -> "+ " + renderValue(edit.newValue()) + "\n";
            case REMOVE -> "- " + renderValue(edit.oldValue()) + "\n";
            case CHANGE -> "- " + renderValue(edit.oldValue()) + "\n+ " + renderValue(edit.newValue()) + "\n";
        };
    }

    protected static long length(List<String> lines) {
        long length = 0;
        for (String line : lines) {
            length += line.length();
        }
        return length;
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

    protected record Edit(EditType type, String oldValue, String newValue) {
    }

    /**
     * Strips the common prefix and the common suffix, then aligns only what is left.
     * <p>
     * This is what makes the dominant real case cheap. A 5 000 paragraph contract with one amended
     * paragraph shares 2 500 leading and 2 499 trailing paragraphs with its previous version: the
     * quadratic alignment then runs on 1x1 instead of 5000x5000 (figures in the class Javadoc). A
     * fully rewritten document shares nothing and trims nothing, and only pays the two linear scans
     * on top of the alignment - the optimisation is never a pessimisation.
     * <p>
     * <b>Why this is safe.</b> Identical lines produce no {@link Edit} at all, so removing them
     * cannot change the output. And when {@code a[0].equals(b[0])} there is always an optimal
     * alignment that pairs those two lines, so {@code LCS(a, b) = prefix + LCS(middle) + suffix}:
     * trimming preserves optimality, which {@code TestTextDifferScaling} asserts against a
     * brute-force oracle.
     * <p>
     * Past the last aligned line, a removal is unknown when the new side is truncated and an
     * addition when the old side is: the line may lie beyond the cut. Those edits are dropped.
     * <p>
     * The kept characters are those of the common prefix, the common suffix and the aligned lines,
     * counted before that drop: every line of the old side is either kept or removed.
     */
    protected Comparison positionalEdits(List<String> a, List<String> b, boolean oldTruncated,
            boolean newTruncated) {
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

        List<Edit> edits = new ArrayList<>();
        var alignment = new Alignment(a.subList(prefix, n - suffix), b.subList(prefix, m - suffix), edits);
        alignment.align(0, n - prefix - suffix, 0, m - prefix - suffix);
        long kept = length(a);
        for (Edit edit : edits) {
            if (edit.type() == EditType.REMOVE) {
                kept -= edit.oldValue().length();
            }
        }
        if (suffix > 0 || !(oldTruncated || newTruncated)) {
            return new Comparison(edits, kept);
        }
        List<Edit> known = new ArrayList<>(edits.subList(0, alignment.tailStart));
        for (Edit edit : edits.subList(alignment.tailStart, edits.size())) {
            if (edit.type() == EditType.REMOVE ? !newTruncated : !oldTruncated) {
                known.add(edit);
            }
        }
        return new Comparison(known, kept);
    }

    /**
     * Edits of a positional comparison, and the characters of the lines it kept unchanged.
     *
     * @since 2025.1
     */
    protected record Comparison(List<Edit> edits, long keptChars) {
    }

    /**
     * One positional alignment, over interned line ids rather than strings.
     * <p>
     * The inner loop of the LCS runs {@code n*m} comparisons. Comparing {@code int} identities
     * instead of calling {@code String.equals} removes the length check, the char-by-char walk and
     * the cache misses on the string data, which matters on paragraphs of a few hundred characters.
     * Interning is a single O(n+m) pass, and equal ids mean equal strings by construction.
     */
    protected static class Alignment {

        protected final List<String> aValues;

        protected final List<String> bValues;

        protected final int[] a;

        protected final int[] b;

        protected final List<Edit> out;

        /** Index in {@link #out} of the first edit after the last aligned pair of lines. */
        protected int tailStart;

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
                    tailStart = out.size();
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
            if (current.type() == EditType.REMOVE && i + 1 < edits.size()
                    && edits.get(i + 1).type() == EditType.ADD) {
                result.add(new Edit(EditType.CHANGE, current.oldValue(), edits.get(i + 1).newValue()));
                i++;
            } else {
                result.add(current);
            }
        }
        return result;
    }
}

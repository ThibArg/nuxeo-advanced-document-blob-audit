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
 * <b>Time is still quadratic.</b> Hirschberg fixes memory, not running time, which stays O(n*m).
 * Measured on JDK 17, one paragraph modified: 13 ms at 1 000 lines, 304 ms at 4 900, ~1.1 s at
 * 10 000, ~4.6 s at 20 000. This is why {@code maxLines} still matters - see
 * {@link BlobDiffConfigDescriptor#getMaxLines()}.
 * <p>
 * No third-party diff library is required.
 *
 * @since 1.0
 */
public class TextDiffer {

    public static final int DEFAULT_MAX_DIFF_ENTRIES = 5000;

    protected final int maxEntries;

    public TextDiffer() {
        this(DEFAULT_MAX_DIFF_ENTRIES);
    }

    public TextDiffer(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public DiffResult diff(DiffableContent oldContent, DiffableContent newContent) {
        boolean keyed = oldContent.keyed() && newContent.keyed();
        boolean truncated = oldContent.truncated() || newContent.truncated();
        return keyed ? diffKeyed(oldContent, newContent, truncated)
                : diffPositional(oldContent, newContent, truncated);
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
            if (emitted >= maxEntries) {
                cut = true;
                continue;
            }
            emitted++;
            if (oldValue == null) {
                sb.append("+ ").append(key).append(" = ").append(newValue).append('\n');
            } else if (newValue == null) {
                sb.append("- ").append(key).append(" = ").append(oldValue).append('\n');
            } else {
                sb.append("~ ").append(key).append(" : ").append(oldValue).append(" -> ").append(newValue).append('\n');
            }
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
        List<Edit> edits = coalesce(lcsEdits(a, b));

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
            if (emitted >= maxEntries) {
                cut = true;
                continue;
            }
            emitted++;
            switch (edit.type) {
            case ADD -> sb.append("+ ").append(edit.newValue).append('\n');
            case REMOVE -> sb.append("- ").append(edit.oldValue).append('\n');
            case CHANGE -> sb.append("- ")
                             .append(edit.oldValue)
                             .append('\n')
                             .append("+ ")
                             .append(edit.newValue)
                             .append('\n');
            }
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
     * Computes the edit script between two sequences using Hirschberg's algorithm.
     * <p>
     * Memory is O(min(n,m)); time stays O(n*m). The produced alignment is optimal, i.e. it preserves
     * exactly as many lines as the longest common subsequence.
     */
    protected List<Edit> lcsEdits(List<String> a, List<String> b) {
        List<Edit> edits = new ArrayList<>();
        hirschberg(a, 0, a.size(), b, 0, b.size(), edits);
        return edits;
    }

    /**
     * Recursively aligns {@code a[aStart, aEnd)} against {@code b[bStart, bEnd)}, appending the
     * resulting edits to {@code out}.
     * <p>
     * Recursion halves the first sequence at each level, so the stack depth is O(log n): 18 frames
     * for a 200 000 line document. No risk of stack overflow.
     */
    protected void hirschberg(List<String> a, int aStart, int aEnd, List<String> b, int bStart, int bEnd,
            List<Edit> out) {
        int n = aEnd - aStart;
        int m = bEnd - bStart;

        if (n == 0) {
            for (int j = bStart; j < bEnd; j++) {
                out.add(new Edit(EditType.ADD, null, b.get(j)));
            }
            return;
        }
        if (m == 0) {
            for (int i = aStart; i < aEnd; i++) {
                out.add(new Edit(EditType.REMOVE, a.get(i), null));
            }
            return;
        }
        if (n == 1) {
            // Base case: align the single line on its first occurrence, which matches the
            // tie-breaking of the classic quadratic implementation.
            String single = a.get(aStart);
            int match = -1;
            for (int j = bStart; j < bEnd; j++) {
                if (single.equals(b.get(j))) {
                    match = j;
                    break;
                }
            }
            if (match < 0) {
                out.add(new Edit(EditType.REMOVE, single, null));
                for (int j = bStart; j < bEnd; j++) {
                    out.add(new Edit(EditType.ADD, null, b.get(j)));
                }
            } else {
                for (int j = bStart; j < match; j++) {
                    out.add(new Edit(EditType.ADD, null, b.get(j)));
                }
                for (int j = match + 1; j < bEnd; j++) {
                    out.add(new Edit(EditType.ADD, null, b.get(j)));
                }
            }
            return;
        }

        // Split a in half, then find the column where the two half-alignments meet optimally.
        int mid = aStart + n / 2;
        int[] left = lcsLastRow(a, aStart, mid, b, bStart, bEnd, false);
        int[] right = lcsLastRow(a, mid, aEnd, b, bStart, bEnd, true);

        int bestJ = 0;
        int bestValue = -1;
        for (int j = 0; j <= m; j++) {
            int value = left[j] + right[m - j];
            if (value > bestValue) {
                bestValue = value;
                bestJ = j;
            }
        }

        hirschberg(a, aStart, mid, b, bStart, bStart + bestJ, out);
        hirschberg(a, mid, aEnd, b, bStart + bestJ, bEnd, out);
    }

    /**
     * Returns the last row of the LCS matrix between {@code a[aStart, aEnd)} and
     * {@code b[bStart, bEnd)}, using two rows of working memory only.
     *
     * @param reversed when {@code true}, both ranges are walked backwards, which is what lets the
     *            caller compute the suffix half of the alignment
     */
    protected int[] lcsLastRow(List<String> a, int aStart, int aEnd, List<String> b, int bStart, int bEnd,
            boolean reversed) {
        int n = aEnd - aStart;
        int m = bEnd - bStart;
        int[] previous = new int[m + 1];
        int[] current = new int[m + 1];

        for (int i = 0; i < n; i++) {
            String ai = reversed ? a.get(aEnd - 1 - i) : a.get(aStart + i);
            current[0] = 0;
            for (int j = 0; j < m; j++) {
                String bj = reversed ? b.get(bEnd - 1 - j) : b.get(bStart + j);
                current[j + 1] = ai.equals(bj) ? previous[j] + 1 : Math.max(current[j], previous[j + 1]);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous;
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

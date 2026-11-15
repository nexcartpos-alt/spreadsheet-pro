import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The logic behind the fill handle: extends a block of cells by copying or continuing it.
 * <ul>
 * <li>1, 2 -> 3, 4, 5 ... (a steady number series; a single number is simply copied)</li>
 * <li>Item 1 -> Item 2, Item 3 ...   Mon -> Tue, Wed ...   Jan -> Feb, Mar ...</li>
 * <li>formulas are copied with their relative references moved ($A$1 stays put)</li>
 * <li>anything else repeats the selected block</li>
 * </ul>
 */
public final class FillSeries {

    public enum Dir { DOWN, UP, RIGHT, LEFT }

    private static final Pattern NUMBER = Pattern.compile("^[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?$");
    private static final Pattern TRAILING = Pattern.compile("^(.*?)(\\d+)$", Pattern.DOTALL);

    private static final String[][] LISTS = {
            {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"},
            {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"},
            {"January", "February", "March", "April", "May", "June", "July", "August", "September",
                    "October", "November", "December"},
            {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"}};

    private FillSeries() {
    }

    /**
     * Extends the block (r1,c1)-(r2,c2) by {@code count} rows (DOWN / UP) or columns (RIGHT / LEFT).
     * Cells beyond the sheet are skipped. Does nothing on a protected sheet.
     * @return the number of cells written
     */
    public static int fill(SpreadsheetModel m, int r1, int c1, int r2, int c2, Dir dir, int count) {
        if (m.isProtected() || count <= 0) return 0;
        boolean vertical = dir == Dir.DOWN || dir == Dir.UP;
        boolean forward = dir == Dir.DOWN || dir == Dir.RIGHT;
        int lines = vertical ? c2 - c1 + 1 : r2 - r1 + 1;   // columns (or rows) are filled independently
        int n = vertical ? r2 - r1 + 1 : c2 - c1 + 1;       // source cells per line
        int written = 0;
        m.beginBatch();
        try {
            for (int line = 0; line < lines; line++) {
                List<String> src = new ArrayList<>();
                List<CellStyle> styles = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    int k = forward ? i : n - 1 - i; // fill order: the last one is next to the new cells
                    int r = vertical ? r1 + k : r1 + line;
                    int c = vertical ? c1 + line : c1 + k;
                    src.add(m.getInput(r, c));
                    CellStyle st = m.getStyle(r, c);
                    styles.add(st == null ? null : st.copy());
                }
                List<String> out = generate(src, count, forward ? 1 : -1, vertical);
                for (int k = 0; k < count; k++) {
                    int pos = n + k; // distance from the start of the source, in fill order
                    int r;
                    int c;
                    if (vertical) {
                        r = forward ? r1 + pos : r2 - pos;
                        c = c1 + line;
                    } else {
                        r = r1 + line;
                        c = forward ? c1 + pos : c2 - pos;
                    }
                    if (!m.inBounds(r, c)) continue;
                    CellStyle st = styles.get(k % n);
                    m.setInputs(r, c, out.get(k), st == null ? null : st.copy());
                    written++;
                }
            }
        } finally {
            m.endBatch();
        }
        return written;
    }

    /** Remembers the current contents of a block so that it can be put back (undo). */
    public static Runnable snapshot(SpreadsheetModel m, int r1, int c1, int r2, int c2) {
        int rows = r2 - r1 + 1;
        int cols = c2 - c1 + 1;
        String[][] text = new String[rows][cols];
        CellStyle[][] st = new CellStyle[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                text[r][c] = m.getInput(r1 + r, c1 + c);
                CellStyle s = m.getStyle(r1 + r, c1 + c);
                st[r][c] = s == null ? null : s.copy();
            }
        }
        return () -> {
            if (m.isProtected()) return;
            m.beginBatch();
            try {
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < cols; c++) {
                        m.setInputs(r1 + r, c1 + c, text[r][c], st[r][c] == null ? null : st[r][c].copy());
                    }
                }
            } finally {
                m.endBatch();
            }
        };
    }

    /** What to write into {@code count} new cells after the source cells (given in fill order). */
    static List<String> generate(List<String> src, int count, int sign, boolean vertical) {
        int n = src.size();
        List<String> out = new ArrayList<>(count);

        // 1) steady number series (a single number is just copied, below)
        if (n >= 2 && allNumbers(src)) {
            BigDecimal last = new BigDecimal(src.get(n - 1).trim());
            BigDecimal step = last.subtract(new BigDecimal(src.get(n - 2).trim()));
            boolean steady = true;
            for (int i = 2; i < n && steady; i++) {
                BigDecimal d = new BigDecimal(src.get(i).trim()).subtract(new BigDecimal(src.get(i - 1).trim()));
                if (d.compareTo(step) != 0) steady = false;
            }
            if (steady) {
                for (int k = 1; k <= count; k++) out.add(plain(last.add(step.multiply(BigDecimal.valueOf(k)))));
                return out;
            }
        }

        // 2) names of days / months
        List<String> names = nameSeries(src, count, sign);
        if (names != null) return names;

        // 3) text ending in a number (Item 1, Q3, ...)
        List<String> numbered = numberedSeries(src, count, sign);
        if (numbered != null) return numbered;

        // 4) repeat the block; formulas move with their cell
        for (int k = 0; k < count; k++) {
            int idx = k % n;
            String s = src.get(idx);
            if (s.startsWith("=")) {
                int delta = (n + k - idx) * sign; // distance between this copy and the cell it came from
                out.add(FormulaRefs.translate(s, vertical ? delta : 0, vertical ? 0 : delta));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static boolean allNumbers(List<String> src) {
        for (String s : src) if (!NUMBER.matcher(s.trim()).matches()) return false;
        return true;
    }

    private static String plain(BigDecimal v) {
        v = v.stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0).toPlainString() : v.toPlainString();
    }

    // ---- weekdays and months ----

    private static int[] locate(String s) {
        String t = s.trim();
        for (int l = 0; l < LISTS.length; l++) {
            for (int i = 0; i < LISTS[l].length; i++) if (LISTS[l][i].equalsIgnoreCase(t)) return new int[]{l, i};
        }
        return null;
    }

    private static List<String> nameSeries(List<String> src, int count, int sign) {
        int n = src.size();
        int[] last = locate(src.get(n - 1));
        if (last == null) return null;
        int step = sign;
        if (n >= 2) {
            int[] prev = locate(src.get(n - 2));
            if (prev == null || prev[0] != last[0]) return null;
            int len = LISTS[last[0]].length;
            step = ((last[1] - prev[1]) % len + len) % len;
            if (step == 0) return null;
            if (step > len / 2 && len > 7) step -= len; // e.g. Mar, Feb counts backwards
        }
        for (int i = 0; i < n; i++) {
            int[] x = locate(src.get(i));
            if (x == null || x[0] != last[0]) return null;
        }
        String[] list = LISTS[last[0]];
        String sample = src.get(n - 1).trim();
        List<String> out = new ArrayList<>();
        for (int k = 1; k <= count; k++) {
            int idx = (((last[1] + step * k) % list.length) + list.length) % list.length;
            String name = list[idx];
            if (sample.equals(sample.toUpperCase()) && sample.length() > 1) name = name.toUpperCase();
            else if (sample.equals(sample.toLowerCase())) name = name.toLowerCase();
            out.add(name);
        }
        return out;
    }

    // ---- "Item 1" style text ----

    private static List<String> numberedSeries(List<String> src, int count, int sign) {
        int n = src.size();
        Matcher last = TRAILING.matcher(src.get(n - 1));
        if (!last.matches() || src.get(n - 1).startsWith("=") || NUMBER.matcher(src.get(n - 1).trim()).matches()) {
            return null;
        }
        String prefix = last.group(1);
        String digits = last.group(2);
        if (digits.length() > 15) return null;
        long step = sign;
        if (n >= 2) {
            Matcher prev = TRAILING.matcher(src.get(n - 2));
            if (!prev.matches() || !prev.group(1).equals(prefix) || prev.group(2).length() > 15) return null;
            step = Long.parseLong(digits) - Long.parseLong(prev.group(2));
        }
        for (int i = 0; i < n - 1; i++) {
            Matcher x = TRAILING.matcher(src.get(i));
            if (!x.matches() || !x.group(1).equals(prefix)) return null;
        }
        long base = Long.parseLong(digits);
        List<String> out = new ArrayList<>();
        for (int k = 1; k <= count; k++) {
            long v = base + step * k;
            if (v < 0) v = 0;
            String num = String.valueOf(v);
            while (digits.startsWith("0") && num.length() < digits.length()) num = "0" + num; // keep 007 -> 008
            out.add(prefix + num);
        }
        return out;
    }
}

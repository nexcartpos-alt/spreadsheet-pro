import java.awt.Color;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Power Pivot style tools. They read a table (first row = headers) and build a new sheet:
 * a pivot table (group by one or two fields and Sum / Count / Average / Min / Max a value field),
 * a per-column summary, and calculated columns written back next to the table.
 */
public final class PivotBuilder {

    public enum Func { SUM, COUNT, AVERAGE, MIN, MAX }

    public static final String[] FUNC_NAMES = {"Sum", "Count", "Average", "Min", "Max"};

    private static final Color HEADER_BG = new Color(0x107C41);
    private static final Color TOTAL_BG = new Color(0xE2F0D9);

    private PivotBuilder() {
    }

    // ------------------------------------------------------------------

    /** Header names of the table in row r1 (blank headers become "Column X"). */
    public static List<String> headers(SpreadsheetModel m, int r1, int c1, int c2) {
        List<String> list = new ArrayList<>();
        for (int c = c1; c <= c2; c++) {
            String t = String.valueOf(m.getValueAt(r1, c)).trim();
            list.add(t.isEmpty() ? "Column " + FormulaEngine.columnLabel(c) : t);
        }
        return list;
    }

    private static final class Acc {
        int count;
        int numbers;
        double sum;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        void add(Object v) {
            if (v == null) return;
            count++;
            if (v instanceof Double) {
                double d = (Double) v;
                numbers++;
                sum += d;
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
        }

        /** @return the aggregate, or null when there is nothing to show. */
        String result(Func f) {
            switch (f) {
                case COUNT:
                    return count == 0 ? null : String.valueOf(count);
                case SUM:
                    return numbers == 0 ? null : num(sum);
                case AVERAGE:
                    return numbers == 0 ? null : num(sum / numbers);
                case MIN:
                    return numbers == 0 ? null : num(min);
                default:
                    return numbers == 0 ? null : num(max);
            }
        }
    }

    private static String num(double d) {
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private static String safe(String text) {
        return text.startsWith("=") ? "'" + text : text;
    }

    private static String key(SpreadsheetModel m, int r, int c) {
        String t = String.valueOf(m.getValueAt(r, c)).trim();
        return t.isEmpty() ? "(blank)" : t;
    }

    private static List<String> sortedKeys(Set<String> keys) {
        List<String> list = new ArrayList<>(keys);
        boolean allNumeric = true;
        for (String k : list) {
            try {
                Double.parseDouble(k.replace(",", ""));
            } catch (NumberFormatException e) {
                allNumeric = false;
                break;
            }
        }
        if (allNumeric) list.sort(Comparator.comparingDouble(k -> Double.parseDouble(k.replace(",", ""))));
        else list.sort(String.CASE_INSENSITIVE_ORDER);
        return list;
    }

    private static CellStyle headerStyle() {
        CellStyle st = new CellStyle();
        st.bold = true;
        st.background = HEADER_BG;
        st.foreground = Color.WHITE;
        return st;
    }

    private static CellStyle totalStyle() {
        CellStyle st = new CellStyle();
        st.bold = true;
        st.background = TOTAL_BG;
        return st;
    }

    /**
     * Builds a pivot table sheet. Field numbers are 0-based positions inside c1..c2;
     * {@code colField < 0} means no column field.
     */
    public static SpreadsheetModel pivot(SpreadsheetModel src, int r1, int c1, int r2, int c2,
                                         int rowField, int colField, int valField, Func func, boolean totals) {
        List<String> heads = headers(src, r1, c1, c2);
        Set<String> rowKeys = new LinkedHashSet<>();
        Set<String> colKeys = new LinkedHashSet<>();
        Map<String, Acc> cells = new LinkedHashMap<>();
        Map<String, Acc> rowTotals = new LinkedHashMap<>();
        Map<String, Acc> colTotals = new LinkedHashMap<>();
        Acc grand = new Acc();
        for (int r = r1 + 1; r <= r2; r++) {
            String rk = key(src, r, c1 + rowField);
            String ck = colField >= 0 ? key(src, r, c1 + colField) : "";
            Object v = src.getComputedValue(r, c1 + valField);
            rowKeys.add(rk);
            if (colField >= 0) colKeys.add(ck);
            cells.computeIfAbsent(rk + "\u0001" + ck, x -> new Acc()).add(v);
            rowTotals.computeIfAbsent(rk, x -> new Acc()).add(v);
            colTotals.computeIfAbsent(ck, x -> new Acc()).add(v);
            grand.add(v);
        }
        List<String> rks = sortedKeys(rowKeys);
        List<String> cks = colField >= 0 ? sortedKeys(colKeys) : new ArrayList<>(List.of(""));
        boolean cross = colField >= 0;
        int outCols = 1 + cks.size() + (cross && totals ? 1 : 0);
        int outRows = 1 + rks.size() + (totals ? 1 : 0);
        SpreadsheetModel out = new SpreadsheetModel(Math.max(SpreadsheetModel.DEFAULT_ROWS, outRows + 5),
                Math.max(SpreadsheetModel.DEFAULT_COLS, outCols + 2));
        out.beginBatch();
        try {
            String label = FUNC_NAMES[func.ordinal()] + " of " + heads.get(valField);
            out.setInputs(0, 0, safe(heads.get(rowField)), headerStyle());
            for (int j = 0; j < cks.size(); j++) {
                out.setInputs(0, 1 + j, cross ? safe(cks.get(j)) : label, headerStyle());
            }
            if (cross && totals) out.setInputs(0, 1 + cks.size(), "Grand Total", headerStyle());
            for (int i = 0; i < rks.size(); i++) {
                out.setInputs(1 + i, 0, safe(rks.get(i)), null);
                for (int j = 0; j < cks.size(); j++) {
                    Acc a = cells.get(rks.get(i) + "\u0001" + cks.get(j));
                    String res = a == null ? null : a.result(func);
                    if (res != null) out.setInputs(1 + i, 1 + j, res, null);
                }
                if (cross && totals) {
                    String res = rowTotals.get(rks.get(i)).result(func);
                    if (res != null) out.setInputs(1 + i, 1 + cks.size(), res, totalStyle());
                }
            }
            if (totals) {
                int tr = 1 + rks.size();
                out.setInputs(tr, 0, "Grand Total", totalStyle());
                for (int j = 0; j < cks.size(); j++) {
                    Acc a = cross ? colTotals.get(cks.get(j)) : grand;
                    String res = a == null ? null : a.result(func);
                    out.setInputs(tr, 1 + j, res == null ? "" : res, totalStyle());
                }
                if (cross) {
                    String res = grand.result(func);
                    out.setInputs(tr, 1 + cks.size(), res == null ? "" : res, totalStyle());
                }
            }
            if (func == Func.AVERAGE) {
                for (int r = 1; r < outRows; r++) {
                    for (int c = 1; c < outCols; c++) {
                        CellStyle st = out.getStyle(r, c);
                        CellStyle n = st == null ? new CellStyle() : st.copy();
                        n.numFmt = CellFormats.NUMBER;
                        out.setInputs(r, c, out.getInput(r, c), n);
                    }
                }
            }
            for (int c = 0; c < outCols; c++) out.setColumnWidth(c, c == 0 ? 130 : 105);
        } finally {
            out.endBatch();
        }
        return out;
    }

    /** One row per column of the table: type, counts, distinct values and basic statistics. */
    public static SpreadsheetModel summary(SpreadsheetModel src, int r1, int c1, int r2, int c2) {
        List<String> heads = headers(src, r1, c1, c2);
        String[] titles = {"Column", "Type", "Count", "Blanks", "Distinct", "Sum", "Average", "Min", "Max"};
        SpreadsheetModel out = new SpreadsheetModel(Math.max(SpreadsheetModel.DEFAULT_ROWS, heads.size() + 6),
                SpreadsheetModel.DEFAULT_COLS);
        out.beginBatch();
        try {
            for (int i = 0; i < titles.length; i++) out.setInputs(0, i, titles[i], headerStyle());
            for (int k = 0; k < heads.size(); k++) {
                int c = c1 + k;
                Acc a = new Acc();
                int blanks = 0;
                int texts = 0;
                Set<String> distinct = new LinkedHashSet<>();
                for (int r = r1 + 1; r <= r2; r++) {
                    Object v = src.getComputedValue(r, c);
                    if (v == null) {
                        blanks++;
                        continue;
                    }
                    a.add(v);
                    distinct.add(String.valueOf(v).toLowerCase());
                    if (!(v instanceof Double)) texts++;
                }
                String type = a.count == 0 ? "Empty" : texts == 0 ? "Number" : a.numbers == 0 ? "Text" : "Mixed";
                int row = 1 + k;
                out.setInputs(row, 0, safe(heads.get(k)), null);
                out.setInputs(row, 1, type, null);
                out.setInputs(row, 2, String.valueOf(a.count), null);
                out.setInputs(row, 3, String.valueOf(blanks), null);
                out.setInputs(row, 4, String.valueOf(distinct.size()), null);
                if (a.numbers > 0) {
                    out.setInputs(row, 5, num(a.sum), null);
                    out.setInputs(row, 6, num(a.sum / a.numbers), null);
                    out.setInputs(row, 7, num(a.min), null);
                    out.setInputs(row, 8, num(a.max), null);
                }
            }
            for (int c = 0; c < titles.length; c++) out.setColumnWidth(c, c == 0 ? 150 : 90);
        } finally {
            out.endBatch();
        }
        return out;
    }

    private static final Pattern FIELD = Pattern.compile("\\[([^\\]]+)\\]");

    /**
     * Adds a calculated column to the right of the table. {@code expr} may use [Header] names,
     * e.g. {@code [Qty]*[Price]}. @return the number of rows filled.
     * @throws IllegalArgumentException when a [Header] does not exist or there is no room.
     */
    public static int addCalculatedColumn(SpreadsheetModel m, int r1, int c1, int r2, int c2,
                                          String name, String expr) {
        if (c2 + 1 >= m.getColumnCount()) throw new IllegalArgumentException("There is no free column to the right of the table.");
        if (expr == null || expr.trim().isEmpty()) throw new IllegalArgumentException("Enter a formula, for example [Qty]*[Price]");
        List<String> heads = headers(m, r1, c1, c2);
        int target = c2 + 1;
        m.beginBatch();
        try {
            m.setInput(r1, target, name.trim().isEmpty() ? "Calculated" : name.trim());
            CellStyle hs = m.getStyle(r1, c2);
            if (hs != null) m.updateStyle(r1, target, st -> {
                st.bold = hs.bold;
                st.background = hs.background;
                st.foreground = hs.foreground;
            });
            String body = expr.trim();
            if (body.startsWith("=")) body = body.substring(1);
            for (int r = r1 + 1; r <= r2; r++) {
                final int row = r;
                Matcher mt = FIELD.matcher(body);
                StringBuffer sb = new StringBuffer();
                while (mt.find()) {
                    int idx = -1;
                    for (int i = 0; i < heads.size(); i++) if (heads.get(i).equalsIgnoreCase(mt.group(1).trim())) idx = i;
                    if (idx < 0) throw new IllegalArgumentException("Unknown column [" + mt.group(1) + "]");
                    mt.appendReplacement(sb, Matcher.quoteReplacement(FormulaEngine.cellRef(row, c1 + idx)));
                }
                mt.appendTail(sb);
                m.setInput(r, target, "=" + sb);
            }
        } finally {
            m.endBatch();
        }
        return r2 - r1;
    }
}

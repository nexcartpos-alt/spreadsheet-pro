import java.util.ArrayList;
import java.util.List;

/** The numbers behind a chart, read live from the sheet every time the chart is painted. */
public final class ChartData {

    public final List<String> names = new ArrayList<>();     // one per series
    public final List<double[]> values = new ArrayList<>();  // [series][point], NaN = empty
    public final List<String> categories = new ArrayList<>();// one per point
    public double[] xs;                                      // numeric x for scatter charts (else null)
    public int points;

    public int series() {
        return values.size();
    }

    public boolean isEmpty() {
        for (double[] v : values) for (double d : v) if (!Double.isNaN(d)) return false;
        return true;
    }

    private static boolean isText(Object o) {
        return o != null && !(o instanceof Double) && !String.valueOf(o).isEmpty();
    }

    private static String label(SpreadsheetModel m, int r, int c) {
        Object v = m.getComputedValue(r, c);
        if (v == null) return "";
        if (v instanceof Double) return FormulaEngine.formatNumber((Double) v);
        if (v instanceof Boolean) return ((Boolean) v) ? "TRUE" : "FALSE";
        return v.toString();
    }

    /**
     * Reads the range: a first row of text is taken as the series names, a first column of text as
     * the category labels. Without them the series are called "Series 1, 2 ..." and the points 1, 2, 3 ...
     */
    public static ChartData read(SpreadsheetModel m, ChartSpec s) {
        ChartData d = new ChartData();
        int r1 = Math.max(0, s.r1);
        int c1 = Math.max(0, s.c1);
        int r2 = Math.min(m.getRowCount() - 1, s.r2);
        int c2 = Math.min(m.getColumnCount() - 1, s.c2);
        if (r2 < r1 || c2 < c1) return d;

        boolean header = false;
        if (r2 > r1) {
            for (int c = c1; c <= c2; c++) if (isText(m.getComputedValue(r1, c))) header = true;
        }
        int first = r1 + (header ? 1 : 0);

        boolean cats = false;
        if (c2 > c1) {
            boolean anyText = false;
            boolean allNonNumeric = true;
            for (int r = first; r <= r2; r++) {
                Object v = m.getComputedValue(r, c1);
                if (v instanceof Double) allNonNumeric = false;
                if (isText(v)) anyText = true;
            }
            cats = (anyText && allNonNumeric) || (header && !isText(m.getComputedValue(r1, c1))
                    && m.getComputedValue(r1, c1) == null);
        }
        boolean numericX = s.type == ChartSpec.Type.SCATTER && !cats && c2 > c1;
        int firstSeries = c1 + (cats || numericX ? 1 : 0);

        d.points = r2 - first + 1;
        for (int r = first; r <= r2; r++) d.categories.add(cats ? label(m, r, c1) : String.valueOf(r - first + 1));
        if (numericX) {
            d.xs = new double[d.points];
            for (int i = 0; i < d.points; i++) {
                Object v = m.getComputedValue(first + i, c1);
                d.xs[i] = v instanceof Double ? (Double) v : Double.NaN;
            }
        }
        for (int c = firstSeries; c <= c2; c++) {
            double[] vals = new double[d.points];
            for (int i = 0; i < d.points; i++) {
                Object v = m.getComputedValue(first + i, c);
                vals[i] = v instanceof Double ? (Double) v : Double.NaN;
            }
            d.values.add(vals);
            String name = header ? label(m, r1, c) : "";
            d.names.add(name.isEmpty() ? "Series " + (c - firstSeries + 1) : name);
        }
        return d;
    }
}

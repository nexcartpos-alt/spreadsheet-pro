import java.awt.Color;

/** A tiny chart drawn inside one cell from a row or column of numbers. Immutable. */
public final class Sparkline {

    public enum Type {
        LINE("Line"), COLUMN("Column"), WIN_LOSS("Win/Loss");

        public final String label;

        Type(String label) {
            this.label = label;
        }
    }

    public final int row, col;           // the cell it is drawn in
    public final int r1, c1, r2, c2;     // where the numbers come from (a single row or a single column)
    public final Type type;
    public final Color color;

    public Sparkline(int row, int col, int r1, int c1, int r2, int c2, Type type, Color color) {
        this.row = row;
        this.col = col;
        this.r1 = Math.min(r1, r2);
        this.c1 = Math.min(c1, c2);
        this.r2 = Math.max(r1, r2);
        this.c2 = Math.max(c1, c2);
        this.type = type;
        this.color = color == null ? new Color(0x107C41) : color;
    }

    /** The numbers right now; blank / text cells are NaN. */
    public double[] values(SpreadsheetModel m) {
        int n = Math.max(r2 - r1 + 1, c2 - c1 + 1);
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            int r = r1 == r2 ? r1 : r1 + i;
            int c = c1 == c2 ? c1 : c1 + i;
            Object o = m.inBounds(r, c) ? m.getComputedValue(r, c) : null;
            v[i] = o instanceof Double ? (Double) o : Double.NaN;
        }
        return v;
    }
}

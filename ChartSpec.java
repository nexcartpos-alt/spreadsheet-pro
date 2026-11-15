import java.util.HashSet;
import java.util.Set;

/** One chart floating on a sheet: what kind it is, where its data lives and where it sits (100% zoom pixels). */
public final class ChartSpec {

    public enum Type {
        COLUMN("Column"), BAR("Bar"), LINE("Line"), AREA("Area"), PIE("Pie"), SCATTER("Scatter");

        public final String label;

        Type(String label) {
            this.label = label;
        }
    }

    public Type type = Type.COLUMN;
    public String title = "";
    /** Source range; the first row holds series names and the first column holds categories when they are text. */
    public int r1, c1, r2, c2;
    public int x, y, width = 460, height = 290;
    public boolean legend = true;

    /** Series (or, for pie charts, slices) switched off by clicking the legend. Not saved. */
    public final Set<Integer> hidden = new HashSet<>();
    /** Entrance animation progress 0..1 (not saved). */
    public transient double anim = 1.0;

    public ChartSpec() {
    }

    public ChartSpec(Type type, String title, int r1, int c1, int r2, int c2, int x, int y, int width, int height) {
        this.type = type;
        this.title = title == null ? "" : title;
        this.r1 = Math.min(r1, r2);
        this.c1 = Math.min(c1, c2);
        this.r2 = Math.max(r1, r2);
        this.c2 = Math.max(c1, c2);
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public String rangeText() {
        return FormulaEngine.cellRef(r1, c1) + ":" + FormulaEngine.cellRef(r2, c2);
    }
}

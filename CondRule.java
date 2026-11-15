import java.awt.Color;

/** A conditional-formatting rule: cells in a range that match get a fill / text colour (immutable). */
public final class CondRule {

    public enum Type { GREATER, LESS, EQUAL, BETWEEN, CONTAINS, DUPLICATE }

    public final Type type;
    public final int r1;
    public final int c1;
    public final int r2;
    public final int c2;
    public final String v1;
    public final String v2;
    public final Color bg;
    /** Text colour, or null to keep the cell's own. */
    public final Color fg;

    public CondRule(Type type, int r1, int c1, int r2, int c2, String v1, String v2, Color bg, Color fg) {
        this.type = type;
        this.r1 = Math.min(r1, r2);
        this.c1 = Math.min(c1, c2);
        this.r2 = Math.max(r1, r2);
        this.c2 = Math.max(c1, c2);
        this.v1 = v1 == null ? "" : v1;
        this.v2 = v2 == null ? "" : v2;
        this.bg = bg;
        this.fg = fg;
    }

    public boolean covers(int r, int c) {
        return r >= r1 && r <= r2 && c >= c1 && c <= c2;
    }

    public CondRule withRange(int nr1, int nc1, int nr2, int nc2) {
        return new CondRule(type, nr1, nc1, nr2, nc2, v1, v2, bg, fg);
    }

    /** The colour presets offered in the dialog (name, fill, text). */
    public static final String[] PRESET_NAMES = {
            "Light red fill with dark red text", "Yellow fill with dark yellow text",
            "Green fill with dark green text", "Light blue fill", "Orange fill"};
    public static final Color[] PRESET_BG = {
            new Color(0xFFC7CE), new Color(0xFFEB9C), new Color(0xC6EFCE), new Color(0xBDD7EE), new Color(0xF8CBAD)};
    public static final Color[] PRESET_FG = {
            new Color(0x9C0006), new Color(0x9C5700), new Color(0x006100), null, null};
}

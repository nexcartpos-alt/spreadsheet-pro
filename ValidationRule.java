import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A data-validation rule for a block of cells: what may be typed there (a list, a whole number,
 * a decimal or a text length) and what to do when something else is typed. Immutable.
 */
public final class ValidationRule {

    public enum Type {
        LIST("List"), WHOLE("Whole number"), DECIMAL("Decimal"), TEXT_LENGTH("Text length");

        public final String label;

        Type(String label) {
            this.label = label;
        }
    }

    public enum Op {
        BETWEEN("between"), NOT_BETWEEN("not between"), EQUAL("equal to"), NOT_EQUAL("not equal to"),
        GREATER("greater than"), LESS("less than"), GREATER_EQ("greater than or equal to"), LESS_EQ("less than or equal to");

        public final String label;

        Op(String label) {
            this.label = label;
        }

        public boolean needsTwo() {
            return this == BETWEEN || this == NOT_BETWEEN;
        }
    }

    /** What happens when invalid data is typed. */
    public enum Alert {
        STOP("Stop"), WARNING("Warning"), INFORMATION("Information");

        public final String label;

        Alert(String label) {
            this.label = label;
        }
    }

    private static final Pattern NUMBER = Pattern.compile("^[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?$");
    private static final Pattern RANGE = Pattern.compile(
            "^=?\\$?([A-Za-z]{1,3})\\$?(\\d+)\\s*:\\s*\\$?([A-Za-z]{1,3})\\$?(\\d+)$");
    private static final int MAX_LIST = 5000;

    public final Type type;
    public final Op op;
    public final int r1, c1, r2, c2;
    /** List: comma separated items or a range such as =$A$1:$A$5. Numbers: the first / lower limit. */
    public final String v1;
    /** Upper limit for between / not between. */
    public final String v2;
    public final boolean allowBlank;
    public final boolean dropdown;
    public final Alert alert;
    public final String title;
    public final String message;

    public ValidationRule(Type type, Op op, int r1, int c1, int r2, int c2, String v1, String v2,
                          boolean allowBlank, boolean dropdown, Alert alert, String title, String message) {
        this.type = type;
        this.op = op == null ? Op.BETWEEN : op;
        this.r1 = Math.min(r1, r2);
        this.c1 = Math.min(c1, c2);
        this.r2 = Math.max(r1, r2);
        this.c2 = Math.max(c1, c2);
        this.v1 = v1 == null ? "" : v1;
        this.v2 = v2 == null ? "" : v2;
        this.allowBlank = allowBlank;
        this.dropdown = dropdown;
        this.alert = alert == null ? Alert.STOP : alert;
        this.title = title == null ? "" : title;
        this.message = message == null ? "" : message;
    }

    public boolean covers(int r, int c) {
        return r >= r1 && r <= r2 && c >= c1 && c <= c2;
    }

    public ValidationRule withRange(int nr1, int nc1, int nr2, int nc2) {
        return new ValidationRule(type, op, nr1, nc1, nr2, nc2, v1, v2, allowBlank, dropdown, alert, title, message);
    }

    public String rangeText() {
        return FormulaEngine.cellRef(r1, c1) + ":" + FormulaEngine.cellRef(r2, c2);
    }

    // ------------------------------------------------------------------

    /** The choices of a list rule (empty for other types). */
    public List<String> listItems(SpreadsheetModel m) {
        List<String> items = new ArrayList<>();
        if (type != Type.LIST) return items;
        Matcher rm = RANGE.matcher(v1.trim());
        if (rm.matches()) {
            int ca = FormulaEngine.parseColumn(rm.group(1));
            int cb = FormulaEngine.parseColumn(rm.group(3));
            int ra = Integer.parseInt(rm.group(2)) - 1;
            int rb = Integer.parseInt(rm.group(4)) - 1;
            for (int r = Math.min(ra, rb); r <= Math.max(ra, rb) && items.size() < MAX_LIST; r++) {
                for (int c = Math.min(ca, cb); c <= Math.max(ca, cb) && items.size() < MAX_LIST; c++) {
                    if (!m.inBounds(r, c)) continue;
                    Object v = m.getComputedValue(r, c);
                    String s = v == null ? "" : v instanceof Double ? FormulaEngine.formatNumber((Double) v) : v.toString();
                    if (!s.isEmpty()) items.add(s);
                }
            }
        } else {
            for (String part : v1.split(",")) {
                String s = part.trim();
                if (!s.isEmpty() && items.size() < MAX_LIST) items.add(s);
            }
        }
        return items;
    }

    private static Double num(String s) {
        String t = s == null ? "" : s.trim();
        if (t.endsWith("%") && NUMBER.matcher(t.substring(0, t.length() - 1)).matches()) {
            return Double.parseDouble(t.substring(0, t.length() - 1)) / 100.0;
        }
        return NUMBER.matcher(t).matches() ? Double.parseDouble(t) : null;
    }

    /** A sentence describing the rule, used when no custom message was given. */
    public String describe() {
        switch (type) {
            case LIST:
                return "The value must be one of the items in the list.";
            case TEXT_LENGTH:
                return "The text length must be " + opText() + ".";
            case WHOLE:
                return "The value must be a whole number " + opText() + ".";
            default:
                return "The value must be a number " + opText() + ".";
        }
    }

    private String opText() {
        return op.label + " " + v1 + (op.needsTwo() ? " and " + v2 : "");
    }

    /**
     * Checks what was typed into a cell of this rule.
     * @return null if it is allowed, otherwise the message to show
     */
    public String check(SpreadsheetModel m, String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) return allowBlank ? null : "This cell cannot be left empty.";
        if (text.startsWith("=")) return null; // formulas are not checked, like in Excel
        boolean ok;
        switch (type) {
            case LIST:
                ok = false;
                for (String item : listItems(m)) {
                    if (item.equalsIgnoreCase(text)) {
                        ok = true;
                        break;
                    }
                }
                break;
            case TEXT_LENGTH:
                ok = compare(text.length());
                break;
            default: {
                Double v = num(text);
                ok = v != null && (type != Type.WHOLE || v == Math.rint(v)) && compare(v);
            }
        }
        if (ok) return null;
        return message.isEmpty() ? describe() : message;
    }

    private boolean compare(double v) {
        Double a = num(v1);
        Double b = num(v2);
        if (a == null) return true; // incomplete rule: do not block anything
        switch (op) {
            case BETWEEN:
                return b == null || (v >= Math.min(a, b) && v <= Math.max(a, b));
            case NOT_BETWEEN:
                return b == null || v < Math.min(a, b) || v > Math.max(a, b);
            case EQUAL:
                return v == a;
            case NOT_EQUAL:
                return v != a;
            case GREATER:
                return v > a;
            case LESS:
                return v < a;
            case GREATER_EQ:
                return v >= a;
            default:
                return v <= a;
        }
    }

    /** True if the rule has everything it needs (a list source, or the limits). */
    public boolean isComplete() {
        if (type == Type.LIST) return !v1.trim().isEmpty();
        if (num(v1) == null) return false;
        return !op.needsTwo() || num(v2) != null;
    }
}

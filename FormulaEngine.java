import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calculation layer: parses a formula into a small expression tree and evaluates it.
 *
 * Values are Double (numbers), String (text) and Boolean. Empty cells are null and
 * behave as 0 or "" depending on context.
 *
 * Grammar (lowest to highest precedence):
 * <pre>
 *   comparison := concat (('=' | '&lt;&gt;' | '&lt;' | '&gt;' | '&lt;=' | '&gt;=') concat)*
 *   concat     := additive ('&amp;' additive)*
 *   additive   := term (('+' | '-') term)*
 *   term       := power (('*' | '/') power)*
 *   power      := unary ('^' power)?
 *   unary      := ('-' | '+') unary | postfix
 *   postfix    := primary '%'*
 *   primary    := NUMBER | "text" | TRUE | FALSE | CELL | CELL ':' CELL | NAME '(' args ')' | '(' comparison ')'
 * </pre>
 * Because the tree is built first and evaluated afterwards, IF and IFERROR only evaluate
 * the branch they need (so IF(A1=0, 0, 10/A1) never raises #DIV/0!).
 *
 * See {@link FunctionCatalog} for the list of supported functions.
 * The engine never throws: {@link #evaluate(String)} returns a Double, String, Boolean
 * or one of the error strings #DIV/0!, #SYNTAX!, #NAME?, #N/A, #ERROR!.
 */
public class FormulaEngine {

    public static final String ERR_DIV0 = "#DIV/0!";
    public static final String ERR_SYNTAX = "#SYNTAX!";
    public static final String ERR_NAME = "#NAME?";
    public static final String ERR_NA = "#N/A";
    public static final String ERR_GENERIC = "#ERROR!";

    /** Supplies cell values to the engine (implemented by the model). */
    public interface CellResolver {
        /** @return Double, String (text or error code), Boolean, or null when the cell is empty. */
        Object resolve(int row, int col);
    }

    /** Internal control-flow exception carrying a spreadsheet error code. */
    public static class FormulaException extends Exception {
        private static final long serialVersionUID = 1L;
        private final String code;

        public FormulaException(String code) {
            super(code, null, false, false); // no stack trace: cheap to throw
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    private static final Pattern REF = Pattern.compile("^\\$?([A-Za-z]{1,3})\\$?(\\d+)$");
    private static final Pattern NUMBER =
            Pattern.compile("^[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?$");

    /** Function name -> {minimum arguments, maximum arguments (-1 = unlimited)}. */
    private static final Map<String, int[]> ARITY = new HashMap<>();

    private static void def(String name, int min, int max) {
        ARITY.put(name, new int[]{min, max});
    }

    static {
        for (String n : new String[]{"SUM", "AVERAGE", "MIN", "MAX", "COUNT", "COUNTA", "PRODUCT", "MEDIAN",
                "STDEV", "VAR", "AND", "OR", "CONCATENATE", "CONCAT"}) {
            def(n, 1, -1);
        }
        for (String n : new String[]{"SUM", "COUNT", "COUNTA"}) {
            def(n, 0, -1); // SUM() is 0, like the original engine
        }
        for (String n : new String[]{"COUNTBLANK", "ABS", "SQRT", "INT", "EXP", "LN", "LOG10", "SIGN", "NOT",
                "ISNUMBER", "ISTEXT", "ISBLANK", "ISERROR", "LEN", "UPPER", "LOWER", "PROPER", "TRIM", "VALUE",
                "YEAR", "MONTH", "DAY"}) {
            def(n, 1, 1);
        }
        for (String n : new String[]{"LARGE", "SMALL", "MOD", "POWER", "IFERROR", "COUNTIF", "REPT",
                "RANDBETWEEN", "DAYS"}) {
            def(n, 2, 2);
        }
        def("ROUND", 1, 2);
        def("ROUNDUP", 1, 2);
        def("ROUNDDOWN", 1, 2);
        def("LOG", 1, 2);
        def("PI", 0, 0);
        def("RAND", 0, 0);
        def("TODAY", 0, 0);
        def("NOW", 0, 0);
        def("IF", 2, 3);
        def("SUMIF", 2, 3);
        def("AVERAGEIF", 2, 3);
        def("LEFT", 1, 2);
        def("RIGHT", 1, 2);
        def("MID", 3, 3);
        def("SUBSTITUTE", 3, 3);
        def("FIND", 2, 3);
        def("VLOOKUP", 3, 4);
        def("MATCH", 2, 3);
        def("INDEX", 2, 3);
    }

    public static boolean isFunction(String name) {
        return ARITY.containsKey(name.toUpperCase(Locale.ROOT));
    }

    public static Set<String> functionNames() {
        return Collections.unmodifiableSet(new TreeSet<>(ARITY.keySet()));
    }

    private final CellResolver resolver;
    private int maxRows;
    private final int maxCols;

    /** The sheet grew or shrank: references beyond this row are errors. */
    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public FormulaEngine(CellResolver resolver, int maxRows, int maxCols) {
        this.resolver = resolver;
        this.maxRows = maxRows;
        this.maxCols = maxCols;
    }

    // ------------------------------------------------------------------
    // Public helpers shared with the other layers
    // ------------------------------------------------------------------

    /** 0 -> "A", 25 -> "Z", 26 -> "AA". */
    public static String columnLabel(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            n--;
            sb.insert(0, (char) ('A' + n % 26));
            n /= 26;
        }
        return sb.toString();
    }

    /** "A" -> 0, "Z" -> 25, "AA" -> 26 (case-insensitive). */
    public static int parseColumn(String letters) {
        int value = 0;
        for (char ch : letters.toUpperCase(Locale.ROOT).toCharArray()) {
            value = value * 26 + (ch - 'A' + 1);
        }
        return value - 1;
    }

    /** (0,0) -> "A1". */
    public static String cellRef(int row, int col) {
        return columnLabel(col) + (row + 1);
    }

    public static boolean isErrorCode(String s) {
        return ERR_DIV0.equals(s) || ERR_SYNTAX.equals(s) || ERR_NAME.equals(s)
                || ERR_NA.equals(s) || ERR_GENERIC.equals(s);
    }

    /** Formats a number the way a spreadsheet displays it (no trailing zeros, no ".0"). */
    public static String formatNumber(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return ERR_GENERIC;
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        BigDecimal bd = new BigDecimal(d).round(new MathContext(12)).stripTrailingZeros();
        return bd.toPlainString();
    }

    // ------------------------------------------------------------------
    // Evaluation entry point
    // ------------------------------------------------------------------

    /**
     * Evaluates a formula such as "=A1+B2*5" or "=IF(SUM(A1:A10)>100,"big","small")".
     * @return a Double, String, Boolean, or an error String
     */
    public Object evaluate(String formula) {
        try {
            String body = formula.trim();
            if (body.startsWith("=")) body = body.substring(1);
            Node root = new Parser(body).parseAll();
            Object v = root.eval();
            if (v == null) return 0.0;
            if (v instanceof Double) {
                double d = (Double) v;
                if (Double.isNaN(d) || Double.isInfinite(d)) return ERR_GENERIC;
            }
            return v;
        } catch (FormulaException e) {
            return e.getCode();
        } catch (NumberFormatException e) {
            return ERR_SYNTAX;
        } catch (StackOverflowError e) {
            return ERR_GENERIC;
        } catch (RuntimeException e) {
            return ERR_GENERIC;
        }
    }

    // ------------------------------------------------------------------
    // Expression tree
    // ------------------------------------------------------------------

    private interface Node {
        Object eval() throws FormulaException;
    }

    private final class CellNode implements Node {
        final int row;
        final int col;

        CellNode(int row, int col) {
            this.row = row;
            this.col = col;
        }

        @Override
        public Object eval() throws FormulaException {
            return cellValue(row, col);
        }
    }

    /** A rectangular range; only meaningful as a function argument. */
    private final class RangeNode implements Node {
        final int r1;
        final int c1;
        final int r2;
        final int c2;

        RangeNode(int ra, int ca, int rb, int cb) {
            r1 = Math.min(ra, rb);
            r2 = Math.max(ra, rb);
            c1 = Math.min(ca, cb);
            c2 = Math.max(ca, cb);
        }

        @Override
        public Object eval() throws FormulaException {
            throw new FormulaException(ERR_SYNTAX); // a range cannot be used as a single value
        }

        int rows() {
            return r2 - r1 + 1;
        }

        int cols() {
            return c2 - c1 + 1;
        }

        void checkBounds() throws FormulaException {
            if (r1 < 0 || c1 < 0 || r2 >= maxRows || c2 >= maxCols) throw new FormulaException(ERR_GENERIC);
        }

        Object get(int i, int j) throws FormulaException {
            return cellValue(r1 + i, c1 + j);
        }

        /** All values, row by row (empty cells are null). */
        List<Object> all() throws FormulaException {
            checkBounds();
            List<Object> out = new ArrayList<>();
            for (int r = r1; r <= r2; r++) {
                for (int c = c1; c <= c2; c++) out.add(cellValue(r, c));
            }
            return out;
        }
    }

    private RangeNode asRange(Node n) throws FormulaException {
        RangeNode r;
        if (n instanceof RangeNode) {
            r = (RangeNode) n;
        } else if (n instanceof CellNode) {
            CellNode c = (CellNode) n;
            r = new RangeNode(c.row, c.col, c.row, c.col);
        } else {
            throw new FormulaException(ERR_GENERIC);
        }
        r.checkBounds();
        return r;
    }

    /** Reads a cell; errors stored in other cells propagate. */
    private Object cellValue(int row, int col) throws FormulaException {
        if (row < 0 || col < 0 || row >= maxRows || col >= maxCols) throw new FormulaException(ERR_GENERIC);
        Object v = resolver.resolve(row, col);
        if (v instanceof String && isErrorCode((String) v)) throw new FormulaException((String) v);
        return v;
    }

    // ------------------------------------------------------------------
    // Type coercion
    // ------------------------------------------------------------------

    private static double num(Object v) throws FormulaException {
        if (v == null) return 0;
        if (v instanceof Double) return (Double) v;
        if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
        String s = v.toString().trim();
        if (isErrorCode(s)) throw new FormulaException(s);
        if (NUMBER.matcher(s).matches()) return Double.parseDouble(s);
        throw new FormulaException(ERR_GENERIC); // text used as a number
    }

    private static String text(Object v) throws FormulaException {
        if (v == null) return "";
        if (v instanceof Double) return formatNumber((Double) v);
        if (v instanceof Boolean) return ((Boolean) v) ? "TRUE" : "FALSE";
        String s = v.toString();
        if (isErrorCode(s)) throw new FormulaException(s);
        return s;
    }

    private static boolean bool(Object v) throws FormulaException {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Double) return (Double) v != 0;
        String s = v.toString().trim();
        if (s.equalsIgnoreCase("TRUE")) return true;
        if (s.equalsIgnoreCase("FALSE")) return false;
        if (isErrorCode(s)) throw new FormulaException(s);
        throw new FormulaException(ERR_GENERIC);
    }

    private static int rank(Object v) {
        if (v instanceof Double) return 0;
        if (v instanceof String) return 1;
        return 2; // Boolean
    }

    private static Boolean compare(Object a, Object b, String op) {
        if (a == null && b == null) {
            a = 0.0;
            b = 0.0;
        } else if (a == null) {
            a = b instanceof String ? (Object) "" : b instanceof Boolean ? (Object) Boolean.FALSE : (Object) 0.0;
        } else if (b == null) {
            b = a instanceof String ? (Object) "" : a instanceof Boolean ? (Object) Boolean.FALSE : (Object) 0.0;
        }
        int cmp;
        int ra = rank(a);
        int rb = rank(b);
        if (ra != rb) {
            cmp = Integer.compare(ra, rb);
        } else if (a instanceof Double) {
            double x = (Double) a;
            double y = (Double) b;
            cmp = x < y ? -1 : x > y ? 1 : 0;
        } else if (a instanceof String) {
            cmp = ((String) a).compareToIgnoreCase((String) b);
        } else {
            cmp = Boolean.compare((Boolean) a, (Boolean) b);
        }
        switch (op) {
            case "=": return cmp == 0;
            case "<>": return cmp != 0;
            case "<": return cmp < 0;
            case ">": return cmp > 0;
            case "<=": return cmp <= 0;
            default: return cmp >= 0;
        }
    }

    private static boolean looseEquals(Object a, Object b) {
        if (a instanceof Double && b instanceof Double) return ((Double) a).doubleValue() == ((Double) b).doubleValue();
        if (a instanceof String && b instanceof String) return ((String) a).equalsIgnoreCase((String) b);
        if (a instanceof Boolean && b instanceof Boolean) return a.equals(b);
        return false;
    }

    /** Ordering for approximate lookups; null when the two values cannot be compared. */
    private static Integer orderCompare(Object a, Object b) {
        if (a instanceof Double && b instanceof Double) return Double.compare((Double) a, (Double) b);
        if (a instanceof String && b instanceof String) return ((String) a).compareToIgnoreCase((String) b);
        return null;
    }

    // ------------------------------------------------------------------
    // Parser
    // ------------------------------------------------------------------

    private class Parser {
        private final String s;
        private int pos = 0;

        Parser(String s) {
            this.s = s;
        }

        Node parseAll() throws FormulaException {
            skipWs();
            if (pos >= s.length()) throw syntax();
            Node n = comparison();
            skipWs();
            if (pos < s.length()) throw syntax();
            return n;
        }

        private FormulaException syntax() {
            return new FormulaException(ERR_SYNTAX);
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private boolean match(char c) {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private Node comparison() throws FormulaException {
            Node left = concat();
            while (true) {
                skipWs();
                String op = null;
                if (s.startsWith("<>", pos)) op = "<>";
                else if (s.startsWith("<=", pos)) op = "<=";
                else if (s.startsWith(">=", pos)) op = ">=";
                else if (pos < s.length() && "<>=".indexOf(s.charAt(pos)) >= 0) op = String.valueOf(s.charAt(pos));
                if (op == null) return left;
                pos += op.length();
                final Node l = left;
                final Node r = concat();
                final String o = op;
                left = () -> compare(l.eval(), r.eval(), o);
            }
        }

        private Node concat() throws FormulaException {
            Node left = additive();
            while (match('&')) {
                final Node l = left;
                final Node r = additive();
                left = () -> text(l.eval()) + text(r.eval());
            }
            return left;
        }

        private Node additive() throws FormulaException {
            Node left = term();
            while (true) {
                if (match('+')) {
                    final Node l = left;
                    final Node r = term();
                    left = () -> num(l.eval()) + num(r.eval());
                } else if (match('-')) {
                    final Node l = left;
                    final Node r = term();
                    left = () -> num(l.eval()) - num(r.eval());
                } else {
                    return left;
                }
            }
        }

        private Node term() throws FormulaException {
            Node left = power();
            while (true) {
                if (match('*')) {
                    final Node l = left;
                    final Node r = power();
                    left = () -> num(l.eval()) * num(r.eval());
                } else if (match('/')) {
                    final Node l = left;
                    final Node r = power();
                    left = () -> {
                        double x = num(l.eval());
                        double d = num(r.eval());
                        if (d == 0) throw new FormulaException(ERR_DIV0);
                        return x / d;
                    };
                } else {
                    return left;
                }
            }
        }

        private Node power() throws FormulaException {
            final Node base = unary();
            if (match('^')) {
                final Node exp = power();
                return () -> Math.pow(num(base.eval()), num(exp.eval()));
            }
            return base;
        }

        private Node unary() throws FormulaException {
            if (match('-')) {
                final Node n = unary();
                return () -> -num(n.eval());
            }
            if (match('+')) return unary();
            return postfix();
        }

        private Node postfix() throws FormulaException {
            Node n = primary();
            while (match('%')) {
                final Node x = n;
                n = () -> num(x.eval()) / 100.0;
            }
            return n;
        }

        private Node primary() throws FormulaException {
            skipWs();
            if (pos >= s.length()) throw syntax();
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                Node n = comparison();
                if (!match(')')) throw syntax();
                return n;
            }
            if (c == '"') return stringLiteral();
            if (Character.isDigit(c) || c == '.') return numberLiteral();
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '$') return identifier();
            throw syntax();
        }

        private Node stringLiteral() throws FormulaException {
            StringBuilder sb = new StringBuilder();
            pos++; // opening quote
            while (true) {
                if (pos >= s.length()) throw syntax(); // unterminated
                char c = s.charAt(pos);
                if (c == '"') {
                    if (pos + 1 < s.length() && s.charAt(pos + 1) == '"') {
                        sb.append('"');
                        pos += 2;
                        continue;
                    }
                    pos++;
                    break;
                }
                sb.append(c);
                pos++;
            }
            final String value = sb.toString();
            return () -> value;
        }

        private Node numberLiteral() {
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                int save = pos;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                if (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
                } else {
                    pos = save; // not an exponent after all
                }
            }
            final double v = Double.parseDouble(s.substring(start, pos)); // NumberFormatException -> #SYNTAX!
            return () -> v;
        }

        private String word() {
            int start = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || Character.isDigit(c) || c == '$' || c == '_') pos++;
                else break;
            }
            return s.substring(start, pos);
        }

        /** Function call, TRUE/FALSE, cell reference or range. */
        private Node identifier() throws FormulaException {
            String word = word();

            int save = pos;
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '(') {
                final String name = word.toUpperCase(Locale.ROOT);
                int[] arity = ARITY.get(name);
                if (arity == null) throw new FormulaException(ERR_NAME);
                pos++; // consume '('
                final List<Node> args = parseArgs();
                if (args.size() < arity[0] || (arity[1] >= 0 && args.size() > arity[1])) throw syntax();
                return () -> callFunction(name, args);
            }
            pos = save;

            String upper = word.toUpperCase(Locale.ROOT);
            if (upper.equals("TRUE")) return () -> Boolean.TRUE;
            if (upper.equals("FALSE")) return () -> Boolean.FALSE;

            Matcher m = REF.matcher(word);
            if (!m.matches()) throw new FormulaException(ERR_NAME);
            int col = parseColumn(m.group(1));
            int row = Integer.parseInt(m.group(2)) - 1;

            skipWs();
            if (pos < s.length() && s.charAt(pos) == ':') {
                pos++;
                skipWs();
                if (pos >= s.length()) throw syntax();
                Matcher m2 = REF.matcher(word());
                if (!m2.matches()) throw syntax();
                return new RangeNode(row, col, Integer.parseInt(m2.group(2)) - 1, parseColumn(m2.group(1)));
            }
            return new CellNode(row, col);
        }

        private List<Node> parseArgs() throws FormulaException {
            List<Node> args = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ')') {
                pos++;
                return args;
            }
            while (true) {
                args.add(comparison());
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                } else if (pos < s.length() && s.charAt(pos) == ')') {
                    pos++;
                    return args;
                } else {
                    throw syntax();
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Functions
    // ------------------------------------------------------------------

    private double numArg(List<Node> a, int i) throws FormulaException {
        return num(a.get(i).eval());
    }

    private String textArg(List<Node> a, int i) throws FormulaException {
        return text(a.get(i).eval());
    }

    /** Numbers from ranges (text/blank/boolean skipped), references (non-numbers skipped) and plain values. */
    private double[] numbers(List<Node> args) throws FormulaException {
        List<Double> out = new ArrayList<>();
        for (Node n : args) {
            if (n instanceof RangeNode) {
                for (Object v : ((RangeNode) n).all()) if (v instanceof Double) out.add((Double) v);
            } else if (n instanceof CellNode) {
                Object v = n.eval();
                if (v instanceof Double) out.add((Double) v);
            } else {
                Object v = n.eval();
                if (v != null) out.add(num(v));
            }
        }
        double[] r = new double[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    private static double roundTo(double x, int digits, RoundingMode mode) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return x;
        int d = Math.max(-300, Math.min(300, digits));
        return BigDecimal.valueOf(x).setScale(d, mode).doubleValue();
    }

    private static LocalDate parseDate(String s) throws FormulaException {
        String t = s.trim();
        try {
            return LocalDate.parse(t.substring(0, Math.min(10, t.length())));
        } catch (RuntimeException e) {
            throw new FormulaException(ERR_GENERIC);
        }
    }

    private int lookupIndex(Object key, List<Object> keys, int type) {
        if (key == null) return -1;
        int best = -1;
        for (int i = 0; i < keys.size(); i++) {
            Object k = keys.get(i);
            if (type == 0) {
                if (looseEquals(k, key)) return i;
            } else {
                Integer c = orderCompare(k, key);
                if (c == null) continue;
                if (type > 0) {
                    if (c <= 0) best = i;
                    else break;
                } else {
                    if (c >= 0) best = i;
                    else break;
                }
            }
        }
        return best;
    }

    private Object callFunction(String name, List<Node> a) throws FormulaException {
        switch (name) {
            // ---------------- math & statistics ----------------
            case "SUM": {
                double s = 0;
                for (double v : numbers(a)) s += v;
                return s;
            }
            case "AVERAGE": {
                double[] n = numbers(a);
                if (n.length == 0) throw new FormulaException(ERR_DIV0);
                double s = 0;
                for (double v : n) s += v;
                return s / n.length;
            }
            case "MIN":
            case "MAX": {
                double[] n = numbers(a);
                if (n.length == 0) return 0.0;
                double r = n[0];
                for (double v : n) r = name.equals("MIN") ? Math.min(r, v) : Math.max(r, v);
                return r;
            }
            case "COUNT":
                return (double) numbers(a).length;
            case "COUNTA": {
                int count = 0;
                for (Node n : a) {
                    if (n instanceof RangeNode) {
                        for (Object v : ((RangeNode) n).all()) if (v != null) count++;
                    } else if (n.eval() != null) {
                        count++;
                    }
                }
                return (double) count;
            }
            case "COUNTBLANK": {
                int count = 0;
                for (Object v : asRange(a.get(0)).all()) if (v == null || "".equals(v)) count++;
                return (double) count;
            }
            case "PRODUCT": {
                double[] n = numbers(a);
                if (n.length == 0) return 0.0;
                double p = 1;
                for (double v : n) p *= v;
                return p;
            }
            case "MEDIAN": {
                double[] n = numbers(a);
                if (n.length == 0) throw new FormulaException(ERR_GENERIC);
                Arrays.sort(n);
                int mid = n.length / 2;
                return n.length % 2 == 1 ? n[mid] : (n[mid - 1] + n[mid]) / 2.0;
            }
            case "STDEV":
            case "VAR": {
                double[] n = numbers(a);
                if (n.length < 2) throw new FormulaException(ERR_DIV0);
                double mean = 0;
                for (double v : n) mean += v;
                mean /= n.length;
                double ss = 0;
                for (double v : n) ss += (v - mean) * (v - mean);
                double var = ss / (n.length - 1);
                return name.equals("VAR") ? var : Math.sqrt(var);
            }
            case "LARGE":
            case "SMALL": {
                double[] n = numbers(a.subList(0, 1));
                int k = (int) numArg(a, 1);
                if (k < 1 || k > n.length) throw new FormulaException(ERR_GENERIC);
                Arrays.sort(n);
                return name.equals("LARGE") ? n[n.length - k] : n[k - 1];
            }
            case "ABS":
                return Math.abs(numArg(a, 0));
            case "SQRT": {
                double x = numArg(a, 0);
                if (x < 0) throw new FormulaException(ERR_GENERIC);
                return Math.sqrt(x);
            }
            case "ROUND":
            case "ROUNDUP":
            case "ROUNDDOWN": {
                int digits = a.size() > 1 ? (int) numArg(a, 1) : 0;
                RoundingMode mode = name.equals("ROUND") ? RoundingMode.HALF_UP
                        : name.equals("ROUNDUP") ? RoundingMode.UP : RoundingMode.DOWN;
                return roundTo(numArg(a, 0), digits, mode);
            }
            case "INT":
                return Math.floor(numArg(a, 0));
            case "MOD": {
                double x = numArg(a, 0);
                double d = numArg(a, 1);
                if (d == 0) throw new FormulaException(ERR_DIV0);
                return x - d * Math.floor(x / d);
            }
            case "POWER":
                return Math.pow(numArg(a, 0), numArg(a, 1));
            case "EXP":
                return Math.exp(numArg(a, 0));
            case "LN": {
                double x = numArg(a, 0);
                if (x <= 0) throw new FormulaException(ERR_GENERIC);
                return Math.log(x);
            }
            case "LOG10": {
                double x = numArg(a, 0);
                if (x <= 0) throw new FormulaException(ERR_GENERIC);
                return Math.log10(x);
            }
            case "LOG": {
                double x = numArg(a, 0);
                double base = a.size() > 1 ? numArg(a, 1) : 10;
                if (x <= 0 || base <= 0 || base == 1) throw new FormulaException(ERR_GENERIC);
                return Math.log(x) / Math.log(base);
            }
            case "SIGN":
                return Math.signum(numArg(a, 0));
            case "PI":
                return Math.PI;
            case "RAND":
                return ThreadLocalRandom.current().nextDouble();
            case "RANDBETWEEN": {
                long lo = (long) Math.ceil(numArg(a, 0));
                long hi = (long) Math.floor(numArg(a, 1));
                if (hi < lo) throw new FormulaException(ERR_GENERIC);
                return (double) ThreadLocalRandom.current().nextLong(lo, hi + 1);
            }

            // ---------------- logical & conditional ----------------
            case "IF": {
                if (bool(a.get(0).eval())) return a.get(1).eval();
                return a.size() > 2 ? a.get(2).eval() : Boolean.FALSE;
            }
            case "AND":
            case "OR": {
                boolean isAnd = name.equals("AND");
                boolean result = isAnd;
                boolean any = false;
                for (Node n : a) {
                    boolean fromRange = n instanceof RangeNode;
                    List<Object> vals = new ArrayList<>();
                    if (fromRange) vals.addAll(((RangeNode) n).all());
                    else vals.add(n.eval());
                    for (Object v : vals) {
                        if (fromRange && !(v instanceof Boolean || v instanceof Double)) continue;
                        any = true;
                        boolean b = bool(v);
                        result = isAnd ? (result && b) : (result || b);
                    }
                }
                if (!any) throw new FormulaException(ERR_GENERIC);
                return result;
            }
            case "NOT":
                return !bool(a.get(0).eval());
            case "IFERROR": {
                try {
                    return a.get(0).eval();
                } catch (FormulaException e) {
                    return a.get(1).eval();
                }
            }
            case "ISNUMBER": {
                try {
                    return a.get(0).eval() instanceof Double;
                } catch (FormulaException e) {
                    return false;
                }
            }
            case "ISTEXT": {
                try {
                    return a.get(0).eval() instanceof String;
                } catch (FormulaException e) {
                    return false;
                }
            }
            case "ISBLANK": {
                try {
                    return a.get(0).eval() == null;
                } catch (FormulaException e) {
                    return false;
                }
            }
            case "ISERROR": {
                try {
                    a.get(0).eval();
                    return false;
                } catch (FormulaException e) {
                    return true;
                }
            }
            case "COUNTIF": {
                Criteria crit = Criteria.parse(a.get(1).eval());
                int n = 0;
                for (Object v : asRange(a.get(0)).all()) if (crit.matches(v)) n++;
                return (double) n;
            }
            case "SUMIF":
            case "AVERAGEIF": {
                List<Object> test = asRange(a.get(0)).all();
                Criteria crit = Criteria.parse(a.get(1).eval());
                List<Object> vals = a.size() > 2 ? asRange(a.get(2)).all() : test;
                double sum = 0;
                int n = 0;
                for (int i = 0; i < test.size() && i < vals.size(); i++) {
                    if (crit.matches(test.get(i)) && vals.get(i) instanceof Double) {
                        sum += (Double) vals.get(i);
                        n++;
                    }
                }
                if (name.equals("AVERAGEIF")) {
                    if (n == 0) throw new FormulaException(ERR_DIV0);
                    return sum / n;
                }
                return sum;
            }

            // ---------------- text ----------------
            case "CONCATENATE":
            case "CONCAT": {
                StringBuilder sb = new StringBuilder();
                for (Node n : a) {
                    if (n instanceof RangeNode) {
                        for (Object v : ((RangeNode) n).all()) sb.append(text(v));
                    } else {
                        sb.append(text(n.eval()));
                    }
                }
                return sb.toString();
            }
            case "LEN":
                return (double) textArg(a, 0).length();
            case "UPPER":
                return textArg(a, 0).toUpperCase(Locale.ROOT);
            case "LOWER":
                return textArg(a, 0).toLowerCase(Locale.ROOT);
            case "PROPER": {
                StringBuilder sb = new StringBuilder();
                boolean up = true;
                for (char ch : textArg(a, 0).toCharArray()) {
                    if (Character.isLetter(ch)) {
                        sb.append(up ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
                        up = false;
                    } else {
                        sb.append(ch);
                        up = true;
                    }
                }
                return sb.toString();
            }
            case "TRIM":
                return textArg(a, 0).trim().replaceAll("\\s+", " ");
            case "LEFT": {
                String t = textArg(a, 0);
                int n = a.size() > 1 ? (int) numArg(a, 1) : 1;
                if (n < 0) throw new FormulaException(ERR_GENERIC);
                return t.substring(0, Math.min(n, t.length()));
            }
            case "RIGHT": {
                String t = textArg(a, 0);
                int n = a.size() > 1 ? (int) numArg(a, 1) : 1;
                if (n < 0) throw new FormulaException(ERR_GENERIC);
                return t.substring(Math.max(0, t.length() - n));
            }
            case "MID": {
                String t = textArg(a, 0);
                int start = (int) numArg(a, 1);
                int len = (int) numArg(a, 2);
                if (start < 1 || len < 0) throw new FormulaException(ERR_GENERIC);
                if (start > t.length()) return "";
                int end = (int) Math.min((long) t.length(), (long) start - 1 + len);
                return t.substring(start - 1, end);
            }
            case "SUBSTITUTE": {
                String t = textArg(a, 0);
                String oldText = textArg(a, 1);
                if (oldText.isEmpty()) return t;
                return t.replace(oldText, textArg(a, 2));
            }
            case "FIND": {
                String find = textArg(a, 0);
                String within = textArg(a, 1);
                int start = a.size() > 2 ? (int) numArg(a, 2) : 1;
                if (start < 1 || start > within.length() + 1) throw new FormulaException(ERR_GENERIC);
                int idx = within.indexOf(find, start - 1);
                if (idx < 0) throw new FormulaException(ERR_GENERIC);
                return (double) (idx + 1);
            }
            case "REPT": {
                String t = textArg(a, 0);
                int n = (int) numArg(a, 1);
                if (n < 0 || (long) t.length() * n > 32767) throw new FormulaException(ERR_GENERIC);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) sb.append(t);
                return sb.toString();
            }
            case "VALUE":
                return num(a.get(0).eval());

            // ---------------- lookup ----------------
            case "VLOOKUP": {
                Object key = a.get(0).eval();
                RangeNode t = asRange(a.get(1));
                int colIndex = (int) numArg(a, 2);
                boolean approx = a.size() < 4 || bool(a.get(3).eval());
                if (colIndex < 1 || colIndex > t.cols()) throw new FormulaException(ERR_GENERIC);
                List<Object> keys = new ArrayList<>();
                for (int i = 0; i < t.rows(); i++) keys.add(t.get(i, 0));
                int row = lookupIndex(key, keys, approx ? 1 : 0);
                if (row < 0) throw new FormulaException(ERR_NA);
                return t.get(row, colIndex - 1);
            }
            case "MATCH": {
                Object key = a.get(0).eval();
                List<Object> list = asRange(a.get(1)).all();
                int type = a.size() > 2 ? (int) Math.signum(numArg(a, 2)) : 1;
                int idx = lookupIndex(key, list, type);
                if (idx < 0) throw new FormulaException(ERR_NA);
                return (double) (idx + 1);
            }
            case "INDEX": {
                RangeNode t = asRange(a.get(0));
                int r = (int) numArg(a, 1);
                int c;
                if (a.size() > 2) {
                    c = (int) numArg(a, 2);
                } else if (t.cols() == 1) {
                    c = 1;
                } else if (t.rows() == 1) {
                    c = r;
                    r = 1;
                } else {
                    throw new FormulaException(ERR_GENERIC);
                }
                if (r < 1 || r > t.rows() || c < 1 || c > t.cols()) throw new FormulaException(ERR_GENERIC);
                return t.get(r - 1, c - 1);
            }

            // ---------------- date ----------------
            case "TODAY":
                return LocalDate.now().toString();
            case "NOW":
                return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            case "YEAR":
                return (double) parseDate(textArg(a, 0)).getYear();
            case "MONTH":
                return (double) parseDate(textArg(a, 0)).getMonthValue();
            case "DAY":
                return (double) parseDate(textArg(a, 0)).getDayOfMonth();
            case "DAYS":
                return (double) ChronoUnit.DAYS.between(parseDate(textArg(a, 1)), parseDate(textArg(a, 0)));

            default:
                throw new FormulaException(ERR_NAME);
        }
    }

    // ------------------------------------------------------------------
    // COUNTIF / SUMIF criteria such as ">5", "<>0", "apple", "ap*"
    // ------------------------------------------------------------------

    private static final class Criteria {
        private final String op;
        private final Double number;
        private final String text;
        private final Pattern pattern;

        private Criteria(String op, Double number, String text, Pattern pattern) {
            this.op = op;
            this.number = number;
            this.text = text;
            this.pattern = pattern;
        }

        static Criteria parse(Object v) throws FormulaException {
            if (v instanceof Double) return new Criteria("=", (Double) v, null, null);
            String s = v == null ? "" : text(v);
            String op = "=";
            String rest = s;
            for (String o : new String[]{">=", "<=", "<>", ">", "<", "="}) {
                if (s.startsWith(o)) {
                    op = o;
                    rest = s.substring(o.length());
                    break;
                }
            }
            String trimmed = rest.trim();
            if (NUMBER.matcher(trimmed).matches()) return new Criteria(op, Double.parseDouble(trimmed), null, null);
            Pattern p = null;
            if ((op.equals("=") || op.equals("<>")) && (rest.indexOf('*') >= 0 || rest.indexOf('?') >= 0)) {
                StringBuilder sb = new StringBuilder();
                for (char ch : rest.toCharArray()) {
                    if (ch == '*') sb.append(".*");
                    else if (ch == '?') sb.append('.');
                    else sb.append(Pattern.quote(String.valueOf(ch)));
                }
                p = Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            }
            return new Criteria(op, null, rest, p);
        }

        boolean matches(Object cell) {
            if (number != null) {
                if (!(cell instanceof Double)) return op.equals("<>");
                double x = (Double) cell;
                double n = number;
                switch (op) {
                    case "=": return x == n;
                    case "<>": return x != n;
                    case "<": return x < n;
                    case ">": return x > n;
                    case "<=": return x <= n;
                    default: return x >= n;
                }
            }
            String t = cell == null ? ""
                    : cell instanceof Double ? formatNumber((Double) cell)
                    : cell instanceof Boolean ? (((Boolean) cell) ? "TRUE" : "FALSE")
                    : cell.toString();
            boolean eq = pattern != null ? pattern.matcher(t).matches() : t.equalsIgnoreCase(text);
            switch (op) {
                case "=": return eq;
                case "<>": return !eq;
                default: {
                    if (!(cell instanceof String)) return false;
                    int c = t.compareToIgnoreCase(text);
                    switch (op) {
                        case "<": return c < 0;
                        case ">": return c > 0;
                        case "<=": return c <= 0;
                        default: return c >= 0;
                    }
                }
            }
        }
    }
}

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the cell references inside formula text when rows or columns are inserted,
 * deleted or moved (so =SUM(B2:B9) keeps pointing at the right cells, like Excel).
 * String literals and function names are never touched.
 */
public final class FormulaRefs {

    /** A reference that is always out of bounds, so the formula shows #ERROR! (like #REF!). */
    public static final String INVALID = "ZZZ99999";

    private static final Pattern REF = Pattern.compile("^(\\$?)([A-Za-z]{1,3})(\\$?)(\\d+)$");

    private FormulaRefs() {
    }

    private static final class Tok {
        String text;
        boolean ref;
        String dollarCol = "";
        String dollarRow = "";
        int col;
        int row;
    }

    private static boolean identPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.';
    }

    private static List<Tok> tokenize(String f) {
        List<Tok> out = new ArrayList<>();
        StringBuilder other = new StringBuilder();
        int n = f.length();
        int i = 0;
        while (i < n) {
            char c = f.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < n) {
                    if (f.charAt(j) == '"') {
                        if (j + 1 < n && f.charAt(j + 1) == '"') {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                j = Math.min(n, j + 1);
                other.append(f, i, j);
                i = j;
            } else if ((Character.isLetter(c) || c == '$') && (i == 0 || !identPart(f.charAt(i - 1)))) {
                int j = i;
                while (j < n && identPart(f.charAt(j))) j++;
                String word = f.substring(i, j);
                Matcher m = REF.matcher(word);
                if (!(j < n && f.charAt(j) == '(') && m.matches()) {
                    if (other.length() > 0) {
                        Tok t = new Tok();
                        t.text = other.toString();
                        out.add(t);
                        other.setLength(0);
                    }
                    Tok t = new Tok();
                    t.ref = true;
                    t.dollarCol = m.group(1);
                    t.dollarRow = m.group(3);
                    t.col = FormulaEngine.parseColumn(m.group(2));
                    t.row = Integer.parseInt(m.group(4)) - 1;
                    out.add(t);
                } else {
                    other.append(word);
                }
                i = j;
            } else {
                other.append(c);
                i++;
            }
        }
        if (other.length() > 0) {
            Tok t = new Tok();
            t.text = other.toString();
            out.add(t);
        }
        return out;
    }

    private static String refText(Tok t) {
        return t.dollarCol + FormulaEngine.columnLabel(t.col) + t.dollarRow + (t.row + 1);
    }

    /**
     * Applies an insert ({@code insert == true}, n rows / columns at {@code index}) or a delete
     * (the n rows / columns starting at {@code index}) to every reference in the formula.
     */
    public static String shift(String formula, boolean rows, int index, int n, boolean insert) {
        if (formula == null || !formula.startsWith("=")) return formula;
        List<Tok> toks = tokenize(formula);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (!t.ref) {
                sb.append(t.text);
                continue;
            }
            boolean isRange = i + 2 < toks.size() && !toks.get(i + 1).ref && toks.get(i + 1).text.equals(":")
                    && toks.get(i + 2).ref;
            if (isRange) {
                Tok e = toks.get(i + 2);
                int s0 = rows ? t.row : t.col;
                int e0 = rows ? e.row : e.col;
                int s1;
                int e1;
                if (insert) {
                    s1 = s0 >= index ? s0 + n : s0;
                    e1 = e0 >= index ? e0 + n : e0;
                } else {
                    s1 = s0 < index ? s0 : (s0 < index + n ? index : s0 - n);
                    e1 = e0 < index ? e0 : (e0 < index + n ? index - 1 : e0 - n);
                }
                if (e1 < s1 || s1 < 0) {
                    sb.append(INVALID);
                } else {
                    if (rows) {
                        t.row = s1;
                        e.row = e1;
                    } else {
                        t.col = s1;
                        e.col = e1;
                    }
                    sb.append(refText(t)).append(':').append(refText(e));
                }
                i += 2;
            } else {
                int v = rows ? t.row : t.col;
                if (insert) {
                    if (v >= index) v += n;
                } else if (v >= index && v < index + n) {
                    sb.append(INVALID);
                    continue;
                } else if (v >= index + n) {
                    v -= n;
                }
                if (rows) t.row = v;
                else t.col = v;
                sb.append(refText(t));
            }
        }
        return sb.toString();
    }

    /**
     * Copies a formula to another cell: relative references move by (dRow, dCol), absolute ones
     * ($A$1, $A1, A$1 - the $ part) stay. A reference pushed off the sheet becomes invalid (#ERROR!).
     */
    public static String translate(String formula, int dRow, int dCol) {
        if (formula == null || !formula.startsWith("=") || (dRow == 0 && dCol == 0)) return formula;
        StringBuilder sb = new StringBuilder();
        for (Tok t : tokenize(formula)) {
            if (!t.ref) {
                sb.append(t.text);
                continue;
            }
            int row = t.dollarRow.isEmpty() ? t.row + dRow : t.row;
            int col = t.dollarCol.isEmpty() ? t.col + dCol : t.col;
            if (row < 0 || col < 0) {
                sb.append(INVALID);
            } else {
                t.row = row;
                t.col = col;
                sb.append(refText(t));
            }
        }
        return sb.toString();
    }

    /** Rewrites references to {@code oldRow} so they point at {@code newRow} (row moved by a sort). */
    public static String moveRow(String formula, int oldRow, int newRow) {
        if (formula == null || !formula.startsWith("=") || oldRow == newRow) return formula;
        List<Tok> toks = tokenize(formula);
        StringBuilder sb = new StringBuilder();
        for (Tok t : toks) {
            if (!t.ref) {
                sb.append(t.text);
            } else {
                if (t.row == oldRow) t.row = newRow;
                sb.append(refText(t));
            }
        }
        return sb.toString();
    }
}

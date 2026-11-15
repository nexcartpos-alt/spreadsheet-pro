import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Human-readable list of every function {@link FormulaEngine} understands.
 * Used by the Insert Function dialog and the Formula Reference help window.
 *
 * The examples are written for a sample sheet where A1:A5 holds 10, 20, 30, 40, 50
 * and B1:B5 holds apple, banana, cherry, date, elder - the self-test evaluates every
 * example against it, so they stay correct.
 */
public final class FunctionCatalog {

    /** One function's documentation. */
    public static final class Entry {
        public final String category;
        public final String name;
        public final String syntax;
        public final String description;
        public final String example;

        Entry(String category, String name, String syntax, String description, String example) {
            this.category = category;
            this.name = name;
            this.syntax = syntax;
            this.description = description;
            this.example = example;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<Entry> ALL = new ArrayList<>();

    private static void add(String cat, String name, String syntax, String desc, String example) {
        ALL.add(new Entry(cat, name, syntax, desc, example));
    }

    static {
        String m = "Math & Statistics";
        add(m, "SUM", "SUM(number1, [number2], ...)", "Adds numbers, cells and ranges.", "=SUM(A1:A5)");
        add(m, "AVERAGE", "AVERAGE(number1, [number2], ...)", "Average of the numbers.", "=AVERAGE(A1:A5)");
        add(m, "MIN", "MIN(number1, [number2], ...)", "Smallest number.", "=MIN(A1:A5)");
        add(m, "MAX", "MAX(number1, [number2], ...)", "Largest number.", "=MAX(A1:A5)");
        add(m, "COUNT", "COUNT(value1, [value2], ...)", "Counts the cells that contain numbers.", "=COUNT(A1:A5)");
        add(m, "COUNTA", "COUNTA(value1, [value2], ...)", "Counts the cells that are not empty.", "=COUNTA(B1:B5)");
        add(m, "COUNTBLANK", "COUNTBLANK(range)", "Counts the empty cells in a range.", "=COUNTBLANK(C1:C5)");
        add(m, "PRODUCT", "PRODUCT(number1, [number2], ...)", "Multiplies the numbers together.", "=PRODUCT(1,2,3,4)");
        add(m, "MEDIAN", "MEDIAN(number1, [number2], ...)", "The middle value of the numbers.", "=MEDIAN(A1:A5)");
        add(m, "STDEV", "STDEV(number1, [number2], ...)", "Sample standard deviation.", "=STDEV(A1:A5)");
        add(m, "VAR", "VAR(number1, [number2], ...)", "Sample variance.", "=VAR(A1:A5)");
        add(m, "LARGE", "LARGE(range, k)", "The k-th largest number (1 = largest).", "=LARGE(A1:A5,2)");
        add(m, "SMALL", "SMALL(range, k)", "The k-th smallest number (1 = smallest).", "=SMALL(A1:A5,2)");
        add(m, "ABS", "ABS(number)", "Absolute value (removes the minus sign).", "=ABS(-7.5)");
        add(m, "SQRT", "SQRT(number)", "Square root.", "=SQRT(144)");
        add(m, "ROUND", "ROUND(number, [digits])", "Rounds to a number of decimal places.", "=ROUND(3.14159,2)");
        add(m, "ROUNDUP", "ROUNDUP(number, [digits])", "Rounds away from zero.", "=ROUNDUP(3.141,2)");
        add(m, "ROUNDDOWN", "ROUNDDOWN(number, [digits])", "Rounds toward zero.", "=ROUNDDOWN(3.999,2)");
        add(m, "INT", "INT(number)", "Rounds down to the nearest whole number.", "=INT(-2.5)");
        add(m, "MOD", "MOD(number, divisor)", "Remainder after division.", "=MOD(17,5)");
        add(m, "POWER", "POWER(number, power)", "Raises a number to a power.", "=POWER(2,10)");
        add(m, "EXP", "EXP(number)", "e raised to a power.", "=EXP(1)");
        add(m, "LN", "LN(number)", "Natural logarithm.", "=LN(10)");
        add(m, "LOG", "LOG(number, [base])", "Logarithm to a base (default 10).", "=LOG(8,2)");
        add(m, "LOG10", "LOG10(number)", "Base-10 logarithm.", "=LOG10(1000)");
        add(m, "SIGN", "SIGN(number)", "-1, 0 or 1 depending on the sign.", "=SIGN(-12)");
        add(m, "PI", "PI()", "The number pi.", "=PI()");
        add(m, "RAND", "RAND()", "A random number from 0 up to 1.", "=RAND()");
        add(m, "RANDBETWEEN", "RANDBETWEEN(low, high)", "A random whole number between two values.", "=RANDBETWEEN(1,100)");

        String l = "Logical & Conditional";
        add(l, "IF", "IF(condition, value_if_true, [value_if_false])",
                "Chooses between two results. Only the chosen branch is evaluated.", "=IF(A1>=10,\"pass\",\"fail\")");
        add(l, "AND", "AND(logical1, [logical2], ...)", "TRUE when every condition is TRUE.", "=AND(A1>5,A2<100)");
        add(l, "OR", "OR(logical1, [logical2], ...)", "TRUE when any condition is TRUE.", "=OR(A1>500,A2<100)");
        add(l, "NOT", "NOT(logical)", "Reverses TRUE and FALSE.", "=NOT(A1>5)");
        add(l, "IFERROR", "IFERROR(value, value_if_error)", "Returns a fallback when the value is an error.", "=IFERROR(10/0,\"n/a\")");
        add(l, "ISNUMBER", "ISNUMBER(value)", "TRUE if the value is a number.", "=ISNUMBER(A1)");
        add(l, "ISTEXT", "ISTEXT(value)", "TRUE if the value is text.", "=ISTEXT(B1)");
        add(l, "ISBLANK", "ISBLANK(cell)", "TRUE if the cell is empty.", "=ISBLANK(C1)");
        add(l, "ISERROR", "ISERROR(value)", "TRUE if the value is an error.", "=ISERROR(1/0)");
        add(l, "COUNTIF", "COUNTIF(range, criteria)",
                "Counts cells that meet a condition such as \">25\", \"<>0\" or \"ap*\".", "=COUNTIF(A1:A5,\">25\")");
        add(l, "SUMIF", "SUMIF(range, criteria, [sum_range])", "Adds the cells that meet a condition.", "=SUMIF(A1:A5,\">25\")");
        add(l, "AVERAGEIF", "AVERAGEIF(range, criteria, [average_range])",
                "Average of the cells that meet a condition.", "=AVERAGEIF(A1:A5,\">=20\")");

        String t = "Text";
        add(t, "CONCATENATE", "CONCATENATE(text1, [text2], ...)", "Joins text together (same as the & operator).",
                "=CONCATENATE(\"Hello\",\" \",\"World\")");
        add(t, "CONCAT", "CONCAT(text1, [text2], ...)", "Joins text and whole ranges.", "=CONCAT(B1:B3)");
        add(t, "LEN", "LEN(text)", "Number of characters.", "=LEN(\"Spreadsheet\")");
        add(t, "UPPER", "UPPER(text)", "Converts to UPPER CASE.", "=UPPER(\"hello\")");
        add(t, "LOWER", "LOWER(text)", "Converts to lower case.", "=LOWER(\"HELLO\")");
        add(t, "PROPER", "PROPER(text)", "Capitalises The First Letter Of Each Word.", "=PROPER(\"john smith\")");
        add(t, "TRIM", "TRIM(text)", "Removes extra spaces.", "=TRIM(\"  too   many   spaces \")");
        add(t, "LEFT", "LEFT(text, [count])", "The first characters of the text.", "=LEFT(\"Spreadsheet\",6)");
        add(t, "RIGHT", "RIGHT(text, [count])", "The last characters of the text.", "=RIGHT(\"Spreadsheet\",5)");
        add(t, "MID", "MID(text, start, count)", "Characters from the middle of the text.", "=MID(\"Spreadsheet\",7,5)");
        add(t, "SUBSTITUTE", "SUBSTITUTE(text, old_text, new_text)", "Replaces every occurrence of some text.",
                "=SUBSTITUTE(\"2026-11-12\",\"-\",\"/\")");
        add(t, "FIND", "FIND(find_text, within_text, [start])", "Position of text inside other text (case-sensitive).",
                "=FIND(\"sheet\",\"Spreadsheet\")");
        add(t, "REPT", "REPT(text, times)", "Repeats text a number of times.", "=REPT(\"ab\",3)");
        add(t, "VALUE", "VALUE(text)", "Converts text such as \"42.5\" to a number.", "=VALUE(\"42.5\")");

        String k = "Lookup";
        add(k, "VLOOKUP", "VLOOKUP(lookup_value, table, column, [exact_match])",
                "Finds a value in the first column of a table and returns a value from another column. "
                        + "Use FALSE for an exact match; TRUE (default) needs the first column sorted.",
                "=VLOOKUP(30,A1:B5,2,FALSE)");
        add(k, "MATCH", "MATCH(lookup_value, range, [type])",
                "Position of a value in a range. Type 0 = exact, 1 = largest value not above (sorted ascending).",
                "=MATCH(30,A1:A5,0)");
        add(k, "INDEX", "INDEX(range, row, [column])", "The value at a row and column inside a range.",
                "=INDEX(A1:B5,2,2)");

        String d = "Date & Time";
        add(d, "TODAY", "TODAY()", "Today's date (year-month-day).", "=TODAY()");
        add(d, "NOW", "NOW()", "Current date and time.", "=NOW()");
        add(d, "YEAR", "YEAR(date)", "Year of a date such as \"2026-11-12\".", "=YEAR(\"2026-11-12\")");
        add(d, "MONTH", "MONTH(date)", "Month number (1-12) of a date.", "=MONTH(\"2026-11-12\")");
        add(d, "DAY", "DAY(date)", "Day of the month of a date.", "=DAY(\"2026-11-12\")");
        add(d, "DAYS", "DAYS(end_date, start_date)", "Number of days between two dates.",
                "=DAYS(\"2026-12-25\",\"2026-11-12\")");
    }

    private FunctionCatalog() {
    }

    public static List<Entry> all() {
        return Collections.unmodifiableList(ALL);
    }

    public static List<String> categories() {
        return new ArrayList<>(new LinkedHashSet<String>() {
            private static final long serialVersionUID = 1L;

            {
                for (Entry e : ALL) add(e.category);
            }
        });
    }

    public static Entry find(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        for (Entry e : ALL) if (e.name.equals(n)) return e;
        return null;
    }
}

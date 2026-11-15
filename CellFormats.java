import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Number formats (General, Number, Comma, Currency, Percentage, Scientific). */
public final class CellFormats {

    public static final int GENERAL = 0;
    public static final int NUMBER = 1;
    public static final int COMMA = 2;
    public static final int CURRENCY = 3;
    public static final int PERCENT = 4;
    public static final int SCIENTIFIC = 5;

    public static final String[] NAMES = {"General", "Number", "Comma", "Currency", "Percentage", "Scientific"};

    private CellFormats() {
    }

    public static int defaultDecimals(int fmt) {
        switch (fmt) {
            case NUMBER:
            case COMMA:
            case CURRENCY:
            case SCIENTIFIC:
                return 2;
            default:
                return 0;
        }
    }

    /** Formats a number for display. {@code decimals < 0} means the format's default. */
    public static String format(double v, int fmt, int decimals) {
        if (fmt == GENERAL || Double.isNaN(v) || Double.isInfinite(v)) return FormulaEngine.formatNumber(v);
        int d = decimals < 0 ? defaultDecimals(fmt) : Math.min(decimals, 15);
        StringBuilder zeros = new StringBuilder();
        for (int i = 0; i < d; i++) zeros.append('0');
        String frac = d > 0 ? "." + zeros : "";
        String pattern;
        switch (fmt) {
            case NUMBER:
                pattern = "0" + frac;
                break;
            case COMMA:
                pattern = "#,##0" + frac;
                break;
            case CURRENCY:
                pattern = "$#,##0" + frac + ";-$#,##0" + frac;
                break;
            case PERCENT:
                pattern = "0" + frac + "%";
                break;
            default:
                pattern = "0" + frac + "E00";
        }
        DecimalFormat df = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.US));
        df.setRoundingMode(RoundingMode.HALF_UP);
        String s = df.format(java.math.BigDecimal.valueOf(v)); // 2.675 rounds to 2.68, like Excel
        if (s.startsWith("-") && s.substring(1).matches("[0.,$%]*")) s = s.substring(1); // no "-0"
        return s;
    }

    /** How many decimals the General format currently shows for this value. */
    public static int visibleDecimals(double v) {
        String s = FormulaEngine.formatNumber(v);
        if (s.contains("E")) return 0;
        int dot = s.indexOf('.');
        return dot < 0 ? 0 : s.length() - dot - 1;
    }
}

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * How one sheet is printed: paper, orientation, margins, scaling, header and footer text and the print area.
 * Header and footer text may contain codes (the same ones Excel uses):
 * <pre>&amp;P page number   &amp;N number of pages   &amp;D date   &amp;T time   &amp;F file name   &amp;A sheet name   &amp;&amp; an ampersand</pre>
 */
public final class PageSetup {

    /** Paper sizes in points (1/72 inch), portrait. */
    public enum Paper {
        LETTER("Letter (8.5 x 11 in)", 612, 792),
        LEGAL("Legal (8.5 x 14 in)", 612, 1008),
        A4("A4 (210 x 297 mm)", 595, 842),
        A3("A3 (297 x 420 mm)", 842, 1191),
        A5("A5 (148 x 210 mm)", 420, 595);

        public final String label;
        public final int width;
        public final int height;

        Paper(String label, int width, int height) {
            this.label = label;
            this.width = width;
            this.height = height;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** How the sheet is sized onto pages. */
    public enum Fit {
        SCALE("Adjust to a percentage"),
        WIDTH("Fit all columns on one page wide"),
        PAGE("Fit the whole print area on one page");

        public final String label;

        Fit(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final int MIN_SCALE = 10;
    public static final int MAX_SCALE = 400;

    public Paper paper = Paper.LETTER;
    public boolean landscape = false;
    /** Margins in inches. */
    public double top = 0.75;
    public double bottom = 0.75;
    public double left = 0.7;
    public double right = 0.7;
    /** Distance of the header / footer text from the paper edge, in inches. */
    public double headerMargin = 0.3;
    public double footerMargin = 0.3;
    public Fit fit = Fit.SCALE;
    public int scale = 100;
    public boolean gridlines = false;
    public boolean headings = false;
    public boolean centerHorizontally = false;
    public boolean centerVertically = false;
    /** Print across first (left to right, then down) instead of down first. */
    public boolean overThenDown = false;
    /** Number of rows at the top of the print range that repeat on every page. */
    public int repeatRows = 0;
    public String headerLeft = "";
    public String headerCenter = "";
    public String headerRight = "";
    public String footerLeft = "";
    public String footerCenter = "";
    public String footerRight = "";
    /** Print area, or null for "everything that is used". */
    public int areaR1 = -1, areaC1 = -1, areaR2 = -1, areaC2 = -1;

    public PageSetup copy() {
        PageSetup p = new PageSetup();
        p.paper = paper;
        p.landscape = landscape;
        p.top = top;
        p.bottom = bottom;
        p.left = left;
        p.right = right;
        p.headerMargin = headerMargin;
        p.footerMargin = footerMargin;
        p.fit = fit;
        p.scale = scale;
        p.gridlines = gridlines;
        p.headings = headings;
        p.centerHorizontally = centerHorizontally;
        p.centerVertically = centerVertically;
        p.overThenDown = overThenDown;
        p.repeatRows = repeatRows;
        p.headerLeft = headerLeft;
        p.headerCenter = headerCenter;
        p.headerRight = headerRight;
        p.footerLeft = footerLeft;
        p.footerCenter = footerCenter;
        p.footerRight = footerRight;
        p.areaR1 = areaR1;
        p.areaC1 = areaC1;
        p.areaR2 = areaR2;
        p.areaC2 = areaC2;
        return p;
    }

    /** Copies every setting from another page setup (used when a dialog is confirmed). */
    public void set(PageSetup o) {
        PageSetup c = o.copy();
        paper = c.paper;
        landscape = c.landscape;
        top = c.top;
        bottom = c.bottom;
        left = c.left;
        right = c.right;
        headerMargin = c.headerMargin;
        footerMargin = c.footerMargin;
        fit = c.fit;
        scale = c.scale;
        gridlines = c.gridlines;
        headings = c.headings;
        centerHorizontally = c.centerHorizontally;
        centerVertically = c.centerVertically;
        overThenDown = c.overThenDown;
        repeatRows = c.repeatRows;
        headerLeft = c.headerLeft;
        headerCenter = c.headerCenter;
        headerRight = c.headerRight;
        footerLeft = c.footerLeft;
        footerCenter = c.footerCenter;
        footerRight = c.footerRight;
        areaR1 = c.areaR1;
        areaC1 = c.areaC1;
        areaR2 = c.areaR2;
        areaC2 = c.areaC2;
    }

    public boolean hasArea() {
        return areaR1 >= 0 && areaC1 >= 0 && areaR2 >= areaR1 && areaC2 >= areaC1;
    }

    public void setArea(int r1, int c1, int r2, int c2) {
        areaR1 = Math.min(r1, r2);
        areaC1 = Math.min(c1, c2);
        areaR2 = Math.max(r1, r2);
        areaC2 = Math.max(c1, c2);
    }

    public void clearArea() {
        areaR1 = areaC1 = areaR2 = areaC2 = -1;
    }

    /** Paper width in points for the chosen orientation. */
    public double pageWidth() {
        return landscape ? paper.height : paper.width;
    }

    public double pageHeight() {
        return landscape ? paper.width : paper.height;
    }

    /** True if every setting is still the default (nothing needs saving). */
    public boolean isDefault() {
        return equals(new PageSetup());
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PageSetup)) return false;
        PageSetup p = (PageSetup) o;
        return paper == p.paper && landscape == p.landscape && top == p.top && bottom == p.bottom && left == p.left
                && right == p.right && headerMargin == p.headerMargin && footerMargin == p.footerMargin
                && fit == p.fit && scale == p.scale && gridlines == p.gridlines && headings == p.headings
                && centerHorizontally == p.centerHorizontally && centerVertically == p.centerVertically
                && overThenDown == p.overThenDown && repeatRows == p.repeatRows
                && headerLeft.equals(p.headerLeft) && headerCenter.equals(p.headerCenter)
                && headerRight.equals(p.headerRight) && footerLeft.equals(p.footerLeft)
                && footerCenter.equals(p.footerCenter) && footerRight.equals(p.footerRight)
                && areaR1 == p.areaR1 && areaC1 == p.areaC1 && areaR2 == p.areaR2 && areaC2 == p.areaC2;
    }

    @Override
    public int hashCode() {
        return paper.hashCode() * 31 + scale;
    }

    /** Replaces the &amp; codes in a header or footer text. */
    public static String resolve(String text, int page, int pages, String fileName, String sheetName) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        Date now = new Date();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch != '&' || i + 1 >= text.length()) {
                sb.append(ch);
                continue;
            }
            char code = Character.toUpperCase(text.charAt(++i));
            switch (code) {
                case 'P':
                    sb.append(page);
                    break;
                case 'N':
                    sb.append(pages);
                    break;
                case 'D':
                    sb.append(new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now));
                    break;
                case 'T':
                    sb.append(new SimpleDateFormat("HH:mm", Locale.US).format(now));
                    break;
                case 'F':
                    sb.append(fileName == null ? "" : fileName);
                    break;
                case 'A':
                    sb.append(sheetName == null ? "" : sheetName);
                    break;
                case '&':
                    sb.append('&');
                    break;
                default:
                    sb.append('&').append(text.charAt(i));
            }
        }
        return sb.toString();
    }

    // ---- .spx storage ----

    public void write(DataOutputStream out) throws IOException {
        out.writeByte(paper.ordinal());
        out.writeBoolean(landscape);
        out.writeDouble(top);
        out.writeDouble(bottom);
        out.writeDouble(left);
        out.writeDouble(right);
        out.writeDouble(headerMargin);
        out.writeDouble(footerMargin);
        out.writeByte(fit.ordinal());
        out.writeInt(scale);
        out.writeBoolean(gridlines);
        out.writeBoolean(headings);
        out.writeBoolean(centerHorizontally);
        out.writeBoolean(centerVertically);
        out.writeBoolean(overThenDown);
        out.writeInt(repeatRows);
        writeText(out, headerLeft);
        writeText(out, headerCenter);
        writeText(out, headerRight);
        writeText(out, footerLeft);
        writeText(out, footerCenter);
        writeText(out, footerRight);
        out.writeInt(areaR1);
        out.writeInt(areaC1);
        out.writeInt(areaR2);
        out.writeInt(areaC2);
    }

    public static PageSetup read(DataInputStream in) throws IOException {
        PageSetup p = new PageSetup();
        Paper[] papers = Paper.values();
        Fit[] fits = Fit.values();
        int pi = in.readUnsignedByte();
        p.landscape = in.readBoolean();
        p.top = margin(in.readDouble());
        p.bottom = margin(in.readDouble());
        p.left = margin(in.readDouble());
        p.right = margin(in.readDouble());
        p.headerMargin = margin(in.readDouble());
        p.footerMargin = margin(in.readDouble());
        int fi = in.readUnsignedByte();
        int sc = in.readInt();
        p.gridlines = in.readBoolean();
        p.headings = in.readBoolean();
        p.centerHorizontally = in.readBoolean();
        p.centerVertically = in.readBoolean();
        p.overThenDown = in.readBoolean();
        int rep = in.readInt();
        p.headerLeft = readText(in);
        p.headerCenter = readText(in);
        p.headerRight = readText(in);
        p.footerLeft = readText(in);
        p.footerCenter = readText(in);
        p.footerRight = readText(in);
        p.areaR1 = in.readInt();
        p.areaC1 = in.readInt();
        p.areaR2 = in.readInt();
        p.areaC2 = in.readInt();
        if (pi >= papers.length || fi >= fits.length) throw new IOException("Invalid page setup");
        p.paper = papers[pi];
        p.fit = fits[fi];
        p.scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, sc));
        p.repeatRows = Math.max(0, Math.min(1000, rep));
        if (!p.hasArea()) p.clearArea();
        return p;
    }

    private static double margin(double v) {
        if (Double.isNaN(v) || v < 0 || v > 5) return 0.7;
        return v;
    }

    private static void writeText(DataOutputStream out, String s) throws IOException {
        byte[] b = (s == null ? "" : s).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readText(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 10_000) throw new IOException("Invalid text length");
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }
}

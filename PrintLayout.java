import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.SwingConstants;

/**
 * Cuts one sheet into printed pages and paints them. The same code draws the print preview and the real
 * printout, so what you see in the preview is what comes out of the printer.
 * All painting is in points (1/72 inch) with the origin at the top-left corner of the paper.
 */
public final class PrintLayout implements Printable {

    /** Row height and row-heading width in sheet pixels (the same as on screen at 100%). */
    public static final int ROW_PX = 22;
    public static final int HEADING_W_PX = 38;
    private static final double PX_TO_PT = 0.75; // 96 dpi screen pixels -> points
    private static final int BASE_FONT = 13;
    private static final Color GRID = new Color(0xC8C8C8);
    private static final Color HEADING_BG = new Color(0xEDEDED);

    private final SpreadsheetModel model;
    private final PageSetup setup;
    private final String sheetName;
    private final String fileName;
    private final boolean[] hidden;
    private final int[] rowTop;   // y of every row in sheet pixels (length rows + 1)
    private final int[] colLeft;  // x of every column in sheet pixels (length cols + 1)
    private final int r1, c1, r2, c2;
    private final boolean empty;
    private final double scalePercent;
    private final double k; // sheet pixels -> points
    private final List<int[]> rowSegs = new ArrayList<>();   // {firstRow, lastRow}
    private final List<int[]> colSegs = new ArrayList<>();   // {firstCol, lastCol}
    private final List<int[]> pages = new ArrayList<>();     // {rowSegIndex, colSegIndex}
    private final int titleRows;
    private final Map<String, Font> fonts = new HashMap<>();

    public PrintLayout(SpreadsheetModel model, PageSetup setup, String sheetName, String fileName) {
        this.model = model;
        this.setup = setup.copy();
        this.sheetName = sheetName == null ? "" : sheetName;
        this.fileName = fileName == null ? "" : fileName;
        int rows = model.getRowCount();
        int cols = model.getColumnCount();
        hidden = model.computeHiddenRows();
        rowTop = new int[rows + 1];
        for (int r = 0; r < rows; r++) rowTop[r + 1] = rowTop[r] + rowHeight(r);
        colLeft = new int[cols + 1];
        for (int c = 0; c < cols; c++) colLeft[c + 1] = colLeft[c] + model.getColumnWidth(c);

        int ar1, ac1, ar2, ac2;
        boolean isEmpty = false;
        if (setup.hasArea()) {
            ar1 = clamp(setup.areaR1, 0, rows - 1);
            ac1 = clamp(setup.areaC1, 0, cols - 1);
            ar2 = clamp(setup.areaR2, ar1, rows - 1);
            ac2 = clamp(setup.areaC2, ac1, cols - 1);
        } else {
            ar1 = 0;
            ac1 = 0;
            ar2 = model.getUsedRowCount() - 1;
            ac2 = model.getUsedColumnCount() - 1;
            for (SheetImage im : model.getImages()) {
                ar2 = Math.max(ar2, rowAt(im.y + im.height));
                ac2 = Math.max(ac2, colAt(im.x + im.width));
            }
            for (ChartSpec ch : model.getCharts()) {
                ar2 = Math.max(ar2, rowAt(ch.y + ch.height));
                ac2 = Math.max(ac2, colAt(ch.x + ch.width));
            }
            for (Sparkline sp : model.getSparklines()) {
                ar2 = Math.max(ar2, sp.row);
                ac2 = Math.max(ac2, sp.col);
            }
            if (ar2 < 0 || ac2 < 0) {
                isEmpty = true;
                ar2 = 0;
                ac2 = 0;
            }
        }
        r1 = ar1;
        c1 = ac1;
        r2 = Math.min(ar2, rows - 1);
        c2 = Math.min(ac2, cols - 1);
        empty = isEmpty;

        // scale
        double availW = setup.pageWidth() - (setup.left + setup.right) * 72;
        double availH = setup.pageHeight() - (setup.top + setup.bottom) * 72;
        availW = Math.max(36, availW);
        availH = Math.max(36, availH);
        double headW = setup.headings ? HEADING_W_PX : 0;
        double headH = setup.headings ? ROW_PX : 0;
        double totalW = colLeft[c2 + 1] - colLeft[c1] + headW;
        double totalH = rowTop[r2 + 1] - rowTop[r1] + headH;
        double pct = setup.scale;
        if (setup.fit == PageSetup.Fit.WIDTH || setup.fit == PageSetup.Fit.PAGE) {
            double fw = availW / (Math.max(1, totalW) * PX_TO_PT) * 100;
            double fh = availH / (Math.max(1, totalH) * PX_TO_PT) * 100;
            pct = setup.fit == PageSetup.Fit.WIDTH ? Math.min(100, fw) : Math.min(100, Math.min(fw, fh));
        }
        scalePercent = Math.max(PageSetup.MIN_SCALE, Math.min(PageSetup.MAX_SCALE, pct));
        k = PX_TO_PT * scalePercent / 100.0;

        // columns -> page columns
        double colAvail = availW / k - headW;
        int c = c1;
        while (c <= c2) {
            int start = c;
            double used = 0;
            while (c <= c2) {
                int w = colLeft[c + 1] - colLeft[c];
                if (c > start && used + w > colAvail) break;
                used += w;
                c++;
            }
            colSegs.add(new int[]{start, c - 1});
        }

        // rows -> page rows
        int wantedTitle = Math.max(0, Math.min(setup.repeatRows, r2 - r1));
        double titleH = 0;
        for (int r = r1; r < r1 + wantedTitle; r++) titleH += rowHeight(r);
        double firstAvail = availH / k - headH;
        titleRows = (titleH > 0 && titleH < firstAvail * 0.6) ? wantedTitle : 0;
        if (titleRows == 0) titleH = 0;
        int r = r1;
        boolean first = true;
        while (r <= r2) {
            double avail = first ? firstAvail : firstAvail - titleH;
            int start = r;
            double used = 0;
            while (r <= r2) {
                int h = rowHeight(r);
                if (r > start && used + h > avail) break;
                used += h;
                r++;
            }
            rowSegs.add(new int[]{start, r - 1});
            first = false;
        }

        if (setup.overThenDown) {
            for (int i = 0; i < rowSegs.size(); i++) for (int j = 0; j < colSegs.size(); j++) pages.add(new int[]{i, j});
        } else {
            for (int j = 0; j < colSegs.size(); j++) for (int i = 0; i < rowSegs.size(); i++) pages.add(new int[]{i, j});
        }
    }

    // ---- facts ----

    public int pageCount() {
        return Math.max(1, pages.size());
    }

    /** True when the sheet has nothing to print (the one page then only holds the header and footer). */
    public boolean isEmpty() {
        return empty;
    }

    public double scalePercent() {
        return scalePercent;
    }

    /** The range that is printed, as {firstRow, firstCol, lastRow, lastCol}. */
    public int[] range() {
        return new int[]{r1, c1, r2, c2};
    }

    public double paperWidth() {
        return setup.pageWidth();
    }

    public double paperHeight() {
        return setup.pageHeight();
    }

    private int rowHeight(int r) {
        return r >= 0 && r < hidden.length && hidden[r] ? 0 : ROW_PX;
    }

    private int rowAt(int y) {
        int lo = 0;
        int hi = model.getRowCount() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (rowTop[mid + 1] > y) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    private int colAt(int x) {
        int lo = 0;
        int hi = model.getColumnCount() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (colLeft[mid + 1] > x) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---- java.awt.print ----

    /** A PageFormat that matches the setup (paper, orientation, no extra unprintable margin). */
    public PageFormat pageFormat() {
        Paper p = new Paper();
        p.setSize(setup.paper.width, setup.paper.height);
        p.setImageableArea(0, 0, setup.paper.width, setup.paper.height);
        PageFormat pf = new PageFormat();
        pf.setPaper(p);
        pf.setOrientation(setup.landscape ? PageFormat.LANDSCAPE : PageFormat.PORTRAIT);
        return pf;
    }

    @Override
    public int print(java.awt.Graphics graphics, PageFormat pf, int pageIndex) {
        if (pageIndex >= pageCount()) return NO_SUCH_PAGE;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.translate(-pf.getImageableX(), -pf.getImageableY());
            if (pf.getOrientation() != PageFormat.PORTRAIT && !setup.landscape) {
                // the printer driver chose another orientation than ours: keep our layout as it is
                g.translate(0, 0);
            }
            paintPage(g, pageIndex);
        } finally {
            g.dispose();
        }
        return PAGE_EXISTS;
    }

    // ---- painting ----

    /** Paints page {@code index} (0-based) in paper coordinates. The caller fills the paper white first. */
    public void paintPage(Graphics2D g0, int index) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            int total = pageCount();
            paintHeaderFooter(g, index + 1, total);
            if (pages.isEmpty()) return;
            int[] pg = pages.get(index);
            int[] rs = rowSegs.get(pg[0]);
            int[] cs = colSegs.get(pg[1]);
            paintBody(g, pg[0], rs, cs);
        } finally {
            g.dispose();
        }
    }

    private void paintHeaderFooter(Graphics2D g, int page, int total) {
        Font f = new Font(Font.SANS_SERIF, Font.PLAIN, 9);
        g.setFont(f);
        g.setColor(new Color(0x333333));
        FontMetrics fm = g.getFontMetrics();
        double w = setup.pageWidth();
        double h = setup.pageHeight();
        float left = (float) (setup.left * 72);
        float right = (float) (w - setup.right * 72);
        float headY = (float) (setup.headerMargin * 72) + fm.getAscent();
        float footY = (float) (h - setup.footerMargin * 72) - fm.getDescent();
        drawThree(g, fm, PageSetup.resolve(setup.headerLeft, page, total, fileName, sheetName),
                PageSetup.resolve(setup.headerCenter, page, total, fileName, sheetName),
                PageSetup.resolve(setup.headerRight, page, total, fileName, sheetName), left, right, headY);
        drawThree(g, fm, PageSetup.resolve(setup.footerLeft, page, total, fileName, sheetName),
                PageSetup.resolve(setup.footerCenter, page, total, fileName, sheetName),
                PageSetup.resolve(setup.footerRight, page, total, fileName, sheetName), left, right, footY);
    }

    private static void drawThree(Graphics2D g, FontMetrics fm, String l, String c, String r, float left, float right, float y) {
        if (!l.isEmpty()) g.drawString(l, left, y);
        if (!c.isEmpty()) g.drawString(c, (left + right) / 2f - fm.stringWidth(c) / 2f, y);
        if (!r.isEmpty()) g.drawString(r, right - fm.stringWidth(r), y);
    }

    private void paintBody(Graphics2D g, int rowSegIndex, int[] rs, int[] cs) {
        double availW = setup.pageWidth() - (setup.left + setup.right) * 72;
        double availH = setup.pageHeight() - (setup.top + setup.bottom) * 72;
        double headW = setup.headings ? HEADING_W_PX : 0;
        double headH = setup.headings ? ROW_PX : 0;
        boolean repeat = rowSegIndex > 0 && titleRows > 0;

        int colsW = colLeft[cs[1] + 1] - colLeft[cs[0]];
        int rowsH = rowTop[rs[1] + 1] - rowTop[rs[0]];
        double titleH = 0;
        if (repeat) titleH = rowTop[r1 + titleRows] - rowTop[r1];
        double contentW = (headW + colsW) * k;
        double contentH = (headH + titleH + rowsH) * k;
        double x0 = setup.left * 72;
        double y0 = setup.top * 72;
        if (setup.centerHorizontally) x0 += Math.max(0, (availW - contentW) / 2);
        if (setup.centerVertically) y0 += Math.max(0, (availH - contentH) / 2);

        g.translate(x0, y0);
        g.scale(k, k);
        g.setClip(new Rectangle(-2, -2, (int) Math.ceil(contentW / k) + 4, (int) Math.ceil(contentH / k) + 4));

        double bodyX = headW;
        double bodyY = headH;

        // column headings
        if (setup.headings) {
            for (int c = cs[0], x = 0; c <= cs[1]; c++) {
                int w = colLeft[c + 1] - colLeft[c];
                drawHeading(g, FormulaEngine.columnLabel(c), bodyX + x, 0, w, ROW_PX);
                x += w;
            }
        }
        // rows: the repeated title rows first, then this page's rows
        double y = bodyY;
        if (repeat) {
            for (int r = r1; r < r1 + titleRows; r++) y = paintRow(g, r, cs, bodyX, y);
        }
        double bodyRowsTop = y;
        for (int r = rs[0]; r <= rs[1]; r++) y = paintRow(g, r, cs, bodyX, y);

        // pictures, charts, sparklines are drawn on top of the cells
        paintObjects(g, rs, cs, bodyX, bodyRowsTop, colsW, rowsH);
    }

    private double paintRow(Graphics2D g, int r, int[] cs, double bodyX, double y) {
        int h = rowHeight(r);
        if (h == 0) return y;
        if (setup.headings) drawHeading(g, String.valueOf(r + 1), 0, y, HEADING_W_PX, h);
        double x = bodyX;
        for (int c = cs[0]; c <= cs[1]; c++) {
            int w = colLeft[c + 1] - colLeft[c];
            paintCell(g, r, c, x, y, w, h);
            x += w;
        }
        return y + h;
    }

    private void drawHeading(Graphics2D g, String text, double x, double y, int w, int h) {
        g.setColor(HEADING_BG);
        g.fill(new Rectangle.Double(x, y, w, h));
        g.setColor(GRID);
        g.setStroke(new BasicStroke(1f));
        g.draw(new Rectangle.Double(x, y, w, h));
        g.setColor(new Color(0x444444));
        Font f = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, (float) (x + (w - fm.stringWidth(text)) / 2.0), (float) (y + (h + fm.getAscent() - fm.getDescent()) / 2.0));
    }

    private Font fontFor(CellStyle st) {
        int flags = Font.PLAIN;
        if (st != null && st.bold) flags |= Font.BOLD;
        if (st != null && st.italic) flags |= Font.ITALIC;
        int size = st != null && st.fontSize > 0 ? st.fontSize : BASE_FONT;
        String fam = st != null && st.fontFamily != null ? st.fontFamily : Font.SANS_SERIF;
        String key = fam + "|" + flags + "|" + size;
        Font f = fonts.get(key);
        if (f == null) {
            f = new Font(fam, flags, size);
            fonts.put(key, f);
        }
        return f;
    }

    private void paintCell(Graphics2D g, int r, int c, double x, double y, int w, int h) {
        CellStyle st = model.getStyle(r, c);
        Color bg = st != null && st.background != null ? st.background : null;
        Color fg = st != null && st.foreground != null ? st.foreground : Color.BLACK;
        boolean error = model.isFormulaError(r, c);
        if (error && (st == null || st.foreground == null)) fg = new Color(0xC00000);
        CondRule rule = model.matchingRule(r, c);
        if (rule != null) {
            if (rule.bg != null) bg = rule.bg;
            if (rule.fg != null) fg = rule.fg;
        }
        if (bg != null) {
            g.setColor(bg);
            g.fill(new Rectangle.Double(x, y, w, h));
        }
        if (setup.gridlines) {
            g.setColor(GRID);
            g.setStroke(new BasicStroke(1f));
            g.draw(new java.awt.geom.Line2D.Double(x + w, y, x + w, y + h));
            g.draw(new java.awt.geom.Line2D.Double(x, y + h, x + w, y + h));
        }
        Sparkline sp = model.sparklineAt(r, c);
        if (sp != null) {
            SparklinePainter.paint(g, sp, sp.values(model), new Rectangle((int) x, (int) y, w, h));
            return;
        }
        Object v = model.getValueAt(r, c);
        String text = v == null ? "" : v.toString();
        if (text.isEmpty()) return;
        int align;
        if (st != null && st.alignment != CellStyle.ALIGN_AUTO) align = st.alignment;
        else if (error || model.getComputedValue(r, c) instanceof Boolean) align = SwingConstants.CENTER;
        else if (model.isNumeric(r, c)) align = SwingConstants.RIGHT;
        else align = SwingConstants.LEFT;
        g.setFont(fontFor(st));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text);
        double tx;
        if (align == SwingConstants.RIGHT) tx = x + w - 4 - tw;
        else if (align == SwingConstants.CENTER) tx = x + (w - tw) / 2.0;
        else tx = x + 4;
        double ty = y + (h + fm.getAscent() - fm.getDescent()) / 2.0;
        java.awt.Shape old = g.getClip();
        g.clipRect((int) x, (int) y, w, h);
        g.setColor(fg);
        g.drawString(text, (float) tx, (float) ty);
        g.setClip(old);
    }

    private void paintObjects(Graphics2D g, int[] rs, int[] cs, double bodyX, double bodyY, int colsW, int rowsH) {
        int ox = colLeft[cs[0]];
        int oy = rowTop[rs[0]];
        Rectangle segment = new Rectangle(ox, oy, colsW, rowsH);
        java.awt.Shape oldClip = g.getClip();
        g.clipRect((int) bodyX, (int) bodyY, colsW, rowsH);
        for (SheetImage im : model.getImages()) {
            if (!segment.intersects(new Rectangle(im.x, im.y, im.width, im.height))) continue;
            try {
                g.drawImage(im.getImage(), (int) (bodyX + im.x - ox), (int) (bodyY + im.y - oy), im.width, im.height, null);
            } catch (RuntimeException ignored) {
                // a broken picture must not stop the printout
            }
        }
        for (ChartSpec ch : model.getCharts()) {
            if (!segment.intersects(new Rectangle(ch.x, ch.y, ch.width, ch.height))) continue;
            Graphics2D cg = (Graphics2D) g.create();
            try {
                cg.translate(bodyX + ch.x - ox, bodyY + ch.y - oy);
                ChartPainter.paint(cg, ch, ChartData.read(model, ch), ch.width, ch.height, 1.0, -1, -1, null);
            } catch (RuntimeException ignored) {
                // skip a chart that cannot be drawn
            } finally {
                cg.dispose();
            }
        }
        g.setClip(oldClip);
    }

    /** Renders a page as it would look on paper, for tests and thumbnails (scale = pixels per point). */
    public java.awt.image.BufferedImage render(int index, double pixelsPerPoint) {
        int w = (int) Math.ceil(setup.pageWidth() * pixelsPerPoint);
        int h = (int) Math.ceil(setup.pageHeight() * pixelsPerPoint);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.transform(AffineTransform.getScaleInstance(pixelsPerPoint, pixelsPerPoint));
            paintPage(g, index);
        } finally {
            g.dispose();
        }
        return img;
    }
}

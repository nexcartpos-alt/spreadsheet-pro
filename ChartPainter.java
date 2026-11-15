import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a chart in its own coordinate space (0,0)-(w,h). The caller translates and scales the
 * graphics, so charts follow the sheet's zoom. While painting it records the clickable / hoverable
 * shapes ({@link Hit}) so the layer can show tooltips and react to legend clicks.
 */
public final class ChartPainter {

    public static final Color[] PALETTE = {
            new Color(0x2E7D32), new Color(0x1E88E5), new Color(0xFB8C00), new Color(0x8E24AA),
            new Color(0xE53935), new Color(0x00ACC1), new Color(0xFDD835), new Color(0x6D4C41)};
    private static final Color TEXT = new Color(0x404040);
    private static final Color GRID = new Color(0xE3E3E3);
    private static final Color AXIS = new Color(0x9E9E9E);

    /** A shape the mouse can point at. */
    public static final class Hit {
        public final Shape shape;
        public final String tip;
        public final int series;
        public final int point;
        public final boolean legend;

        Hit(Shape shape, String tip, int series, int point, boolean legend) {
            this.shape = shape;
            this.tip = tip;
            this.series = series;
            this.point = point;
            this.legend = legend;
        }
    }

    private ChartPainter() {
    }

    public static Color color(int i) {
        return PALETTE[Math.floorMod(i, PALETTE.length)];
    }

    // ------------------------------------------------------------------

    private static final class Axis {
        double lo, hi, step;

        double pos(double v, double from, double len) {
            return from + (v - lo) / (hi - lo) * len;
        }
    }

    private static Axis niceAxis(double min, double max, boolean includeZero) {
        if (includeZero) {
            min = Math.min(min, 0);
            max = Math.max(max, 0);
        }
        if (!(max > min)) {
            max = min + 1;
        }
        double raw = (max - min) / 5.0;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double norm = raw / mag;
        double step = (norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10) * mag;
        Axis a = new Axis();
        a.step = step;
        a.lo = Math.floor(min / step + 1e-9) * step;
        a.hi = Math.ceil(max / step - 1e-9) * step;
        if (a.hi <= a.lo) a.hi = a.lo + step;
        return a;
    }

    private static String tick(double v, double step) {
        if (Math.abs(v) < step * 1e-9) v = 0;
        int dec = step >= 1 ? 0 : (int) Math.min(6, Math.ceil(-Math.log10(step) - 1e-9));
        double a = Math.abs(v);
        if (a >= 1e9) return trim(v / 1e9, dec) + "B";
        if (a >= 1e6) return trim(v / 1e6, Math.max(dec, 0)) + "M";
        return String.format("%,." + dec + "f", v);
    }

    private static String trim(double v, int dec) {
        String s = String.format("%." + Math.max(1, dec) + "f", v);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    /** Value as shown in tooltips. */
    public static String num(double v) {
        return FormulaEngine.formatNumber(v);
    }

    private static String fit(String s, FontMetrics fm, int maxWidth) {
        if (fm.stringWidth(s) <= maxWidth) return s;
        String t = s;
        while (t.length() > 1 && fm.stringWidth(t + "…") > maxWidth) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    // ------------------------------------------------------------------

    /**
     * @param anim         0..1 entrance animation
     * @param hoverSeries  highlighted series (-1 none)
     * @param hoverPoint   highlighted point (-1 none)
     * @param hits         receives the shapes in paint order (may be null)
     */
    public static void paint(Graphics2D g0, ChartSpec s, ChartData d, int w, int h, double anim,
                             int hoverSeries, int hoverPoint, List<Hit> hits) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(0xBDBDBD));
            g.drawRect(0, 0, w - 1, h - 1);

            int top = 10;
            if (s.title != null && !s.title.isEmpty()) {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
                FontMetrics fm = g.getFontMetrics();
                String t = fit(s.title, fm, w - 20);
                g.setColor(TEXT);
                g.drawString(t, (w - fm.stringWidth(t)) / 2, top + fm.getAscent());
                top += fm.getHeight() + 6;
            }
            if (d.series() == 0 || d.points == 0 || d.isEmpty()) {
                g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 12));
                g.setColor(AXIS);
                String msg = "No numbers in " + s.rangeText();
                g.drawString(msg, (w - g.getFontMetrics().stringWidth(msg)) / 2, h / 2);
                return;
            }
            boolean pie = s.type == ChartSpec.Type.PIE;
            int legendCount = pie ? d.points : d.series();
            boolean showLegend = s.legend && (pie || d.series() > 1);
            int bottom = 8;
            List<Object[]> legendItems = new ArrayList<>(); // {label, colorIndex, Rectangle}
            if (showLegend) {
                bottom += layoutLegend(g, s, d, pie, legendCount, w, legendItems, h - 8);
            }
            Rectangle plot = new Rectangle(10, top, w - 22, Math.max(20, h - top - bottom));
            switch (s.type) {
                case PIE:
                    paintPie(g, s, d, plot, anim, hoverPoint, hits);
                    break;
                case BAR:
                    paintBars(g, s, d, plot, anim, hoverSeries, hoverPoint, hits, true);
                    break;
                case COLUMN:
                    paintBars(g, s, d, plot, anim, hoverSeries, hoverPoint, hits, false);
                    break;
                case SCATTER:
                    paintLines(g, s, d, plot, anim, hoverSeries, hoverPoint, hits, false, true);
                    break;
                default:
                    paintLines(g, s, d, plot, anim, hoverSeries, hoverPoint, hits, s.type == ChartSpec.Type.AREA, false);
            }
            if (showLegend) paintLegend(g, s, pie, legendItems, hits);
        } finally {
            g.dispose();
        }
    }

    // ---- legend ----

    private static int layoutLegend(Graphics2D g, ChartSpec s, ChartData d, boolean pie, int count, int w,
                                    List<Object[]> items, int bottomY) {
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        FontMetrics fm = g.getFontMetrics();
        int rowH = 18;
        List<Object[]> row = new ArrayList<>();
        List<List<Object[]>> rows = new ArrayList<>();
        int rowW = 0;
        for (int i = 0; i < count; i++) {
            String label = fit(pie ? d.categories.get(i) : d.names.get(i), fm, Math.max(40, w / 3));
            int iw = 14 + fm.stringWidth(label) + 14;
            if (!row.isEmpty() && rowW + iw > w - 20 && rows.size() < 2) {
                rows.add(row);
                row = new ArrayList<>();
                rowW = 0;
            }
            if (rows.size() >= 2 && rowW + iw > w - 20) break; // too many entries: the rest are not listed
            row.add(new Object[]{label, i, iw});
            rowW += iw;
        }
        if (!row.isEmpty()) rows.add(row);
        int y = bottomY - rows.size() * rowH;
        for (List<Object[]> r : rows) {
            int total = 0;
            for (Object[] it : r) total += (Integer) it[2];
            int x = (w - total) / 2;
            for (Object[] it : r) {
                items.add(new Object[]{it[0], it[1], new Rectangle(x, y, (Integer) it[2], rowH)});
                x += (Integer) it[2];
            }
            y += rowH;
        }
        return rows.size() * rowH + 4;
    }

    private static void paintLegend(Graphics2D g, ChartSpec s, boolean pie, List<Object[]> items, List<Hit> hits) {
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        FontMetrics fm = g.getFontMetrics();
        for (Object[] it : items) {
            int idx = (Integer) it[1];
            Rectangle r = (Rectangle) it[2];
            boolean off = s.hidden.contains(idx);
            g.setColor(off ? new Color(0xC8C8C8) : color(idx));
            g.fillRoundRect(r.x + 2, r.y + 5, 8, 8, 3, 3);
            g.setColor(off ? new Color(0xA0A0A0) : TEXT);
            g.drawString((String) it[0], r.x + 14, r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
            if (off) {
                int sw = fm.stringWidth((String) it[0]);
                int ly = r.y + r.height / 2;
                g.drawLine(r.x + 14, ly, r.x + 14 + sw, ly);
            }
            if (hits != null) {
                hits.add(new Hit(r, off ? "Click to show" : "Click to hide", pie ? -1 : idx, pie ? idx : -1, true));
            }
        }
    }

    // ---- bar / column ----

    private static void paintBars(Graphics2D g, ChartSpec s, ChartData d, Rectangle area, double anim,
                                  int hs, int hp, List<Hit> hits, boolean horizontal) {
        List<Integer> vis = visibleSeries(s, d);
        double min = 0;
        double max = 0;
        for (int si : vis) for (double v : d.values.get(si)) if (!Double.isNaN(v)) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        Axis ax = niceAxis(min, max, true);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        FontMetrics fm = g.getFontMetrics();

        int catLabelW = 0;
        if (horizontal) {
            for (String c : d.categories) catLabelW = Math.max(catLabelW, fm.stringWidth(c));
            catLabelW = Math.min(catLabelW, area.width / 4) + 8;
        }
        int valLabelW = 0;
        if (!horizontal) {
            for (double v = ax.lo; v <= ax.hi + ax.step / 2; v += ax.step) {
                valLabelW = Math.max(valLabelW, fm.stringWidth(tick(v, ax.step)));
            }
            valLabelW += 8;
        }
        Rectangle p = horizontal
                ? new Rectangle(area.x + catLabelW, area.y + 4, area.width - catLabelW - 6, area.height - fm.getHeight() - 10)
                : new Rectangle(area.x + valLabelW, area.y + 6, area.width - valLabelW - 4, area.height - fm.getHeight() - 12);

        // grid + value labels
        g.setStroke(new BasicStroke(1f));
        for (double v = ax.lo; v <= ax.hi + ax.step / 2; v += ax.step) {
            String t = tick(v, ax.step);
            g.setColor(GRID);
            if (horizontal) {
                int x = (int) Math.round(ax.pos(v, p.x, p.width));
                g.drawLine(x, p.y, x, p.y + p.height);
                g.setColor(TEXT);
                g.drawString(t, x - fm.stringWidth(t) / 2, p.y + p.height + fm.getAscent() + 3);
            } else {
                int y = (int) Math.round(p.y + p.height - (v - ax.lo) / (ax.hi - ax.lo) * p.height);
                g.drawLine(p.x, y, p.x + p.width, y);
                g.setColor(TEXT);
                g.drawString(t, p.x - 6 - fm.stringWidth(t), y + (fm.getAscent() - fm.getDescent()) / 2);
            }
        }
        double zero = horizontal ? ax.pos(0, p.x, p.width) : p.y + p.height - (0 - ax.lo) / (ax.hi - ax.lo) * p.height;
        g.setColor(AXIS);
        if (horizontal) g.drawLine((int) Math.round(zero), p.y, (int) Math.round(zero), p.y + p.height);
        else g.drawLine(p.x, (int) Math.round(zero), p.x + p.width, (int) Math.round(zero));

        int n = d.points;
        int ns = Math.max(1, vis.size());
        double band = (horizontal ? p.height : p.width) / (double) n;
        double gap = band * 0.18;
        double barW = (band - gap * 2) / ns;
        int labelEvery = Math.max(1, (int) Math.ceil(fm.getHeight() * 1.3 / Math.max(1, band)));
        for (int i = 0; i < n; i++) {
            double start = (horizontal ? p.y : p.x) + band * i;
            // category label
            g.setColor(TEXT);
            if (horizontal) {
                if (i % labelEvery == 0) {
                    String t = fit(d.categories.get(i), fm, catLabelW - 8);
                    g.drawString(t, p.x - 6 - fm.stringWidth(t), (int) (start + band / 2 + (fm.getAscent() - fm.getDescent()) / 2.0));
                }
            } else {
                if (i % labelEvery == 0) {
                    int maxW = (int) (band * labelEvery) - 4;
                    String t = fit(d.categories.get(i), fm, Math.max(10, maxW));
                    g.drawString(t, (int) (start + band / 2.0 - fm.stringWidth(t) / 2.0), p.y + p.height + fm.getAscent() + 4);
                }
            }
            for (int k = 0; k < vis.size(); k++) {
                int si = vis.get(k);
                double v = d.values.get(si)[i];
                if (Double.isNaN(v)) continue;
                double off = start + gap + barW * k;
                double a = zeroToValue(v * anim, ax, p, horizontal);
                Rectangle2D r;
                if (horizontal) {
                    double x0 = Math.min(zero, a);
                    r = new Rectangle2D.Double(x0, off, Math.max(1, Math.abs(a - zero)), Math.max(1, barW - 1));
                } else {
                    double y0 = Math.min(zero, a);
                    r = new Rectangle2D.Double(off, y0, Math.max(1, barW - 1), Math.max(1, Math.abs(a - zero)));
                }
                boolean hot = si == hs && i == hp;
                g.setColor(hot ? color(si).brighter() : color(si));
                g.fill(r);
                if (hot) {
                    g.setColor(color(si).darker());
                    g.setStroke(new BasicStroke(2f));
                    g.draw(r);
                    g.setStroke(new BasicStroke(1f));
                }
                if (hits != null) {
                    hits.add(new Hit(r, d.categories.get(i) + "\n" + d.names.get(si) + ": " + num(v), si, i, false));
                }
            }
        }
    }

    private static double zeroToValue(double v, Axis ax, Rectangle p, boolean horizontal) {
        return horizontal ? ax.pos(v, p.x, p.width) : p.y + p.height - (v - ax.lo) / (ax.hi - ax.lo) * p.height;
    }

    // ---- line / area / scatter ----

    private static void paintLines(Graphics2D g, ChartSpec s, ChartData d, Rectangle area, double anim,
                                   int hs, int hp, List<Hit> hits, boolean fillArea, boolean scatter) {
        List<Integer> vis = visibleSeries(s, d);
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int si : vis) for (double v : d.values.get(si)) if (!Double.isNaN(v)) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (min == Double.MAX_VALUE) {
            min = 0;
            max = 1;
        }
        Axis ay = niceAxis(min, max, fillArea || (min >= 0 && min < max * 0.6));
        Axis axx = null;
        boolean numericX = scatter && d.xs != null;
        if (numericX) {
            double xmin = Double.MAX_VALUE;
            double xmax = -Double.MAX_VALUE;
            for (double x : d.xs) if (!Double.isNaN(x)) {
                xmin = Math.min(xmin, x);
                xmax = Math.max(xmax, x);
            }
            if (xmin == Double.MAX_VALUE) {
                xmin = 0;
                xmax = 1;
            }
            axx = niceAxis(xmin, xmax, false);
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        FontMetrics fm = g.getFontMetrics();
        int valLabelW = 0;
        for (double v = ay.lo; v <= ay.hi + ay.step / 2; v += ay.step) {
            valLabelW = Math.max(valLabelW, fm.stringWidth(tick(v, ay.step)));
        }
        valLabelW += 8;
        Rectangle p = new Rectangle(area.x + valLabelW, area.y + 6, area.width - valLabelW - 8, area.height - fm.getHeight() - 12);

        g.setStroke(new BasicStroke(1f));
        for (double v = ay.lo; v <= ay.hi + ay.step / 2; v += ay.step) {
            int y = (int) Math.round(p.y + p.height - (v - ay.lo) / (ay.hi - ay.lo) * p.height);
            g.setColor(GRID);
            g.drawLine(p.x, y, p.x + p.width, y);
            g.setColor(TEXT);
            String t = tick(v, ay.step);
            g.drawString(t, p.x - 6 - fm.stringWidth(t), y + (fm.getAscent() - fm.getDescent()) / 2);
        }
        g.setColor(AXIS);
        g.drawLine(p.x, p.y + p.height, p.x + p.width, p.y + p.height);

        int n = d.points;
        double[] px = new double[n];
        double band = p.width / (double) Math.max(1, n);
        for (int i = 0; i < n; i++) {
            if (numericX) {
                px[i] = Double.isNaN(d.xs[i]) ? Double.NaN : axx.pos(d.xs[i], p.x, p.width);
            } else {
                px[i] = p.x + band * (i + 0.5);
            }
        }
        // x labels
        g.setColor(TEXT);
        if (numericX) {
            for (double v = axx.lo; v <= axx.hi + axx.step / 2; v += axx.step) {
                String t = tick(v, axx.step);
                int x = (int) Math.round(axx.pos(v, p.x, p.width));
                g.setColor(GRID);
                g.drawLine(x, p.y, x, p.y + p.height);
                g.setColor(TEXT);
                g.drawString(t, x - fm.stringWidth(t) / 2, p.y + p.height + fm.getAscent() + 4);
            }
        } else {
            int every = Math.max(1, (int) Math.ceil(60.0 / Math.max(1, band)));
            for (int i = 0; i < n; i += every) {
                String t = fit(d.categories.get(i), fm, (int) Math.max(14, band * every - 4));
                g.drawString(t, (int) (px[i] - fm.stringWidth(t) / 2.0), p.y + p.height + fm.getAscent() + 4);
            }
        }

        Shape oldClip = g.getClip();
        if (!scatter) g.clipRect(p.x - 2, p.y - 8, (int) Math.ceil((p.width + 14) * anim), p.height + 20);
        for (int k = 0; k < vis.size(); k++) {
            int si = vis.get(k);
            double[] vals = d.values.get(si);
            Color c = color(si);
            java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
            boolean started = false;
            double baseY = p.y + p.height - (Math.max(ay.lo, Math.min(0, ay.hi)) - ay.lo) / (ay.hi - ay.lo) * p.height;
            double firstX = 0;
            double lastX = 0;
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(vals[i]) || Double.isNaN(px[i])) continue;
                double y = p.y + p.height - (vals[i] - ay.lo) / (ay.hi - ay.lo) * p.height;
                if (!started) {
                    path.moveTo(px[i], y);
                    started = true;
                    firstX = px[i];
                } else {
                    path.lineTo(px[i], y);
                }
                lastX = px[i];
            }
            if (started && !scatter) {
                if (fillArea) {
                    java.awt.geom.Path2D.Double fillPath = new java.awt.geom.Path2D.Double(path);
                    fillPath.lineTo(lastX, baseY);
                    fillPath.lineTo(firstX, baseY);
                    fillPath.closePath();
                    g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 70));
                    g.fill(fillPath);
                }
                g.setColor(c);
                g.setStroke(new BasicStroke(si == hs ? 3.2f : 2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(path);
            }
            g.setStroke(new BasicStroke(1f));
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(vals[i]) || Double.isNaN(px[i])) continue;
                double y = p.y + p.height - (vals[i] - ay.lo) / (ay.hi - ay.lo) * p.height;
                boolean hot = si == hs && i == hp;
                double r = (hot ? 6.5 : 4) * (scatter ? Math.max(0.2, anim) : 1);
                Ellipse2D dot = new Ellipse2D.Double(px[i] - r, y - r, r * 2, r * 2);
                g.setColor(Color.WHITE);
                g.fill(new Ellipse2D.Double(px[i] - r - 1, y - r - 1, r * 2 + 2, r * 2 + 2));
                g.setColor(hot ? c.darker() : c);
                g.fill(dot);
                if (hits != null) {
                    String head = numericX ? "x = " + num(d.xs[i]) : d.categories.get(i);
                    hits.add(new Hit(new Ellipse2D.Double(px[i] - 9, y - 9, 18, 18),
                            head + "\n" + d.names.get(si) + ": " + num(vals[i]), si, i, false));
                }
            }
        }
        g.setClip(oldClip);
    }

    // ---- pie ----

    private static void paintPie(Graphics2D g, ChartSpec s, ChartData d, Rectangle area, double anim,
                                 int hp, List<Hit> hits) {
        double[] vals = d.values.get(0);
        double total = 0;
        for (int i = 0; i < vals.length; i++) {
            if (!s.hidden.contains(i) && !Double.isNaN(vals[i]) && vals[i] > 0) total += vals[i];
        }
        if (total <= 0) {
            g.setColor(AXIS);
            g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 12));
            g.drawString("Nothing to show", area.x + area.width / 2 - 40, area.y + area.height / 2);
            return;
        }
        int size = Math.min(area.width, area.height) - 24;
        if (size < 20) size = 20;
        double cx = area.x + area.width / 2.0;
        double cy = area.y + area.height / 2.0;
        double angle = 90;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < vals.length; i++) {
            if (s.hidden.contains(i) || Double.isNaN(vals[i]) || vals[i] <= 0) continue;
            double sweep = -360.0 * vals[i] / total;
            double mid = Math.toRadians(angle + sweep / 2);
            boolean hot = i == hp;
            double ox = hot ? Math.cos(mid) * 8 : 0;
            double oy = hot ? -Math.sin(mid) * 8 : 0;
            Arc2D.Double arc = new Arc2D.Double(cx - size / 2.0 + ox, cy - size / 2.0 + oy, size, size,
                    angle, sweep * anim, Arc2D.PIE);
            g.setColor(color(i));
            g.fill(arc);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.5f));
            g.draw(arc);
            double frac = vals[i] / total;
            if (frac > 0.06 && anim > 0.95) {
                String t = Math.round(frac * 100) + "%";
                g.setColor(Color.WHITE);
                g.drawString(t, (int) (cx + ox + Math.cos(mid) * size * 0.32 - fm.stringWidth(t) / 2.0),
                        (int) (cy + oy - Math.sin(mid) * size * 0.32 + fm.getAscent() / 2.0 - 1));
            }
            if (hits != null) {
                hits.add(new Hit(arc, d.categories.get(i) + "\n" + d.names.get(0) + ": " + num(vals[i])
                        + "  (" + String.format("%.1f", frac * 100) + "%)", 0, i, false));
            }
            angle += sweep;
        }
    }

    private static List<Integer> visibleSeries(ChartSpec s, ChartData d) {
        List<Integer> vis = new ArrayList<>();
        for (int i = 0; i < d.series(); i++) if (!s.hidden.contains(i)) vis.add(i);
        return vis;
    }
}

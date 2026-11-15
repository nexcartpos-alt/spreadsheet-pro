import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/** Draws a {@link Sparkline} inside a cell rectangle. */
public final class SparklinePainter {

    private static final Color NEGATIVE = new Color(0xD32F2F);

    private SparklinePainter() {
    }

    public static void paint(Graphics2D g0, Sparkline sp, double[] vals, Rectangle cell) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int padX = Math.max(3, cell.width / 14);
            int padY = Math.max(3, cell.height / 6);
            Rectangle r = new Rectangle(cell.x + padX, cell.y + padY, cell.width - 2 * padX, cell.height - 2 * padY);
            if (r.width < 6 || r.height < 4 || vals.length == 0) return;
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (double v : vals) {
                if (Double.isNaN(v)) continue;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            if (min == Double.MAX_VALUE) return; // no numbers at all
            int n = vals.length;
            double slot = r.width / (double) n;
            switch (sp.type) {
                case LINE: {
                    if (max == min) {
                        max = min + 1;
                        min = min - 1;
                    }
                    Path2D.Double path = new Path2D.Double();
                    boolean started = false;
                    double lx = 0;
                    double ly = 0;
                    for (int i = 0; i < n; i++) {
                        if (Double.isNaN(vals[i])) continue;
                        double x = n == 1 ? r.x + r.width / 2.0 : r.x + r.width * i / (double) (n - 1);
                        double y = r.y + r.height - (vals[i] - min) / (max - min) * r.height;
                        if (!started) {
                            path.moveTo(x, y);
                            started = true;
                        } else {
                            path.lineTo(x, y);
                        }
                        lx = x;
                        ly = y;
                    }
                    g.setColor(sp.color);
                    g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(path);
                    g.fill(new java.awt.geom.Ellipse2D.Double(lx - 2.2, ly - 2.2, 4.4, 4.4));
                    break;
                }
                case COLUMN: {
                    double lo = Math.min(0, min);
                    double hi = Math.max(0, max);
                    if (hi == lo) hi = lo + 1;
                    double zero = r.y + r.height - (0 - lo) / (hi - lo) * r.height;
                    for (int i = 0; i < n; i++) {
                        if (Double.isNaN(vals[i])) continue;
                        double y = r.y + r.height - (vals[i] - lo) / (hi - lo) * r.height;
                        g.setColor(vals[i] < 0 ? NEGATIVE : sp.color);
                        double top = Math.min(y, zero);
                        double h = Math.max(1, Math.abs(zero - y));
                        g.fill(new java.awt.geom.Rectangle2D.Double(r.x + slot * i + slot * 0.15, top, Math.max(1, slot * 0.7), h));
                    }
                    break;
                }
                default: { // WIN_LOSS
                    double mid = r.y + r.height / 2.0;
                    double h = r.height / 2.0 - 1;
                    for (int i = 0; i < n; i++) {
                        if (Double.isNaN(vals[i]) || vals[i] == 0) continue;
                        g.setColor(vals[i] < 0 ? NEGATIVE : sp.color);
                        double y = vals[i] > 0 ? mid - h : mid + 1;
                        g.fill(new java.awt.geom.Rectangle2D.Double(r.x + slot * i + slot * 0.15, y, Math.max(1, slot * 0.7), h));
                    }
                    g.setColor(new Color(0xB0B0B0));
                    g.setStroke(new BasicStroke(1f));
                    g.drawLine(r.x, (int) mid, r.x + r.width, (int) mid);
                }
            }
        } finally {
            g.dispose();
        }
    }
}

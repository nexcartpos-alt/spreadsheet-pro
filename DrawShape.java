import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/**
 * One freehand stroke or shape drawn on top of a worksheet (immutable).
 * Coordinates are in 100%-zoom pixels so drawings follow the sheet's zoom level.
 */
public final class DrawShape {

    public enum Type { PEN, HIGHLIGHT, LINE, RECT, ELLIPSE }

    public final Type type;
    public final int argb;
    public final float width;
    public final boolean filled;
    /** x0, y0, x1, y1, ... (pen strokes have many points, the other shapes two). */
    public final float[] pts;

    public DrawShape(Type type, int argb, float width, boolean filled, float[] pts) {
        this.type = type;
        this.argb = argb;
        this.width = width;
        this.filled = filled;
        this.pts = pts;
    }

    public Shape shape() {
        switch (type) {
            case LINE:
                return new Line2D.Float(pts[0], pts[1], pts[2], pts[3]);
            case RECT:
                return new Rectangle2D.Float(Math.min(pts[0], pts[2]), Math.min(pts[1], pts[3]),
                        Math.abs(pts[2] - pts[0]), Math.abs(pts[3] - pts[1]));
            case ELLIPSE:
                return new Ellipse2D.Float(Math.min(pts[0], pts[2]), Math.min(pts[1], pts[3]),
                        Math.abs(pts[2] - pts[0]), Math.abs(pts[3] - pts[1]));
            default:
                return freehand();
        }
    }

    /** Smooth path through the points (quadratic curves between midpoints). */
    private Shape freehand() {
        Path2D.Float p = new Path2D.Float();
        int n = pts.length / 2;
        if (n == 1) {
            p.moveTo(pts[0], pts[1]);
            p.lineTo(pts[0] + 0.01f, pts[1]);
            return p;
        }
        p.moveTo(pts[0], pts[1]);
        if (n == 2) {
            p.lineTo(pts[2], pts[3]);
            return p;
        }
        for (int i = 1; i < n - 1; i++) {
            float mx = (pts[2 * i] + pts[2 * i + 2]) / 2f;
            float my = (pts[2 * i + 1] + pts[2 * i + 3]) / 2f;
            p.quadTo(pts[2 * i], pts[2 * i + 1], mx, my);
        }
        p.lineTo(pts[2 * (n - 1)], pts[2 * (n - 1) + 1]);
        return p;
    }

    public void paint(Graphics2D g) {
        g.setColor(new Color(argb, true));
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Shape s = shape();
        if (filled && (type == Type.RECT || type == Type.ELLIPSE)) g.fill(s);
        else g.draw(s);
    }

    /** True if (x, y) is within {@code radius} of the visible ink (used by the eraser). */
    public boolean hits(double x, double y, double radius) {
        Shape s = shape();
        double pad = width / 2.0 + radius;
        Rectangle2D b = s.getBounds2D();
        if (x < b.getMinX() - pad || x > b.getMaxX() + pad || y < b.getMinY() - pad || y > b.getMaxY() + pad) {
            return false;
        }
        if (filled && (type == Type.RECT || type == Type.ELLIPSE) && s.contains(x, y)) return true;
        return new BasicStroke((float) (width + 2 * radius)).createStrokedShape(s).contains(x, y);
    }
}

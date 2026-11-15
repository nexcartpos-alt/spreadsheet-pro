import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import javax.swing.Icon;

/**
 * Crisp vector icons drawn with Java2D - no image files needed, so they stay sharp at
 * any size and any colour. Each icon is designed on a 24 x 24 grid and scaled.
 */
public final class Icons {

    private Icons() {
    }

    public static Icon get(String name, int size, Color color) {
        return new VectorIcon(name, size, color);
    }

    /** The app logo as an image (window / task-bar icon). */
    public static BufferedImage logo(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        get("logo", size, new Color(0x107C41)).paintIcon(null, g, 0, 0);
        g.dispose();
        return img;
    }

    private static final class VectorIcon implements Icon {
        private final String name;
        private final int size;
        private final Color color;

        VectorIcon(String name, int size, Color color) {
            this.name = name;
            this.size = size;
            this.color = color;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.translate(x, y);
            double s = size / 24.0;
            g2.scale(s, s);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            draw(g2, name, color);
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------

    private static void line(Graphics2D g, double x1, double y1, double x2, double y2) {
        g.draw(new Line2D.Double(x1, y1, x2, y2));
    }

    private static void poly(Graphics2D g, boolean close, boolean fill, double... p) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(p[0], p[1]);
        for (int i = 2; i < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
        if (close) path.closePath();
        if (fill) g.fill(path);
        else g.draw(path);
    }

    private static void box(Graphics2D g, double x, double y, double w, double h, double r, boolean fill) {
        RoundRectangle2D.Double rr = new RoundRectangle2D.Double(x, y, w, h, r, r);
        if (fill) g.fill(rr);
        else g.draw(rr);
    }

    private static void dot(Graphics2D g, double cx, double cy, double r) {
        g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
    }

    private static void text(Graphics2D g, String s, int style, float size, boolean serif) {
        Font f = new Font(serif ? Font.SERIF : Font.SANS_SERIF, style, 1).deriveFont(size);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (24 - fm.stringWidth(s)) / 2f, (24 - fm.getHeight()) / 2f + fm.getAscent() - 1);
    }

    private static void textAt(Graphics2D g, String s, int style, float size, float x, float baseline) {
        g.setFont(new Font(Font.SANS_SERIF, style, 1).deriveFont(size));
        g.drawString(s, x, baseline);
    }

    private static void arrowDown(Graphics2D g, double x, double y1, double y2) {
        line(g, x, y1, x, y2);
        poly(g, false, false, x - 3.2, y2 - 3.4, x, y2, x + 3.2, y2 - 3.4);
    }

    private static void funnel(Graphics2D g) {
        poly(g, true, false, 4, 5, 20, 5, 14, 12, 14, 19, 10, 17, 10, 12);
    }

    private static void draw(Graphics2D g, String name, Color color) {
        switch (name) {
            case "logo":
                box(g, 1, 1, 22, 22, 6, true);
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                box(g, 5, 5, 14, 14, 2, false);
                line(g, 5, 12, 19, 12);
                line(g, 12, 5, 12, 19);
                break;
            case "save":
                poly(g, true, false, 5, 4, 16, 4, 20, 8, 20, 20, 5, 20);
                poly(g, true, false, 8, 4, 8, 9, 15, 9, 15, 4);
                box(g, 8, 14, 8, 6, 1, false);
                break;
            case "open":
                poly(g, false, false, 3, 18, 3, 6, 9, 6, 11, 8, 19, 8, 19, 11);
                poly(g, true, false, 3, 19, 6, 11, 22, 11, 19, 19);
                break;
            case "newsheet":
                poly(g, true, false, 6, 3, 14, 3, 19, 8, 19, 21, 6, 21);
                line(g, 12.5, 12, 12.5, 18);
                line(g, 9.5, 15, 15.5, 15);
                break;
            case "deletesheet":
                poly(g, true, false, 6, 3, 14, 3, 19, 8, 19, 21, 6, 21);
                line(g, 9.5, 12, 15.5, 18);
                line(g, 15.5, 12, 9.5, 18);
                break;
            case "sum":
                poly(g, false, false, 18, 5, 7, 5, 13, 12, 7, 19, 18, 19);
                break;
            case "chart":
                line(g, 4, 20, 20, 20);
                box(g, 5, 12, 3.5, 8, 1, true);
                box(g, 10.2, 7, 3.5, 13, 1, true);
                box(g, 15.5, 3.5, 3.5, 16.5, 1, true);
                break;
            case "picture":
                box(g, 3, 5, 18, 14, 3, false);
                dot(g, 8.5, 10, 1.6);
                poly(g, false, false, 3.5, 17, 9, 12.5, 13, 16, 16, 13.5, 20.5, 17.5);
                break;
            case "clipboard":
                box(g, 5, 5, 14, 16, 2, false);
                box(g, 9, 3, 6, 4, 1.5, true);
                line(g, 8.5, 12, 15.5, 12);
                line(g, 8.5, 16, 13.5, 16);
                break;
            case "macro":
                poly(g, false, false, 9, 7, 4, 12, 9, 17);
                poly(g, false, false, 15, 7, 20, 12, 15, 17);
                line(g, 13.5, 5, 10.5, 19);
                break;
            case "lock":
                box(g, 5, 11, 14, 10, 3, true);
                g.draw(new Arc2D.Double(8, 3, 8, 8, 0, 180, Arc2D.OPEN));
                line(g, 8, 7, 8, 11);
                line(g, 16, 7, 16, 11);
                break;
            case "unlock":
                box(g, 5, 11, 14, 10, 3, true);
                g.draw(new Arc2D.Double(8, 3, 8, 8, 0, 180, Arc2D.OPEN));
                line(g, 8, 7, 8, 11);
                line(g, 16, 7, 16, 5);
                break;
            case "bold":
                text(g, "B", Font.BOLD, 22f, false);
                break;
            case "italic":
                text(g, "I", Font.BOLD | Font.ITALIC, 22f, true);
                break;
            case "alignleft":
                line(g, 4, 6, 20, 6);
                line(g, 4, 10.5, 14, 10.5);
                line(g, 4, 15, 20, 15);
                line(g, 4, 19.5, 14, 19.5);
                break;
            case "aligncenter":
                line(g, 4, 6, 20, 6);
                line(g, 7, 10.5, 17, 10.5);
                line(g, 4, 15, 20, 15);
                line(g, 7, 19.5, 17, 19.5);
                break;
            case "alignright":
                line(g, 4, 6, 20, 6);
                line(g, 10, 10.5, 20, 10.5);
                line(g, 4, 15, 20, 15);
                line(g, 10, 19.5, 20, 19.5);
                break;
            case "fill":
                poly(g, true, false, 4, 13, 11, 6, 18, 13, 11, 20);
                line(g, 11, 6, 8, 3);
                dot(g, 20, 17, 2.2);
                break;
            case "nofill":
                poly(g, true, false, 4, 13, 11, 6, 18, 13, 11, 20);
                line(g, 11, 6, 8, 3);
                line(g, 3.5, 3.5, 20.5, 20.5);
                break;
            case "function":
                text(g, "fx", Font.BOLD | Font.ITALIC, 17f, true);
                break;
            case "zoomin":
            case "zoomout":
                g.draw(new Ellipse2D.Double(3.5, 3.5, 12, 12));
                line(g, 14, 14, 20.5, 20.5);
                line(g, 6.5, 9.5, 12.5, 9.5);
                if (name.equals("zoomin")) line(g, 9.5, 6.5, 9.5, 12.5);
                break;
            case "zoomreset":
                g.draw(new Ellipse2D.Double(3.5, 3.5, 12, 12));
                line(g, 14, 14, 20.5, 20.5);
                text(g, "1", Font.BOLD, 9f, false);
                break;
            case "columns":
                box(g, 3, 5, 18, 14, 2, false);
                line(g, 9, 5, 9, 19);
                line(g, 15, 5, 15, 19);
                break;
            case "help":
                g.draw(new Ellipse2D.Double(3, 3, 18, 18));
                text(g, "?", Font.BOLD, 15f, false);
                break;
            case "trash":
                line(g, 4, 7, 20, 7);
                poly(g, false, false, 9, 7, 9, 4, 15, 4, 15, 7);
                poly(g, false, false, 6, 7, 7, 20, 17, 20, 18, 7);
                line(g, 10, 11, 10, 16);
                line(g, 14, 11, 14, 16);
                break;
            case "cursor":
                poly(g, true, true, 6, 3.5, 6, 19, 10.2, 15, 13, 21, 15.5, 19.8, 12.7, 14, 18.5, 14);
                break;
            case "pen":
                poly(g, true, false, 4, 20, 5.2, 15, 16, 4.2, 19.8, 8, 9, 18.8);
                line(g, 13, 7.2, 16.8, 11);
                break;
            case "highlighter":
                poly(g, true, false, 7, 14, 15, 5.5, 19.5, 10, 11, 18);
                poly(g, true, true, 7, 14, 11, 18, 7, 19, 4, 19.5);
                line(g, 13, 20.5, 21, 20.5);
                break;
            case "line":
                line(g, 5, 19, 19, 5);
                dot(g, 5, 19, 2);
                dot(g, 19, 5, 2);
                break;
            case "rect":
                box(g, 4, 6, 16, 12, 2, false);
                break;
            case "ellipse":
                g.draw(new Ellipse2D.Double(3, 5.5, 18, 13));
                break;
            case "eraser":
                poly(g, true, false, 3.5, 15, 11, 6.5, 19, 12, 12.5, 19.5);
                line(g, 7.2, 10.8, 15, 16.2);
                line(g, 8, 21, 21, 21);
                break;
            case "undo":
            case "redo": {
                Graphics2D h = (Graphics2D) g.create();
                if (name.equals("redo")) {
                    h.translate(24, 0);
                    h.scale(-1, 1);
                }
                Path2D.Double p = new Path2D.Double();
                p.moveTo(8, 8);
                p.lineTo(14, 8);
                p.quadTo(20, 8, 20, 13);
                p.quadTo(20, 18, 14, 18);
                p.lineTo(8, 18);
                h.draw(p);
                poly(h, false, false, 12, 3.8, 7.8, 8, 12, 12.2);
                h.dispose();
                break;
            }
            case "palette":
                g.draw(new Ellipse2D.Double(3, 3, 18, 18));
                dot(g, 8, 10, 1.6);
                dot(g, 12, 7, 1.6);
                dot(g, 16, 10, 1.6);
                dot(g, 8.5, 15, 1.6);
                break;
            case "textcolor":
                text(g, "A", Font.BOLD, 17f, false);
                g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
                line(g, 5, 21.5, 19, 21.5);
                break;
            case "percent":
                text(g, "%", Font.BOLD, 17f, false);
                break;
            case "comma":
                text(g, ",", Font.BOLD, 26f, false);
                break;
            case "decup":
                textAt(g, ".00", Font.BOLD, 11f, 3, 19);
                line(g, 4, 8, 18, 8);
                poly(g, false, false, 14.5, 4.8, 18.5, 8, 14.5, 11.2);
                break;
            case "decdown":
                textAt(g, ".0", Font.BOLD, 11f, 3, 19);
                line(g, 6, 8, 20, 8);
                poly(g, false, false, 9.5, 4.8, 5.5, 8, 9.5, 11.2);
                break;
            case "insertrow":
                box(g, 3, 3, 18, 8, 2, false);
                line(g, 12, 14.5, 12, 20.5);
                line(g, 9, 17.5, 15, 17.5);
                break;
            case "deleterow":
                box(g, 3, 3, 18, 8, 2, false);
                line(g, 9, 14.5, 15, 20.5);
                line(g, 15, 14.5, 9, 20.5);
                break;
            case "insertcol":
                box(g, 3, 3, 8, 18, 2, false);
                line(g, 17.5, 9, 17.5, 15);
                line(g, 14.5, 12, 20.5, 12);
                break;
            case "deletecol":
                box(g, 3, 3, 8, 18, 2, false);
                line(g, 14.5, 9, 20.5, 15);
                line(g, 20.5, 9, 14.5, 15);
                break;
            case "sort":
                line(g, 4, 6, 13, 6);
                line(g, 4, 11, 10, 11);
                line(g, 4, 16, 7, 16);
                arrowDown(g, 18, 4, 19);
                break;
            case "sortaz":
                textAt(g, "A", Font.BOLD, 10f, 2.5f, 10);
                textAt(g, "Z", Font.BOLD, 10f, 2.5f, 21);
                arrowDown(g, 18, 3.5, 20);
                break;
            case "sortza":
                textAt(g, "Z", Font.BOLD, 10f, 2.5f, 10);
                textAt(g, "A", Font.BOLD, 10f, 2.5f, 21);
                arrowDown(g, 18, 3.5, 20);
                break;
            case "filter":
                funnel(g);
                break;
            case "clearfilter":
                funnel(g);
                line(g, 16, 15, 21, 20);
                line(g, 21, 15, 16, 20);
                break;
            case "styles":
                box(g, 3, 5, 18, 14, 4, false);
                textAt(g, "Aa", Font.BOLD, 10f, 5.5f, 15.5f);
                break;
            case "condfmt":
                box(g, 4, 4, 16, 16, 2, false);
                line(g, 12, 4, 12, 20);
                line(g, 4, 12, 20, 12);
                box(g, 12, 12, 8, 8, 1, true);
                break;
            case "pivot":
                box(g, 3, 4, 18, 16, 2, false);
                line(g, 3, 10, 21, 10);
                line(g, 9, 4, 9, 20);
                box(g, 3, 4, 6, 6, 1, true);
                break;
            case "summary":
                line(g, 9, 7, 20, 7);
                line(g, 9, 12, 20, 12);
                line(g, 9, 17, 20, 17);
                dot(g, 5, 7, 1.4);
                dot(g, 5, 12, 1.4);
                dot(g, 5, 17, 1.4);
                break;
            case "calc":
                box(g, 5, 3, 14, 18, 3, false);
                box(g, 8, 6, 8, 3.5, 1, true);
                for (int ix = 0; ix < 3; ix++) {
                    dot(g, 9 + ix * 3, 13.5, 1.0);
                    dot(g, 9 + ix * 3, 17.5, 1.0);
                }
                break;
            case "showformula":
                text(g, "=", Font.BOLD, 24f, false);
                break;
            case "info":
            case "about":
                g.draw(new Ellipse2D.Double(3, 3, 18, 18));
                text(g, "i", Font.BOLD, 15f, true);
                break;
            case "inspect":
                g.draw(new Ellipse2D.Double(3.5, 3.5, 12, 12));
                line(g, 14, 14, 20.5, 20.5);
                dot(g, 9.5, 9.5, 1.6);
                break;
            case "dedupe":
                box(g, 3.5, 3.5, 11, 11, 2, false);
                box(g, 9.5, 9.5, 11, 11, 2, false);
                break;
            case "split":
                box(g, 3, 7, 18, 10, 2, false);
                line(g, 12, 7, 12, 17);
                break;
            case "csvin":
                arrowDown(g, 12, 3, 14);
                poly(g, false, false, 4, 15, 4, 20, 20, 20, 20, 15);
                break;
            case "csvout": {
                Graphics2D h = (Graphics2D) g.create();
                h.translate(0, 24);
                h.scale(1, -1);
                arrowDown(h, 12, 8, 19);
                h.dispose();
                poly(g, false, false, 4, 15, 4, 20, 20, 20, 20, 15);
                break;
            }
            case "validation":
                box(g, 3, 4, 18, 6, 2, false);
                poly(g, false, false, 14.5, 6.2, 16.5, 8.2, 18.5, 6.2);
                poly(g, false, false, 4, 15, 8, 19, 15, 12);
                line(g, 17, 16, 21, 16);
                line(g, 17, 19, 21, 19);
                break;
            case "goalseek":
                g.draw(new java.awt.geom.Ellipse2D.Double(3, 3, 18, 18));
                g.draw(new java.awt.geom.Ellipse2D.Double(7, 7, 10, 10));
                dot(g, 12, 12, 1.8);
                line(g, 12, 1, 12, 5);
                line(g, 12, 19, 12, 23);
                line(g, 1, 12, 5, 12);
                line(g, 19, 12, 23, 12);
                break;
            case "sparkline":
                line(g, 3, 20, 21, 20);
                poly(g, false, false, 3, 15, 8, 9, 12, 13, 16, 6, 21, 10);
                dot(g, 21, 10, 1.6);
                break;
            case "sheetsize":
                box(g, 3, 3, 14, 18, 2, false);
                line(g, 3, 9, 17, 9);
                line(g, 3, 15, 17, 15);
                line(g, 10, 3, 10, 21);
                arrowDown(g, 20, 4, 20.5);
                break;
            case "circle":
                g.draw(new java.awt.geom.Ellipse2D.Double(2.5, 6, 19, 12));
                line(g, 6, 12, 18, 12);
                break;
            case "customsort":
                line(g, 4, 6, 14, 6);
                line(g, 4, 12, 11, 12);
                line(g, 4, 18, 8, 18);
                arrowDown(g, 18, 5, 19);
                break;
            case "keyboard":
                box(g, 2, 6, 20, 12, 3, false);
                for (int ix = 0; ix < 4; ix++) dot(g, 6 + ix * 4, 10, 0.9);
                line(g, 8, 14.5, 16, 14.5);
                break;
            case "autosave":
                poly(g, true, false, 5, 4, 16, 4, 20, 8, 20, 20, 5, 20);
                box(g, 8, 13, 8, 7, 1, false);
                line(g, 8, 4, 8, 8.5);
                break;
            case "print":
                poly(g, true, false, 7, 9, 7, 3.5, 17, 3.5, 17, 9);
                box(g, 3, 9, 18, 8, 2, false);
                box(g, 7, 14, 10, 6.5, 0.5, false);
                dot(g, 18, 11.5, 0.9);
                break;
            case "preview":
                box(g, 5, 2.5, 12, 17, 1.5, false);
                line(g, 8, 7, 14, 7);
                line(g, 8, 10.5, 14, 10.5);
                g.draw(new java.awt.geom.Ellipse2D.Double(12, 13, 7, 7));
                line(g, 17.5, 18.5, 21.5, 22);
                break;
            case "pagesetup":
                box(g, 4, 2.5, 13, 17, 1.5, false);
                line(g, 7, 7, 14, 7);
                line(g, 7, 10.5, 14, 10.5);
                line(g, 7, 14, 11, 14);
                line(g, 17, 14, 21, 14);
                line(g, 19, 11, 19, 17);
                break;
            case "printarea":
                g.setStroke(new java.awt.BasicStroke(1.6f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND,
                        10f, new float[]{2.6f, 2.4f}, 0f));
                box(g, 3.5, 4.5, 17, 15, 1, false);
                g.setStroke(new java.awt.BasicStroke(1.6f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                line(g, 7.5, 9.5, 16.5, 9.5);
                line(g, 7.5, 14.5, 16.5, 14.5);
                line(g, 12, 7, 12, 17);
                break;
            case "history":
                g.draw(new Ellipse2D.Double(3.5, 3.5, 17, 17));
                poly(g, false, false, 12, 7, 12, 12, 16, 14.5);
                poly(g, false, false, 2, 8.5, 3.8, 11.5, 6.8, 9.8);
                break;
            case "readonly":
                box(g, 4, 10, 16, 11, 2, false);
                poly(g, false, false, 7.5, 10, 7.5, 6.5, 9, 4, 12, 3, 15, 4, 16.5, 6.5, 16.5, 10);
                line(g, 8.5, 15.5, 15.5, 15.5);
                break;
            case "recover":
                box(g, 4, 4, 16, 16, 2, false);
                poly(g, false, false, 8, 12, 12, 8, 16, 12);
                line(g, 12, 8, 12, 17);
                break;
            case "cmdpalette":
                box(g, 3, 4, 18, 16, 3, false);
                line(g, 6.5, 9, 6.5, 9.01);
                poly(g, false, false, 8, 9.5, 11, 12, 8, 14.5);
                line(g, 13, 15, 17.5, 15);
                break;
            case "snapshot":
                box(g, 3, 7, 18, 13, 2, false);
                poly(g, false, false, 8, 7, 9.5, 4, 14.5, 4, 16, 7);
                g.draw(new Ellipse2D.Double(8, 10, 8, 7.5));
                break;
            default:
                g.draw(new Ellipse2D.Double(4, 4, 16, 16));
        }
    }
}

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Draws a worksheet's charts above the grid and makes them interactive, like {@link ImageLayer}:
 * <ul>
 * <li>hover a bar / point / slice: it lights up and a tooltip shows the exact value</li>
 * <li>click a legend entry: hide or show that series</li>
 * <li>click a chart to select it (its source cells get a dashed outline), drag to move,
 *     drag the corner square to resize, Delete removes it, double-click edits it, right-click for a menu</li>
 * <li>charts read the sheet live, so editing a cell updates the chart immediately</li>
 * </ul>
 */
public class ChartLayer {

    public interface Host {
        void changed();

        void edit(ChartSpec chart);

        void menu(ChartSpec chart, MouseEvent e);
    }

    private static final Color GREEN = new Color(0x107C41);
    private static final int HANDLE = 10;
    private static final int MIN_W = 120;
    private static final int MIN_H = 90;
    private static final int NONE = 0;
    private static final int MOVE = 1;
    private static final int RESIZE = 2;

    private final JTable table;
    private final SpreadsheetModel model;
    private final Host host;
    private final Map<ChartSpec, List<ChartPainter.Hit>> hits = new IdentityHashMap<>();

    private ChartSpec selected;
    private ChartSpec hoverChart;
    private ChartPainter.Hit hoverHit;
    private Point mouse = new Point();
    private int mode = NONE;
    private int grabDx;
    private int grabDy;
    private int pressX;
    private int pressY;
    private int startW;
    private int startH;
    private boolean moved;

    public ChartLayer(JTable table, SpreadsheetModel model, Host host) {
        this.table = table;
        this.model = model;
        this.host = host;
    }

    private double zoom() {
        return model.getZoomPercent() / 100.0;
    }

    private Rectangle screen(ChartSpec c) {
        double z = zoom();
        return new Rectangle((int) Math.round(c.x * z), (int) Math.round(c.y * z),
                Math.max(1, (int) Math.round(c.width * z)), Math.max(1, (int) Math.round(c.height * z)));
    }

    // ------------------------------------------------------------------
    // Selection
    // ------------------------------------------------------------------

    public void select(ChartSpec c) {
        selected = c;
        table.repaint();
    }

    public ChartSpec selected() {
        return hasSelection() ? selected : null;
    }

    public boolean hasSelection() {
        return selected != null && model.getCharts().contains(selected);
    }

    public boolean deleteSelected() {
        if (!hasSelection() || model.isProtected()) return false;
        hits.remove(selected);
        model.removeChart(selected);
        selected = null;
        hoverChart = null;
        hoverHit = null;
        mode = NONE;
        table.setCursor(Cursor.getDefaultCursor());
        table.repaint();
        host.changed();
        return true;
    }

    /** Plays the grow-in animation for a new chart. */
    public void animate(ChartSpec c) {
        c.anim = 0;
        final long start = System.currentTimeMillis();
        Timer t = new Timer(16, null);
        t.addActionListener(e -> {
            double p = Math.min(1.0, (System.currentTimeMillis() - start) / 550.0);
            c.anim = 1 - Math.pow(1 - p, 3);
            table.repaint(screen(c));
            if (p >= 1.0) {
                c.anim = 1.0;
                t.stop();
            }
        });
        t.start();
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    public void paint(Graphics g0) {
        List<ChartSpec> list = model.getCharts();
        if (list.isEmpty()) return;
        double z = zoom();
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (ChartSpec c : list) {
                Rectangle r = screen(c);
                if (!r.intersects(g.getClipBounds() == null ? r : g.getClipBounds())) continue;
                List<ChartPainter.Hit> h = new ArrayList<>();
                Graphics2D gc = (Graphics2D) g.create(r.x, r.y, r.width, r.height);
                try {
                    gc.scale(z, z);
                    int hs = c == hoverChart && hoverHit != null ? hoverHit.series : -1;
                    int hp = c == hoverChart && hoverHit != null ? hoverHit.point : -1;
                    if (hoverHit != null && hoverHit.legend) {
                        hs = -1;
                        hp = -1;
                    }
                    ChartPainter.paint(gc, c, ChartData.read(model, c), c.width, c.height, c.anim, hs, hp, h);
                } finally {
                    gc.dispose();
                }
                hits.put(c, h);
            }
            if (hasSelection() && !model.isProtected()) {
                // dashed outline around the cells the chart is drawn from
                Rectangle a = table.getCellRect(selected.r1, selected.c1, false);
                Rectangle b = table.getCellRect(selected.r2, selected.c2, false);
                Rectangle src = a.union(b);
                g.setColor(new Color(0x1E88E5));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f));
                g.drawRect(src.x, src.y, src.width - 1, src.height - 1);

                Rectangle r = screen(selected);
                g.setColor(GREEN);
                g.setStroke(new BasicStroke(2f));
                g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
                Rectangle h = handle(r);
                g.setColor(Color.WHITE);
                g.fillRect(h.x, h.y, h.width, h.height);
                g.setColor(GREEN);
                g.drawRect(h.x, h.y, h.width, h.height);
            }
            if (hoverChart != null && hoverHit != null && !hoverHit.legend && mode == NONE) paintTooltip(g, hoverHit.tip);
        } finally {
            g.dispose();
        }
    }

    private void paintTooltip(Graphics2D g, String tip) {
        String[] lines = tip.split("\n");
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        FontMetrics fm = g.getFontMetrics();
        int w = 0;
        for (String l : lines) w = Math.max(w, fm.stringWidth(l));
        w += 16;
        int h = lines.length * fm.getHeight() + 10;
        int x = mouse.x + 14;
        int y = mouse.y + 16;
        Rectangle vis = table.getVisibleRect();
        if (x + w > vis.x + vis.width) x = Math.max(vis.x, mouse.x - w - 8);
        if (y + h > vis.y + vis.height) y = Math.max(vis.y, mouse.y - h - 8);
        g.setColor(new Color(0x2B2B2B));
        g.fillRoundRect(x, y, w, h, 8, 8);
        g.setColor(Color.WHITE);
        for (int i = 0; i < lines.length; i++) {
            g.setFont(new Font(Font.SANS_SERIF, i == 0 ? Font.BOLD : Font.PLAIN, 12));
            g.drawString(lines[i], x + 8, y + 5 + fm.getAscent() + i * fm.getHeight());
        }
    }

    // ------------------------------------------------------------------
    // Mouse (return true = consumed)
    // ------------------------------------------------------------------

    private ChartSpec chartAt(Point p) {
        List<ChartSpec> list = model.getCharts();
        for (int i = list.size() - 1; i >= 0; i--) if (screen(list.get(i)).contains(p)) return list.get(i);
        return null;
    }

    private ChartPainter.Hit hitAt(ChartSpec c, Point p) {
        List<ChartPainter.Hit> h = hits.get(c);
        if (h == null) return null;
        double z = zoom();
        Rectangle r = screen(c);
        double lx = (p.x - r.x) / z;
        double ly = (p.y - r.y) / z;
        for (int i = h.size() - 1; i >= 0; i--) {
            if (h.get(i).shape.contains(lx, ly)) return h.get(i);
        }
        return null;
    }

    public boolean handleMouse(MouseEvent e) {
        switch (e.getID()) {
            case MouseEvent.MOUSE_PRESSED: {
                ChartSpec c = chartAt(e.getPoint());
                if (c == null) {
                    if (selected != null) {
                        selected = null;
                        table.repaint();
                    }
                    return false;
                }
                table.requestFocusInWindow();
                if (e.isPopupTrigger()) {
                    selected = c;
                    table.repaint();
                    if (!model.isProtected()) host.menu(c, e);
                    return true;
                }
                if (!SwingUtilities.isLeftMouseButton(e)) return true;
                ChartPainter.Hit h = hitAt(c, e.getPoint());
                if (h != null && h.legend) {
                    int idx = h.series >= 0 ? h.series : h.point;
                    if (!c.hidden.remove(idx)) c.hidden.add(idx);
                    selected = c;
                    table.repaint();
                    return true;
                }
                Rectangle r = screen(c);
                boolean onHandle = c == selected && handleHit(r, e.getPoint()) && !model.isProtected();
                selected = c;
                if (!model.isProtected()) {
                    mode = onHandle ? RESIZE : MOVE;
                    grabDx = e.getX() - r.x;
                    grabDy = e.getY() - r.y;
                    pressX = e.getX();
                    pressY = e.getY();
                    startW = c.width;
                    startH = c.height;
                    moved = false;
                }
                table.repaint();
                return true;
            }
            case MouseEvent.MOUSE_RELEASED: {
                if (mode != NONE) {
                    mode = NONE;
                    if (moved) host.changed();
                    table.repaint();
                    return true;
                }
                ChartSpec c = chartAt(e.getPoint());
                if (c != null && e.isPopupTrigger()) {
                    selected = c;
                    table.repaint();
                    if (!model.isProtected()) host.menu(c, e);
                    return true;
                }
                return c != null;
            }
            case MouseEvent.MOUSE_CLICKED: {
                ChartSpec c = chartAt(e.getPoint());
                if (c == null) return false;
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && !model.isProtected()) {
                    ChartPainter.Hit h = hitAt(c, e.getPoint());
                    if (h == null || !h.legend) host.edit(c);
                }
                return true;
            }
            case MouseEvent.MOUSE_EXITED:
                if (hoverChart != null) {
                    hoverChart = null;
                    hoverHit = null;
                    table.repaint();
                }
                return false;
            default:
                return false;
        }
    }

    public boolean handleMotion(MouseEvent e) {
        double z = zoom();
        mouse = e.getPoint();
        if (e.getID() == MouseEvent.MOUSE_DRAGGED && mode != NONE && selected != null) {
            moved = true;
            if (mode == MOVE) {
                selected.x = Math.max(0, (int) Math.round((e.getX() - grabDx) / z));
                selected.y = Math.max(0, (int) Math.round((e.getY() - grabDy) / z));
            } else {
                selected.width = Math.max(MIN_W, startW + (int) Math.round((e.getX() - pressX) / z));
                selected.height = Math.max(MIN_H, startH + (int) Math.round((e.getY() - pressY) / z));
            }
            table.repaint();
            return true;
        }
        if (e.getID() == MouseEvent.MOUSE_MOVED) {
            ChartSpec c = chartAt(e.getPoint());
            ChartPainter.Hit h = c == null ? null : hitAt(c, e.getPoint());
            boolean changed = c != hoverChart || h == null != (hoverHit == null)
                    || (h != null && hoverHit != null && (h.series != hoverHit.series || h.point != hoverHit.point
                    || h.legend != hoverHit.legend));
            hoverChart = c;
            hoverHit = h;
            if (c == null) {
                if (changed) table.repaint();
                return false; // let the image layer / cells handle the pointer
            }
            if (h != null && h.legend) {
                table.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            } else if (c == selected && handleHit(screen(c), e.getPoint()) && !model.isProtected()) {
                table.setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
            } else {
                table.setCursor(Cursor.getPredefinedCursor(h != null ? Cursor.HAND_CURSOR : Cursor.MOVE_CURSOR));
            }
            if (changed || h != null) table.repaint();
            return true;
        }
        return mode != NONE;
    }

    private static Rectangle handle(Rectangle r) {
        return new Rectangle(r.x + r.width - HANDLE, r.y + r.height - HANDLE, HANDLE, HANDLE);
    }

    private static boolean handleHit(Rectangle r, Point p) {
        Rectangle h = handle(r);
        h.grow(3, 3);
        return h.contains(p);
    }
}

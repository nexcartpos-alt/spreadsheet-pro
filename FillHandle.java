import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

/**
 * The small square in the bottom-right corner of the selection. Drag it down, up, left or right
 * to copy or continue the selected cells (see {@link FillSeries}); double-click it to fill down as
 * far as the neighbouring column goes.
 */
public class FillHandle {

    public interface Host {
        /** @param undo puts the overwritten cells back */
        void filled(int cells, Runnable undo);

        void blocked();
    }

    private static final Color GREEN = new Color(0x107C41);
    private static final int SIZE = 8;

    private final JTable table;
    private final SpreadsheetModel model;
    private final Host host;

    private boolean dragging;
    private int[] src;          // {r1, c1, r2, c2} of the selection being dragged
    private FillSeries.Dir dir; // direction of the current preview (null = nothing to fill)
    private int count;

    public FillHandle(JTable table, SpreadsheetModel model, Host host) {
        this.table = table;
        this.model = model;
        this.host = host;
        javax.swing.event.ListSelectionListener l = e -> table.repaint();
        table.getSelectionModel().addListSelectionListener(l);
        table.getColumnModel().getSelectionModel().addListSelectionListener(l);
    }

    /** {r1, c1, r2, c2} when the selection is one solid block, else null. */
    private int[] selection() {
        int[] rows = table.getSelectedRows();
        int[] cols = table.getSelectedColumns();
        if (rows.length == 0 || cols.length == 0) return null;
        if (rows[rows.length - 1] - rows[0] + 1 != rows.length) return null;
        if (cols[cols.length - 1] - cols[0] + 1 != cols.length) return null;
        return new int[]{rows[0], cols[0], rows[rows.length - 1], cols[cols.length - 1]};
    }

    private Rectangle handleRect(int[] s) {
        Rectangle br = table.getCellRect(s[2], s[3], false);
        return new Rectangle(br.x + br.width - SIZE / 2 - 1, br.y + br.height - SIZE / 2 - 1, SIZE, SIZE);
    }

    private boolean available() {
        return !model.isProtected() && !table.isEditing();
    }

    // ------------------------------------------------------------------

    public void paint(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            if (dragging && src != null && dir != null && count > 0) {
                Rectangle r = extended();
                g.setColor(new Color(0x107C41));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{5f, 3f}, 0f));
                g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
                return;
            }
            int[] s = dragging ? src : selection();
            if (s == null || !available()) return;
            Rectangle h = handleRect(s);
            g.setColor(Color.WHITE);
            g.fillRect(h.x - 1, h.y - 1, h.width + 2, h.height + 2);
            g.setColor(GREEN);
            g.fillRect(h.x, h.y, h.width, h.height);
        } finally {
            g.dispose();
        }
    }

    /** The block including the cells that would be filled. */
    private Rectangle extended() {
        int r1 = src[0];
        int c1 = src[1];
        int r2 = src[2];
        int c2 = src[3];
        switch (dir) {
            case DOWN:
                r2 += count;
                break;
            case UP:
                r1 -= count;
                break;
            case RIGHT:
                c2 += count;
                break;
            default:
                c1 -= count;
        }
        r1 = Math.max(0, r1);
        c1 = Math.max(0, c1);
        r2 = Math.min(model.getRowCount() - 1, r2);
        c2 = Math.min(model.getColumnCount() - 1, c2);
        return table.getCellRect(r1, c1, false).union(table.getCellRect(r2, c2, false));
    }

    // ------------------------------------------------------------------

    private boolean onHandle(Point p) {
        int[] s = selection();
        if (s == null || !available()) return false;
        Rectangle h = handleRect(s);
        h.grow(3, 3);
        return h.contains(p);
    }

    public boolean handleMouse(MouseEvent e) {
        switch (e.getID()) {
            case MouseEvent.MOUSE_PRESSED:
                if (SwingUtilities.isLeftMouseButton(e) && onHandle(e.getPoint())) {
                    src = selection();
                    dragging = true;
                    dir = null;
                    count = 0;
                    table.requestFocusInWindow();
                    return true;
                }
                return false;
            case MouseEvent.MOUSE_RELEASED:
                if (dragging) {
                    dragging = false;
                    table.setCursor(Cursor.getDefaultCursor());
                    if (dir != null && count > 0) apply(src, dir, count);
                    table.repaint();
                    return true;
                }
                return false;
            case MouseEvent.MOUSE_CLICKED:
                if (e.getClickCount() == 2 && onHandle(e.getPoint())) {
                    doubleClickFill();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    public boolean handleMotion(MouseEvent e) {
        if (dragging && e.getID() == MouseEvent.MOUSE_DRAGGED) {
            int row = table.rowAtPoint(new Point(Math.max(0, Math.min(e.getX(), table.getWidth() - 1)),
                    Math.max(0, Math.min(e.getY(), table.getHeight() - 1))));
            int col = table.columnAtPoint(new Point(Math.max(0, Math.min(e.getX(), table.getWidth() - 1)),
                    Math.max(0, Math.min(e.getY(), table.getHeight() - 1))));
            if (row < 0) row = e.getY() < 0 ? 0 : model.getRowCount() - 1;
            if (col < 0) col = e.getX() < 0 ? 0 : model.getColumnCount() - 1;
            int down = Math.max(0, row - src[2]);
            int up = Math.max(0, src[0] - row);
            int right = Math.max(0, col - src[3]);
            int left = Math.max(0, src[1] - col);
            int vert = Math.max(down, up);
            int horiz = Math.max(right, left);
            if (vert == 0 && horiz == 0) {
                dir = null;
                count = 0;
            } else if (vert >= horiz) {
                dir = down >= up ? FillSeries.Dir.DOWN : FillSeries.Dir.UP;
                count = vert;
            } else {
                dir = right >= left ? FillSeries.Dir.RIGHT : FillSeries.Dir.LEFT;
                count = horiz;
            }
            table.scrollRectToVisible(new Rectangle(e.getX(), e.getY(), 1, 1));
            table.repaint();
            return true;
        }
        if (e.getID() == MouseEvent.MOUSE_MOVED) {
            if (onHandle(e.getPoint())) {
                table.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                return true;
            }
            return false;
        }
        return dragging;
    }

    // ------------------------------------------------------------------

    private void apply(int[] s, FillSeries.Dir d, int n) {
        if (model.isProtected()) {
            host.blocked();
            return;
        }
        // limit to the sheet
        int maxCount;
        switch (d) {
            case DOWN:
                maxCount = model.getRowCount() - 1 - s[2];
                break;
            case UP:
                maxCount = s[0];
                break;
            case RIGHT:
                maxCount = model.getColumnCount() - 1 - s[3];
                break;
            default:
                maxCount = s[1];
        }
        n = Math.min(n, maxCount);
        if (n <= 0) return;
        int tr1 = s[0];
        int tc1 = s[1];
        int tr2 = s[2];
        int tc2 = s[3];
        switch (d) {
            case DOWN:
                tr1 = s[2] + 1;
                tr2 = s[2] + n;
                break;
            case UP:
                tr1 = s[0] - n;
                tr2 = s[0] - 1;
                break;
            case RIGHT:
                tc1 = s[3] + 1;
                tc2 = s[3] + n;
                break;
            default:
                tc1 = s[1] - n;
                tc2 = s[1] - 1;
        }
        Runnable undo = FillSeries.snapshot(model, tr1, tc1, tr2, tc2);
        int cells = FillSeries.fill(model, s[0], s[1], s[2], s[3], d, n);
        // selection grows to cover the new cells
        int nr1 = Math.min(s[0], tr1);
        int nc1 = Math.min(s[1], tc1);
        int nr2 = Math.max(s[2], tr2);
        int nc2 = Math.max(s[3], tc2);
        table.setRowSelectionInterval(nr1, nr2);
        table.setColumnSelectionInterval(nc1, nc2);
        host.filled(cells, undo);
    }

    /** Double-click: fill down as far as the column next to the selection has data. */
    private void doubleClickFill() {
        int[] s = selection();
        if (s == null) return;
        int side = s[1] > 0 ? s[1] - 1 : s[3] + 1;
        if (side >= model.getColumnCount()) return;
        int r = s[2];
        while (r + 1 < model.getRowCount() && !model.getInput(r + 1, side).isEmpty()) r++;
        if (r > s[2]) apply(s, FillSeries.Dir.DOWN, r - s[2]);
    }
}

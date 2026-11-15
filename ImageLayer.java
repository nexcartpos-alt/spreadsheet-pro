import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

/**
 * Draws a worksheet's pictures on top of its JTable and lets the user select,
 * drag and resize them. The table subclass forwards paint and mouse events here;
 * when a handler returns true the table does not process the event itself.
 *
 * Pictures are stored in 100%-zoom coordinates and scaled by the sheet's zoom
 * when painted and hit-tested, so they follow the zoom level.
 *
 * Click a picture to select it, drag it to move it, drag the square handle in its
 * bottom-right corner to resize (hold Shift to stretch freely), press Delete to remove it.
 * Pictures on a protected sheet are inert.
 */
public class ImageLayer {

    private static final Color GREEN = new Color(0x107C41);
    private static final int HANDLE = 10;   // screen pixels
    private static final int MIN_SIZE = 16; // 100%-zoom pixels
    private static final int MODE_NONE = 0;
    private static final int MODE_MOVE = 1;
    private static final int MODE_RESIZE = 2;

    private final JTable table;
    private final SpreadsheetModel model;
    private final Runnable onChanged;

    private SheetImage selected;
    private int mode = MODE_NONE;
    private int grabDx;   // screen pixels from the picture's corner to the mouse
    private int grabDy;
    private int pressX;   // screen coordinates of the press
    private int pressY;
    private int startW;   // 100%-zoom size at the press
    private int startH;

    public ImageLayer(JTable table, SpreadsheetModel model, Runnable onChanged) {
        this.table = table;
        this.model = model;
        this.onChanged = onChanged;
    }

    private double zoom() {
        return model.getZoomPercent() / 100.0;
    }

    /** Where a picture currently appears on screen. */
    private Rectangle screen(SheetImage im) {
        double z = zoom();
        return new Rectangle((int) Math.round(im.x * z), (int) Math.round(im.y * z),
                Math.max(1, (int) Math.round(im.width * z)), Math.max(1, (int) Math.round(im.height * z)));
    }

    // ------------------------------------------------------------------
    // Selection
    // ------------------------------------------------------------------

    public void select(SheetImage image) {
        selected = image;
        table.repaint();
    }

    public boolean hasSelection() {
        return selected != null && model.getImages().contains(selected);
    }

    /** Removes the selected picture. @return true if one was removed. */
    public boolean deleteSelected() {
        if (!hasSelection() || model.isProtected()) return false;
        model.removeImage(selected);
        selected = null;
        mode = MODE_NONE;
        table.setCursor(Cursor.getDefaultCursor());
        table.repaint();
        onChanged.run();
        return true;
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    public void paint(Graphics g0) {
        List<SheetImage> list = model.getImages();
        if (list.isEmpty()) return;
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            for (SheetImage im : list) {
                Rectangle r = screen(im);
                g.drawImage(im.getImage(), r.x, r.y, r.width, r.height, null);
                g.setColor(new Color(0, 0, 0, 50));
                g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
            }
            if (hasSelection() && !model.isProtected()) {
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
        } finally {
            g.dispose();
        }
    }

    // ------------------------------------------------------------------
    // Mouse handling (return true = event consumed)
    // ------------------------------------------------------------------

    public boolean handleMouse(MouseEvent e) {
        if (model.isProtected()) {
            if (selected != null) {
                selected = null;
                table.repaint();
            }
            return false;
        }
        switch (e.getID()) {
            case MouseEvent.MOUSE_PRESSED: {
                if (!SwingUtilities.isLeftMouseButton(e)) return false;
                SheetImage hit = hit(e.getPoint());
                if (hit == null) {
                    if (selected != null) {
                        selected = null;
                        table.repaint();
                    }
                    return false; // normal cell selection
                }
                table.requestFocusInWindow();
                Rectangle r = screen(hit);
                boolean onHandle = hit == selected && handleHit(r, e.getPoint());
                selected = hit;
                mode = onHandle ? MODE_RESIZE : MODE_MOVE;
                grabDx = e.getX() - r.x;
                grabDy = e.getY() - r.y;
                pressX = e.getX();
                pressY = e.getY();
                startW = hit.width;
                startH = hit.height;
                table.repaint();
                return true;
            }
            case MouseEvent.MOUSE_RELEASED:
                if (mode != MODE_NONE) {
                    mode = MODE_NONE;
                    onChanged.run();
                    return true;
                }
                return false;
            case MouseEvent.MOUSE_CLICKED:
                return hit(e.getPoint()) != null; // no cell editing through a picture
            case MouseEvent.MOUSE_EXITED:
                table.setCursor(Cursor.getDefaultCursor());
                return false;
            default:
                return false;
        }
    }

    public boolean handleMotion(MouseEvent e) {
        if (model.isProtected()) return false;
        double z = zoom();
        if (e.getID() == MouseEvent.MOUSE_DRAGGED && mode != MODE_NONE && selected != null) {
            if (mode == MODE_MOVE) {
                int maxX = Math.max(0, (int) Math.round(table.getWidth() / z) - MIN_SIZE);
                int maxY = Math.max(0, (int) Math.round(table.getHeight() / z) - MIN_SIZE);
                selected.x = clamp((int) Math.round((e.getX() - grabDx) / z), 0, maxX);
                selected.y = clamp((int) Math.round((e.getY() - grabDy) / z), 0, maxY);
            } else {
                int w = Math.max(MIN_SIZE, startW + (int) Math.round((e.getX() - pressX) / z));
                int h = e.isShiftDown()
                        ? Math.max(MIN_SIZE, startH + (int) Math.round((e.getY() - pressY) / z))
                        : Math.max(MIN_SIZE, (int) Math.round(w * (double) startH / startW));
                selected.width = w;
                selected.height = h;
            }
            table.repaint();
            return true;
        }
        if (e.getID() == MouseEvent.MOUSE_MOVED) {
            SheetImage hit = hit(e.getPoint());
            if (hit == null) {
                table.setCursor(Cursor.getDefaultCursor());
            } else if (hit == selected && handleHit(screen(hit), e.getPoint())) {
                table.setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
            } else {
                table.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }
        }
        return mode != MODE_NONE;
    }

    // ------------------------------------------------------------------

    private SheetImage hit(Point p) {
        List<SheetImage> list = model.getImages();
        for (int i = list.size() - 1; i >= 0; i--) { // topmost first
            if (screen(list.get(i)).contains(p)) return list.get(i);
        }
        return null;
    }

    private static Rectangle handle(Rectangle r) {
        return new Rectangle(r.x + r.width - HANDLE, r.y + r.height - HANDLE, HANDLE, HANDLE);
    }

    private static boolean handleHit(Rectangle r, Point p) {
        Rectangle h = handle(r);
        h.grow(3, 3);
        return h.contains(p);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

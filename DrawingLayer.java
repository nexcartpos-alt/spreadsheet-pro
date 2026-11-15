import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.swing.JTable;

/**
 * Painting and drawing on top of a worksheet. Like {@link ImageLayer} it is driven by the
 * table: the table forwards paint and mouse events and skips its own handling when a
 * handler returns true. While a drawing tool is active the mouse draws instead of
 * selecting cells.
 *
 * Tools: pen, highlighter, line, rectangle, ellipse and an object eraser (removes the
 * whole stroke or shape you touch). Hold Shift for straight 45-degree lines and perfect
 * squares / circles. Every change can be undone and redone.
 */
public class DrawingLayer {

    public enum Tool { NONE, PEN, HIGHLIGHTER, LINE, RECT, ELLIPSE, ERASER }

    /** Tool options shared by every sheet (owned by the application window). */
    public static final class Settings {
        public Tool tool = Tool.NONE;
        public Color color = new Color(0xE53935);
        public float width = 3f;
        public boolean filled = false;
    }

    private static final int MAX_UNDO = 100;
    private static final float HIGHLIGHT_WIDTH_FACTOR = 4f;
    private static final int HIGHLIGHT_ALPHA = 105;

    private final JTable table;
    private final SpreadsheetModel model;
    private final Settings settings;
    private final Runnable onChanged;
    private final Runnable onBlocked;

    private final Deque<List<DrawShape>> undo = new ArrayDeque<>();
    private final Deque<List<DrawShape>> redo = new ArrayDeque<>();

    private boolean drawing;          // a stroke / shape is being dragged out
    private final List<Float> pen = new ArrayList<>();
    private float startX, startY, endX, endY;
    private boolean erasing;
    private boolean erasedAny;
    private List<DrawShape> beforeErase;
    private float hoverX = -1, hoverY = -1;
    private boolean hoverValid;

    public DrawingLayer(JTable table, SpreadsheetModel model, Settings settings,
                        Runnable onChanged, Runnable onBlocked) {
        this.table = table;
        this.model = model;
        this.settings = settings;
        this.onChanged = onChanged;
        this.onBlocked = onBlocked;
    }

    private double zoom() {
        return model.getZoomPercent() / 100.0;
    }

    public boolean isActive() {
        return settings.tool != Tool.NONE;
    }

    /** Crosshair while a drawing tool is on, the normal arrow otherwise. */
    public void updateCursor() {
        table.setCursor(isActive() ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) : Cursor.getDefaultCursor());
        drawing = false;
        erasing = false;
        pen.clear();
        hoverValid = false;
        table.repaint();
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    public void paint(Graphics g) {
        List<DrawShape> shapes = model.getShapes();
        DrawShape preview = drawing ? build() : null;
        boolean ring = isActive() && hoverValid && !drawing;
        if (shapes.isEmpty() && preview == null && !ring) return;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double z = zoom();
        g2.scale(z, z);
        for (DrawShape s : shapes) s.paint(g2);
        if (preview != null) preview.paint(g2);
        if (ring) paintBrushRing(g2, z);
        g2.dispose();
    }

    /** A small circle that follows the mouse and shows the brush / eraser size. */
    private void paintBrushRing(Graphics2D g2, double z) {
        float w = settings.tool == Tool.HIGHLIGHTER ? settings.width * HIGHLIGHT_WIDTH_FACTOR : settings.width;
        double d = settings.tool == Tool.ERASER ? 12 / z : w;
        if (settings.tool == Tool.LINE || settings.tool == Tool.RECT || settings.tool == Tool.ELLIPSE) d = w;
        g2.setStroke(new BasicStroke((float) (1.0 / z)));
        g2.setColor(settings.tool == Tool.ERASER ? new Color(0x777777) : new Color(0, 0, 0, 110));
        g2.draw(new Ellipse2D.Double(hoverX - d / 2, hoverY - d / 2, d, d));
    }

    // ------------------------------------------------------------------
    // Mouse handling (return true = event consumed)
    // ------------------------------------------------------------------

    public boolean handleMouse(MouseEvent e) {
        if (!isActive() || e.isPopupTrigger() || e.getButton() == MouseEvent.BUTTON3) return false;
        switch (e.getID()) {
            case MouseEvent.MOUSE_PRESSED:
                if (e.getButton() != MouseEvent.BUTTON1) return true;
                if (model.isProtected()) {
                    if (onBlocked != null) onBlocked.run();
                    return true;
                }
                press(e);
                return true;
            case MouseEvent.MOUSE_RELEASED:
                release(e);
                return true;
            case MouseEvent.MOUSE_CLICKED:
                return true;
            case MouseEvent.MOUSE_EXITED:
                hoverValid = false;
                table.repaint();
                return false;
            default:
                return false;
        }
    }

    public boolean handleMotion(MouseEvent e) {
        if (!isActive()) return false;
        float x = (float) (e.getX() / zoom());
        float y = (float) (e.getY() / zoom());
        hoverX = x;
        hoverY = y;
        hoverValid = true;
        if (e.getID() == MouseEvent.MOUSE_DRAGGED) {
            if (drawing) {
                if (settings.tool == Tool.PEN || settings.tool == Tool.HIGHLIGHTER) {
                    addPenPoint(x, y);
                } else {
                    endX = x;
                    endY = y;
                    constrain(e.isShiftDown());
                }
            } else if (erasing) {
                erase(x, y);
            }
        }
        table.repaint();
        return true;
    }

    private void press(MouseEvent e) {
        table.requestFocusInWindow();
        float x = (float) (e.getX() / zoom());
        float y = (float) (e.getY() / zoom());
        if (settings.tool == Tool.ERASER) {
            erasing = true;
            erasedAny = false;
            beforeErase = new ArrayList<>(model.getShapes());
            erase(x, y);
        } else {
            drawing = true;
            startX = endX = x;
            startY = endY = y;
            pen.clear();
            pen.add(x);
            pen.add(y);
        }
        hoverX = x;
        hoverY = y;
        hoverValid = true;
        table.repaint();
    }

    private void release(MouseEvent e) {
        if (erasing) {
            erasing = false;
            if (erasedAny) {
                pushUndo(beforeErase);
                onChanged.run();
            }
            beforeErase = null;
        } else if (drawing) {
            if (e != null && settings.tool != Tool.PEN && settings.tool != Tool.HIGHLIGHTER) {
                endX = (float) (e.getX() / zoom());
                endY = (float) (e.getY() / zoom());
                constrain(e.isShiftDown());
            }
            DrawShape s = build();
            drawing = false;
            pen.clear();
            if (s != null && !tooSmall(s)) {
                List<DrawShape> before = new ArrayList<>(model.getShapes());
                model.addShape(s);
                pushUndo(before);
                onChanged.run();
            }
        }
        table.repaint();
    }

    private void addPenPoint(float x, float y) {
        int n = pen.size();
        float dx = x - pen.get(n - 2);
        float dy = y - pen.get(n - 1);
        if (dx * dx + dy * dy >= 2.0f) { // ignore sub-pixel jitter
            pen.add(x);
            pen.add(y);
        }
    }

    /** Shift: lines snap to 45-degree steps, rectangles / ellipses become squares / circles. */
    private void constrain(boolean shift) {
        if (!shift) return;
        float dx = endX - startX;
        float dy = endY - startY;
        if (settings.tool == Tool.LINE) {
            double angle = Math.atan2(dy, dx);
            double snapped = Math.round(angle / (Math.PI / 4)) * (Math.PI / 4);
            double len = Math.hypot(dx, dy);
            endX = startX + (float) (Math.cos(snapped) * len);
            endY = startY + (float) (Math.sin(snapped) * len);
        } else if (settings.tool == Tool.RECT || settings.tool == Tool.ELLIPSE) {
            float side = Math.max(Math.abs(dx), Math.abs(dy));
            endX = startX + Math.copySign(side, dx == 0 ? 1 : dx);
            endY = startY + Math.copySign(side, dy == 0 ? 1 : dy);
        }
    }

    // ------------------------------------------------------------------
    // Shapes, eraser, undo / redo
    // ------------------------------------------------------------------

    private DrawShape build() {
        int rgb = settings.color.getRGB();
        switch (settings.tool) {
            case PEN: {
                float[] p = toArray(pen);
                return new DrawShape(DrawShape.Type.PEN, rgb, settings.width, false, p);
            }
            case HIGHLIGHTER: {
                int argb = (HIGHLIGHT_ALPHA << 24) | (rgb & 0xFFFFFF);
                return new DrawShape(DrawShape.Type.HIGHLIGHT, argb, settings.width * HIGHLIGHT_WIDTH_FACTOR,
                        false, toArray(pen));
            }
            case LINE:
                return new DrawShape(DrawShape.Type.LINE, rgb, settings.width, false,
                        new float[]{startX, startY, endX, endY});
            case RECT:
                return new DrawShape(DrawShape.Type.RECT, rgb, settings.width, settings.filled,
                        new float[]{startX, startY, endX, endY});
            case ELLIPSE:
                return new DrawShape(DrawShape.Type.ELLIPSE, rgb, settings.width, settings.filled,
                        new float[]{startX, startY, endX, endY});
            default:
                return null;
        }
    }

    private static float[] toArray(List<Float> list) {
        float[] a = new float[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    /** Shapes dragged out to almost nothing are accidental clicks. */
    private static boolean tooSmall(DrawShape s) {
        if (s.type == DrawShape.Type.PEN || s.type == DrawShape.Type.HIGHLIGHT) return false; // a dot is fine
        return Math.abs(s.pts[2] - s.pts[0]) < 2 && Math.abs(s.pts[3] - s.pts[1]) < 2;
    }

    private void erase(float x, float y) {
        double radius = 6 / zoom();
        List<DrawShape> hit = new ArrayList<>();
        for (DrawShape s : model.getShapes()) if (s.hits(x, y, radius)) hit.add(s);
        for (DrawShape s : hit) model.removeShape(s);
        if (!hit.isEmpty()) erasedAny = true;
    }

    private void pushUndo(List<DrawShape> before) {
        undo.push(before);
        while (undo.size() > MAX_UNDO) undo.removeLast();
        redo.clear();
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public boolean undo() {
        if (undo.isEmpty() || model.isProtected()) return false;
        redo.push(new ArrayList<>(model.getShapes()));
        model.setShapes(undo.pop());
        table.repaint();
        onChanged.run();
        return true;
    }

    public boolean redo() {
        if (redo.isEmpty() || model.isProtected()) return false;
        undo.push(new ArrayList<>(model.getShapes()));
        model.setShapes(redo.pop());
        table.repaint();
        onChanged.run();
        return true;
    }

    /** Removes every drawing on the sheet (can be undone). */
    public boolean clearAll() {
        if (model.getShapes().isEmpty() || model.isProtected()) return false;
        pushUndo(new ArrayList<>(model.getShapes()));
        model.setShapes(new ArrayList<>());
        table.repaint();
        onChanged.run();
        return true;
    }
}

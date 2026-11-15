import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.Timer;

/**
 * The start-up animation: a small sheet builds itself cell by cell, a column total counts up, the name slides in
 * and a progress bar runs while the real window is prepared behind it. Click it to skip.
 */
public final class SplashWindow extends JWindow {

    public static final int WIDTH = 600;
    public static final int HEIGHT = 360;
    /** Length of the animation in milliseconds. */
    public static final int DURATION = 2600;
    private static final int FADE = 260;

    private static final String[] STEPS = {"Loading the formula engine...", "Building the grid...",
            "Setting up the ribbon...", "Ready"};

    private static final int COLS = 5;
    private static final int ROWS = 4;
    private static final String[][] CELLS = {
            {"Item", "Q1", "Q2", "Q3", "Total"},
            {"North", "42", "55", "61", "158"},
            {"South", "38", "47", "52", "137"},
            {"Sum", "80", "102", "113", "295"}};

    private long start;
    private final Timer timer;
    private boolean skipped;
    private Runnable whenDone;

    private SplashWindow() {
        setSize(WIDTH, HEIGHT);
        setLocationRelativeTo(null);
        setAlwaysOnTop(true);
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                double t = skipped ? 1.0 : Math.min(1.0, (System.currentTimeMillis() - start) / (double) DURATION);
                paintFrame((Graphics2D) g, getWidth(), getHeight(), t);
            }
        };
        panel.setPreferredSize(new Dimension(WIDTH, HEIGHT));
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                skipped = true;
            }
        });
        setContentPane(panel);
        timer = new Timer(16, e -> tick());
    }

    private void tick() {
        long elapsed = System.currentTimeMillis() - start;
        if (skipped) elapsed = Math.max(elapsed, DURATION);
        if (elapsed >= DURATION) {
            double f = Math.min(1.0, (elapsed - DURATION) / (double) FADE);
            setOpacitySafe(1f - (float) f);
            if (f >= 1.0) {
                timer.stop();
                dispose();
                if (whenDone != null) whenDone.run();
            }
        }
        getContentPane().repaint();
    }

    private void setOpacitySafe(float o) {
        try {
            setOpacity(Math.max(0f, Math.min(1f, o)));
        } catch (UnsupportedOperationException | java.awt.IllegalComponentStateException ignored) {
            // some systems cannot fade windows: the splash then simply disappears
        }
    }

    /**
     * Shows the splash and keeps it up for {@link #DURATION} ms.
     * @param prepare  runs right after the first frame (build the main window here, but keep it hidden)
     * @param finished runs when the splash has faded away (show the main window here)
     */
    public static void play(Runnable prepare, Runnable finished) {
        SplashWindow s = new SplashWindow();
        s.whenDone = finished;
        s.start = System.currentTimeMillis();
        s.setVisible(true);
        ((javax.swing.JComponent) s.getContentPane()).paintImmediately(0, 0, WIDTH, HEIGHT);
        s.timer.start();
        if (prepare != null) javax.swing.SwingUtilities.invokeLater(prepare);
    }

    // ------------------------------------------------------------------
    // The picture (pure drawing, so tests can render any moment)
    // ------------------------------------------------------------------

    /** Renders the splash at time {@code t} (0..1 of the animation) for tests and thumbnails. */
    public static BufferedImage frame(double t) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            paintFrame(g, WIDTH, HEIGHT, t);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** Which message belongs to the progress bar at time t. */
    public static String stepText(double t) {
        double p = progress(t);
        if (p < 0.25) return STEPS[0];
        if (p < 0.55) return STEPS[1];
        if (p < 0.88) return STEPS[2];
        return STEPS[3];
    }

    /** Progress bar fill (0..1): quick at first, slower in the middle, done at the end. */
    public static double progress(double t) {
        t = clamp01(t / 0.92);
        return t * t * (3 - 2 * t);
    }

    static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double easeOutBack(double x) {
        x = clamp01(x);
        double c1 = 1.70158;
        double c3 = c1 + 1;
        return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2);
    }

    private static double easeOutCubic(double x) {
        x = clamp01(x);
        return 1 - Math.pow(1 - x, 3);
    }

    static void paintFrame(Graphics2D g0, int w, int h, double t) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            g.setPaint(new GradientPaint(0, 0, new Color(0x0B5D31), 0, h, new Color(0x1A9B57)));
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(255, 255, 255, 60));
            g.setStroke(new BasicStroke(2f));
            g.drawRect(1, 1, w - 3, h - 3);

            double ms = t * DURATION;

            // ---- the sheet builds itself ----
            int cw = 92;
            int ch = 32;
            int gap = 4;
            int gridW = COLS * cw + (COLS - 1) * gap;
            int gridX = (w - gridW) / 2;
            int gridY = 40;
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    double delay = (r + c) * 90.0;          // diagonal wave
                    double local = (ms - delay) / 420.0;
                    if (local <= 0) continue;
                    double s = easeOutBack(local);
                    float alpha = (float) clamp01(local * 1.6);
                    int x = gridX + c * (cw + gap);
                    int y = gridY + r * (ch + gap);
                    AffineTransform old = g.getTransform();
                    g.translate(x + cw / 2.0, y + ch / 2.0);
                    g.scale(s, s);
                    boolean header = r == 0;
                    boolean total = r == ROWS - 1 || c == COLS - 1;
                    Color fill = header ? new Color(0xFFFFFF) : (total ? new Color(0xE3F4EA) : new Color(255, 255, 255));
                    g.setColor(withAlpha(fill, alpha * 0.96f));
                    g.fill(new RoundRectangle2D.Double(-cw / 2.0, -ch / 2.0, cw, ch, 8, 8));
                    if (header) {
                        g.setColor(withAlpha(new Color(0x107C41), alpha));
                        g.fill(new RoundRectangle2D.Double(-cw / 2.0, ch / 2.0 - 4, cw, 4, 2, 2));
                    }
                    // text appears slightly after the cell
                    double tl = clamp01((ms - delay - 260) / 260.0);
                    if (tl > 0) {
                        String text = CELLS[r][c];
                        if (r == ROWS - 1 && c == COLS - 1) { // the grand total counts up
                            double count = clamp01((ms - 900) / 900.0);
                            text = String.valueOf(Math.round(295 * easeOutCubic(count)));
                        }
                        g.setFont(new Font(Font.SANS_SERIF, header || c == 0 || r == ROWS - 1 ? Font.BOLD : Font.PLAIN, 13));
                        FontMetrics fm = g.getFontMetrics();
                        g.setColor(withAlpha(new Color(0x1F3D2B), (float) tl));
                        int tx = c == 0 ? -cw / 2 + 10 : cw / 2 - 10 - fm.stringWidth(text);
                        g.drawString(text, tx, (fm.getAscent() - fm.getDescent()) / 2);
                    }
                    g.setTransform(old);
                }
            }

            // a selection frame sweeps across the data once the grid is there
            double sweep = clamp01((ms - 1100) / 700.0);
            if (sweep > 0 && sweep < 1.0) {
                double eased = easeOutCubic(sweep);
                double sx = gridX + eased * (gridW - cw);
                g.setColor(new Color(0x0A4A27));
                g.setStroke(new BasicStroke(3f));
                g.draw(new Rectangle2D.Double(sx - 2, gridY + ch + gap - 2, cw + 4, ch + 4));
            }

            // ---- title ----
            double tt = easeOutCubic((ms - 1000) / 650.0);
            if (tt > 0) {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
                String title = "Spreadsheet Pro";
                FontMetrics fm = g.getFontMetrics();
                int ty = gridY + ROWS * (ch + gap) + 58 + (int) ((1 - tt) * 18);
                g.setColor(withAlpha(Color.WHITE, (float) tt));
                g.drawString(title, (w - fm.stringWidth(title)) / 2, ty);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
                fm = g.getFontMetrics();
                String sub = "Formulas, charts, pivots and macros - built in Java";
                g.setColor(withAlpha(new Color(0xD6F0E0), (float) clamp01(tt * 1.2 - 0.2)));
                g.drawString(sub, (w - fm.stringWidth(sub)) / 2, ty + 26);
            }

            // ---- progress bar ----
            int bx = 70;
            int bw = w - 140;
            int by = h - 46;
            g.setColor(new Color(255, 255, 255, 50));
            g.fill(new RoundRectangle2D.Double(bx, by, bw, 6, 6, 6));
            double p = progress(t);
            g.setColor(Color.WHITE);
            g.fill(new RoundRectangle2D.Double(bx, by, bw * p, 6, 6, 6));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(new Color(0xD6F0E0));
            g.drawString(stepText(t), bx, by + 24);
            String pct = Math.round(p * 100) + "%";
            g.drawString(pct, bx + bw - g.getFontMetrics().stringWidth(pct), by + 24);
        } finally {
            g.dispose();
        }
    }

    private static Color withAlpha(Color c, float a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, Math.round(a * 255))));
    }
}

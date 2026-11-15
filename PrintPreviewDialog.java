import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.geom.AffineTransform;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * Shows the pages exactly as {@link PrintLayout} will print them. The layout is rebuilt through the supplier
 * whenever the page setup changes, so the preview never shows stale pages.
 */
public final class PrintPreviewDialog extends JDialog {

    /** What the preview needs from the application. */
    public interface Host {
        /** A fresh layout for the sheet being previewed (reads the current page setup). */
        PrintLayout layout();

        /** Opens Page Setup; returns true if the setup changed. */
        boolean pageSetup();
    }

    private static final String[] ZOOMS = {"Fit page", "50%", "75%", "100%", "150%"};

    private final Host host;
    private final Window owner;
    private PrintLayout layout;
    private int page = 0;
    private int zoomIndex = 0;
    private final JLabel pageLabel = new JLabel();
    private final JLabel infoLabel = new JLabel();
    private final JButton prev = new JButton("◀ Previous");
    private final JButton next = new JButton("Next ▶");
    private final PagePanel panel = new PagePanel();
    private final JScrollPane scroll = new JScrollPane(panel);

    public PrintPreviewDialog(Window owner, Host host) {
        super(owner, "Print Preview", ModalityType.APPLICATION_MODAL);
        this.owner = owner;
        this.host = host;
        layout = host.layout();

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        JButton print = new JButton("Print...");
        JButton setup = new JButton("Page Setup...");
        JComboBox<String> zoom = new JComboBox<>(ZOOMS);
        top.add(print);
        top.add(setup);
        top.add(prev);
        top.add(pageLabel);
        top.add(next);
        top.add(new JLabel("Zoom"));
        top.add(zoom);
        top.add(infoLabel);
        top.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xCCCCCC)));

        panel.setBackground(new Color(0x7F8A83));
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getHorizontalScrollBar().setUnitIncrement(24);
        scroll.setBorder(null);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        JButton close = new JButton("Close");
        bottom.add(close);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        prev.addActionListener(e -> go(page - 1));
        next.addActionListener(e -> go(page + 1));
        zoom.addActionListener(e -> {
            zoomIndex = zoom.getSelectedIndex();
            panel.revalidate();
            panel.repaint();
        });
        setup.addActionListener(e -> {
            if (host.pageSetup()) {
                layout = host.layout();
                go(Math.min(page, layout.pageCount() - 1));
            }
        });
        print.addActionListener(e -> doPrint());
        close.addActionListener(e -> dispose());

        JComponent root = getRootPane();
        bind(root, KeyEvent.VK_ESCAPE, 0, "close", this::dispose);
        bind(root, KeyEvent.VK_PAGE_DOWN, 0, "next", () -> go(page + 1));
        bind(root, KeyEvent.VK_PAGE_UP, 0, "prev", () -> go(page - 1));

        setSize(new Dimension(900, 760));
        setLocationRelativeTo(owner);
        update();
    }

    private static void bind(JComponent c, int key, int mods, String name, Runnable r) {
        c.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, mods), name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                r.run();
            }
        });
    }

    /** The page that is showing (0-based). */
    public int currentPage() {
        return page;
    }

    public PrintLayout currentLayout() {
        return layout;
    }

    public void go(int p) {
        page = Math.max(0, Math.min(layout.pageCount() - 1, p));
        update();
    }

    private void update() {
        pageLabel.setText("Page " + (page + 1) + " of " + layout.pageCount());
        prev.setEnabled(page > 0);
        next.setEnabled(page < layout.pageCount() - 1);
        String scale = String.format("%.0f%%", layout.scalePercent());
        infoLabel.setText(layout.isEmpty() ? "   (nothing to print on this sheet)" : "   Printed at " + scale);
        panel.revalidate();
        panel.repaint();
    }

    private void doPrint() {
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("Spreadsheet Pro");
        job.setPrintable(layout, layout.pageFormat());
        try {
            if (!job.printDialog()) return;
            job.print();
            dispose();
        } catch (PrinterException | java.awt.HeadlessException ex) {
            JOptionPane.showMessageDialog(this, "Could not print:\n" + ex.getMessage(), "Print", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Draws the paper with a soft shadow at the chosen zoom. */
    private final class PagePanel extends JPanel {
        private static final int MARGIN = 24;

        private double factor() {
            double pw = layout.paperWidth();
            double ph = layout.paperHeight();
            switch (zoomIndex) {
                case 1:
                    return 0.5 * 96 / 72.0;
                case 2:
                    return 0.75 * 96 / 72.0;
                case 3:
                    return 96 / 72.0;
                case 4:
                    return 1.5 * 96 / 72.0;
                default:
                    Dimension v = scroll.getViewport().getExtentSize();
                    double fw = (v.width - 2 * MARGIN) / pw;
                    double fh = (v.height - 2 * MARGIN) / ph;
                    return Math.max(0.1, Math.min(fw, fh));
            }
        }

        @Override
        public Dimension getPreferredSize() {
            double f = factor();
            return new Dimension((int) (layout.paperWidth() * f) + 2 * MARGIN, (int) (layout.paperHeight() * f) + 2 * MARGIN);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            try {
                double f = factor();
                int w = (int) Math.round(layout.paperWidth() * f);
                int h = (int) Math.round(layout.paperHeight() * f);
                int x = Math.max(MARGIN, (getWidth() - w) / 2);
                int y = MARGIN;
                g.setColor(new Color(0, 0, 0, 60));
                g.fillRect(x + 4, y + 4, w, h);
                g.setColor(Color.WHITE);
                g.fillRect(x, y, w, h);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setClip(x, y, w, h);
                g.transform(new AffineTransform(f, 0, 0, f, x, y));
                layout.paintPage(g, page);
            } finally {
                g.dispose();
            }
        }
    }

    /** Opens the preview. */
    public static void open(Window owner, Host host) {
        PrintPreviewDialog d = new PrintPreviewDialog(owner, host);
        d.setVisible(true);
    }
}

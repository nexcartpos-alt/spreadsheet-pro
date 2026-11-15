import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

/** The Page Setup dialog: page, margins, header/footer and sheet options (print area, repeated rows ...). */
public final class PageSetupDialog {

    private static final Pattern AREA = Pattern.compile("^\\s*\\$?([A-Za-z]{1,3})\\$?(\\d+)\\s*(?::\\s*\\$?([A-Za-z]{1,3})\\$?(\\d+))?\\s*$");

    private PageSetupDialog() {
    }

    /** Parses "A1:D20" (or "B3") into {r1, c1, r2, c2}, or null if the text is not a valid area for the sheet. */
    public static int[] parseArea(String text, SpreadsheetModel m) {
        Matcher mt = AREA.matcher(text == null ? "" : text);
        if (!mt.matches()) return null;
        int c1 = FormulaEngine.parseColumn(mt.group(1).toUpperCase());
        int r1 = Integer.parseInt(mt.group(2)) - 1;
        int c2 = mt.group(3) == null ? c1 : FormulaEngine.parseColumn(mt.group(3).toUpperCase());
        int r2 = mt.group(4) == null ? r1 : Integer.parseInt(mt.group(4)) - 1;
        if (!m.inBounds(r1, c1) || !m.inBounds(r2, c2)) return null;
        return new int[]{Math.min(r1, r2), Math.min(c1, c2), Math.max(r1, r2), Math.max(c1, c2)};
    }

    public static String areaText(PageSetup p) {
        if (!p.hasArea()) return "";
        String a = FormulaEngine.cellRef(p.areaR1, p.areaC1);
        String b = FormulaEngine.cellRef(p.areaR2, p.areaC2);
        return a.equals(b) ? a : a + ":" + b;
    }

    /**
     * Shows the dialog.
     * @param selection the selected cells {r1, c1, r2, c2}, offered by the "Use selection" button (may be null)
     * @return the new setup, or null if the user cancelled
     */
    public static PageSetup show(Component parent, PageSetup current, SpreadsheetModel model, int[] selection) {
        PageSetup p = current.copy();

        // ---- Page ----
        JRadioButton portrait = new JRadioButton("Portrait", !p.landscape);
        JRadioButton landscape = new JRadioButton("Landscape", p.landscape);
        ButtonGroup og = new ButtonGroup();
        og.add(portrait);
        og.add(landscape);
        JComboBox<PageSetup.Paper> paper = new JComboBox<>(PageSetup.Paper.values());
        paper.setSelectedItem(p.paper);
        JComboBox<PageSetup.Fit> fit = new JComboBox<>(PageSetup.Fit.values());
        fit.setSelectedItem(p.fit);
        JSpinner scale = new JSpinner(new SpinnerNumberModel(p.scale, PageSetup.MIN_SCALE, PageSetup.MAX_SCALE, 5));
        scale.setEnabled(p.fit == PageSetup.Fit.SCALE);
        fit.addActionListener(e -> scale.setEnabled(fit.getSelectedItem() == PageSetup.Fit.SCALE));

        JPanel page = form();
        row(page, 0, "Orientation", flow(portrait, landscape));
        row(page, 1, "Paper size", paper);
        row(page, 2, "Scaling", fit);
        row(page, 3, "Scale (%)", scale);

        // ---- Margins ----
        JSpinner top = inches(p.top);
        JSpinner bottom = inches(p.bottom);
        JSpinner left = inches(p.left);
        JSpinner right = inches(p.right);
        JSpinner head = inches(p.headerMargin);
        JSpinner foot = inches(p.footerMargin);
        JCheckBox centerH = new JCheckBox("Horizontally", p.centerHorizontally);
        JCheckBox centerV = new JCheckBox("Vertically", p.centerVertically);
        JPanel margins = form();
        row(margins, 0, "Top (inches)", top);
        row(margins, 1, "Bottom", bottom);
        row(margins, 2, "Left", left);
        row(margins, 3, "Right", right);
        row(margins, 4, "Header from edge", head);
        row(margins, 5, "Footer from edge", foot);
        row(margins, 6, "Center on page", flow(centerH, centerV));

        // ---- Header / footer ----
        JTextField hl = new JTextField(p.headerLeft, 16);
        JTextField hc = new JTextField(p.headerCenter, 16);
        JTextField hr = new JTextField(p.headerRight, 16);
        JTextField fl = new JTextField(p.footerLeft, 16);
        JTextField fc = new JTextField(p.footerCenter, 16);
        JTextField fr = new JTextField(p.footerRight, 16);
        JPanel hf = form();
        row(hf, 0, "Header left", hl);
        row(hf, 1, "Header center", hc);
        row(hf, 2, "Header right", hr);
        row(hf, 3, "Footer left", fl);
        row(hf, 4, "Footer center", fc);
        row(hf, 5, "Footer right", fr);
        JLabel codes = new JLabel("<html><small>Codes: <b>&amp;P</b> page &nbsp; <b>&amp;N</b> pages &nbsp; <b>&amp;D</b> date &nbsp; "
                + "<b>&amp;T</b> time &nbsp; <b>&amp;F</b> file &nbsp; <b>&amp;A</b> sheet &nbsp; <b>&amp;&amp;</b> an &amp;</small></html>");
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 6;
        gc.gridwidth = 2;
        gc.insets = new Insets(8, 4, 2, 4);
        gc.anchor = GridBagConstraints.WEST;
        hf.add(codes, gc);
        JComboBox<String> preset = new JComboBox<>(new String[]{"Presets...", "Page 1 of 3 (footer center)",
                "Sheet name + date (header)", "File name + page (footer)", "Clear header and footer"});
        preset.addActionListener(e -> {
            switch (preset.getSelectedIndex()) {
                case 1:
                    fc.setText("Page &P of &N");
                    break;
                case 2:
                    hl.setText("&A");
                    hr.setText("&D");
                    break;
                case 3:
                    fl.setText("&F");
                    fr.setText("Page &P");
                    break;
                case 4:
                    for (JTextField t : new JTextField[]{hl, hc, hr, fl, fc, fr}) t.setText("");
                    break;
                default:
            }
            preset.setSelectedIndex(0);
        });
        gc.gridy = 7;
        hf.add(preset, gc);

        // ---- Sheet ----
        JTextField area = new JTextField(areaText(p), 14);
        javax.swing.JButton useSel = new javax.swing.JButton("Use selection");
        useSel.setEnabled(selection != null);
        useSel.addActionListener(e -> area.setText(FormulaEngine.cellRef(selection[0], selection[1]) + ":"
                + FormulaEngine.cellRef(selection[2], selection[3])));
        javax.swing.JButton clearArea = new javax.swing.JButton("Clear");
        clearArea.addActionListener(e -> area.setText(""));
        JSpinner repeat = new JSpinner(new SpinnerNumberModel(p.repeatRows, 0, 50, 1));
        JCheckBox grid = new JCheckBox("Print gridlines", p.gridlines);
        JCheckBox headings = new JCheckBox("Print row and column headings", p.headings);
        JRadioButton downFirst = new JRadioButton("Down, then over", !p.overThenDown);
        JRadioButton overFirst = new JRadioButton("Over, then down", p.overThenDown);
        ButtonGroup pg = new ButtonGroup();
        pg.add(downFirst);
        pg.add(overFirst);
        JPanel sheet = form();
        row(sheet, 0, "Print area (e.g. A1:F40)", flow(area, useSel, clearArea));
        row(sheet, 1, "Repeat top rows on every page", repeat);
        row(sheet, 2, "Options", flow(grid));
        row(sheet, 3, "", flow(headings));
        row(sheet, 4, "Page order", flow(downFirst, overFirst));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Page", page);
        tabs.addTab("Margins", margins);
        tabs.addTab("Header/Footer", hf);
        tabs.addTab("Sheet", sheet);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(tabs, BorderLayout.CENTER);
        wrap.setPreferredSize(new Dimension(560, 330));

        while (true) {
            int res = JOptionPane.showConfirmDialog(parent, wrap, "Page Setup", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (res != JOptionPane.OK_OPTION) return null;
            String areaStr = area.getText().trim();
            int[] parsed = null;
            if (!areaStr.isEmpty()) {
                parsed = parseArea(areaStr, model);
                if (parsed == null) {
                    JOptionPane.showMessageDialog(parent, "\"" + areaStr + "\" is not a valid print area.\nUse a range such as A1:F40.",
                            "Page Setup", JOptionPane.WARNING_MESSAGE);
                    tabs.setSelectedComponent(sheet);
                    continue;
                }
            }
            PageSetup out = new PageSetup();
            out.landscape = landscape.isSelected();
            out.paper = (PageSetup.Paper) paper.getSelectedItem();
            out.fit = (PageSetup.Fit) fit.getSelectedItem();
            out.scale = ((Number) scale.getValue()).intValue();
            out.top = ((Number) top.getValue()).doubleValue();
            out.bottom = ((Number) bottom.getValue()).doubleValue();
            out.left = ((Number) left.getValue()).doubleValue();
            out.right = ((Number) right.getValue()).doubleValue();
            out.headerMargin = ((Number) head.getValue()).doubleValue();
            out.footerMargin = ((Number) foot.getValue()).doubleValue();
            out.centerHorizontally = centerH.isSelected();
            out.centerVertically = centerV.isSelected();
            out.headerLeft = hl.getText();
            out.headerCenter = hc.getText();
            out.headerRight = hr.getText();
            out.footerLeft = fl.getText();
            out.footerCenter = fc.getText();
            out.footerRight = fr.getText();
            out.gridlines = grid.isSelected();
            out.headings = headings.isSelected();
            out.overThenDown = overFirst.isSelected();
            out.repeatRows = ((Number) repeat.getValue()).intValue();
            if (parsed != null) out.setArea(parsed[0], parsed[1], parsed[2], parsed[3]);
            double usableW = out.pageWidth() - (out.left + out.right) * 72;
            double usableH = out.pageHeight() - (out.top + out.bottom) * 72;
            if (usableW < 72 || usableH < 72) {
                JOptionPane.showMessageDialog(parent, "The margins leave less than one inch of room on the page.\nMake them smaller.",
                        "Page Setup", JOptionPane.WARNING_MESSAGE);
                tabs.setSelectedComponent(margins);
                continue;
            }
            return out;
        }
    }

    // ---- small layout helpers ----

    private static JPanel form() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        return p;
    }

    private static void row(JPanel p, int y, String label, Component field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 4, 4, 12);
        p.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(field, c);
    }

    private static JPanel flow(Component... parts) {
        JPanel f = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (Component c : parts) f.add(c);
        return f;
    }

    private static JSpinner inches(double v) {
        return new JSpinner(new SpinnerNumberModel(v, 0.0, 3.0, 0.05));
    }
}

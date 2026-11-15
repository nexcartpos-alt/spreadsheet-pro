import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

/** Asks which table and fields to use for a pivot table (the first row of the range holds the headers). */
public class PivotDialog extends JDialog {

    /** The chosen settings. Field numbers are positions inside the range's columns. */
    public static final class Config {
        public int r1, c1, r2, c2;
        public int rowField, colField, valField;
        public PivotBuilder.Func func;
        public boolean totals;
    }

    private static final Pattern RANGE = Pattern.compile("^\\s*([A-Za-z]{1,3})(\\d+)\\s*:\\s*([A-Za-z]{1,3})(\\d+)\\s*$");

    private final SpreadsheetModel model;
    private final JTextField range = new JTextField(12);
    private final JComboBox<String> rowBox = new JComboBox<>();
    private final JComboBox<String> colBox = new JComboBox<>();
    private final JComboBox<String> valBox = new JComboBox<>();
    private final JComboBox<String> funcBox = new JComboBox<>(PivotBuilder.FUNC_NAMES);
    private final JCheckBox totals = new JCheckBox("Show grand totals", true);
    private int[] current;

    public PivotDialog(JFrame owner, SpreadsheetModel model, int[] region, Consumer<Config> onOk) {
        super(owner, "PivotTable", true);
        this.model = model;
        range.setText(FormulaEngine.cellRef(region[0], region[1]) + ":" + FormulaEngine.cellRef(region[2], region[3]));
        range.addActionListener(e -> loadFields());
        range.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                loadFields();
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 14, 6, 14));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        addRow(form, g, 0, "Table range (first row = headers):", range);
        addRow(form, g, 1, "Rows:", rowBox);
        addRow(form, g, 2, "Columns (optional):", colBox);
        addRow(form, g, 3, "Values:", valBox);
        addRow(form, g, 4, "Summarize with:", funcBox);
        g.gridx = 1;
        g.gridy = 5;
        form.add(totals, g);
        JLabel note = new JLabel("<html><i>The pivot table is a snapshot placed on a new sheet. Run it again to refresh.</i></html>");
        g.gridx = 0;
        g.gridy = 6;
        g.gridwidth = 2;
        form.add(note, g);

        JButton ok = new JButton("Create");
        ok.addActionListener(e -> {
            Config c = build();
            if (c == null) return;
            dispose();
            onOk.accept(c);
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(ok);
        buttons.add(cancel);
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        loadFields();
        pack();
        setLocationRelativeTo(owner);
    }

    private static void addRow(JPanel form, GridBagConstraints g, int row, String label, java.awt.Component field) {
        g.gridwidth = 1;
        g.gridx = 0;
        g.gridy = row;
        g.weightx = 0;
        form.add(new JLabel(label), g);
        g.gridx = 1;
        g.weightx = 1;
        form.add(field, g);
    }

    /** @return {r1, c1, r2, c2} or null when the text is not a valid range inside the sheet. */
    private int[] parseRange() {
        Matcher m = RANGE.matcher(range.getText());
        if (!m.matches()) return null;
        int c1 = FormulaEngine.parseColumn(m.group(1));
        int c2 = FormulaEngine.parseColumn(m.group(3));
        int r1 = Integer.parseInt(m.group(2)) - 1;
        int r2 = Integer.parseInt(m.group(4)) - 1;
        int[] a = {Math.min(r1, r2), Math.min(c1, c2), Math.max(r1, r2), Math.max(c1, c2)};
        return model.inBounds(a[0], a[1]) && model.inBounds(a[2], a[3]) ? a : null;
    }

    private void loadFields() {
        int[] r = parseRange();
        if (r == null || (current != null && java.util.Arrays.equals(r, current))) return;
        current = r;
        List<String> heads = PivotBuilder.headers(model, r[0], r[1], r[3]);
        rowBox.removeAllItems();
        colBox.removeAllItems();
        valBox.removeAllItems();
        colBox.addItem("(none)");
        for (String h : heads) {
            rowBox.addItem(h);
            colBox.addItem(h);
            valBox.addItem(h);
        }
        // sensible defaults: first column as rows, last column as values
        rowBox.setSelectedIndex(0);
        valBox.setSelectedIndex(heads.size() - 1);
    }

    private Config build() {
        int[] r = parseRange();
        if (r == null || r[2] <= r[0]) {
            JOptionPane.showMessageDialog(this, "Enter a range with a header row and at least one data row, like A1:D20.",
                    "PivotTable", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        loadFields();
        Config c = new Config();
        c.r1 = r[0];
        c.c1 = r[1];
        c.r2 = r[2];
        c.c2 = r[3];
        c.rowField = rowBox.getSelectedIndex();
        c.colField = colBox.getSelectedIndex() - 1;
        c.valField = valBox.getSelectedIndex();
        c.func = PivotBuilder.Func.values()[funcBox.getSelectedIndex()];
        c.totals = totals.isSelected();
        if (c.rowField < 0 || c.valField < 0) return null;
        if (c.colField == c.rowField) {
            JOptionPane.showMessageDialog(this, "Rows and Columns must be different fields.", "PivotTable",
                    JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return c;
    }
}

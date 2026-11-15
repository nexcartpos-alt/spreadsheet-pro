import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/** A searchable cheat sheet of every keyboard shortcut. */
public final class ShortcutsDialog extends JDialog {

    /** {group, action, keys} */
    public static final String[][] SHORTCUTS = {
            {"File", "New workbook", "Ctrl+N"},
            {"File", "Open workbook", "Ctrl+O"},
            {"File", "Save", "Ctrl+S"},
            {"File", "Save As", "Ctrl+Shift+S"},
            {"File", "Print", "Ctrl+P"},
            {"General", "Command palette (search every command)", "Ctrl+Shift+P"},
            {"General", "Keyboard shortcuts", "Ctrl+/"},
            {"Cells", "Move to the next cell", "Enter / Tab / Arrow keys"},
            {"Cells", "Edit the active cell", "F2"},
            {"Cells", "Clear the selected cells", "Delete"},
            {"Cells", "Right-click menu", "Right mouse button"},
            {"Formatting", "Bold", "Ctrl+B"},
            {"Formatting", "Italic", "Ctrl+I"},
            {"Formatting", "Align left", "Ctrl+L"},
            {"Formatting", "Align center", "Ctrl+E"},
            {"Formatting", "Align right", "Ctrl+R"},
            {"Formulas", "Insert function", "Shift+F3"},
            {"Formulas", "Cancel editing in the formula bar", "Esc"},
            {"Sheets", "New sheet", "Shift+F11"},
            {"View", "Zoom in", "Ctrl+ +  or  Ctrl+mouse wheel up"},
            {"View", "Zoom out", "Ctrl+ -  or  Ctrl+mouse wheel down"},
            {"View", "Back to 100%", "Ctrl+0"},
            {"View", "Resize a column to fit", "Double-click the column border"},
            {"Drawing", "Undo the last stroke or shape", "Ctrl+Z"},
            {"Drawing", "Redo", "Ctrl+Y"},
            {"Drawing", "Straight line / square / circle", "Hold Shift while dragging"},
            {"Print preview", "Next page", "Page Down"},
            {"Print preview", "Previous page", "Page Up"},
            {"Print preview", "Close", "Esc"},
            {"Command palette", "Move in the list", "Up / Down"},
            {"Command palette", "Run the selected command", "Enter"},
    };

    private final List<String[]> shown = new ArrayList<>(List.of(SHORTCUTS));
    private final AbstractTableModel model = new AbstractTableModel() {
        private final String[] cols = {"Area", "What it does", "Keys"};

        @Override
        public int getRowCount() {
            return shown.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            return shown.get(r)[c];
        }
    };

    public ShortcutsDialog(Window owner) {
        super(owner, "Keyboard Shortcuts", ModalityType.APPLICATION_MODAL);
        JTextField search = new JTextField();
        search.setToolTipText("Search by action, key or area");
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));
        top.add(new JLabel("Search"), BorderLayout.WEST);
        top.add(search, BorderLayout.CENTER);

        JTable table = new JTable(model);
        table.setRowHeight(26);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.getColumnModel().getColumn(0).setPreferredWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getColumnModel().getColumn(2).setPreferredWidth(220);
        table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, s, f, r, c);
                comp.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
                if (!s) comp.setForeground(new Color(0x107C41));
                return comp;
            }
        });
        JLabel count = new JLabel();
        count.setBorder(BorderFactory.createEmptyBorder(6, 12, 8, 12));
        count.setForeground(Color.GRAY);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(count, BorderLayout.SOUTH);
        setSize(660, 520);
        setLocationRelativeTo(owner);

        search.getDocument().addDocumentListener(new DocumentListener() {
            private void apply() {
                shown.clear();
                shown.addAll(filter(search.getText()));
                model.fireTableDataChanged();
                count.setText(shown.size() + " of " + SHORTCUTS.length + " shortcuts");
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                apply();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                apply();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                apply();
            }
        });
        count.setText(SHORTCUTS.length + " of " + SHORTCUTS.length + " shortcuts");
        getRootPane().registerKeyboardAction(e -> dispose(),
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    /** Rows whose area, action or keys contain every word of the query. */
    public static List<String[]> filter(String query) {
        String[] words = query == null ? new String[0] : query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<String[]> out = new ArrayList<>();
        for (String[] row : SHORTCUTS) {
            String hay = (row[0] + " " + row[1] + " " + row[2]).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : words) if (!w.isEmpty() && !hay.contains(w)) all = false;
            if (all) out.add(row);
        }
        return out;
    }

    public static void open(Window owner) {
        new ShortcutsDialog(owner).setVisible(true);
    }
}

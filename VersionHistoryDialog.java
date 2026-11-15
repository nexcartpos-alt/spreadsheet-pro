import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.IOException;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

/** Lists the saved copies of a workbook and lets you name, restore or delete them. */
public final class VersionHistoryDialog extends JDialog {

    /** What the dialog needs from the application. */
    public interface Host {
        /** Saves the workbook as it is now as a named version; returns false if that was not possible. */
        boolean saveVersion(String label);

        /** Replaces the open workbook with a version. Returns true if it was restored. */
        boolean restore(VersionStore.Version v);
    }

    private final VersionStore store;
    private final String key;
    private final Host host;
    private List<VersionStore.Version> versions;
    private final JTable table;
    private final JLabel info = new JLabel();
    private final AbstractTableModel model = new AbstractTableModel() {
        private final String[] cols = {"Saved", "Name", "Size", "Kind"};

        @Override
        public int getRowCount() {
            return versions.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            VersionStore.Version v = versions.get(r);
            switch (c) {
                case 0:
                    return v.when();
                case 1:
                    return v.label;
                case 2:
                    return size(v.size());
                default:
                    return v.named ? "Named" : "Automatic";
            }
        }
    };

    public VersionHistoryDialog(Window owner, String workbookName, VersionStore store, String key, Host host) {
        super(owner, "Version History - " + workbookName, ModalityType.APPLICATION_MODAL);
        this.store = store;
        this.key = key;
        this.host = host;
        versions = store.list(key);
        table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(220);
        table.getColumnModel().getColumn(2).setPreferredWidth(70);
        table.getColumnModel().getColumn(3).setPreferredWidth(80);

        JButton saveNow = new JButton("Save Version...");
        JButton restore = new JButton("Restore");
        JButton rename = new JButton("Name...");
        JButton delete = new JButton("Delete");
        JButton close = new JButton("Close");
        saveNow.setToolTipText("Keep the workbook as it is now under a name");
        restore.setToolTipText("Go back to the selected version (your current state is kept as a version first)");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        buttons.add(saveNow);
        buttons.add(restore);
        buttons.add(rename);
        buttons.add(delete);
        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.WEST);
        JPanel closeRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        closeRow.add(close);
        south.add(closeRow, BorderLayout.EAST);

        info.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        setLayout(new BorderLayout());
        add(info, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        setSize(new Dimension(640, 420));
        setLocationRelativeTo(owner);
        refresh();

        saveNow.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(this, "Name this version (for example \"Before the price change\"):",
                    "Save Version", JOptionPane.QUESTION_MESSAGE);
            if (name == null) return;
            if (name.trim().isEmpty()) name = "Saved version";
            if (host.saveVersion(name)) refresh();
        });
        restore.addActionListener(e -> {
            VersionStore.Version v = selected();
            if (v == null) return;
            int ok = JOptionPane.showConfirmDialog(this, "Go back to the version from " + v.when() + "?\n"
                            + "What you have open now is kept as a version first, so nothing is lost.",
                    "Restore Version", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) return;
            if (host.restore(v)) dispose();
        });
        rename.addActionListener(e -> {
            VersionStore.Version v = selected();
            if (v == null) return;
            String name = (String) JOptionPane.showInputDialog(this, "Name for this version (empty = automatic):",
                    "Name Version", JOptionPane.QUESTION_MESSAGE, null, null, v.label);
            if (name == null) return;
            try {
                store.rename(v, name);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Could not rename:\n" + ex.getMessage(), "Version History",
                        JOptionPane.ERROR_MESSAGE);
            }
            refresh();
        });
        delete.addActionListener(e -> {
            VersionStore.Version v = selected();
            if (v == null) return;
            if (JOptionPane.showConfirmDialog(this, "Delete the version from " + v.when() + "?", "Delete Version",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            store.delete(v);
            refresh();
        });
        close.addActionListener(e -> dispose());
        table.getSelectionModel().addListSelectionListener(e -> {
            boolean any = table.getSelectedRow() >= 0;
            restore.setEnabled(any);
            rename.setEnabled(any);
            delete.setEnabled(any);
        });
        restore.setEnabled(false);
        rename.setEnabled(false);
        delete.setEnabled(false);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) restore.doClick();
            }
        });
    }

    private VersionStore.Version selected() {
        int r = table.getSelectedRow();
        return r >= 0 && r < versions.size() ? versions.get(r) : null;
    }

    private void refresh() {
        versions = store.list(key);
        model.fireTableDataChanged();
        info.setText(versions.isEmpty()
                ? "<html>No versions yet. Every time you save, a copy is kept here.</html>"
                : "<html>" + versions.size() + " version(s). Every save keeps a copy; named versions are never deleted automatically.</html>");
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes + 512) / 1024 + " KB";
        return String.format("%.1f MB", bytes / 1048576.0);
    }
}

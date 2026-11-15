import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The drop-down shown when you click a filter arrow: sort the column, search its values and
 * tick the values to keep. Closes by itself when you click elsewhere.
 */
public class FilterDialog extends JDialog {

    public interface Callback {
        /** @param allowed the values to keep, or null for "show everything". */
        void apply(Set<String> allowed);

        void sort(boolean ascending);
    }

    private final List<JCheckBox> boxes = new ArrayList<>();
    private final JCheckBox selectAll = new JCheckBox("(Select All)");
    private final JTextField search = new JTextField();

    public FilterDialog(JFrame owner, Point screenLocation, String columnName, Collection<String> values,
                        Set<String> currentlyAllowed, Callback cb) {
        super(owner, false);
        setUndecorated(true);
        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x107C41), 2), BorderFactory.createEmptyBorder(8, 8, 8, 8)));

        JButton asc = new JButton("Sort A to Z");
        JButton desc = new JButton("Sort Z to A");
        asc.addActionListener(e -> {
            dispose();
            cb.sort(true);
        });
        desc.addActionListener(e -> {
            dispose();
            cb.sort(false);
        });
        JPanel sorts = new JPanel(new java.awt.GridLayout(1, 2, 6, 0));
        sorts.add(asc);
        sorts.add(desc);

        search.setToolTipText("Search values");
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filterList();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filterList();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filterList();
            }
        });
        JPanel top = new JPanel(new BorderLayout(0, 6));
        JPanel head = new JPanel(new BorderLayout());
        head.add(new javax.swing.JLabel("Filter \"" + columnName + "\""), BorderLayout.WEST);
        top.add(head, BorderLayout.NORTH);
        top.add(sorts, BorderLayout.CENTER);
        top.add(search, BorderLayout.SOUTH);

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(Color.WHITE);
        selectAll.setOpaque(false);
        selectAll.addActionListener(e -> {
            for (JCheckBox b : boxes) if (b.isVisible()) b.setSelected(selectAll.isSelected());
        });
        list.add(selectAll);
        for (String v : ordered(values)) {
            JCheckBox b = new JCheckBox(v, currentlyAllowed == null || currentlyAllowed.contains(v));
            b.setOpaque(false);
            b.addActionListener(e -> updateSelectAll());
            boxes.add(b);
            list.add(b);
        }
        updateSelectAll();
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(230, 200));

        JButton ok = new JButton("OK");
        ok.addActionListener(e -> {
            Set<String> chosen = new LinkedHashSet<>();
            boolean all = true;
            for (JCheckBox b : boxes) {
                if (b.isSelected()) chosen.add(b.getText());
                else all = false;
            }
            dispose();
            cb.apply(all ? null : chosen);
        });
        JButton clear = new JButton("Clear");
        clear.setToolTipText("Show everything in this column");
        clear.addActionListener(e -> {
            dispose();
            cb.apply(null);
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(clear);
        buttons.add(cancel);
        buttons.add(ok);

        root.add(top, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(ok);
        pack();
        setLocation(screenLocation);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowDeactivated(WindowEvent e) {
                dispose();
            }
        });
    }

    /** Numbers in numeric order, text alphabetically, (Blanks) last. */
    private static List<String> ordered(Collection<String> values) {
        List<String> list = new ArrayList<>(values);
        boolean blanks = list.remove(AutoFilter.BLANKS);
        boolean numeric = !list.isEmpty();
        for (String s : list) {
            try {
                Double.parseDouble(s.replace(",", "").replace("$", "").replace("%", ""));
            } catch (NumberFormatException e) {
                numeric = false;
                break;
            }
        }
        if (numeric) {
            list.sort(Comparator.comparingDouble(s -> Double.parseDouble(s.replace(",", "").replace("$", "").replace("%", ""))));
        } else {
            list.sort(String.CASE_INSENSITIVE_ORDER);
        }
        if (blanks) list.add(AutoFilter.BLANKS);
        return list;
    }

    private void filterList() {
        String q = search.getText().trim().toLowerCase();
        for (JCheckBox b : boxes) b.setVisible(q.isEmpty() || b.getText().toLowerCase().contains(q));
        updateSelectAll();
    }

    private void updateSelectAll() {
        boolean all = true;
        for (JCheckBox b : boxes) if (b.isVisible() && !b.isSelected()) all = false;
        selectAll.setSelected(all);
    }
}

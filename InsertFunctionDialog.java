import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * "Insert Function" picker (opened from the fx button): search or browse every function
 * by category, read its syntax and an example, then insert it into the formula bar.
 */
public class InsertFunctionDialog extends JDialog {

    private static final String ALL = "All";

    private final DefaultListModel<FunctionCatalog.Entry> listModel = new DefaultListModel<>();
    private final JList<FunctionCatalog.Entry> list = new JList<>(listModel);
    private final JTextField search = new JTextField();
    private final JComboBox<String> category = new JComboBox<>();
    private final JTextArea details = new JTextArea(7, 38);
    private final Consumer<String> onInsert;

    public InsertFunctionDialog(JFrame owner, Consumer<String> onInsert) {
        super(owner, "Insert Function", true);
        this.onInsert = onInsert;

        category.addItem(ALL);
        for (String c : FunctionCatalog.categories()) category.addItem(c);
        category.addActionListener(e -> refill());
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refill();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refill();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refill();
            }
        });

        JPanel top = new JPanel(new BorderLayout(8, 6));
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.add(new JLabel("Search:"), BorderLayout.WEST);
        row.add(search, BorderLayout.CENTER);
        top.add(row, BorderLayout.NORTH);
        JPanel cat = new JPanel(new BorderLayout(8, 0));
        cat.add(new JLabel("Category:"), BorderLayout.WEST);
        cat.add(category, BorderLayout.CENTER);
        top.add(cat, BorderLayout.SOUTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        list.addListSelectionListener(e -> showDetails());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) insertSelected();
            }
        });
        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setPreferredSize(new Dimension(420, 210));
        listScroll.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));

        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        details.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JScrollPane detailScroll = new JScrollPane(details);
        detailScroll.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(8, 12, 0, 12), BorderFactory.createLineBorder(SpreadsheetCellRenderer.GRID)));

        JButton insert = new JButton("Insert");
        insert.addActionListener(e -> insertSelected());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(insert);
        buttons.add(cancel);

        JPanel center = new JPanel(new BorderLayout());
        center.add(listScroll, BorderLayout.CENTER);
        center.add(detailScroll, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(insert);
        refill();
        pack();
        setLocationRelativeTo(owner);
    }

    private void refill() {
        String q = search.getText().trim().toLowerCase();
        Object cat = category.getSelectedItem();
        FunctionCatalog.Entry keep = list.getSelectedValue();
        listModel.clear();
        for (FunctionCatalog.Entry e : FunctionCatalog.all()) {
            if (cat != null && !ALL.equals(cat) && !e.category.equals(cat)) continue;
            if (!q.isEmpty() && !e.name.toLowerCase().contains(q) && !e.description.toLowerCase().contains(q)) continue;
            listModel.addElement(e);
        }
        if (listModel.isEmpty()) {
            details.setText("No function matches.");
        } else if (keep != null && listModel.contains(keep)) {
            list.setSelectedValue(keep, true);
        } else {
            list.setSelectedIndex(0);
        }
    }

    private void showDetails() {
        FunctionCatalog.Entry e = list.getSelectedValue();
        if (e == null) return;
        details.setText(e.syntax + "\n\n" + e.description + "\n\nExample:  " + e.example);
        details.setCaretPosition(0);
    }

    private void insertSelected() {
        FunctionCatalog.Entry e = list.getSelectedValue();
        if (e == null) return;
        dispose();
        onInsert.accept(e.name);
    }
}

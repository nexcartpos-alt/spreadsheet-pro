import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Ctrl+Shift+P: type a few letters of any command ("sort", "print", "protect") and press Enter to run it.
 */
public final class CommandPalette extends JDialog {

    /** One thing the palette can run. */
    public static final class Command {
        public final String name;
        public final String category;
        public final String detail;
        public final String shortcut;
        public final Runnable action;

        public Command(String name, String category, String detail, String shortcut, Runnable action) {
            this.name = name;
            this.category = category == null ? "" : category;
            this.detail = detail == null ? "" : detail;
            this.shortcut = shortcut == null ? "" : shortcut;
            this.action = action;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final List<Command> all;
    private final JTextField input = new JTextField();
    private final DefaultListModel<Command> shown = new DefaultListModel<>();
    private final JList<Command> list = new JList<>(shown);
    private Command chosen;

    public CommandPalette(Window owner, List<Command> commands) {
        super(owner);
        this.all = new ArrayList<>(commands);
        setUndecorated(true);
        setModalityType(ModalityType.APPLICATION_MODAL);

        input.setFont(input.getFont().deriveFont(Font.PLAIN, 16f));
        input.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xDDDDDD)),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Row());
        list.setFixedCellHeight(40);
        list.setFocusable(false);
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(null);
        sp.setPreferredSize(new Dimension(560, 40 * 8));

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createLineBorder(new Color(0x107C41), 2));
        JLabel hint = new JLabel("  Type a command - Enter runs it, Esc closes");
        hint.setForeground(Color.GRAY);
        hint.setFont(hint.getFont().deriveFont(11f));
        hint.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        root.add(input, BorderLayout.NORTH);
        root.add(sp, BorderLayout.CENTER);
        root.add(hint, BorderLayout.SOUTH);
        setContentPane(root);
        pack();

        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refilter();
            }
        });
        input.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int n = shown.size();
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN:
                        if (n > 0) move(1);
                        e.consume();
                        break;
                    case KeyEvent.VK_UP:
                        if (n > 0) move(-1);
                        e.consume();
                        break;
                    case KeyEvent.VK_ENTER:
                        run();
                        e.consume();
                        break;
                    case KeyEvent.VK_ESCAPE:
                        dispose();
                        e.consume();
                        break;
                    default:
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 1 && list.locationToIndex(e.getPoint()) >= 0) {
                    list.setSelectedIndex(list.locationToIndex(e.getPoint()));
                    run();
                }
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowDeactivated(WindowEvent e) {
                dispose();
            }
        });
        refilter();
    }

    private void move(int d) {
        int n = shown.size();
        int i = Math.max(0, Math.min(n - 1, list.getSelectedIndex() + d));
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
    }

    private void run() {
        Command c = list.getSelectedValue();
        if (c == null) return;
        chosen = c;
        dispose();
    }

    private void refilter() {
        String q = input.getText();
        shown.clear();
        for (Command c : rank(all, q, 60)) shown.addElement(c);
        if (!shown.isEmpty()) list.setSelectedIndex(0);
    }

    /**
     * Commands that match the query, best first. Every word of the query has to appear in the name, category or
     * description; matches at the start of the name rank highest. An empty query lists commands in their original order.
     */
    public static List<Command> rank(List<Command> commands, String query, int limit) {
        String[] words = query == null ? new String[0] : query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<Command> out = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (Command c : commands) {
            int score = score(c, words);
            if (score < 0) continue;
            int at = 0;
            while (at < out.size() && scores.get(at) >= score) at++; // stable: equal scores keep their order
            out.add(at, c);
            scores.add(at, score);
            if (out.size() > limit * 2) {
                out.remove(out.size() - 1);
                scores.remove(scores.size() - 1);
            }
        }
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private static int score(Command c, String[] words) {
        String name = c.name.toLowerCase(Locale.ROOT);
        String cat = c.category.toLowerCase(Locale.ROOT);
        String det = c.detail.toLowerCase(Locale.ROOT);
        int total = 0;
        boolean any = false;
        for (String w : words) {
            if (w.isEmpty()) continue;
            any = true;
            int s;
            if (name.equals(w)) s = 100;
            else if (name.startsWith(w)) s = 60;
            else if (name.contains(" " + w)) s = 45;
            else if (name.contains(w)) s = 30;
            else if (subsequence(name, w)) s = 12;
            else if (cat.contains(w)) s = 8;
            else if (det.contains(w)) s = 5;
            else return -1;
            total += s;
        }
        return any ? total : 1;
    }

    /** True if the letters of {@code w} appear in {@code s} in order ("sz" matches "sheet size"). */
    private static boolean subsequence(String s, String w) {
        if (w.length() < 2) return false;
        int i = 0;
        for (int k = 0; k < s.length() && i < w.length(); k++) if (s.charAt(k) == w.charAt(i)) i++;
        return i == w.length();
    }

    private static final class Row extends JPanel implements ListCellRenderer<Command> {
        private final JLabel name = new JLabel();
        private final JLabel detail = new JLabel();
        private final JLabel right = new JLabel();

        Row() {
            super(new BorderLayout());
            JPanel left = new JPanel(new BorderLayout());
            left.setOpaque(false);
            name.setFont(name.getFont().deriveFont(Font.BOLD, 13f));
            detail.setFont(detail.getFont().deriveFont(Font.PLAIN, 11f));
            detail.setForeground(Color.GRAY);
            right.setFont(right.getFont().deriveFont(Font.PLAIN, 11f));
            right.setForeground(new Color(0x107C41));
            left.add(name, BorderLayout.NORTH);
            left.add(detail, BorderLayout.CENTER);
            add(left, BorderLayout.CENTER);
            add(right, BorderLayout.EAST);
            setBorder(BorderFactory.createEmptyBorder(3, 12, 3, 12));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Command> l, Command c, int index,
                                                      boolean selected, boolean focus) {
            name.setText(c.name);
            detail.setText(c.detail.isEmpty() ? " " : c.detail);
            right.setText(c.shortcut.isEmpty() ? c.category : c.category + "   " + c.shortcut);
            setBackground(selected ? new Color(0xE3F1E8) : Color.WHITE);
            setOpaque(true);
            return this;
        }
    }

    /** Opens the palette and runs the chosen command after it has closed. */
    public static void open(Window owner, List<Command> commands) {
        CommandPalette p = new CommandPalette(owner, commands);
        if (owner != null) {
            p.setLocation(owner.getX() + (owner.getWidth() - p.getWidth()) / 2, owner.getY() + 90);
        }
        p.setVisible(true); // modal: returns when closed
        Command c = p.chosen;
        if (c != null) SwingUtilities.invokeLater(c.action);
    }

    /** The command that was chosen (for tests). */
    Command chosen() {
        return chosen;
    }

    /** Test hook: types into the box. */
    void setQuery(String q) {
        input.setText(q);
    }

    List<Command> visible() {
        List<Command> l = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) l.add(shown.get(i));
        return l;
    }
}

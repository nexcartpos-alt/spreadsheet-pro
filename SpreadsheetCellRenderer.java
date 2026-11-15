import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.HashMap;
import java.util.Map;

/**
 * Rendering layer: paints each cell according to its {@link CellStyle}
 * (bold / italic / size, alignment, fill and text colour) with subtle grid
 * lines, a tinted selection and a green "active cell" frame like Excel.
 */
public class SpreadsheetCellRenderer extends DefaultTableCellRenderer {

    public static final Color GREEN = new Color(0x107C41);
    static final Color GRID = new Color(0xD9D9D9);
    static final Color HEADER_BG = new Color(0xF3F3F3);
    static final Color HEADER_SELECTED_BG = new Color(0xC6E5D1);
    static final Color HEADER_HOVER_BG = new Color(0xE3F1E8);
    private static final Color ERROR_FG = new Color(0xC00000);

    private final SpreadsheetModel model;
    private double zoom = 1.0;
    private final Map<String, Font> familyCache = new HashMap<>();
    /** 0 = no filter arrow on the cell being painted, 1 = arrow, 2 = arrow of a filtered column. */
    private int arrowState;
    /** Sparkline of the cell being painted (null = none). */
    private Sparkline spark;
    /** Cell under the mouse (-1 = none); gets a faint highlight. Set by the sheet view. */
    public int hoverRow = -1;
    public int hoverCol = -1;

    public SpreadsheetCellRenderer(SpreadsheetModel model) {
        this.model = model;
    }

    /** Scale factor for explicitly sized fonts (the table's own font is already scaled). */
    public void setZoom(double zoom) {
        this.zoom = zoom;
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                   boolean hasFocus, int row, int column) {
        JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, false, false, row, column);
        CellStyle st = model.getStyle(row, column);

        // Font
        Font base = table.getFont();
        int flags = Font.PLAIN;
        if (st != null && st.bold) flags |= Font.BOLD;
        if (st != null && st.italic) flags |= Font.ITALIC;
        float size = (st != null && st.fontSize > 0) ? (float) (st.fontSize * zoom) : base.getSize2D();
        if (st != null && st.fontFamily != null) {
            Font fam = familyCache.computeIfAbsent(st.fontFamily, n -> new Font(n, Font.PLAIN, 12));
            label.setFont(fam.deriveFont(flags, size));
        } else {
            label.setFont(base.deriveFont(flags, size));
        }

        // Alignment: explicit style wins; otherwise numbers right, errors centred, text left
        int align;
        if (st != null && st.alignment != CellStyle.ALIGN_AUTO) align = st.alignment;
        else if (model.isShowFormulas() && model.getInput(row, column).startsWith("=")) align = SwingConstants.LEFT;
        else if (model.isFormulaError(row, column) || model.getComputedValue(row, column) instanceof Boolean) {
            align = SwingConstants.CENTER; // errors and TRUE/FALSE are centred, like Excel
        }
        else if (model.isNumeric(row, column)) align = SwingConstants.RIGHT;
        else align = SwingConstants.LEFT;
        label.setHorizontalAlignment(align);

        // Colours
        Color bg = (st != null && st.background != null) ? st.background : Color.WHITE;
        Color fg = (st != null && st.foreground != null) ? st.foreground : Color.BLACK;
        if (model.isFormulaError(row, column) && (st == null || st.foreground == null)) fg = ERROR_FG;

        CondRule rule = model.matchingRule(row, column);
        if (rule != null) {
            if (rule.bg != null) bg = rule.bg;
            if (rule.fg != null) fg = rule.fg;
        }
        spark = model.sparklineAt(row, column);
        AutoFilter af = model.getFilter();
        arrowState = af != null && row == af.headerRow && af.coversColumn(column) ? (af.isFiltered(column) ? 2 : 1) : 0;

        boolean active = row == table.getSelectionModel().getLeadSelectionIndex()
                && column == table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        if (isSelected && !active) bg = blend(bg, GREEN, 0.20f); // active cell stays "clean", like Excel
        else if (!isSelected && row == hoverRow && column == hoverCol) bg = blend(bg, GREEN, 0.07f);
        label.setBackground(bg);
        label.setForeground(fg);
        label.setOpaque(true);

        // Borders
        if (active) {
            label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(GREEN, 2),
                    BorderFactory.createEmptyBorder(0, 2, 0, 2)));
        } else {
            label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 1, GRID),
                    BorderFactory.createEmptyBorder(0, 4, 0, 4)));
        }
        return label;
    }

    /** Where the filter arrow sits inside a header cell (used for painting and for clicks). */
    public static Rectangle arrowRect(Rectangle cell, double zoom) {
        int size = Math.max(10, (int) Math.round(16 * zoom));
        size = Math.min(size, Math.max(10, cell.height - 2));
        return new Rectangle(cell.x + cell.width - size - 2, cell.y + (cell.height - size) / 2, size, size);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (spark != null) {
            SparklinePainter.paint((Graphics2D) g, spark, spark.values(model), new Rectangle(0, 0, getWidth(), getHeight()));
        }
        if (arrowState == 0) return;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Rectangle r = arrowRect(new Rectangle(0, 0, getWidth(), getHeight()), zoom);
        g2.setColor(arrowState == 2 ? GREEN : Color.WHITE);
        g2.fillRoundRect(r.x, r.y, r.width, r.height, 4, 4);
        g2.setColor(arrowState == 2 ? GREEN : new Color(0x888888));
        g2.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, 4, 4);
        g2.setColor(arrowState == 2 ? Color.WHITE : new Color(0x444444));
        int cx = r.x + r.width / 2;
        int cy = r.y + r.height / 2;
        int a = Math.max(3, r.width / 5);
        g2.fillPolygon(new int[]{cx - a, cx + a, cx}, new int[]{cy - a / 2, cy - a / 2, cy + a / 2 + 1}, 3);
        g2.dispose();
    }

    private static Color blend(Color a, Color b, float amount) {
        float inv = 1f - amount;
        return new Color(
                Math.round(a.getRed() * inv + b.getRed() * amount),
                Math.round(a.getGreen() * inv + b.getGreen() * amount),
                Math.round(a.getBlue() * inv + b.getBlue() * amount));
    }

    // ------------------------------------------------------------------

    /** Column header (A, B, C ...) - highlights columns that contain the selection. */
    public static class ColumnHeaderRenderer extends DefaultTableCellRenderer {
        public int hover = -1; // header under the mouse

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(table, value, false, false, row, column);
            boolean sel = table.isColumnSelected(column);
            l.setHorizontalAlignment(SwingConstants.CENTER);
            l.setFont(table.getFont().deriveFont(Font.BOLD, Math.max(7f, table.getFont().getSize2D() - 1f)));
            l.setOpaque(true);
            l.setBackground(sel ? HEADER_SELECTED_BG : column == hover ? HEADER_HOVER_BG : HEADER_BG);
            l.setForeground(sel || column == hover ? new Color(0x0B5D31) : new Color(0x444444));
            l.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, new Color(0xBFBFBF)));
            return l;
        }
    }

    /** Row header (1, 2, 3 ...) - used as the JList in the scroll pane's row header. */
    public static class RowHeaderRenderer extends JLabel implements ListCellRenderer<String> {
        private final JTable table;
        public int hover = -1; // row header under the mouse
        private int index;

        public RowHeaderRenderer(JTable table) {
            this.table = table;
            setOpaque(true);
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        /** Same height as the table row, so rows hidden by a filter disappear from the numbers too. */
        @Override
        public java.awt.Dimension getPreferredSize() {
            java.awt.Dimension d = super.getPreferredSize();
            return new java.awt.Dimension(d.width, table.getRowHeight(Math.max(0, Math.min(index, table.getRowCount() - 1))));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends String> list, String value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            this.index = index;
            boolean sel = table.isRowSelected(index);
            setText(value);
            setFont(table.getFont().deriveFont(Font.BOLD, Math.max(7f, table.getFont().getSize2D() - 1f)));
            setBackground(sel ? HEADER_SELECTED_BG : index == hover ? HEADER_HOVER_BG : HEADER_BG);
            setForeground(sel || index == hover ? new Color(0x0B5D31) : new Color(0x444444));
            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, new Color(0xBFBFBF)));
            return this;
        }
    }
}

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The sheet's filter: a header row with a drop-down arrow on each column of a range, and for
 * each filtered column the set of display values that stay visible. Rows below the header whose
 * value is not allowed are hidden.
 */
public final class AutoFilter {

    public static final String BLANKS = "(Blanks)";

    public int headerRow;
    public int firstCol;
    public int lastCol;
    /** column -> the values that are shown (columns without an entry are not filtered). */
    public final Map<Integer, Set<String>> allowed = new LinkedHashMap<>();

    public AutoFilter(int headerRow, int firstCol, int lastCol) {
        this.headerRow = headerRow;
        this.firstCol = Math.min(firstCol, lastCol);
        this.lastCol = Math.max(firstCol, lastCol);
    }

    public boolean coversColumn(int col) {
        return col >= firstCol && col <= lastCol;
    }

    public boolean isFiltered(int col) {
        return allowed.containsKey(col);
    }

    public boolean anyCriteria() {
        return !allowed.isEmpty();
    }

    public Set<String> copyAllowed(int col) {
        Set<String> s = allowed.get(col);
        return s == null ? null : new LinkedHashSet<>(s);
    }
}

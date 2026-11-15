import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.swing.SwingConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads and writes Excel (.xlsx) workbooks using only the JDK (an .xlsx file is a zip of XML parts).
 *
 * Carried over: sheet names, values, formulas (with their last result), fonts, bold / italic, text and
 * fill colours, number formats, alignment and column widths. Not carried over: pictures, drawings, charts,
 * conditional formatting, filters and sheet protection.
 */
public final class XlsxIO {

    public static final String EXTENSION = "xlsx";
    private static final int MAX_ROWS = SpreadsheetModel.MAX_ROWS; // same limits as the .spx format
    private static final int MAX_COLS = 256;
    private static final int MAX_CELLS = 4_000_000;
    private static final long MAX_PART_BYTES = 256L * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    private static final String NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private XlsxIO() {
    }

    /** Sheets read from a file plus things that could not be carried over. */
    public static final class Result {
        public final List<WorkbookIO.SheetData> sheets = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public int activeIndex;
    }

    // ==================================================================
    // Writing
    // ==================================================================

    /** One distinct cell format in the output. */
    private static final class Xf {
        int fontId, fillId, numFmtId, align; // align: 0 none, 1 left, 2 center, 3 right
    }

    private static final class Styles {
        final List<String> fontXml = new ArrayList<>();
        final List<String> fillXml = new ArrayList<>();
        final List<String> numFmts = new ArrayList<>();   // custom format codes (ids start at 164)
        final List<Xf> xfs = new ArrayList<>();
        final Map<String, Integer> xfIndex = new HashMap<>();

        Styles() {
            fontXml.add(font(false, false, 11, "Calibri", null));
            fillXml.add("<fill><patternFill patternType=\"none\"/></fill>");
            fillXml.add("<fill><patternFill patternType=\"gray125\"/></fill>");
            xfs.add(new Xf());
        }

        static String font(boolean b, boolean i, int size, String name, Color color) {
            StringBuilder sb = new StringBuilder("<font>");
            if (b) sb.append("<b/>");
            if (i) sb.append("<i/>");
            sb.append("<sz val=\"").append(size).append("\"/>");
            if (color != null) sb.append("<color rgb=\"FF").append(hex(color)).append("\"/>");
            sb.append("<name val=\"").append(esc(name)).append("\"/><family val=\"2\"/></font>");
            return sb.toString();
        }

        int indexOf(List<String> list, String xml) {
            int i = list.indexOf(xml);
            if (i < 0) {
                list.add(xml);
                i = list.size() - 1;
            }
            return i;
        }

        int xfFor(CellStyle st) {
            if (st == null || st.isDefault()) return 0;
            String key = st.bold + "|" + st.italic + "|" + st.alignment + "|" + st.background + "|" + st.foreground
                    + "|" + st.fontSize + "|" + st.fontFamily + "|" + st.numFmt + "|" + st.decimals;
            Integer have = xfIndex.get(key);
            if (have != null) return have;
            Xf xf = new Xf();
            xf.fontId = indexOf(fontXml, font(st.bold, st.italic, st.fontSize > 0 ? st.fontSize : 11,
                    st.fontFamily == null ? "Calibri" : st.fontFamily, st.foreground));
            xf.fillId = st.background == null ? 0 : indexOf(fillXml, "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF"
                    + hex(st.background) + "\"/><bgColor indexed=\"64\"/></patternFill></fill>");
            if (st.numFmt != CellFormats.GENERAL) {
                String code = formatCode(st.numFmt, st.decimals);
                int idx = numFmts.indexOf(code);
                if (idx < 0) {
                    numFmts.add(code);
                    idx = numFmts.size() - 1;
                }
                xf.numFmtId = 164 + idx;
            }
            xf.align = st.alignment == SwingConstants.LEFT ? 1 : st.alignment == SwingConstants.CENTER ? 2
                    : st.alignment == SwingConstants.RIGHT ? 3 : 0;
            xfs.add(xf);
            xfIndex.put(key, xfs.size() - 1);
            return xfs.size() - 1;
        }

        String toXml() {
            StringBuilder sb = new StringBuilder();
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
            sb.append("<styleSheet xmlns=\"").append(NS_MAIN).append("\">");
            if (!numFmts.isEmpty()) {
                sb.append("<numFmts count=\"").append(numFmts.size()).append("\">");
                for (int i = 0; i < numFmts.size(); i++) {
                    sb.append("<numFmt numFmtId=\"").append(164 + i).append("\" formatCode=\"").append(esc(numFmts.get(i))).append("\"/>");
                }
                sb.append("</numFmts>");
            }
            sb.append("<fonts count=\"").append(fontXml.size()).append("\">");
            for (String f : fontXml) sb.append(f);
            sb.append("</fonts><fills count=\"").append(fillXml.size()).append("\">");
            for (String f : fillXml) sb.append(f);
            sb.append("</fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>");
            sb.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>");
            sb.append("<cellXfs count=\"").append(xfs.size()).append("\">");
            for (Xf x : xfs) {
                sb.append("<xf numFmtId=\"").append(x.numFmtId).append("\" fontId=\"").append(x.fontId)
                        .append("\" fillId=\"").append(x.fillId).append("\" borderId=\"0\" xfId=\"0\"");
                if (x.numFmtId != 0) sb.append(" applyNumberFormat=\"1\"");
                if (x.fontId != 0) sb.append(" applyFont=\"1\"");
                if (x.fillId != 0) sb.append(" applyFill=\"1\"");
                if (x.align != 0) {
                    sb.append(" applyAlignment=\"1\"><alignment horizontal=\"")
                            .append(x.align == 1 ? "left" : x.align == 2 ? "center" : "right").append("\"/></xf>");
                } else {
                    sb.append("/>");
                }
            }
            sb.append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
            return sb.toString();
        }
    }

    static String formatCode(int fmt, int decimals) {
        int dec = decimals >= 0 ? decimals : CellFormats.defaultDecimals(fmt);
        StringBuilder zeros = new StringBuilder();
        for (int i = 0; i < dec; i++) zeros.append('0');
        String frac = dec > 0 ? "." + zeros : "";
        switch (fmt) {
            case CellFormats.NUMBER:
                return "0" + frac;
            case CellFormats.COMMA:
                return "#,##0" + frac;
            case CellFormats.CURRENCY:
                return "\"$\"#,##0" + frac;
            case CellFormats.PERCENT:
                return "0" + frac + "%";
            case CellFormats.SCIENTIFIC:
                return "0" + frac + "E+00";
            default:
                return "General";
        }
    }

    private static String hex(Color c) {
        return String.format("%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                default:
                    if (ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') break; // not allowed in XML 1.0
                    if (ch == 0xFFFE || ch == 0xFFFF) break;
                    sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String sheetName(String wanted, List<String> used) {
        String n = wanted.replaceAll("[\\[\\]:*?/\\\\]", "_").trim();
        if (n.isEmpty()) n = "Sheet";
        if (n.length() > 31) n = n.substring(0, 31);
        String base = n;
        int k = 2;
        while (containsIgnoreCase(used, n)) {
            String suffix = " (" + k++ + ")";
            n = base.substring(0, Math.min(base.length(), 31 - suffix.length())) + suffix;
        }
        used.add(n);
        return n;
    }

    private static boolean containsIgnoreCase(List<String> list, String s) {
        for (String x : list) if (x.equalsIgnoreCase(s)) return true;
        return false;
    }

    /** Things in the workbook that an .xlsx file written by this class cannot hold. */
    public static List<String> unsupportedFeatures(List<WorkbookIO.SheetData> sheets) {
        int pics = 0;
        int draw = 0;
        int charts = 0;
        int rules = 0;
        int filters = 0;
        int sparks = 0;
        for (WorkbookIO.SheetData sd : sheets) {
            sparks += sd.model.getSparklines().size();
            pics += sd.model.getImages().size();
            draw += sd.model.getShapes().size();
            charts += sd.model.getCharts().size();
            rules += sd.model.getCondRules().size();
            if (sd.model.getFilter() != null) filters++;
        }
        List<String> out = new ArrayList<>();
        if (pics > 0) out.add(pics + " picture(s)");
        if (draw > 0) out.add(draw + " drawing(s)");
        if (charts > 0) out.add(charts + " chart(s)");
        if (rules > 0) out.add(rules + " conditional formatting rule(s)");
        if (filters > 0) out.add(filters + " filter(s)");
        if (sparks > 0) out.add(sparks + " sparkline(s)");
        return out;
    }

    public static void write(File file, List<WorkbookIO.SheetData> sheets, int activeIndex) throws IOException {
        if (sheets.isEmpty()) throw new IOException("Nothing to export");
        Styles styles = new Styles();
        List<String> sharedStrings = new ArrayList<>();
        Map<String, Integer> sharedIndex = new HashMap<>();
        List<String> names = new ArrayList<>();
        List<byte[]> sheetXml = new ArrayList<>();
        for (WorkbookIO.SheetData sd : sheets) {
            sheetName(sd.name, names); // adds the unique, Excel-safe name to the list
            sheetXml.add(sheetXml(sd.model, styles, sharedStrings, sharedIndex).getBytes(StandardCharsets.UTF_8));
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            StringBuilder ct = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                    + "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>");
            for (int i = 0; i < sheets.size(); i++) {
                ct.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            }
            ct.append("</Types>");
            put(zip, "[Content_Types].xml", ct.toString());
            put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");

            StringBuilder wb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<workbook xmlns=\"" + NS_MAIN + "\" xmlns:r=\"" + NS_REL + "\"><bookViews><workbookView activeTab=\""
                    + Math.max(0, Math.min(activeIndex, sheets.size() - 1)) + "\"/></bookViews><sheets>");
            StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            for (int i = 0; i < sheets.size(); i++) {
                wb.append("<sheet name=\"").append(esc(names.get(i))).append("\" sheetId=\"").append(i + 1)
                        .append("\" r:id=\"rId").append(i + 1).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(i + 1)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                        .append(i + 1).append(".xml\"/>");
            }
            wb.append("</sheets></workbook>");
            int n = sheets.size();
            rels.append("<Relationship Id=\"rId").append(n + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
                    .append("<Relationship Id=\"rId").append(n + 2)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>")
                    .append("</Relationships>");
            put(zip, "xl/workbook.xml", wb.toString());
            put(zip, "xl/_rels/workbook.xml.rels", rels.toString());
            put(zip, "xl/styles.xml", styles.toXml());

            StringBuilder ss = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<sst xmlns=\""
                    + NS_MAIN + "\" count=\"" + sharedStrings.size() + "\" uniqueCount=\"" + sharedStrings.size() + "\">");
            for (String s : sharedStrings) {
                boolean keep = !s.isEmpty() && (Character.isWhitespace(s.charAt(0)) || Character.isWhitespace(s.charAt(s.length() - 1))
                        || s.contains("\n"));
                ss.append("<si><t").append(keep ? " xml:space=\"preserve\"" : "").append(">").append(esc(s)).append("</t></si>");
            }
            ss.append("</sst>");
            put(zip, "xl/sharedStrings.xml", ss.toString());
            for (int i = 0; i < sheetXml.size(); i++) {
                zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + (i + 1) + ".xml"));
                zip.write(sheetXml.get(i));
                zip.closeEntry();
            }
        }
        Path target = file.toPath().toAbsolutePath();
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bos.toByteArray());
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String sheetXml(SpreadsheetModel m, Styles styles, List<String> shared, Map<String, Integer> sharedIndex) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<worksheet xmlns=\"").append(NS_MAIN)
                .append("\"><sheetViews><sheetView workbookViewId=\"0\"/></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/>");
        boolean anyWidth = false;
        for (int c = 0; c < m.getColumnCount(); c++) if (m.getColumnWidth(c) != SpreadsheetModel.DEFAULT_COLUMN_WIDTH) anyWidth = true;
        if (anyWidth) {
            sb.append("<cols>");
            for (int c = 0; c < m.getColumnCount(); c++) {
                if (m.getColumnWidth(c) == SpreadsheetModel.DEFAULT_COLUMN_WIDTH) continue;
                sb.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"")
                        .append(String.format(Locale.ROOT, "%.2f", m.getColumnWidth(c) / 7.0)).append("\" customWidth=\"1\"/>");
            }
            sb.append("</cols>");
        }
        sb.append("<sheetData>");
        int[][] stored = m.storedCells(); // sparse and sorted by row, then column
        int pos = 0;
        while (pos < stored.length) {
            int r = stored[pos][0];
            StringBuilder row = new StringBuilder();
            for (; pos < stored.length && stored[pos][0] == r; pos++) {
                int c = stored[pos][1];
                String input = m.getInput(r, c);
                CellStyle st = m.getStyle(r, c);
                if (input.isEmpty() && st == null) continue;
                String ref = FormulaEngine.columnLabel(c) + (r + 1);
                int xf = styles.xfFor(st);
                row.append("<c r=\"").append(ref).append('"');
                if (xf != 0) row.append(" s=\"").append(xf).append('"');
                if (input.isEmpty()) {
                    row.append("/>");
                    continue;
                }
                Object v = m.getComputedValue(r, c);
                boolean formula = input.startsWith("=");
                if (formula) {
                    String f = esc(input.substring(1));
                    if (v instanceof Double) {
                        row.append("><f>").append(f).append("</f><v>").append(num((Double) v)).append("</v></c>");
                    } else if (v instanceof Boolean) {
                        row.append(" t=\"b\"><f>").append(f).append("</f><v>").append(((Boolean) v) ? 1 : 0).append("</v></c>");
                    } else if (v != null && FormulaEngine.isErrorCode(v.toString())) {
                        row.append(" t=\"e\"><f>").append(f).append("</f><v>").append(excelError(v.toString())).append("</v></c>");
                    } else {
                        row.append(" t=\"str\"><f>").append(f).append("</f><v>").append(esc(v == null ? "" : v.toString())).append("</v></c>");
                    }
                } else if (v instanceof Double) {
                    row.append("><v>").append(num((Double) v)).append("</v></c>");
                } else if (v instanceof Boolean) {
                    row.append(" t=\"b\"><v>").append(((Boolean) v) ? 1 : 0).append("</v></c>");
                } else {
                    String text = v == null ? input : v.toString();
                    Integer idx = sharedIndex.get(text);
                    if (idx == null) {
                        idx = shared.size();
                        shared.add(text);
                        sharedIndex.put(text, idx);
                    }
                    row.append(" t=\"s\"><v>").append(idx).append("</v></c>");
                }
            }
            if (row.length() > 0) sb.append("<row r=\"").append(r + 1).append("\">").append(row).append("</row>");
        }
        sb.append("</sheetData>");
        List<ValidationRule> rules = m.getValidations();
        if (!rules.isEmpty()) {
            sb.append("<dataValidations count=\"").append(rules.size()).append("\">");
            for (ValidationRule v : rules) {
                if (!v.isComplete()) continue;
                sb.append("<dataValidation type=\"").append(v.type == ValidationRule.Type.LIST ? "list"
                        : v.type == ValidationRule.Type.WHOLE ? "whole" : v.type == ValidationRule.Type.DECIMAL ? "decimal"
                        : "textLength").append('"');
                if (v.type != ValidationRule.Type.LIST) sb.append(" operator=\"").append(OPS[v.op.ordinal()]).append('"');
                sb.append(" errorStyle=\"").append(v.alert == ValidationRule.Alert.STOP ? "stop"
                        : v.alert == ValidationRule.Alert.WARNING ? "warning" : "information").append('"');
                if (v.allowBlank) sb.append(" allowBlank=\"1\"");
                if (!v.dropdown) sb.append(" showDropDown=\"1\""); // sic: 1 hides the arrow in Excel's file format
                sb.append(" showErrorMessage=\"1\"");
                if (!v.title.isEmpty()) sb.append(" errorTitle=\"").append(esc(v.title.length() > 32 ? v.title.substring(0, 32) : v.title)).append('"');
                if (!v.message.isEmpty()) sb.append(" error=\"").append(esc(v.message.length() > 255 ? v.message.substring(0, 255) : v.message)).append('"');
                sb.append(" sqref=\"").append(v.rangeText()).append("\"><formula1>");
                if (v.type == ValidationRule.Type.LIST) {
                    String src = v.v1.trim();
                    if (src.matches("^=?\\$?[A-Za-z]{1,3}\\$?\\d+\\s*:\\s*\\$?[A-Za-z]{1,3}\\$?\\d+$")) {
                        sb.append(esc(src.startsWith("=") ? src.substring(1) : src));
                    } else {
                        sb.append("&quot;").append(esc(src.replace("\"", ""))).append("&quot;");
                    }
                } else {
                    sb.append(esc(v.v1.trim()));
                }
                sb.append("</formula1>");
                if (v.type != ValidationRule.Type.LIST && v.op.needsTwo()) sb.append("<formula2>").append(esc(v.v2.trim())).append("</formula2>");
                sb.append("</dataValidation>");
            }
            sb.append("</dataValidations>");
        }
        sb.append("</worksheet>");
        return sb.toString();
    }

    /** Excel only accepts its own error codes; anything else (like this app's #ERROR!) becomes #VALUE!. */
    private static String excelError(String code) {
        switch (code) {
            case "#NULL!":
            case "#DIV/0!":
            case "#VALUE!":
            case "#REF!":
            case "#NAME?":
            case "#NUM!":
            case "#N/A":
                return code;
            default:
                return "#VALUE!";
        }
    }

    private static final String[] OPS = {"between", "notBetween", "equal", "notEqual", "greaterThan", "lessThan",
            "greaterThanOrEqual", "lessThanOrEqual"};

    private static String num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return Double.toString(d);
    }

    // ==================================================================
    // Reading
    // ==================================================================

    private static byte[] readPart(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_PART_BYTES) throw new IOException("A part of the file is too large");
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static Document parseDom(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new java.io.ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IOException("The file contains invalid XML: " + e.getMessage(), e);
        }
    }

    private static List<Element> children(Element parent, String local) {
        List<Element> list = new ArrayList<>();
        if (parent == null) return list;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && local.equals(n.getLocalName())) list.add((Element) n);
        }
        return list;
    }

    private static Element child(Element parent, String local) {
        List<Element> l = children(parent, local);
        return l.isEmpty() ? null : l.get(0);
    }

    private static int intAttr(Element e, String name, int def) {
        if (e == null || !e.hasAttribute(name)) return def;
        try {
            return Integer.parseInt(e.getAttribute(name).trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /** Text of an <si> / <is> element: all <t> pieces except phonetic runs. */
    private static void collectText(Node n, StringBuilder sb) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (!(c instanceof Element)) continue;
            String ln = c.getLocalName();
            if ("rPh".equals(ln) || "phoneticPr".equals(ln)) continue;
            if ("t".equals(ln)) sb.append(c.getTextContent());
            else collectText(c, sb);
        }
    }

    private static final class ReadStyles {
        final Map<Integer, String> numFmtCodes = new HashMap<>();
        final List<Element> fonts = new ArrayList<>();
        final List<Element> fills = new ArrayList<>();
        final List<Element> xfs = new ArrayList<>();
        final Map<Integer, CellStyle> cache = new HashMap<>();
        final Map<Integer, Boolean> dateCache = new HashMap<>();

        boolean isDate(int xf) {
            return dateCache.computeIfAbsent(xf, k -> {
                if (k < 0 || k >= xfs.size()) return false;
                int id = intAttr(xfs.get(k), "numFmtId", 0);
                return isDateFormat(id, numFmtCodes.get(id));
            });
        }

        CellStyle style(int xf) {
            if (xf <= 0 || xf >= xfs.size()) return null;
            return cache.computeIfAbsent(xf, k -> build(xfs.get(k))).isDefault() ? null : cache.get(xf);
        }

        private CellStyle build(Element x) {
            CellStyle st = new CellStyle();
            int fontId = intAttr(x, "fontId", 0);
            if (fontId > 0 && fontId < fonts.size()) {
                Element f = fonts.get(fontId);
                Element b = child(f, "b");
                st.bold = b != null && !"0".equals(b.getAttribute("val")) && !"false".equals(b.getAttribute("val"));
                Element i = child(f, "i");
                st.italic = i != null && !"0".equals(i.getAttribute("val")) && !"false".equals(i.getAttribute("val"));
                Element sz = child(f, "sz");
                if (sz != null) {
                    try {
                        int size = (int) Math.round(Double.parseDouble(sz.getAttribute("val")));
                        if (size >= 6 && size <= 96 && size != 11) st.fontSize = size;
                    } catch (NumberFormatException ignored) {
                        // keep default size
                    }
                }
                Element nm = child(f, "name");
                if (nm != null && !nm.getAttribute("val").isEmpty() && !"Calibri".equalsIgnoreCase(nm.getAttribute("val"))) {
                    st.fontFamily = nm.getAttribute("val");
                }
                Color col = rgb(child(f, "color"));
                if (col != null && !col.equals(Color.BLACK)) st.foreground = col;
            }
            int fillId = intAttr(x, "fillId", 0);
            if (fillId > 1 && fillId < fills.size()) {
                Element pf = child(fills.get(fillId), "patternFill");
                if (pf != null && "solid".equals(pf.getAttribute("patternType"))) {
                    Color col = rgb(child(pf, "fgColor"));
                    if (col != null && !col.equals(Color.WHITE)) st.background = col;
                }
            }
            int id = intAttr(x, "numFmtId", 0);
            applyNumberFormat(st, id, numFmtCodes.get(id));
            Element al = child(x, "alignment");
            if (al != null) {
                String h = al.getAttribute("horizontal");
                if ("left".equals(h)) st.alignment = SwingConstants.LEFT;
                else if ("center".equals(h) || "centerContinuous".equals(h)) st.alignment = SwingConstants.CENTER;
                else if ("right".equals(h)) st.alignment = SwingConstants.RIGHT;
            }
            return st;
        }
    }

    private static Color rgb(Element e) {
        if (e == null) return null;
        String v = e.getAttribute("rgb");
        if (v.length() == 8 || v.length() == 6) {
            try {
                return new Color(Integer.parseInt(v.substring(v.length() - 6), 16));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null; // theme / indexed colours are not resolved
    }

    private static boolean isDateFormat(int id, String code) {
        if ((id >= 14 && id <= 22) || (id >= 27 && id <= 36) || (id >= 45 && id <= 47) || (id >= 50 && id <= 58)) return true;
        if (code == null) return false;
        String c = code.replaceAll("\"[^\"]*\"", "").replaceAll("\\[[^\\]]*\\]", "").replaceAll("\\\\.", "");
        c = c.toLowerCase(Locale.ROOT);
        if (c.contains("#") || c.contains("0")) return false;
        return Pattern.compile("[ymdhs]").matcher(c).find();
    }

    private static void applyNumberFormat(CellStyle st, int id, String code) {
        switch (id) {
            case 1:
                st.numFmt = CellFormats.NUMBER;
                st.decimals = 0;
                return;
            case 2:
                st.numFmt = CellFormats.NUMBER;
                st.decimals = 2;
                return;
            case 3:
                st.numFmt = CellFormats.COMMA;
                st.decimals = 0;
                return;
            case 4:
                st.numFmt = CellFormats.COMMA;
                st.decimals = 2;
                return;
            case 9:
                st.numFmt = CellFormats.PERCENT;
                st.decimals = 0;
                return;
            case 10:
                st.numFmt = CellFormats.PERCENT;
                st.decimals = 2;
                return;
            case 11:
            case 48:
                st.numFmt = CellFormats.SCIENTIFIC;
                st.decimals = 2;
                return;
            default:
                break;
        }
        if (code == null || code.equalsIgnoreCase("General")) return;
        String first = code.split(";", -1)[0];
        String clean = first.replaceAll("\"[^\"]*\"", "").replaceAll("\\[[^\\]]*\\]", "");
        int dot = clean.indexOf('.');
        int dec = 0;
        if (dot >= 0) {
            for (int i = dot + 1; i < clean.length() && clean.charAt(i) == '0'; i++) dec++;
        }
        if (clean.contains("%")) {
            st.numFmt = CellFormats.PERCENT;
        } else if (clean.toUpperCase(Locale.ROOT).contains("E+") || clean.toUpperCase(Locale.ROOT).contains("E-")) {
            st.numFmt = CellFormats.SCIENTIFIC;
        } else if (first.contains("$") || first.contains("€") || first.contains("£") || first.contains("¥")) {
            st.numFmt = CellFormats.CURRENCY;
        } else if (clean.contains(",") && clean.contains("0")) {
            st.numFmt = CellFormats.COMMA;
        } else if (clean.contains("0")) {
            st.numFmt = CellFormats.NUMBER;
        } else {
            return;
        }
        st.decimals = dec;
    }

    private static final class RawCell {
        int row, col, xf;
        String input;   // what to type into the cell ("" = none)
        String cached;  // last result of a formula, as text
        boolean formula;
    }

    private static final class SheetRef {
        String name;
        String path;
    }

    public static Result read(File file) throws IOException {
        if (file.length() > MAX_FILE_BYTES) throw new IOException("File is too large");
        Result result = new Result();
        try (ZipFile zip = new ZipFile(file)) {
            byte[] wbBytes = readPart(zip, "xl/workbook.xml");
            if (wbBytes == null) throw new IOException("This is not an Excel (.xlsx) workbook");
            Document wb = parseDom(wbBytes);
            Element sheetsEl = child(wb.getDocumentElement(), "sheets");

            Map<String, String> targets = new HashMap<>();
            byte[] relBytes = readPart(zip, "xl/_rels/workbook.xml.rels");
            if (relBytes != null) {
                Document rd = parseDom(relBytes);
                NodeList rels = rd.getDocumentElement().getChildNodes();
                for (int i = 0; i < rels.getLength(); i++) {
                    if (!(rels.item(i) instanceof Element)) continue;
                    Element r = (Element) rels.item(i);
                    targets.put(r.getAttribute("Id"), r.getAttribute("Target"));
                }
            }
            List<SheetRef> refs = new ArrayList<>();
            for (Element sh : children(sheetsEl, "sheet")) {
                SheetRef ref = new SheetRef();
                ref.name = sh.getAttribute("name");
                String rid = sh.getAttributeNS(NS_REL, "id");
                String t = targets.get(rid);
                if (t == null) continue;
                t = t.startsWith("/") ? t.substring(1) : "xl/" + t;
                ref.path = normalize(t);
                refs.add(ref);
            }
            if (refs.isEmpty()) throw new IOException("The workbook has no worksheets");
            if (refs.size() > 255) {
                result.notes.add("Only the first 255 sheets were opened.");
                refs = refs.subList(0, 255);
            }
            Element wv = child(child(wb.getDocumentElement(), "bookViews"), "workbookView");
            result.activeIndex = Math.max(0, Math.min(intAttr(wv, "activeTab", 0), refs.size() - 1));

            List<String> shared = new ArrayList<>();
            byte[] ssBytes = readPart(zip, "xl/sharedStrings.xml");
            if (ssBytes != null) {
                Element sst = parseDom(ssBytes).getDocumentElement();
                for (Element si : children(sst, "si")) {
                    StringBuilder sb = new StringBuilder();
                    collectText(si, sb);
                    shared.add(sb.toString());
                }
            }

            ReadStyles styles = new ReadStyles();
            byte[] stBytes = readPart(zip, "xl/styles.xml");
            if (stBytes != null) {
                Element root = parseDom(stBytes).getDocumentElement();
                for (Element nf : children(child(root, "numFmts"), "numFmt")) {
                    styles.numFmtCodes.put(intAttr(nf, "numFmtId", -1), nf.getAttribute("formatCode"));
                }
                styles.fonts.addAll(children(child(root, "fonts"), "font"));
                styles.fills.addAll(children(child(root, "fills"), "fill"));
                styles.xfs.addAll(children(child(root, "cellXfs"), "xf"));
            }

            List<String> usedNames = new ArrayList<>();
            int[] stats = new int[4]; // truncated cells, formulas turned into values, skipped validation rules
            for (SheetRef ref : refs) {
                if (zip.getEntry(ref.path) == null) {
                    result.notes.add("Sheet \"" + ref.name + "\" is missing from the file and was skipped.");
                    continue;
                }
                String name = ref.name.isEmpty() ? "Sheet" + (usedNames.size() + 1) : ref.name;
                if (name.length() > 60) name = name.substring(0, 60);
                String base = name;
                int k = 2;
                while (containsIgnoreCase(usedNames, name)) name = base + " (" + k++ + ")";
                usedNames.add(name);
                result.sheets.add(new WorkbookIO.SheetData(name, readSheet(zip, ref.path, shared, styles, stats)));
            }
            if (result.sheets.isEmpty()) throw new IOException("The workbook has no readable worksheets");
            if (stats[0] > 0) result.notes.add(stats[0] + " cell(s) beyond the supported size ("
                    + MAX_ROWS + " rows x " + MAX_COLS + " columns) were left out.");
            if (stats[1] > 0) result.notes.add(stats[1] + " formula(s) use features this app does not support yet "
                    + "(for example other sheets or newer Excel functions) and were replaced by their last values.");
            if (stats[2] > 0) result.notes.add(stats[2] + " data validation rule(s) could not be carried over "
                    + "(they use other sheets, named ranges or custom formulas).");
            result.activeIndex = Math.min(result.activeIndex, result.sheets.size() - 1);
        } catch (java.util.zip.ZipException e) {
            throw new IOException("This is not a valid Excel (.xlsx) file: " + e.getMessage(), e);
        }
        return result;
    }

    private static String normalize(String path) {
        List<String> out = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
            } else {
                out.add(part);
            }
        }
        return String.join("/", out);
    }

    private static SpreadsheetModel readSheet(ZipFile zip, String path, List<String> shared, ReadStyles styles, int[] stats)
            throws IOException {
        ZipEntry entry = zip.getEntry(path);
        List<RawCell> cells = new ArrayList<>();
        Map<Integer, Double> widths = new LinkedHashMap<>();
        Map<Integer, Object[]> sharedFormulas = new HashMap<>(); // si -> {text, row, col}
        List<String[]> validations = new ArrayList<>();          // {type, op, blank, hideArrow, style, title, error, sqref, f1, f2}
        String[] dv = null;
        int dvSection = 0;
        int maxRow = 0;
        int maxCol = 0;
        int skipped = 0;

        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        try (InputStream in = new LimitedStream(zip.getInputStream(entry), MAX_PART_BYTES)) {
            XMLStreamReader x = f.createXMLStreamReader(in);
            RawCell cur = null;
            String type = null;
            StringBuilder text = null;       // collects <v> / <t> / <f> text
            String section = null;           // "v", "f", "t" (inside <is>)
            String fType = null;
            int fSi = -1;
            StringBuilder fText = new StringBuilder();
            StringBuilder vText = new StringBuilder();
            StringBuilder isText = new StringBuilder();
            boolean inIs = false;
            int autoRow = 0;
            int autoCol = 0;
            while (x.hasNext()) {
                int ev = x.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String ln = x.getLocalName();
                    if ("row".equals(ln)) {
                        String rr = x.getAttributeValue(null, "r");
                        autoRow = rr != null ? parseIntSafe(rr, autoRow + 1) - 1 : autoRow + 1;
                        autoCol = 0;
                        if (rr == null && autoRow == 0 && !cells.isEmpty()) autoRow = 1;
                    } else if ("col".equals(ln)) {
                        int min = parseIntSafe(x.getAttributeValue(null, "min"), 0);
                        int max = parseIntSafe(x.getAttributeValue(null, "max"), 0);
                        String w = x.getAttributeValue(null, "width");
                        if (w != null && min >= 1) {
                            try {
                                double wd = Double.parseDouble(w);
                                for (int c = min - 1; c < Math.min(max, MAX_COLS); c++) widths.put(c, wd);
                            } catch (NumberFormatException ignored) {
                                // ignore a bad width
                            }
                        }
                    } else if ("c".equals(ln)) {
                        cur = new RawCell();
                        String r = x.getAttributeValue(null, "r");
                        int[] rc = r == null ? null : parseRef(r);
                        if (rc == null) rc = new int[]{autoRow, autoCol};
                        cur.row = rc[0];
                        cur.col = rc[1];
                        autoCol = rc[1] + 1;
                        cur.xf = parseIntSafe(x.getAttributeValue(null, "s"), 0);
                        type = x.getAttributeValue(null, "t");
                        fText.setLength(0);
                        vText.setLength(0);
                        isText.setLength(0);
                        fType = null;
                        fSi = -1;
                        section = null;
                        inIs = false;
                        cur.formula = false;
                    } else if ("dataValidation".equals(ln)) {
                        dv = new String[]{attr(x, "type"), attr(x, "operator"), attr(x, "allowBlank"), attr(x, "showDropDown"),
                                attr(x, "errorStyle"), attr(x, "errorTitle"), attr(x, "error"), attr(x, "sqref"), "", ""};
                    } else if (dv != null && "formula1".equals(ln)) {
                        dvSection = 8;
                    } else if (dv != null && "formula2".equals(ln)) {
                        dvSection = 9;
                    } else if (cur != null && "f".equals(ln)) {
                        section = "f";
                        cur.formula = true;
                        fType = x.getAttributeValue(null, "t");
                        fSi = parseIntSafe(x.getAttributeValue(null, "si"), -1);
                        if ("shared".equals(fType) && x.getAttributeValue(null, "ref") != null) {
                            sharedFormulas.put(fSi, new Object[]{null, cur.row, cur.col});
                        }
                    } else if (cur != null && "v".equals(ln)) {
                        section = "v";
                    } else if (cur != null && "is".equals(ln)) {
                        inIs = true;
                    } else if (cur != null && inIs && "t".equals(ln)) {
                        section = "t";
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    if (dv != null && dvSection > 0) {
                        dv[dvSection] += x.getText();
                        continue;
                    }
                    if (cur == null || section == null) continue;
                    String t = x.getText();
                    if ("f".equals(section)) fText.append(t);
                    else if ("v".equals(section)) vText.append(t);
                    else if ("t".equals(section)) isText.append(t);
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String ln = x.getLocalName();
                    if ("formula1".equals(ln) || "formula2".equals(ln)) {
                        dvSection = 0;
                    } else if ("dataValidation".equals(ln)) {
                        if (dv != null) validations.add(dv);
                        dv = null;
                        dvSection = 0;
                    } else if ("f".equals(ln) || "v".equals(ln) || "t".equals(ln)) {
                        section = null;
                    } else if ("is".equals(ln)) {
                        inIs = false;
                    } else if ("c".equals(ln) && cur != null) {
                        finishCell(cur, type, shared, styles, fType, fSi, fText.toString(), vText.toString(),
                                isText.toString(), sharedFormulas);
                        if (cur.row >= MAX_ROWS || cur.col >= MAX_COLS || cells.size() >= MAX_CELLS) {
                            if (cur.input != null && !cur.input.isEmpty()) skipped++;
                        } else if ((cur.input != null && !cur.input.isEmpty()) || styles.style(cur.xf) != null) {
                            cells.add(cur);
                            maxRow = Math.max(maxRow, cur.row);
                            maxCol = Math.max(maxCol, cur.col);
                        }
                        cur = null;
                    }
                }
            }
            x.close();
        } catch (XMLStreamException e) {
            throw new IOException("The sheet XML is damaged: " + e.getMessage(), e);
        }
        stats[0] += skipped;

        int rows = Math.min(MAX_ROWS, Math.max(SpreadsheetModel.DEFAULT_ROWS, maxRow + 21));
        int cols = Math.min(MAX_COLS, Math.max(SpreadsheetModel.DEFAULT_COLS, maxCol + 3));
        SpreadsheetModel m = new SpreadsheetModel(rows, cols);
        m.beginBatch();
        try {
            for (RawCell c : cells) {
                CellStyle st = styles.style(c.xf);
                m.setInputs(c.row, c.col, c.input == null ? "" : c.input, st == null ? null : st.copy());
            }
            for (Map.Entry<Integer, Double> e : widths.entrySet()) {
                if (e.getKey() < cols) {
                    int px = (int) Math.round(e.getValue() * 7);
                    m.setColumnWidth(e.getKey(), Math.max(SpreadsheetModel.MIN_COLUMN_WIDTH,
                            Math.min(SpreadsheetModel.MAX_COLUMN_WIDTH, px)));
                }
            }
        } finally {
            m.endBatch();
        }
        for (String[] d : validations) addValidation(m, d, stats);
        // formulas this app cannot calculate keep their last Excel result instead of showing an error
        m.beginBatch();
        try {
            for (RawCell c : cells) {
                if (!c.formula || c.input == null || !c.input.startsWith("=")) continue;
                Object v = m.getComputedValue(c.row, c.col);
                String s = v == null ? "" : v.toString();
                boolean broken = s.equals("#NAME?") || s.equals("#SYNTAX!") || s.equals("#ERROR!");
                if (broken && c.cached != null && !c.cached.isEmpty() && !c.cached.startsWith("#")) {
                    CellStyle st = styles.style(c.xf);
                    m.setInputs(c.row, c.col, c.cached, st == null ? null : st.copy());
                    stats[1]++;
                }
            }
        } finally {
            m.endBatch();
        }
        return m;
    }

    private static String attr(XMLStreamReader x, String name) {
        String v = x.getAttributeValue(null, name);
        return v == null ? "" : v;
    }

    private static void addValidation(SpreadsheetModel m, String[] d, int[] stats) {
        ValidationRule.Type type;
        switch (d[0]) {
            case "list":
                type = ValidationRule.Type.LIST;
                break;
            case "whole":
                type = ValidationRule.Type.WHOLE;
                break;
            case "decimal":
                type = ValidationRule.Type.DECIMAL;
                break;
            case "textLength":
                type = ValidationRule.Type.TEXT_LENGTH;
                break;
            default:
                stats[2]++;
                return;
        }
        ValidationRule.Op op = ValidationRule.Op.BETWEEN;
        for (int i = 0; i < OPS.length; i++) if (OPS[i].equals(d[1])) op = ValidationRule.Op.values()[i];
        String f1 = d[8].trim();
        String f2 = d[9].trim();
        String v1;
        if (type == ValidationRule.Type.LIST) {
            if (f1.length() >= 2 && f1.startsWith("\"") && f1.endsWith("\"")) v1 = f1.substring(1, f1.length() - 1);
            else if (f1.matches("^\\$?[A-Za-z]{1,3}\\$?\\d+\\s*:\\s*\\$?[A-Za-z]{1,3}\\$?\\d+$")) v1 = "=" + f1;
            else {
                stats[2]++; // a list that points at another sheet or a named range
                return;
            }
        } else {
            v1 = f1;
        }
        ValidationRule.Alert alert = "warning".equals(d[4]) ? ValidationRule.Alert.WARNING
                : "information".equals(d[4]) ? ValidationRule.Alert.INFORMATION : ValidationRule.Alert.STOP;
        for (String ref : d[7].trim().split("\\s+")) {
            int[] a;
            int[] b;
            String[] parts = ref.split(":");
            a = parseRef(parts[0]);
            b = parts.length > 1 ? parseRef(parts[1]) : a;
            if (a == null || b == null || !m.inBounds(a[0], a[1]) || !m.inBounds(b[0], b[1])) continue;
            ValidationRule rule = new ValidationRule(type, op, a[0], a[1], b[0], b[1], v1, f2, !"0".equals(d[2]) && !"false".equals(d[2]),
                    !"1".equals(d[3]) && !"true".equals(d[3]), alert, d[5], d[6]);
            if (rule.isComplete()) m.addValidation(rule);
            else stats[2]++;
        }
    }

    private static void finishCell(RawCell c, String type, List<String> shared, ReadStyles styles, String fType, int fSi,
                                   String fText, String vText, String isText, Map<Integer, Object[]> sharedFormulas) {
        String raw = vText.trim();
        String value = null;
        if ("s".equals(type)) {
            int idx = parseIntSafe(raw, -1);
            value = idx >= 0 && idx < shared.size() ? shared.get(idx) : "";
        } else if ("inlineStr".equals(type)) {
            value = isText;
        } else if ("str".equals(type)) {
            value = vText;
        } else if ("b".equals(type)) {
            value = "1".equals(raw) ? "TRUE" : "FALSE";
        } else if ("e".equals(type)) {
            value = raw;
        } else if (!raw.isEmpty()) {
            value = raw;
            if (styles.isDate(c.xf)) {
                try {
                    value = excelDate(Double.parseDouble(raw));
                } catch (NumberFormatException ignored) {
                    // keep the raw number
                }
            }
        }
        c.cached = value;

        if (c.formula) {
            String text = fText.trim();
            if ("shared".equals(fType) && fSi >= 0) {
                Object[] master = sharedFormulas.get(fSi);
                if (!text.isEmpty()) {
                    if (master != null) master[0] = text;
                    else sharedFormulas.put(fSi, new Object[]{text, c.row, c.col});
                } else if (master != null && master[0] != null) {
                    String t = "=" + master[0];
                    text = FormulaRefs.translate(t, c.row - (Integer) master[1], c.col - (Integer) master[2]).substring(1);
                }
            }
            if (text.isEmpty()) {
                c.formula = false;
                c.input = value == null ? "" : value;
            } else {
                text = text.replace("_xlfn.", "").replace("_xlws.", "");
                c.input = "=" + text;
            }
        } else {
            c.input = value == null ? "" : value;
            if ("e".equals(type)) c.input = value; // an error value typed as text
        }
    }

    private static String excelDate(double serial) {
        long days = (long) Math.floor(serial);
        double frac = serial - days;
        if (days < 1) return String.valueOf(serial);
        LocalDate d = LocalDate.of(1899, 12, 30).plusDays(days);
        if (frac < 1e-9) return d.toString();
        long secs = Math.round(frac * 86400);
        LocalDateTime dt = d.atStartOfDay().plusSeconds(secs);
        return String.format("%s %02d:%02d", dt.toLocalDate(), dt.getHour(), dt.getMinute());
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** "B12" -> {11, 1}; null when it is not a cell reference. */
    private static int[] parseRef(String ref) {
        int i = 0;
        int col = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
            if (i > 4) return null;
        }
        if (i == 0 || i == ref.length()) return null;
        int row;
        try {
            row = Integer.parseInt(ref.substring(i));
        } catch (NumberFormatException e) {
            return null;
        }
        if (row < 1) return null;
        return new int[]{row - 1, col - 1};
    }

    /** Stops a hostile file from inflating into an enormous stream. */
    private static final class LimitedStream extends java.io.FilterInputStream {
        private long left;

        LimitedStream(InputStream in, long limit) {
            super(in);
            left = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && --left < 0) throw new IOException("A part of the file is too large");
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) {
                left -= n;
                if (left < 0) throw new IOException("A part of the file is too large");
            }
            return n;
        }
    }
}

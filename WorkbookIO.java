import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * File layer: saves and loads whole workbooks in the native ".spx" format.
 *
 * Layout: a 4-byte magic number followed by a GZIP-compressed binary payload
 * (sheets, cell inputs, styles, protection hashes, pictures). When an open
 * password is set the payload is instead encrypted with AES-256-GCM using a key
 * derived from the password with PBKDF2-HMAC-SHA256; a wrong password makes the
 * authentication tag fail, so it can be told apart from a good file.
 */
public final class WorkbookIO {

    public static final String EXTENSION = "spx";

    private static final int MAGIC_PLAIN = 0x53505831;     // "SPX1"
    private static final int MAGIC_ENCRYPTED = 0x53505832; // "SPX2"
    private static final int FORMAT_VERSION = 7; // 7 added page setup (printing); 6 added validation and sparklines; 5 added charts; 4 added fonts, number formats, rules and filter; 3 drawings; 2 zoom and widths; older still readable
    private static final int KDF_ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int MAX_FILE_BYTES = 512 * 1024 * 1024;
    private static final int MAX_STRING_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 64 * 1024 * 1024;

    private WorkbookIO() {
    }

    /** One sheet to save / one sheet that was loaded. */
    public static final class SheetData {
        public final String name;
        public final SpreadsheetModel model;

        public SheetData(String name, SpreadsheetModel model) {
            this.name = name;
            this.model = model;
        }
    }

    /** Result of {@link #load}. */
    public static final class WorkbookData {
        public final List<SheetData> sheets;
        public final int activeIndex;

        WorkbookData(List<SheetData> sheets, int activeIndex) {
            this.sheets = sheets;
            this.activeIndex = activeIndex;
        }
    }

    /** The file is encrypted and no password was supplied. */
    public static class PasswordRequiredException extends IOException {
        private static final long serialVersionUID = 1L;

        public PasswordRequiredException() {
            super("This workbook is protected with an open password.");
        }
    }

    /** The supplied open password is wrong (or the encrypted file is damaged). */
    public static class BadPasswordException extends IOException {
        private static final long serialVersionUID = 1L;

        public BadPasswordException() {
            super("Incorrect password (or the file is damaged).");
        }
    }

    // ==================================================================
    // Save
    // ==================================================================

    /**
     * Writes the workbook. A non-empty {@code password} encrypts the file.
     * The data goes to a temporary file first so an existing workbook is never left half-written.
     */
    public static void save(File file, List<SheetData> sheets, int activeIndex, char[] password)
            throws IOException {
        byte[] payload = serialize(sheets, activeIndex);
        byte[] out;
        if (password == null || password.length == 0) {
            out = new byte[4 + payload.length];
            putInt(out, 0, MAGIC_PLAIN);
            System.arraycopy(payload, 0, out, 4, payload.length);
        } else {
            out = encrypt(payload, password);
        }
        Path target = file.toPath().toAbsolutePath();
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, out);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] serialize(List<SheetData> sheets, int activeIndex) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos))) {
            out.writeInt(FORMAT_VERSION);
            out.writeInt(activeIndex);
            out.writeInt(sheets.size());
            for (SheetData s : sheets) writeSheet(out, s);
        }
        return bos.toByteArray();
    }

    private static void writeSheet(DataOutputStream out, SheetData s) throws IOException {
        SpreadsheetModel m = s.model;
        writeString(out, s.name);

        SheetProtection p = m.getProtection();
        byte[] salt = p.getSalt();
        byte[] hash = p.getHash();
        out.writeBoolean(p.isProtected());
        out.writeBoolean(salt != null && hash != null);
        if (salt != null && hash != null) {
            writeBytes(out, salt);
            writeBytes(out, hash);
        }

        int rows = m.getRowCount();
        int cols = m.getColumnCount();
        out.writeInt(rows);
        out.writeInt(cols);

        int[][] stored = m.storedCells(); // sparse: only cells that hold something
        int cells = 0;
        for (int[] pos : stored) if (!m.getInput(pos[0], pos[1]).isEmpty()) cells++;
        out.writeInt(cells);
        for (int[] pos : stored) {
            String text = m.getInput(pos[0], pos[1]);
            if (text.isEmpty()) continue;
            out.writeInt(pos[0]);
            out.writeInt(pos[1]);
            writeString(out, text);
        }

        int styled = 0;
        for (int[] pos : stored) if (m.getStyle(pos[0], pos[1]) != null) styled++;
        out.writeInt(styled);
        for (int[] pos : stored) {
            {
                int r = pos[0];
                int c = pos[1];
                CellStyle st = m.getStyle(r, c);
                if (st == null) continue;
                out.writeInt(r);
                out.writeInt(c);
                int flags = (st.bold ? 1 : 0) | (st.italic ? 2 : 0)
                        | (st.background != null ? 4 : 0) | (st.foreground != null ? 8 : 0)
                        | (st.fontFamily != null ? 16 : 0) | (st.numFmt != 0 ? 32 : 0);
                out.writeByte(flags);
                out.writeInt(st.alignment);
                out.writeInt(st.fontSize);
                if (st.background != null) out.writeInt(st.background.getRGB());
                if (st.foreground != null) out.writeInt(st.foreground.getRGB());
                if (st.fontFamily != null) writeString(out, st.fontFamily);
                if (st.numFmt != 0) {
                    out.writeByte(st.numFmt);
                    out.writeInt(st.decimals);
                }
            }
        }

        List<SheetImage> images = m.getImages();
        out.writeInt(images.size());
        for (SheetImage im : images) {
            out.writeInt(im.x);
            out.writeInt(im.y);
            out.writeInt(im.width);
            out.writeInt(im.height);
            writeBytes(out, im.getPngBytes());
        }

        // format 2: view settings
        out.writeInt(m.getZoomPercent());
        out.writeInt(cols);
        for (int c = 0; c < cols; c++) out.writeInt(m.getColumnWidth(c));

        // format 3: freehand drawings and shapes
        List<DrawShape> shapes = m.getShapes();
        out.writeInt(shapes.size());
        for (DrawShape sh : shapes) {
            out.writeByte(sh.type.ordinal());
            out.writeInt(sh.argb);
            out.writeFloat(sh.width);
            out.writeBoolean(sh.filled);
            out.writeInt(sh.pts.length / 2);
            for (float f : sh.pts) out.writeFloat(f);
        }

        // format 4: conditional formatting rules and the filter
        List<CondRule> rules = m.getCondRules();
        out.writeInt(rules.size());
        for (CondRule cr : rules) {
            out.writeByte(cr.type.ordinal());
            out.writeInt(cr.r1);
            out.writeInt(cr.c1);
            out.writeInt(cr.r2);
            out.writeInt(cr.c2);
            writeString(out, cr.v1);
            writeString(out, cr.v2);
            out.writeInt(cr.bg == null ? 0 : cr.bg.getRGB());
            out.writeBoolean(cr.fg != null);
            if (cr.fg != null) out.writeInt(cr.fg.getRGB());
        }
        AutoFilter af = m.getFilter();
        out.writeBoolean(af != null);
        if (af != null) {
            out.writeInt(af.headerRow);
            out.writeInt(af.firstCol);
            out.writeInt(af.lastCol);
            out.writeInt(af.allowed.size());
            for (java.util.Map.Entry<Integer, java.util.Set<String>> e : af.allowed.entrySet()) {
                out.writeInt(e.getKey());
                out.writeInt(e.getValue().size());
                for (String v : e.getValue()) writeString(out, v);
            }
        }

        // format 5: charts
        List<ChartSpec> charts = m.getCharts();
        out.writeInt(charts.size());
        for (ChartSpec ch : charts) {
            out.writeByte(ch.type.ordinal());
            writeString(out, ch.title);
            out.writeInt(ch.r1);
            out.writeInt(ch.c1);
            out.writeInt(ch.r2);
            out.writeInt(ch.c2);
            out.writeInt(ch.x);
            out.writeInt(ch.y);
            out.writeInt(ch.width);
            out.writeInt(ch.height);
            out.writeBoolean(ch.legend);
        }

        // format 6: data validation and sparklines
        List<ValidationRule> vals = m.getValidations();
        out.writeInt(vals.size());
        for (ValidationRule v : vals) {
            out.writeByte(v.type.ordinal());
            out.writeByte(v.op.ordinal());
            out.writeInt(v.r1);
            out.writeInt(v.c1);
            out.writeInt(v.r2);
            out.writeInt(v.c2);
            writeString(out, v.v1);
            writeString(out, v.v2);
            out.writeBoolean(v.allowBlank);
            out.writeBoolean(v.dropdown);
            out.writeByte(v.alert.ordinal());
            writeString(out, v.title);
            writeString(out, v.message);
        }
        List<Sparkline> sparks = m.getSparklines();
        out.writeInt(sparks.size());
        for (Sparkline sp : sparks) {
            out.writeByte(sp.type.ordinal());
            out.writeInt(sp.row);
            out.writeInt(sp.col);
            out.writeInt(sp.r1);
            out.writeInt(sp.c1);
            out.writeInt(sp.r2);
            out.writeInt(sp.c2);
            out.writeInt(sp.color.getRGB());
        }

        // format 7: page setup for printing
        PageSetup ps = m.getPageSetup();
        out.writeBoolean(!ps.isDefault());
        if (!ps.isDefault()) ps.write(out);
    }

    // ==================================================================
    // Load
    // ==================================================================

    /**
     * Reads a workbook.
     * @param password the open password, or null if none is known yet
     * @throws PasswordRequiredException if the file is encrypted and {@code password} is null/empty
     * @throws BadPasswordException      if the password does not decrypt the file
     */
    public static WorkbookData load(File file, char[] password) throws IOException {
        if (file.length() > MAX_FILE_BYTES) throw new IOException("File is too large");
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length < 8) throw new IOException("Not a Spreadsheet Pro workbook");
        int magic = getInt(bytes, 0);
        byte[] payload;
        if (magic == MAGIC_PLAIN) {
            payload = Arrays.copyOfRange(bytes, 4, bytes.length);
        } else if (magic == MAGIC_ENCRYPTED) {
            payload = decrypt(bytes, password);
        } else {
            throw new IOException("Not a Spreadsheet Pro workbook");
        }
        try {
            return parse(payload);
        } catch (RuntimeException e) {
            throw new IOException("The workbook file is damaged: " + e.getMessage(), e);
        }
    }

    private static WorkbookData parse(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(payload)))) {
            int version = in.readInt();
            if (version < 1 || version > FORMAT_VERSION) {
                throw new IOException("This workbook was made by a newer version (format " + version + ")");
            }
            int active = in.readInt();
            int count = in.readInt();
            if (count < 1 || count > 255) throw new IOException("Invalid sheet count");
            List<SheetData> sheets = new ArrayList<>();
            for (int i = 0; i < count; i++) sheets.add(readSheet(in, version));
            // Read to the end of the stream: GZIP only verifies its CRC at the trailer,
            // so this is what detects a damaged file.
            byte[] sink = new byte[4096];
            while (in.read(sink) != -1) {
                // discard
            }
            return new WorkbookData(sheets, Math.max(0, Math.min(active, count - 1)));
        }
    }

    private static SheetData readSheet(DataInputStream in, int version) throws IOException {
        String name = readString(in);
        boolean prot = in.readBoolean();
        boolean hasPassword = in.readBoolean();
        byte[] salt = null;
        byte[] hash = null;
        if (hasPassword) {
            salt = readBytes(in, 1024);
            hash = readBytes(in, 1024);
        }
        int rows = in.readInt();
        int cols = in.readInt();
        if (rows < 1 || rows > SpreadsheetModel.MAX_ROWS || cols < 1 || cols > 702) throw new IOException("Invalid sheet size");

        SpreadsheetModel m = new SpreadsheetModel(rows, cols);
        m.beginBatch();
        try {
            int cells = in.readInt();
            if (cells < 0 || cells > (long) rows * cols) throw new IOException("Invalid cell count");
            for (int i = 0; i < cells; i++) {
                int r = in.readInt();
                int c = in.readInt();
                String text = readString(in);
                if (m.inBounds(r, c)) m.setInput(r, c, text);
            }

            int styled = in.readInt();
            if (styled < 0 || styled > (long) rows * cols) throw new IOException("Invalid style count");
            for (int i = 0; i < styled; i++) {
                final int r = in.readInt();
                final int c = in.readInt();
                final int flags = in.readUnsignedByte();
                final int align = in.readInt();
                final int size = in.readInt();
                final Color bg = (flags & 4) != 0 ? new Color(in.readInt(), true) : null;
                final Color fg = (flags & 8) != 0 ? new Color(in.readInt(), true) : null;
                final String family = (flags & 16) != 0 ? readString(in) : null;
                final int fmt = (flags & 32) != 0 ? in.readUnsignedByte() : 0;
                final int dec = (flags & 32) != 0 ? in.readInt() : -1;
                if (fmt >= CellFormats.NAMES.length) throw new IOException("Invalid number format");
                m.updateStyle(r, c, st -> {
                    st.fontFamily = family;
                    st.numFmt = fmt;
                    st.decimals = dec;
                    st.bold = (flags & 1) != 0;
                    st.italic = (flags & 2) != 0;
                    st.alignment = align;
                    st.fontSize = size;
                    st.background = bg;
                    st.foreground = fg;
                });
            }

            int pictures = in.readInt();
            if (pictures < 0 || pictures > 1000) throw new IOException("Invalid picture count");
            for (int i = 0; i < pictures; i++) {
                int x = in.readInt();
                int y = in.readInt();
                int w = in.readInt();
                int h = in.readInt();
                byte[] data = readBytes(in, MAX_IMAGE_BYTES);
                try {
                    m.addImage(SheetImage.fromPng(data, x, y, w, h));
                } catch (IOException skip) {
                    // an unreadable picture is dropped; the rest of the sheet still loads
                }
            }

            if (version >= 2) {
                m.setZoomPercent(in.readInt());
                int widths = in.readInt();
                if (widths < 0 || widths > 10_000) throw new IOException("Invalid column width count");
                for (int c = 0; c < widths; c++) {
                    int w = in.readInt();
                    if (c < cols) m.setColumnWidth(c, w);
                }
            }

            if (version >= 3) {
                int count = in.readInt();
                if (count < 0 || count > 100_000) throw new IOException("Invalid drawing count");
                DrawShape.Type[] types = DrawShape.Type.values();
                List<DrawShape> shapes = new java.util.ArrayList<>();
                for (int i = 0; i < count; i++) {
                    int t = in.readUnsignedByte();
                    int argb = in.readInt();
                    float width = in.readFloat();
                    boolean filled = in.readBoolean();
                    int n = in.readInt();
                    if (t >= types.length || n < 1 || n > 500_000 || !(width > 0 && width < 1000)) {
                        throw new IOException("Invalid drawing data");
                    }
                    if (types[t] != DrawShape.Type.PEN && types[t] != DrawShape.Type.HIGHLIGHT && n != 2) {
                        throw new IOException("Invalid drawing data");
                    }
                    float[] pts = new float[n * 2];
                    for (int k = 0; k < pts.length; k++) {
                        pts[k] = in.readFloat();
                        if (Float.isNaN(pts[k]) || Float.isInfinite(pts[k])) throw new IOException("Invalid drawing data");
                    }
                    shapes.add(new DrawShape(types[t], argb, width, filled, pts));
                }
                m.setShapes(shapes);
            }

            if (version >= 4) {
                int nRules = in.readInt();
                if (nRules < 0 || nRules > 10_000) throw new IOException("Invalid rule count");
                CondRule.Type[] ctypes = CondRule.Type.values();
                for (int i = 0; i < nRules; i++) {
                    int t = in.readUnsignedByte();
                    int r1 = in.readInt();
                    int c1 = in.readInt();
                    int r2 = in.readInt();
                    int c2 = in.readInt();
                    String v1 = readString(in);
                    String v2 = readString(in);
                    int bgRgb = in.readInt();
                    Color fgc = in.readBoolean() ? new Color(in.readInt(), true) : null;
                    if (t >= ctypes.length) throw new IOException("Invalid rule");
                    m.addCondRule(new CondRule(ctypes[t], r1, c1, r2, c2, v1, v2, new Color(bgRgb, true), fgc));
                }
                if (in.readBoolean()) {
                    int hr = in.readInt();
                    int fc = in.readInt();
                    int lc = in.readInt();
                    if (!m.inBounds(hr, fc) || !m.inBounds(hr, lc)) throw new IOException("Invalid filter");
                    m.enableFilter(hr, fc, lc);
                    int nCrit = in.readInt();
                    if (nCrit < 0 || nCrit > 1000) throw new IOException("Invalid filter");
                    for (int i = 0; i < nCrit; i++) {
                        int col = in.readInt();
                        int nv = in.readInt();
                        if (nv < 0 || nv > 100_000) throw new IOException("Invalid filter");
                        java.util.Set<String> vals = new java.util.LinkedHashSet<>();
                        for (int k = 0; k < nv; k++) vals.add(readString(in));
                        m.setFilterValues(col, vals);
                    }
                }
            }

            if (version >= 5) {
                int nCharts = in.readInt();
                if (nCharts < 0 || nCharts > 1000) throw new IOException("Invalid chart count");
                ChartSpec.Type[] ctypes = ChartSpec.Type.values();
                for (int i = 0; i < nCharts; i++) {
                    int t = in.readUnsignedByte();
                    String title = readString(in);
                    int r1 = in.readInt();
                    int c1 = in.readInt();
                    int r2 = in.readInt();
                    int c2 = in.readInt();
                    int x = in.readInt();
                    int y = in.readInt();
                    int w = in.readInt();
                    int h = in.readInt();
                    boolean legend = in.readBoolean();
                    if (t >= ctypes.length || !m.inBounds(r1, c1) || !m.inBounds(r2, c2)
                            || w < 20 || h < 20 || w > 20_000 || h > 20_000 || x < 0 || y < 0) {
                        throw new IOException("Invalid chart");
                    }
                    ChartSpec ch = new ChartSpec(ctypes[t], title, r1, c1, r2, c2, x, y, w, h);
                    ch.legend = legend;
                    m.addChart(ch);
                }
            }

            if (version >= 6) {
                int nVal = in.readInt();
                if (nVal < 0 || nVal > 100_000) throw new IOException("Invalid validation count");
                ValidationRule.Type[] vt = ValidationRule.Type.values();
                ValidationRule.Op[] vo = ValidationRule.Op.values();
                ValidationRule.Alert[] va = ValidationRule.Alert.values();
                for (int i = 0; i < nVal; i++) {
                    int t = in.readUnsignedByte();
                    int o = in.readUnsignedByte();
                    int r1 = in.readInt();
                    int c1 = in.readInt();
                    int r2 = in.readInt();
                    int c2 = in.readInt();
                    String v1 = readString(in);
                    String v2 = readString(in);
                    boolean blank = in.readBoolean();
                    boolean drop = in.readBoolean();
                    int al = in.readUnsignedByte();
                    String title = readString(in);
                    String msg = readString(in);
                    if (t >= vt.length || o >= vo.length || al >= va.length || !m.inBounds(r1, c1) || !m.inBounds(r2, c2)) {
                        throw new IOException("Invalid validation rule");
                    }
                    m.addValidation(new ValidationRule(vt[t], vo[o], r1, c1, r2, c2, v1, v2, blank, drop, va[al], title, msg));
                }
                int nSpark = in.readInt();
                if (nSpark < 0 || nSpark > 1_000_000) throw new IOException("Invalid sparkline count");
                Sparkline.Type[] st = Sparkline.Type.values();
                for (int i = 0; i < nSpark; i++) {
                    int t = in.readUnsignedByte();
                    int row = in.readInt();
                    int col = in.readInt();
                    int r1 = in.readInt();
                    int c1 = in.readInt();
                    int r2 = in.readInt();
                    int c2 = in.readInt();
                    int rgb = in.readInt();
                    if (t >= st.length || !m.inBounds(row, col) || !m.inBounds(r1, c1) || !m.inBounds(r2, c2)) {
                        throw new IOException("Invalid sparkline");
                    }
                    m.setSparkline(new Sparkline(row, col, r1, c1, r2, c2, st[t], new Color(rgb, true)));
                }
            }
            if (version >= 7 && in.readBoolean()) {
                PageSetup ps = PageSetup.read(in);
                if (ps.hasArea() && (!m.inBounds(ps.areaR1, ps.areaC1) || !m.inBounds(ps.areaR2, ps.areaC2))) ps.clearArea();
                m.setPageSetup(ps);
            }
        } finally {
            m.endBatch();
        }
        m.getProtection().restore(prot, salt, hash); // last, so loading is not blocked by it
        return new SheetData(name, m);
    }

    // ==================================================================
    // Encryption
    // ==================================================================

    private static byte[] encrypt(byte[] payload, char[] password) throws IOException {
        try {
            SecureRandom rnd = new SecureRandom();
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            rnd.nextBytes(salt);
            rnd.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(payload);
            byte[] out = new byte[4 + SALT_BYTES + IV_BYTES + encrypted.length];
            putInt(out, 0, MAGIC_ENCRYPTED);
            System.arraycopy(salt, 0, out, 4, SALT_BYTES);
            System.arraycopy(iv, 0, out, 4 + SALT_BYTES, IV_BYTES);
            System.arraycopy(encrypted, 0, out, 4 + SALT_BYTES + IV_BYTES, encrypted.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IOException("Encryption failed: " + e.getMessage(), e);
        }
    }

    private static byte[] decrypt(byte[] file, char[] password) throws IOException {
        if (password == null || password.length == 0) throw new PasswordRequiredException();
        int header = 4 + SALT_BYTES + IV_BYTES;
        if (file.length < header + 16) throw new IOException("The workbook file is truncated");
        byte[] salt = Arrays.copyOfRange(file, 4, 4 + SALT_BYTES);
        byte[] iv = Arrays.copyOfRange(file, 4 + SALT_BYTES, header);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(128, iv));
            return cipher.doFinal(file, header, file.length - header);
        } catch (AEADBadTagException e) {
            throw new BadPasswordException();
        } catch (GeneralSecurityException e) {
            throw new IOException("Decryption failed: " + e.getMessage(), e);
        }
    }

    private static SecretKey deriveKey(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, KDF_ITERATIONS, 256);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    // ==================================================================
    // Small helpers
    // ==================================================================

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static int getInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        writeBytes(out, s.getBytes(StandardCharsets.UTF_8));
    }

    private static String readString(DataInputStream in) throws IOException {
        return new String(readBytes(in, MAX_STRING_BYTES), StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream out, byte[] b) throws IOException {
        out.writeInt(b.length);
        out.write(b);
    }

    private static byte[] readBytes(DataInputStream in, int max) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > max) throw new IOException("Invalid data length");
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
    }
}

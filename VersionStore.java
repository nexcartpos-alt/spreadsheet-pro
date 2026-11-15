import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Keeps older copies of a workbook so that you can go back to them (Version History).
 * <p>
 * Every save adds a copy to a folder that belongs to that workbook (found by the workbook's full path).
 * A copy that is identical to the newest one is skipped. Copies you gave a name ("Before the tax change")
 * are never deleted automatically; plain copies keep only the newest {@link #KEEP_AUTOMATIC}.
 * A copy is simply an ordinary .spx file, so an encrypted workbook stays encrypted in its history.
 */
public final class VersionStore {

    public static final int KEEP_AUTOMATIC = 30;

    /** One saved copy. */
    public static final class Version {
        public final File file;
        public final long time;
        public final String label;
        public final boolean named;

        Version(File file, long time, String label, boolean named) {
            this.file = file;
            this.time = time;
            this.label = label;
            this.named = named;
        }

        public long size() {
            return file.length();
        }

        public String when() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(time));
        }

        @Override
        public String toString() {
            return when() + (label.isEmpty() ? "" : " - " + label);
        }
    }

    private final File root;

    public VersionStore(File root) {
        this.root = root;
    }

    /** ~/.spreadsheet-pro/versions (or the folder in the system property {@code spx.versions.dir}). */
    public static File defaultRoot() {
        String override = System.getProperty("spx.versions.dir");
        if (override != null && !override.isEmpty()) return new File(override);
        return new File(System.getProperty("user.home"), ".spreadsheet-pro" + File.separator + "versions");
    }

    /** Folder name for a workbook: a short fingerprint of its path plus its readable name. */
    public static String keyFor(File workbook) {
        String path;
        try {
            path = workbook.getCanonicalPath();
        } catch (IOException e) {
            path = workbook.getAbsolutePath();
        }
        String name = workbook.getName().replaceAll("[^A-Za-z0-9._ -]", "_");
        if (name.length() > 40) name = name.substring(0, 40);
        return hex(path.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 12) + "-" + name;
    }

    private File folder(String key) {
        return new File(root, key);
    }

    /**
     * Stores a copy of {@code source}.
     * @param label a name for the copy, or "" for an ordinary save (skipped if nothing changed since the last copy)
     * @return the new version, or null if it was skipped
     */
    public Version add(String key, File source, String label) throws IOException {
        label = label == null ? "" : label.trim();
        File dir = folder(key);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        List<Version> existing = list(key);
        if (label.isEmpty() && !existing.isEmpty() && sameContent(existing.get(0).file, source)) return null;
        long time = System.currentTimeMillis();
        if (!existing.isEmpty() && time <= existing.get(0).time) time = existing.get(0).time + 1; // keep the order strict
        File target = new File(dir, "v-" + time + "-" + (label.isEmpty() ? "a" : "n") + "-" + sanitize(label) + ".spx");
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (label.isEmpty()) prune(key);
        return new Version(target, time, label, !label.isEmpty());
    }

    /** All copies of a workbook, newest first. */
    public List<Version> list(String key) {
        List<Version> out = new ArrayList<>();
        File[] files = folder(key).listFiles((d, n) -> n.startsWith("v-") && n.endsWith(".spx"));
        if (files == null) return out;
        for (File f : files) {
            Version v = parse(f);
            if (v != null) out.add(v);
        }
        out.sort(Comparator.comparingLong((Version v) -> v.time).reversed());
        return out;
    }

    public void delete(Version v) {
        //noinspection ResultOfMethodCallIgnored
        v.file.delete();
    }

    /** Names (or renames) a copy and returns the renamed version. */
    public Version rename(Version v, String label) throws IOException {
        label = label == null ? "" : label.trim();
        File target = new File(v.file.getParentFile(), "v-" + v.time + "-" + (label.isEmpty() ? "a" : "n") + "-" + sanitize(label) + ".spx");
        if (!target.equals(v.file)) Files.move(v.file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return new Version(target, v.time, label, !label.isEmpty());
    }

    private void prune(String key) {
        int automatic = 0;
        for (Version v : list(key)) {
            if (v.named) continue;
            if (++automatic > KEEP_AUTOMATIC) delete(v);
        }
    }

    private static Version parse(File f) {
        // v-<millis>-<a|n>-<label>.spx
        String n = f.getName();
        String body = n.substring(2, n.length() - 4);
        int d1 = body.indexOf('-');
        if (d1 < 1 || body.length() < d1 + 3) return null;
        long time;
        try {
            time = Long.parseLong(body.substring(0, d1));
        } catch (NumberFormatException e) {
            return null;
        }
        char kind = body.charAt(d1 + 1);
        String label = body.length() > d1 + 3 ? body.substring(d1 + 3) : "";
        return new Version(f, time, unsanitize(label), kind == 'n');
    }

    private static String sanitize(String label) {
        StringBuilder sb = new StringBuilder();
        for (char ch : label.toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == ' ' || ch == '.' || ch == '_') sb.append(ch);
            else if (ch == '-') sb.append('~');
            else sb.append('_');
            if (sb.length() >= 60) break;
        }
        return sb.toString().trim();
    }

    private static String unsanitize(String s) {
        return s.replace('~', '-');
    }

    private static boolean sameContent(File a, File b) throws IOException {
        if (a.length() != b.length()) return false;
        return digest(a).equals(digest(b));
    }

    private static String digest(File f) throws IOException {
        try (InputStream in = Files.newInputStream(f.toPath())) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String hex(byte[] bytes) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

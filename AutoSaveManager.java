import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;
import javax.swing.Timer;

/**
 * AutoSave. While the workbook has unsaved changes it is written automatically every
 * few seconds, either as a separate recovery copy (default, never touches your file) or
 * straight into the open file. After a crash the recovery copies are offered on the next start.
 * Password-protected workbooks stay encrypted in their recovery copy.
 */
public final class AutoSaveManager {

    public enum Mode { RECOVERY, FILE }

    /** What the manager needs from the application window. */
    public interface Host {
        boolean isDirty();

        boolean hasFile();

        String displayName();

        /** Writes the whole workbook (encrypted if it has a password) to the given file. */
        void writeRecovery(File file) throws IOException;

        /** Saves into the workbook's own file without any dialogs. */
        boolean saveToFile();

        void autoSaved(String message);
    }

    private static final String PREFIX = "recovery-";
    private static final int STAMP_LENGTH = 15; // yyyyMMdd-HHmmss

    private final Host host;
    private final Timer timer;
    private Preferences prefs;
    private boolean enabled = true;
    private int intervalSeconds = 60;
    private Mode mode = Mode.RECOVERY;
    private File myFile;
    private final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    private long lastAuto = System.currentTimeMillis();

    public AutoSaveManager(Host host) {
        this.host = host;
        try {
            prefs = Preferences.userRoot().node("spreadsheet-pro-autosave");
            enabled = prefs.getBoolean("enabled", true);
            intervalSeconds = clamp(prefs.getInt("interval", 60));
            mode = prefs.get("mode", "RECOVERY").equals("FILE") ? Mode.FILE : Mode.RECOVERY;
        } catch (RuntimeException ignored) {
            prefs = null; // preferences unavailable: keep the defaults
        }
        timer = new Timer(1000, e -> tick());
        timer.setRepeats(true);
    }

    private static int clamp(int seconds) {
        return Math.max(5, Math.min(3600, seconds));
    }

    public void start() {
        timer.start();
    }

    public void stop() {
        timer.stop();
    }

    // ---- settings ----

    public boolean isEnabled() {
        return enabled;
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    public Mode getMode() {
        return mode;
    }

    public void configure(boolean enabled, int seconds, Mode mode) {
        this.enabled = enabled;
        this.intervalSeconds = clamp(seconds);
        this.mode = mode;
        lastAuto = System.currentTimeMillis();
        if (prefs != null) {
            try {
                prefs.putBoolean("enabled", enabled);
                prefs.putInt("interval", intervalSeconds);
                prefs.put("mode", mode.name());
                prefs.flush();
            } catch (Exception ignored) {
                // settings just won't persist
            }
        }
    }

    public void setEnabled(boolean on) {
        configure(on, intervalSeconds, mode);
    }

    // ---- saving ----

    private void tick() {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        if (!host.isDirty()) {
            clearOwn(); // nothing unsaved: no recovery copy needed
            lastAuto = now;
            return;
        }
        if (now - lastAuto >= intervalSeconds * 1000L) saveNow();
    }

    private static String clock() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    /** Runs an AutoSave right now. @return true if something was written. */
    public boolean saveNow() {
        lastAuto = System.currentTimeMillis();
        try {
            if (mode == Mode.FILE && host.hasFile()) {
                if (!host.saveToFile()) return false;
                clearOwn();
                host.autoSaved("AutoSaved to file at " + clock());
                return true;
            }
            File dir = folder();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
            if (myFile == null) myFile = new File(dir, PREFIX + stamp + "-" + sanitize(host.displayName()) + ".spx");
            host.writeRecovery(myFile);
            host.autoSaved("AutoSaved a recovery copy at " + clock());
            return true;
        } catch (IOException | RuntimeException ex) {
            host.autoSaved("AutoSave failed: " + ex.getMessage());
            return false;
        }
    }

    /** Deletes this session's recovery copy (after a save, or on a clean exit). */
    public void clearOwn() {
        if (myFile != null) {
            //noinspection ResultOfMethodCallIgnored
            myFile.delete();
            myFile = null;
        }
    }

    // ---- recovery ----

    public static File folder() {
        String override = System.getProperty("spx.autosave.dir");
        if (override != null && !override.isEmpty()) return new File(override);
        return new File(System.getProperty("user.home"), ".spreadsheet-pro" + File.separator + "autosave");
    }

    private static String sanitize(String name) {
        String s = name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (s.toLowerCase(Locale.ROOT).endsWith(".spx")) s = s.substring(0, s.length() - 4);
        return s.isEmpty() ? "Untitled" : s;
    }

    /** Recovery copies left behind by earlier sessions, newest first. */
    public List<File> findRecoveryFiles() {
        File[] files = folder().listFiles((d, n) -> n.startsWith(PREFIX) && n.endsWith(".spx"));
        List<File> list = new ArrayList<>();
        if (files != null) {
            for (File f : files) if (myFile == null || !f.equals(myFile)) list.add(f);
        }
        list.sort(Comparator.comparingLong(File::lastModified).reversed());
        return list;
    }

    /** "Budget" for recovery-20261112-153000-Budget.spx. */
    public static String originalName(File recovery) {
        String n = recovery.getName();
        int start = PREFIX.length() + STAMP_LENGTH + 1;
        if (n.length() <= start + 4) return "Untitled";
        return n.substring(start, n.length() - 4);
    }

    public static void discard(File recovery) {
        //noinspection ResultOfMethodCallIgnored
        recovery.delete();
    }

    @Override
    public String toString() {
        return "AutoSave[" + (enabled ? "on" : "off") + ", " + intervalSeconds + "s, " + mode + ", "
                + Arrays.toString(new Object[]{myFile}) + "]";
    }
}

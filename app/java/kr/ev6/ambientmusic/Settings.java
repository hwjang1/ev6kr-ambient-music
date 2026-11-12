package kr.ev6.ambientmusic;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The owner's choices, kept in the app's own preferences. The UI saves and bumps
 * {@link #version}; the light worker (same process) reloads when it changes.
 */
final class Settings {
    static final String MODE_BANDS = "bands", MODE_PULSE = "pulse", MODE_COLORS = "colors";
    static final String SPEED_FAST = "fast", SPEED_NORMAL = "normal", SPEED_SLOW = "slow";
    static final String DRIVE_NORMAL = "normal", DRIVE_COLORS = "colors", DRIVE_PAUSE = "pause";
    static final String SOURCE_MIC = "mic", SOURCE_VOICE = "voice";
    static final String NOISE_LOW = "low", NOISE_NORMAL = "normal", NOISE_HIGH = "high";

    /** the five frequency bands the colours follow, Hz (lower inclusive, upper exclusive) */
    static final String[] BAND_NAMES = { "저음", "중저음", "중음", "중고음", "고음" };
    static final int[] BAND_LOW = { 40, 150, 400, 1500, 4000 };
    static final int[] BAND_HIGH = { 150, 400, 1500, 4000, 8000 };
    static final int[] DEFAULT_BAND_COLORS = { 0xff321d, 0xff8f3e, 0x68dd62, 0x10d2fc, 0x9b21ef };
    static final int DEFAULT_FIXED_COLOR = 0x106ffc;

    String mode = MODE_BANDS;
    String speed = SPEED_NORMAL;
    String driving = DRIVE_COLORS;
    String source = SOURCE_MIC;
    /** how hard road noise is held back while driving */
    String noise = NOISE_NORMAL;
    int sensitivity = 5;    // 1..10
    int minBright = 2;      // 1..10 (the car's menu levels; 0 would turn the light off)
    int maxBright = 10;     // minBright..10
    int fixedColor = DEFAULT_FIXED_COLOR;
    int[] bandColors = DEFAULT_BAND_COLORS.clone();
    /** on or off as the owner last left it (the app's button, the star key): kept across restarts */
    boolean enabled;

    static volatile int version;

    private static final String FILE = "settings";

    static Settings load(Context c) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Settings s = new Settings();
        s.mode = pick(p.getString("mode", s.mode), MODE_BANDS, MODE_PULSE, MODE_COLORS);
        s.speed = pick(p.getString("speed", s.speed), SPEED_NORMAL, SPEED_FAST, SPEED_SLOW);
        s.driving = pick(p.getString("driving", s.driving), DRIVE_COLORS, DRIVE_NORMAL, DRIVE_PAUSE);
        s.source = pick(p.getString("source", s.source), SOURCE_MIC, SOURCE_VOICE);
        s.noise = pick(p.getString("noise", s.noise), NOISE_NORMAL, NOISE_LOW, NOISE_HIGH);
        s.sensitivity = clamp(p.getInt("sensitivity", s.sensitivity), 1, 10);
        s.minBright = clamp(p.getInt("min_bright", s.minBright), 1, 10);
        s.maxBright = clamp(p.getInt("max_bright", s.maxBright), s.minBright, 10);
        s.fixedColor = color(p.getInt("fixed_color", s.fixedColor), DEFAULT_FIXED_COLOR);
        for (int i = 0; i < s.bandColors.length; i++)
            s.bandColors[i] = color(p.getInt("band_color_" + i, s.bandColors[i]), DEFAULT_BAND_COLORS[i]);
        s.enabled = p.getBoolean("enabled", false);
        return s;
    }

    void save(Context c) {
        SharedPreferences.Editor e = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit();
        e.putString("mode", mode).putString("speed", speed).putString("driving", driving).putString("source", source).putString("noise", noise);
        e.putInt("sensitivity", sensitivity).putInt("min_bright", minBright).putInt("max_bright", maxBright);
        e.putInt("fixed_color", fixedColor);
        for (int i = 0; i < bandColors.length; i++) e.putInt("band_color_" + i, bandColors[i]);
        e.commit();   // "enabled" and "owner_light" are written only by setEnabled / setOwnerLight
        version++;
    }

    /** back to the defaults; on/off and the owner's kept light stay */
    static void reset(Context c) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        boolean on = p.getBoolean("enabled", false);
        String owner = p.getString("owner_light", null);
        p.edit().clear().putBoolean("enabled", on).putString("owner_light", owner).commit();
        version++;
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).commit();
        version++;
    }

    /**
     * The owner's light (the car's report from before the app took the light), kept until the
     * app gives it back: the vehicle settings app saves whatever the car reports, so after a
     * restart with the app on, the car itself would report the music's last colour.
     */
    static String ownerLight(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("owner_light", null);
    }

    static void setOwnerLight(Context c, String hex) {
        SharedPreferences.Editor e = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit();
        if (hex == null) e.remove("owner_light");
        else e.putString("owner_light", hex);
        e.commit();
    }

    private static String pick(String v, String def, String... others) {
        if (def.equals(v)) return def;
        for (String o : others) if (o.equals(v)) return o;
        return def;
    }

    /**
     * The margin over the background while driving, dB: 1 dB per 25 km/h up to 4 (90 km/h: 3.6),
     * times the owner's choice; 2 when the speed isn't known
     */
    static double noiseBoostDb(Integer speedKmh, String noise) {
        double f = NOISE_LOW.equals(noise) ? 0.5 : NOISE_HIGH.equals(noise) ? 1.6 : 1.0;
        double base = speedKmh == null ? 2 : speedKmh < 5 ? 0 : Math.min(4, speedKmh / 25.0);
        return base * f;
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    private static int color(int v, int def) {
        return Palette.has(v) ? v : def;
    }
}

package kr.ev6.ambientmusic;

import android.os.IBinder;

import java.lang.reflect.Method;

/**
 * The car's own ambient light calls, the ones the KR vehicle settings app makes for a custom
 * colour and the brightness (com.android.vehiclesettings, firmware 260902): ISystemService
 * setSMLRGB (red, green, blue as 2 bytes each, big endian) and setSMLConfiguration(5, level)
 * (1 off, 2-11 the menu's levels 1-10). The service checks no caller, so the app calls it
 * directly; nothing of the car's is bundled (looked up by name on the unit's boot classpath).
 *
 * The car answers each change, and requestSMLStatus, with the broadcast
 * com.mobis.intent.action.SML_STATUS_CHANGED (extra com.mobis.intent.extra.SML_STATUS, 9 bytes:
 * red, green, blue as 2 bytes each, brightness, dimmed while driving, drive mode link; switches
 * 1 off, 2 on). With the drive mode link on ("드라이브 모드 연동"), the colour follows the drive
 * mode and a custom colour is ignored (brightness still changes): seen on the owner's car.
 */
final class CarLight {
    static final String SERVICE = "com.hkmc.micom.system", DESCRIPTOR = "com.hkmc.micom.ISystemService";
    static final String ACTION_STATUS = "com.mobis.intent.action.SML_STATUS_CHANGED", EXTRA_STATUS = "com.mobis.intent.extra.SML_STATUS";
    static final int MODE_BRIGHTNESS = 5;
    static final int ON = 2;

    private Object service;
    private Class<?> api;

    /** throws when the car's light service isn't there */
    CarLight connect() throws Exception {
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, SERVICE);
        if (b == null) throw new IllegalStateException("no " + SERVICE);
        String d = b.getInterfaceDescriptor();
        if (!DESCRIPTOR.equals(d)) throw new IllegalStateException(SERVICE + " is " + d);
        api = Class.forName(DESCRIPTOR);   // the public interface: the proxy class itself is private
        service = Class.forName(DESCRIPTOR + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
        return this;
    }

    void color(int rgb) throws Exception {
        colorRaw(two(rgb >> 16 & 255), two(rgb >> 8 & 255), two(rgb & 255));
    }

    void colorRaw(byte[] r, byte[] g, byte[] b) throws Exception {
        api.getMethod("setSMLRGB", byte[].class, byte[].class, byte[].class).invoke(service, r, g, b);
    }

    /** the car's value: 1 off, 2-11 the menu's levels 1-10 */
    void brightnessRaw(int raw) throws Exception {
        api.getMethod("setSMLConfiguration", int.class, int.class).invoke(service, MODE_BRIGHTNESS, raw);
    }

    void requestStatus() throws Exception {
        api.getMethod("requestSMLStatus").invoke(service);
    }

    /** km/h, as the car's own system service reports it */
    int speed() throws Exception {
        Method m = api.getMethod("getVehicleSpeed");
        return (Integer) m.invoke(service);
    }

    static byte[] two(int v) {
        return new byte[] { (byte) (v >> 8), (byte) v };
    }

    /** one SML_STATUS_CHANGED report */
    static final class Status {
        final byte[] red, green, blue;
        final int brightness, dimmed, driveMode;
        final long at;

        Status(byte[] s, long at) {
            red = new byte[] { s[0], s[1] };
            green = new byte[] { s[2], s[3] };
            blue = new byte[] { s[4], s[5] };
            brightness = s[6] & 255;
            dimmed = s[7] & 255;
            driveMode = s[8] & 255;
            this.at = at;
        }

        /** null when it isn't a 9-byte report */
        static Status parse(byte[] s, long at) {
            return s == null || s.length < 9 ? null : new Status(s, at);
        }

        /** the colour as 0xRRGGBB (the low byte of each channel) */
        int rgb() {
            return (red[1] & 255) << 16 | (green[1] & 255) << 8 | (blue[1] & 255);
        }

        boolean driveModeLinked() {
            return driveMode == ON;
        }

        /** the report as 18 hex digits, for keeping the owner's light across restarts */
        String hex() {
            byte[] b = { red[0], red[1], green[0], green[1], blue[0], blue[1], (byte) brightness, (byte) dimmed, (byte) driveMode };
            StringBuilder s = new StringBuilder();
            for (byte x : b) s.append(String.format(java.util.Locale.US, "%02x", x & 255));
            return s.toString();
        }

        /** null when it isn't 18 hex digits */
        static Status fromHex(String h, long at) {
            if (h == null || !h.matches("[0-9a-f]{18}")) return null;
            byte[] b = new byte[9];
            for (int i = 0; i < 9; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
            return new Status(b, at);
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "#%02x%02x %02x%02x %02x%02x brightness %d dimmed %d drive mode %d",
                    red[0], red[1], green[0], green[1], blue[0], blue[1], brightness, dimmed, driveMode);
        }
    }
}

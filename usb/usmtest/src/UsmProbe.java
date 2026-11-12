import android.os.IBinder;

import java.lang.reflect.Method;

/**
 * The KR EV6's own ambient light path, tried by hand: IUSMService.setAmbientLight(red, green, blue,
 * brightness, dimmed while driving, depends on drive mode, fade in/out), what the vehicle settings
 * app (com.android.vehiclesettings) sends on firmware 260902, as opposed to the Sound Mood Lamp calls
 * (ISystemService setSMLRGB / setSMLConfiguration) that HMG-API's ambient_light uses and that this
 * car accepts without changing the light. Run as root with app_process (the car's automotive.jar is
 * on BOOTCLASSPATH; everything is looked up by name, so nothing of the car's is bundled):
 *
 *   CLASSPATH=usmprobe.jar app_process /system/bin UsmProbe dump
 *   CLASSPATH=usmprobe.jar app_process /system/bin UsmProbe set <r> <g> <b> <brightness>
 *
 * "set" keeps the owner's three switches (dimmed while driving, drive mode, fade) as the car reports them.
 */
public final class UsmProbe {
    static Object usm;
    /** the public interface: the proxy class itself is private, so its methods are invoked through this */
    static Class<?> api;

    public static void main(String[] a) throws Exception {
        String name = (String) Class.forName("com.hkmc.Service").getField("USM_SERVICE").get(null);
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        System.out.println("service " + name + ": " + (b == null ? "missing" : b.getInterfaceDescriptor()));
        if (b == null) return;
        usm = Class.forName("com.hkmc.usm.IUSMService$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
        api = Class.forName("com.hkmc.usm.IUSMService");
        if (a.length == 0 || "dump".equals(a[0])) {
            for (String g : new String[] { "getLights_AmbientLightDimmedWhileDriving", "getLights_AmbientLightDependsOnDrivemode",
                    "getLights_AmbientFadeInOut", "getLights_AmbientLightBrightOnly", "getLights_AmbientLightDependsOnSpeed",
                    "getLights_AmbientIllumista", "getLights_AmbientAIVoice", "getDualLight_Mode", "getDualLight_Color",
                    "getDualLight_Speed", "getDualLight_BottomRed", "getDualLight_BottomGreen", "getDualLight_BottomBlue",
                    "getDualLight_Recommend" }) {
                System.out.println(g + " = " + get(g));
            }
            return;
        }
        if ("set".equals(a[0]) && a.length == 5) {
            int dimmed = get("getLights_AmbientLightDimmedWhileDriving"), mode = get("getLights_AmbientLightDependsOnDrivemode"),
                    fade = get("getLights_AmbientFadeInOut");
            if (dimmed < 0 || mode < 0 || fade < 0) {
                System.out.println("not sent: the owner's switches couldn't be read");
                return;
            }
            int r = Integer.parseInt(a[1]), g = Integer.parseInt(a[2]), bl = Integer.parseInt(a[3]), br = Integer.parseInt(a[4]);
            Method m = api.getMethod("setAmbientLight", int.class, int.class, int.class, int.class, int.class, int.class, int.class);
            m.invoke(usm, r, g, bl, br, dimmed, mode, fade);
            System.out.println("setAmbientLight(" + r + ", " + g + ", " + bl + ", " + br + ", " + dimmed + ", " + mode + ", " + fade + ") sent");
            return;
        }
        System.out.println("usage: UsmProbe dump | set <r> <g> <b> <brightness>");
    }

    static int get(String getter) {
        try {
            return (Integer) api.getMethod(getter).invoke(usm);
        } catch (Throwable t) {
            System.out.println(getter + ": " + t);
            return -1;
        }
    }
}

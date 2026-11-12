package kr.ev6.ambientmusic;

import android.os.IBinder;

import java.lang.reflect.Method;

/**
 * The speed for the "가속 연동" mode, with the accelerator pedal and brake pressure to show.
 *
 * First choice: the telematics CAN service (tm_hscan_service, ITM_HSCANService in
 * /system/framework/com.hkmc.ext.hcan.jar, not on the boot classpath, so loaded as HMG-API's
 * vehicle service does): fractional km/h (getCarSpeed(0)), getAccelPdlVal, getCylPrsrVal (raw
 * values, scale not known). Whether it answers an ordinary app isn't known; if it can't be
 * reached, or a call fails, the system service's whole km/h (CarLight.speed) is used from then on.
 */
final class CarMotion {
    static final String HSCAN_SERVICE = "tm_hscan_service";
    static final String HSCAN_JAR = "/system/framework/com.hkmc.ext.hcan.jar";
    static final String HSCAN_IFACE = "com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService";

    private final CarLight car;
    private Object hscan;
    private Method speedM, pedalM, brakeM;
    /** what the speed comes from (shown on the screen) */
    String source;
    /** raw, NaN when not read */
    float pedal = Float.NaN, brake = Float.NaN;

    private CarMotion(CarLight car) {
        this.car = car;
    }

    /** loader: the CAN client's classes (null: load the jar) */
    static CarMotion open(CarLight car, ClassLoader loader) {
        CarMotion m = new CarMotion(car);
        try {
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, HSCAN_SERVICE);
            if (b == null) throw new IllegalStateException("no " + HSCAN_SERVICE);
            ClassLoader cl = loader != null ? loader
                    : new dalvik.system.PathClassLoader(HSCAN_JAR, CarMotion.class.getClassLoader());
            Class<?> api = cl.loadClass(HSCAN_IFACE);
            m.hscan = cl.loadClass(HSCAN_IFACE + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
            m.speedM = api.getMethod("getCarSpeed", byte.class);
            m.pedalM = api.getMethod("getAccelPdlVal");
            m.brakeM = api.getMethod("getCylPrsrVal");
            m.speedM.invoke(m.hscan, (byte) 0);   // one call now: a refusal shows here, not mid-drive
            m.source = "CAN";
        } catch (Throwable t) {
            m.fallBack(t);
        }
        return m;
    }

    private void fallBack(Throwable t) {
        if (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) t = t.getCause();
        hscan = null;
        source = "차속(시스템 서비스)";
        android.util.Log.w(LightService.TAG, "motion: the CAN service can't be used (" + t + "); whole km/h from the system service");
    }

    /** km/h; throws when neither answers */
    double speedKmh() throws Exception {
        if (hscan != null) {
            try {
                return ((Float) speedM.invoke(hscan, (byte) 0)).doubleValue();
            } catch (Throwable t) {
                fallBack(t);
            }
        }
        return car.speed();
    }

    /** the pedal and the brake, for the screen (a few times a second is plenty) */
    void readExtras() {
        if (hscan == null) return;
        try {
            pedal = (Float) pedalM.invoke(hscan);
            brake = (Float) brakeM.invoke(hscan);
        } catch (Throwable t) {
            pedal = brake = Float.NaN;
        }
    }

    /** the slope window: short for CAN's fractional speed, longer for whole km/h */
    long windowMs() {
        return hscan != null ? 800 : 2000;
    }

    boolean can() {
        return hscan != null;
    }
}

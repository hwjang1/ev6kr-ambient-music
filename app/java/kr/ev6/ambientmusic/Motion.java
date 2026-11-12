package kr.ev6.ambientmusic;

/**
 * The car's motion for the "가속 연동" mode: a driving state (stopped, cruising, speeding up,
 * slowing down: braking or regeneration) and how strong it is. Plain Java, tested off the car.
 *
 * From the speed (update): the acceleration is the least-squares slope of the speed over a
 * recent window (0.8 s for the CAN service's fractional km/h, 2 s for the system service's
 * whole km/h, so its 1 km/h steps don't read as jolts), smoothed: rising over half the window,
 * falling over a sixth of it, so a phase ends soon after the driver eases off. A phase starts
 * at 18 % of the full-scale acceleration and ends below 10 %; full scale follows the
 * sensitivity (1: 4 m/s², 5: 2.75, 10: 1.2).
 *
 * From the accelerator pedal (updatePedal, CAN only): at once, no waiting for the speed to
 * change. A light press (holding a speed) is cruising, brighter the more it's pressed; a deep
 * one (half the full-scale travel: 31 % at sensitivity 5) is speeding up; off the pedal it's
 * the speed's phase (regeneration shows there) or braking (brake pressure). The pedal's raw
 * scale isn't known: percent (as Hyundai/Kia CAN carries the pedal position) until a value
 * over 100 shows up, then 0-255. Not a fraction: a percent reading of 0.8 at rest would then
 * look like 80 %. The screen shows the raw value, so the scale can be checked on the car.
 */
final class Motion {
    static final int STOPPED = 0, CRUISE = 1, ACCEL = 2, DECEL = 3;
    static final String[] NAMES = { "정차", "정속", "가속", "감속" };
    private static final int MAX = 64;

    private final long[] t = new long[MAX];
    private final double[] v = new double[MAX];
    private int n, head;
    private long stoppedSince = -1, lastAt = Long.MIN_VALUE;
    /** the speed's own phase, with its hysteresis (the pedal doesn't change it) */
    private int phase = STOPPED;
    private double pedalScale = 100;
    private boolean pedalOn, pedalDeep;

    /** km/h, the last sample */
    double speed;
    /** m/s², smoothed; + speeding up */
    double accel;
    /** 0..1: how strong the state is (brightness) */
    double intensity;
    int state = STOPPED;
    /** 0..100 when the pedal decided the state; NaN otherwise */
    double pedalPct = Double.NaN;

    static double fullScale(int sensitivity) {
        return 4.3 - 0.31 * sensitivity;
    }

    /** pedal travel (0..1) for full brightness: 1: 0.885, 5: 0.62, 10: 0.30 */
    static double pedalFull(int sensitivity) {
        return 0.95 - 0.065 * sensitivity;
    }

    /** one speed sample (km/h) at now (ms); windowMs: the slope's window */
    void update(double kmh, long now, int sensitivity, long windowMs) {
        speed = kmh;
        pedalPct = Double.NaN;
        t[head] = now;
        v[head] = kmh / 3.6;   // m/s
        head = (head + 1) % MAX;
        if (n < MAX) n++;
        double slope = slope(now, windowMs);
        double dt = lastAt == Long.MIN_VALUE ? 0 : Math.min(1000, now - lastAt);
        lastAt = now;
        // up slowly (a 1 km/h step isn't a jolt), down quickly (the phase ends when the driver eases off)
        double tau = Math.abs(slope) < Math.abs(accel) || slope * accel < 0 ? windowMs / 6.0 : windowMs / 2.0;
        accel += (1 - Math.exp(-dt / tau)) * (slope - accel);

        double full = fullScale(sensitivity), on = 0.18 * full, off = 0.10 * full;
        if (kmh < 1.5) {
            if (stoppedSince < 0) stoppedSince = now;
        } else {
            stoppedSince = -1;
        }
        if (stoppedSince >= 0 && now - stoppedSince >= 1500 && Math.abs(accel) < on) {
            phase = STOPPED;
        } else if (phase == ACCEL) {
            if (accel < -on) phase = DECEL;
            else if (accel < off) phase = CRUISE;
        } else if (phase == DECEL) {
            if (accel > on) phase = ACCEL;
            else if (accel > -off) phase = CRUISE;
        } else {
            phase = accel > on ? ACCEL : accel < -on ? DECEL : (stoppedSince >= 0 && phase == STOPPED ? STOPPED : CRUISE);
        }
        state = phase;
        double x = (Math.abs(accel) - off) / (full - off);
        intensity = phase == ACCEL || phase == DECEL ? clamp(x) : 0;
    }

    /**
     * One sample with the accelerator pedal (raw) and the brake pressure (raw, NaN when not
     * read). A NaN pedal leaves it to the speed.
     */
    void updatePedal(double kmh, double pedal, double brake, long now, int sensitivity, long windowMs) {
        update(kmh, now, sensitivity, windowMs);
        if (Double.isNaN(pedal)) return;
        if (pedal > 100.5) pedalScale = 255;
        double p = clamp(pedal / pedalScale), full = pedalFull(sensitivity), deep = 0.5 * full;
        pedalOn = p >= 0.06 || pedalOn && p >= 0.03;
        pedalDeep = p >= deep || pedalDeep && p >= deep - 0.05;
        boolean braking = !Double.isNaN(brake) && brake > 0.5;
        pedalPct = p * 100;
        if (pedalOn) {
            state = pedalDeep ? ACCEL : CRUISE;
            intensity = clamp((p - 0.03) / (full - 0.03));
        } else if (braking && phase != STOPPED) {
            state = DECEL;
            intensity = Math.max(phase == DECEL ? intensity : 0, 0.3);
        } else if (phase == ACCEL) {
            state = CRUISE;   // rolling faster off the pedal (downhill): not the driver speeding up
            intensity = 0;
        } else {
            pedalPct = Double.NaN;   // the speed's phase stands: stopped, cruising or regeneration
        }
    }

    private static double clamp(double x) {
        return x < 0 ? 0 : x > 1 ? 1 : x;
    }

    /** least-squares slope (m/s per s) of the samples within the window; 0 with fewer than two */
    private double slope(long now, long windowMs) {
        double st = 0, sv = 0, stt = 0, stv = 0;
        int k = 0;
        for (int i = 0; i < n; i++) {
            int j = (head - 1 - i + MAX) % MAX;
            if (now - t[j] > windowMs) break;
            double x = (t[j] - now) / 1000.0;
            st += x;
            sv += v[j];
            stt += x * x;
            stv += x * v[j];
            k++;
        }
        if (k < 2) return 0;
        double d = k * stt - st * st;
        return d <= 1e-9 ? 0 : (k * stv - st * sv) / d;
    }
}

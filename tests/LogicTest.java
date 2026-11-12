package kr.ev6.ambientmusic;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Off-car tests: the analysis on synthetic sound, the change pacing against a stand-in
 * for the car's light calls, and the car's light report as logged on the owner's car.
 * tools/build.sh test runs it.
 */
public final class LogicTest {
    static int failures, checks;

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        } else {
            System.out.println("ok   " + what);
        }
    }

    // ---- synthetic sound

    static final Random rnd = new Random(7);
    static long sample;

    /** one frame: noise of the given amplitude plus sines {hz, amplitude, ...} */
    static short[] frame(double noise, double... tones) {
        short[] s = new short[Analyzer.N];
        for (int i = 0; i < s.length; i++, sample++) {
            double v = noise * rnd.nextGaussian();
            for (int t = 0; t + 1 < tones.length; t += 2) v += tones[t + 1] * Math.sin(2 * Math.PI * tones[t] * sample / Analyzer.RATE);
            s[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
        }
        return s;
    }

    static void run(Analyzer a, int frames, double noise, double... tones) {
        for (int i = 0; i < frames; i++) a.process(frame(noise, tones), 5);
    }

    static void analyzer() {
        Analyzer a = new Analyzer();
        run(a, 47, 200);   // 3 s of cabin noise
        check("noise only: quiet", a.quiet);
        check("noise only: level low (" + fmt(a.level) + ")", a.level < 0.15);
        check("noise only: no band yet", a.band == -1);
        run(a, 16, 200, 80, 8000);
        check("80 Hz: low band (" + a.band + ")", a.band == 0);
        check("80 Hz: not quiet", !a.quiet);
        check("80 Hz: bright (" + fmt(a.level) + ")", a.level > 0.5);
        run(a, 16, 200, 1000, 8000);
        check("1 kHz: mid band (" + a.band + ")", a.band == 2);
        run(a, 16, 200, 6000, 8000);
        check("6 kHz: high band (" + a.band + ")", a.band == 4);
        run(a, 32, 200);
        check("back to noise: quiet", a.quiet);
        check("back to noise: dims (" + fmt(a.level) + ")", a.level < 0.2);
        check("back to noise: colour holds (" + a.band + ")", a.band == 4);

        // a steady loud bass, then cymbals come in quieter: the colour follows the surge, not the bass
        Analyzer b = new Analyzer();
        run(b, 47, 200);
        run(b, 47, 200, 80, 8000);
        check("steady bass: low band", b.band == 0);
        int switched = -1;
        for (int i = 0; i < 6 && switched < 0; i++) {
            b.process(frame(200, 80, 8000, 6000, 2000), 5);
            if (b.band == 4) switched = i + 1;
        }
        check("cymbals over a louder bass: high band within 6 frames (" + switched + ")", switched > 0);

        // road noise: hardly any colour changes on its own, still plenty with music over it
        int slow = roadChanges(1.2, false, 21), driving = roadChanges(3.6, false, 21), withMusic = roadChanges(3.6, true, 21);
        check("road noise alone at 30 km/h: at most 3 colour changes in 30 s (" + slow + ")", slow <= 3);
        check("road noise alone at 90 km/h: at most 1 colour change in 30 s (" + driving + ")", driving <= 1);
        check("music over road noise at 90 km/h: the colour still follows it (" + withMusic + " changes)", withMusic >= 6);

        // sensitivity: a soft sound (~5 dB over the noise) lights up, more at 10 than at 1
        double lo = steadyLevel(1), mid = steadyLevel(5), hi = steadyLevel(10);
        check("soft sound, sensitivity 5: lit (" + fmt(mid) + ")", mid > 0.3);
        check("soft sound, sensitivity 10 clearly brighter than 1 (" + fmt(hi) + " > " + fmt(lo) + " + 0.2)", hi > lo + 0.2);

        // music that goes on: settles around the middle and isn't taken for silence
        Analyzer m = new Analyzer();
        run(m, 47, 200);
        run(m, 940, 200, 120, 3000, 900, 3000);   // a minute
        check("a minute of steady music: not quiet", !m.quiet);
        check("a minute of steady music: middle brightness (" + fmt(m.level) + ")", m.level > 0.25 && m.level < 0.55);

        // beats: loud and soft frames take turns, the light follows them
        double top = 0, bottom = 1;
        for (int i = 0; i < 64; i++) {
            boolean kick = i % 8 < 2;
            m.process(frame(200, 80, kick ? 12000 : 1500, 900, 1500), 5);
            if (i >= 32) {
                top = Math.max(top, m.level);
                bottom = Math.min(bottom, m.level);
            }
        }
        check("beats: the light swings with them (" + fmt(bottom) + " .. " + fmt(top) + ")", top - bottom > 0.3);
    }

    // ---- road noise: low rumble that wanders +-3 dB, tyre hiss, now and then a bump

    static double lowState, midState;

    static short[] roadFrame(double amp, Random r, boolean bump, double... tones) {
        short[] s = new short[Analyzer.N];
        double a = amp * (bump ? 2.0 : 1.0);
        for (int i = 0; i < s.length; i++, sample++) {
            double w = r.nextGaussian();
            lowState += 0.06 * (w - lowState);   // ~150 Hz and down: the rumble
            midState += 0.35 * (w - midState);   // up to ~1 kHz: tyres and wind
            double v = a * 5 * lowState + amp * 0.25 * midState;
            for (int t = 0; t + 1 < tones.length; t += 2) v += tones[t + 1] * Math.sin(2 * Math.PI * tones[t] * sample / Analyzer.RATE);
            s[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
        }
        return s;
    }

    /** band changes over 30 s of road noise (music: kick, hi-hat and a voice over it) */
    static int roadChanges(double boost, boolean music, long seed) {
        Analyzer a = new Analyzer();
        Random r = new Random(seed);
        double amp = 600;
        int changes = 0, last = -1;
        for (int f = 0; f < 47 + 470; f++) {
            if (f % 3 == 0) amp = 600 * Math.pow(10, (r.nextDouble() * 2 - 1) * 3 / 20);   // +-3 dB every ~200 ms
            boolean bump = r.nextInt(40) == 0;
            double[] tones = {};
            if (music && f >= 47) {
                int beat = f % 8;
                if (beat < 2) tones = new double[] { 80, 6000 };
                else if (beat == 4) tones = new double[] { 6000, 2500 };
                else if (f % 64 >= 32) tones = new double[] { 800, 3000 };
            }
            a.process(roadFrame(amp, r, bump, tones), 5, boost);
            if (f >= 47 && a.band != last) {
                if (last >= 0) changes++;
                last = a.band;
            }
        }
        return changes;
    }

    static double steadyLevel(int sensitivity) {
        Analyzer a = new Analyzer();
        rnd.setSeed(11);
        sample = 0;
        for (int i = 0; i < 47; i++) a.process(frame(200), sensitivity);
        for (int i = 0; i < 6; i++) a.process(frame(200, 500, 300, 1500, 300), sensitivity);
        return a.level;
    }

    // ---- the car's light calls, with the spacing the MICOM link wants (never two changes within 100 ms)

    static final class FakeCar implements Controller.Sink {
        long now, last = -1000;
        final List<String> log = new ArrayList<String>();
        final List<Long> times = new ArrayList<Long>();
        int tooSoon, restores;
        String failWith;   // answer every change with this (the service failing)

        String change(String what) {
            if (failWith != null) {
                log.add(what + " -> " + failWith);
                return failWith;
            }
            if (now - last < 100) tooSoon++;
            last = now;
            log.add(what);
            times.add(now);
            return null;
        }

        @Override
        public String color(int rgb) {
            if (!Palette.has(rgb)) return "not a palette colour";
            return change("color " + Palette.hex(rgb));
        }

        @Override
        public String brightness(int level) {
            if (level < 1 || level > 10) return "brightness out of range";
            return change("brightness " + level);
        }

        @Override
        public String restore() {
            restores++;
            log.add("restore");
            return null;
        }
    }

    static void controller() {
        for (String speed : new String[] { Settings.SPEED_FAST, Settings.SPEED_NORMAL, Settings.SPEED_SLOW }) {
            FakeCar car = new FakeCar();
            Controller c = new Controller(car);
            Settings s = new Settings();
            s.speed = speed;
            s.driving = Settings.DRIVE_NORMAL;
            Analyzer a = new Analyzer();
            Random r = new Random(3);
            for (long t = 0; t < 30000; t += 64) {   // 30 s of busy music: band and level jump every frame
                a.quiet = r.nextInt(10) == 0;
                a.level = r.nextDouble();
                a.band = r.nextInt(5);
                car.now = t;
                c.frame(t, a, s, Boolean.FALSE);
            }
            long minGap = Long.MAX_VALUE, minColorGap = Long.MAX_VALUE, lastColor = -100000;
            for (int i = 0; i < car.log.size(); i++) {
                if (i > 0) minGap = Math.min(minGap, car.times.get(i) - car.times.get(i - 1));
                if (car.log.get(i).startsWith("color ")) {
                    minColorGap = Math.min(minColorGap, car.times.get(i) - lastColor);
                    lastColor = car.times.get(i);
                }
            }
            double perSecond = car.log.size() / 30.0;
            check(speed + ": never two changes within 100 ms", car.tooSoon == 0);
            check(speed + ": first change is a colour", car.log.get(0).startsWith("color "));
            check(speed + ": changes at least " + Controller.gapMs(speed) + " ms apart (" + minGap + ")", minGap >= Controller.gapMs(speed));
            check(speed + ": colours at least " + Controller.colorHoldMs(speed) + " ms apart (" + minColorGap + ")",
                    minColorGap >= Controller.colorHoldMs(speed));
            check(speed + ": " + fmt(perSecond) + " changes a second (at most 7)", perSecond <= 7);
            check(speed + ": no problem shown", c.problem == null && !c.fatal);
        }

        // driving: pause gives the owner's light back once, and takes the light again when parked
        FakeCar car = new FakeCar();
        Controller c = new Controller(car);
        Settings s = new Settings();
        s.driving = Settings.DRIVE_PAUSE;
        Analyzer a = new Analyzer();
        a.quiet = false;
        a.level = 0.8;
        a.band = 1;
        long t = 0;
        for (; t < 3000; t += 64) frame(car, c, a, s, t, Boolean.FALSE);
        int before = car.log.size();
        for (; t < 6000; t += 64) frame(car, c, a, s, t, Boolean.TRUE);
        List<String> driving = car.log.subList(before, car.log.size());
        check("pause while moving: the owner's light back once, nothing else (" + driving + ")", driving.size() == 1 && "restore".equals(driving.get(0)));
        before = car.log.size();
        for (; t < 7000; t += 64) frame(car, c, a, s, t, Boolean.FALSE);
        check("parked again: the colour is sent again", car.log.size() > before && car.log.get(before).startsWith("color "));

        // driving, "colours only": the brightness stays at the top of the range
        car = new FakeCar();
        c = new Controller(car);
        s = new Settings();
        s.driving = Settings.DRIVE_COLORS;
        s.maxBright = 8;
        Random r = new Random(5);
        for (t = 0; t < 10000; t += 64) {
            a.level = r.nextDouble();
            a.quiet = r.nextInt(5) == 0;
            a.band = r.nextInt(5);
            frame(car, c, a, s, t, null);   // the car didn't say: the driving rule applies
        }
        int brightness = 0;
        boolean steady = true;
        for (String l : car.log) if (l.startsWith("brightness ")) {
            brightness++;
            steady &= "brightness 8".equals(l);
        }
        check("unknown motion, colours only: one brightness, the maximum (" + brightness + ")", brightness == 1 && steady);
        check("unknown motion, colours only: says why", c.state.contains("주행 상태를 몰라"));

        // a failing call: shown, retried after 3 s, cleared when it works
        car = new FakeCar();
        c = new Controller(car);
        s = new Settings();
        car.failWith = "java.lang.IllegalStateException: busy";
        frame(car, c, a, s, 0, Boolean.FALSE);
        int tried = car.log.size();
        for (t = 64; t < 2900; t += 64) frame(car, c, a, s, t, Boolean.FALSE);
        check("a failing call: shown, not fatal", c.problem != null && !c.fatal);
        check("a failing call: no retry before 3 s", car.log.size() == tried);
        car.failWith = null;
        for (; t < 3600; t += 64) frame(car, c, a, s, t, Boolean.FALSE);
        check("working again: changes go and the notice clears", car.log.size() > tried && c.problem == null);

        // the light service gone: stop
        car = new FakeCar();
        c = new Controller(car);
        car.failWith = "missing com.hkmc.micom.system";
        frame(car, c, a, new Settings(), 0, Boolean.FALSE);
        check("the service gone: fatal with a reason", c.fatal && c.problem.contains("com.hkmc.micom.system"));

        check("hex is lowercase and 6 digits", "0059f8".equals(Palette.hex(0x0059f8)) && "ff321d".equals(Palette.hex(0xff321d)));
        boolean defaults = Palette.has(Settings.DEFAULT_FIXED_COLOR);
        for (int col : Settings.DEFAULT_BAND_COLORS) defaults &= Palette.has(col);
        check("default colours are palette colours", defaults);
    }

    // ---- the "가속 연동" mode: a drive, as the CAN service and as whole km/h would report it

    /** the drive's speed at t (s): stopped, 0-100 km/h in 8 s, cruise, regeneration to 60, cruise, a gentle rise */
    static double driveKmh(double s) {
        if (s < 3) return 0;
        if (s < 11) return (s - 3) * 12.5;                   // 3.47 m/s2
        if (s < 25) return 100;
        if (s < 33) return 100 - (s - 25) * 5;               // -1.39 m/s2
        if (s < 45) return 60;
        if (s < 55) return 60 + (s - 45);                    // 0.28 m/s2: still cruising
        return 70;
    }

    /** runs the drive; returns the state at each 100 ms step. coarse: whole km/h, changing every 0.7 s */
    static int[] drive(boolean coarse, long seed) {
        Motion m = new Motion();
        Random r = new Random(seed);
        int[] states = new int[600];
        double held = 0;
        for (int i = 0; i < 600; i++) {
            double s = i / 10.0;
            double kmh = driveKmh(s);
            if (kmh > 0) kmh += r.nextGaussian() * 0.15;     // CAN jitter
            if (coarse) {
                if (i % 7 == 0) held = Math.floor(Math.max(0, kmh));
                kmh = held;
            }
            m.update(kmh, i * 100L, 5, coarse ? 2000 : 800);
            states[i] = m.state;
        }
        return states;
    }

    static int changes(int[] st, double from, double to) {
        int c = 0;
        for (int i = (int) (from * 10) + 1; i < (int) (to * 10); i++) if (st[i] != st[i - 1]) c++;
        return c;
    }

    static double firstAt(int[] st, double from, int state) {
        for (int i = (int) (from * 10); i < st.length; i++) if (st[i] == state) return i / 10.0;
        return -1;
    }

    static void motion() {
        for (boolean coarse : new boolean[] { false, true }) {
            String src = coarse ? "whole km/h" : "CAN";
            int[] st = drive(coarse, 4);
            check(src + ": stopped at the start", st[25] == Motion.STOPPED);
            double acc = firstAt(st, 3, Motion.ACCEL);
            check(src + ": speeding up seen " + fmt(acc - 3) + " s after it starts", acc > 0 && acc - 3 <= (coarse ? 2.5 : 1.0));
            check(src + ": speeding up held through the run", changes(st, acc + 0.1, 10.5) == 0);
            double end = firstAt(st, 11, Motion.CRUISE);
            check(src + ": back to cruising " + fmt(end - 11) + " s after the run ends", end > 0 && end - 11 <= (coarse ? 3.0 : 1.2));
            check(src + ": cruising at 100, no flicker", st[180] == Motion.CRUISE && changes(st, 16, 24.5) == 0);
            double dec = firstAt(st, 25, Motion.DECEL);
            check(src + ": slowing down (regeneration) seen " + fmt(dec - 25) + " s after it starts", dec > 0 && dec - 25 <= (coarse ? 2.5 : 1.0));
            double end2 = firstAt(st, 33, Motion.CRUISE);
            check(src + ": back to cruising " + fmt(end2 - 33) + " s after the regeneration ends", end2 > 0 && end2 - 33 <= (coarse ? 3.0 : 1.2));
            check(src + ": cruising again at 60, no flicker", st[400] == Motion.CRUISE && changes(st, 37, 45) == 0);
            check(src + ": a gentle 0.28 m/s2 rise stays cruising (" + changes(st, 46, 55) + " changes)", changes(st, 46, 55) == 0 && st[500] == Motion.CRUISE);
        }
        // the accelerator pedal: at once; a light press is cruising, a deep one speeding up; braking and
        // regeneration still show off the pedal. Percent and 0-255 raw scales.
        for (double scale : new double[] { 1, 2.55 }) {
            String src = scale == 1 ? "pedal %" : "pedal 0-255";
            Motion m = new Motion();
            int[] st = new int[450];
            double cruisePct = Double.NaN;
            for (int i = 0; i < 450; i++) {
                double sec = i / 10.0, kmh = sec < 33 ? driveKmh(sec) : 60, pedal = 0, brake = 0;
                if (sec >= 3 && sec < 11) pedal = 45;
                else if (sec >= 11 && sec < 25) pedal = 15;
                if (sec >= 33 && sec < 36) brake = 8;
                m.updatePedal(kmh, pedal * scale, brake, i * 100L, 5, 800);
                st[i] = m.state;
                if (i == 180) cruisePct = m.pedalPct;
            }
            check(src + ": stopped before the press", st[25] == Motion.STOPPED);
            check(src + ": a deep press is speeding up at once (0.1 s)", st[31] == Motion.ACCEL);
            check(src + ": a light press holding the speed is cruising, read as " + fmt(cruisePct) + " %",
                    st[180] == Motion.CRUISE && Math.abs(cruisePct - 15) < 0.5);
            check(src + ": off the pedal, regeneration is slowing down", st[300] == Motion.DECEL);
            check(src + ": braking at a steady speed is slowing down at once", st[332] == Motion.DECEL);
            check(src + ": off both at a steady speed is cruising", st[420] == Motion.CRUISE);
        }
        Motion rest = new Motion();
        for (int i = 0; i < 30; i++) rest.updatePedal(0, 0.8, 0, i * 100L, 5, 800);
        check("pedal noise of 0.8 at rest isn't taken for a press", rest.state == Motion.STOPPED);

        // the light: speeding up takes its colour and brightens with the strength, cruising is calmer
        FakeCar car = new FakeCar();
        Controller c = new Controller(car);
        Settings s = new Settings();
        s.mode = Settings.MODE_MOTION;
        Motion m = new Motion();
        long t = 0;
        for (; t < 8000; t += 100) {
            car.now = t;
            m.update(driveKmh(t / 1000.0), t, 5, 800);
            c.frame(t, new Analyzer(), s, null);   // not called in this mode; motionFrame is
            c.motionFrame(t, m, s);
        }
        check("speeding up: the speeding-up colour, near full brightness (" + c.sentBright + ")",
                c.sentColor == s.accelColor && c.sentBright >= 8);
        for (; t < 22000; t += 100) {
            car.now = t;
            m.update(driveKmh(t / 1000.0), t, 5, 800);
            c.motionFrame(t, m, s);
        }
        check("cruising: the cruising colour, middle brightness (" + c.sentBright + ")", c.sentColor == s.cruiseColor && c.sentBright == 6);
        check("acceleration mode: never two changes within 100 ms", car.tooSoon == 0);
        check("acceleration colours are palette colours",
                Palette.has(Settings.DEFAULT_CRUISE_COLOR) && Palette.has(Settings.DEFAULT_ACCEL_COLOR) && Palette.has(Settings.DEFAULT_DECEL_COLOR));
    }

    // ---- the car's light report, as logged on the owner's car (2026-11-12)

    static void status() {
        CarLight.Status s = CarLight.Status.parse(new byte[] { 0x00, (byte) 0xff, 0x00, 0x32, 0x00, 0x1d, 0x0a, 0x02, 0x02 }, 1);
        check("report: colour ff321d", s.rgb() == 0xff321d);
        check("report: brightness 10, dimmed on", s.brightness == 10 && s.dimmed == 2);
        check("report: drive mode link on", s.driveModeLinked());
        CarLight.Status off = CarLight.Status.parse(new byte[] { 0x00, 0x10, 0x00, 0x43, 0x00, (byte) 0xf4, 0x0a, 0x02, 0x01 }, 2);
        check("report: drive mode link off, colour 1043f4", !off.driveModeLinked() && off.rgb() == 0x1043f4);
        CarLight.Status menu = CarLight.Status.parse(new byte[] { 0x01, (byte) 0xd9, 0x01, (byte) 0xae, 0x01, 0x5f, 0x0a, 0x02, 0x01 }, 3);
        check("report: the menu's own colour keeps its first bytes for putting back", menu.red[0] == 1 && menu.red[1] == (byte) 0xd9);
        check("report: short ones are refused", CarLight.Status.parse(new byte[] { 1, 2, 3 }, 4) == null);
        check("driving margin: parked 0, 90 km/h 3.6 dB, capped at 4, unknown speed 2",
                Settings.noiseBoostDb(0, Settings.NOISE_NORMAL) == 0 && Math.abs(Settings.noiseBoostDb(90, Settings.NOISE_NORMAL) - 3.6) < 1e-9
                        && Settings.noiseBoostDb(200, Settings.NOISE_NORMAL) == 4 && Settings.noiseBoostDb(null, Settings.NOISE_NORMAL) == 2);
        check("driving margin: weaker and stronger choices", Settings.noiseBoostDb(90, Settings.NOISE_LOW) < 3.6 && Settings.noiseBoostDb(90, Settings.NOISE_HIGH) > 3.6);
        MicSignals m = new MicSignals();
        check("Siri widget update on its own: the microphone is wanted", m.wanted(MicSignals.SIRI, 10000));
        check("a widget refresh request: not itself a reason", !m.wanted("com.hkmc.request.widgetupdate", 20000));
        check("Siri widget update right after a refresh: an echo, ignored", !m.wanted(MicSignals.SIRI, 20300));
        check("call widget update right after a page change: an echo, ignored",
                !m.wanted("com.android.launcher3.PAGE_CHANGED", 30000) && !m.wanted(MicSignals.CALL, 31000));
        check("Siri widget update 2 s after a refresh: wanted", m.wanted(MicSignals.SIRI, 33000));
        check("voice button right after a refresh: still wanted",
                !m.wanted("com.hkmc.request.widgetupdate.carplay", 40000) && m.wanted("com.hkmc.intent.action.VR_PTT_SHORTCUT", 40100));
        check("car voice recognition: wanted; unknown action: not", m.wanted(MicSignals.VR_BEGIN, 50000) && !m.wanted("x.y", 50000));
        check("listens for 11 actions", MicSignals.all().length == 11);
        CarLight.Status kept = CarLight.Status.fromHex(menu.hex(), 9);
        check("kept light: 18 hex digits, back exactly (menu colour bytes, brightness, switches)",
                menu.hex().equals("01d901ae015f0a0201") && kept.red[0] == 1 && kept.red[1] == (byte) 0xd9 && kept.brightness == 10
                        && kept.dimmed == 2 && kept.driveMode == 1);
        check("kept light: anything else is refused", CarLight.Status.fromHex("zz", 1) == null && CarLight.Status.fromHex(null, 1) == null);
        check("two bytes, big endian (as the settings app sends)", CarLight.two(0x1d9)[0] == 1 && CarLight.two(0x1d9)[1] == (byte) 0xd9);
    }

    static void frame(FakeCar car, Controller c, Analyzer a, Settings s, long t, Boolean moving) {
        car.now = t;
        c.frame(t, a, s, moving);
    }

    static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    public static void main(String[] args) {
        analyzer();
        controller();
        motion();
        status();
        System.out.println(checks - failures + "/" + checks + " checks passed");
        if (failures > 0) System.exit(1);
    }
}

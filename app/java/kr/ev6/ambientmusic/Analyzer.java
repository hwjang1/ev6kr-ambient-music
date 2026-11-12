package kr.ev6.ambientmusic;

/**
 * One 64 ms microphone frame (1024 samples, 16 kHz mono): how loud it is over the
 * cabin's background, and which of the five bands the colour should follow.
 * Plain Java (no Android), so it's tested off the car (tests/).
 *
 * Brightness: each frame against the last few seconds' average (as the original
 * Ambient Sound worker did on a car): the average sits at 40 %, louder frames go
 * up (sensitivity 5: +10 dB is the top), so the light pulses with the music at any
 * volume. Silence: the background (road noise, fan) is tracked as a floor that
 * drops to a quiet moment at once and creeps up over ~1.5 minutes; a short-smoothed
 * level within a few dB of it is "quiet" (the light dims, the colour holds).
 *
 * Colour: each band's energy against its own recent average (~2 s), so the colour
 * follows whatever surges (a kick, a voice, cymbals) rather than the bass, which
 * always carries the most energy. A new band has to beat the current one by 25 %.
 *
 * Road noise (0.2.1): the cabin microphone hears the road too, and its rumble and
 * bumps surge as well. So each band also keeps its own steady background (the floor
 * of its smoothed energy: drops to a quiet moment at once, rises over ~15 s), and a
 * band can take the colour only while it stands well above that background (6.5 dB
 * at sensitivity 5). While driving the caller adds a margin from the speed
 * (noiseBoostDb), twice over for the lowest band, where the rumble is; the silence
 * gate rises by the same margin.
 */
final class Analyzer {
    static final int RATE = 16000, N = 1024;
    static final int BANDS = Settings.BAND_LOW.length;

    private final float[] window = new float[N], re = new float[N], im = new float[N];
    private final float[] cos = new float[N / 2], sin = new float[N / 2];
    private final int[] rev = new int[N], binLo = new int[BANDS], binHi = new int[BANDS];
    private final double[] avg = new double[BANDS], smooth = new double[BANDS], noise = new double[BANDS];
    final double[] energy = new double[BANDS], ratio = new double[BANDS], excessDb = new double[BANDS];
    private long frames;

    double db = -90, floorDb = -90, avgDb = -90, fastDb = -90;
    /** 0..1, smoothed loudness over the background */
    double level;
    /** nothing louder than the background: the colour holds, the light dims */
    boolean quiet = true;
    /** the band the colour follows; -1 until there has been sound */
    int band = -1;

    Analyzer() {
        for (int i = 0; i < N; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
        for (int i = 0; i < N / 2; i++) {
            cos[i] = (float) Math.cos(2 * Math.PI * i / N);
            sin[i] = (float) Math.sin(2 * Math.PI * i / N);
        }
        int bits = Integer.numberOfTrailingZeros(N);
        for (int i = 0; i < N; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        double hzPerBin = (double) RATE / N;
        for (int b = 0; b < BANDS; b++) {
            binLo[b] = Math.max(1, (int) Math.ceil(Settings.BAND_LOW[b] / hzPerBin));
            binHi[b] = Math.min(N / 2, (int) Math.ceil(Settings.BAND_HIGH[b] / hzPerBin));
        }
    }

    /** sensitivity 1..10 (5: neutral) */
    void process(short[] s, int sensitivity) {
        process(s, sensitivity, 0);
    }

    /** noiseBoostDb: the extra margin over the background while driving (0 when parked) */
    void process(short[] s, int sensitivity, double noiseBoostDb) {
        if (s.length < N) throw new IllegalArgumentException(N + " samples needed");
        double boost = noiseBoostDb < 0 ? 0 : noiseBoostDb;
        double sum = 0;
        for (int i = 0; i < N; i++) sum += (double) s[i] * s[i];
        db = 20 * Math.log10(Math.max(Math.sqrt(sum / N), 1) / 32768.0);
        if (frames == 0) floorDb = avgDb = fastDb = db;
        floorDb += (db < floorDb ? 0.2 : 0.0007) * (db - floorDb);
        avgDb += 0.02 * (db - avgDb);     // ~3 s
        fastDb += 0.3 * (db - fastDb);    // ~0.2 s
        double gate = 4.5 - 0.3 * sensitivity + boost;   // dB over the background that counts as sound: 1: 4.2, 5: 3, 10: 1.5 (+ driving)
        quiet = fastDb - floorDb < gate;
        double perDb = (0.02 + 0.008 * sensitivity) / (1 + boost / 4);   // per dB: 1: 0.028, 5: 0.06, 10: 0.1 (less while driving)
        double x = quiet ? 0 : 0.4 + (db - avgDb) * perDb;
        x = x < 0 ? 0 : x > 1 ? 1 : x;
        level += (x > level ? 0.6 : 0.2) * (x - level);

        for (int i = 0; i < N; i++) {
            int j = rev[i];
            re[j] = s[i] * window[i];
            im[j] = 0;
        }
        fft();
        double total = 0;
        for (int b = 0; b < BANDS; b++) {
            double e = 0;
            for (int k = binLo[b]; k < binHi[b]; k++) e += (double) re[k] * re[k] + (double) im[k] * im[k];
            energy[b] = e;
            total += e;
        }
        int best = -1;
        double bandGate = 9 - 0.5 * sensitivity + boost;   // dB over the band's own background: 1: 8.5, 5: 6.5, 10: 4 (+ driving)
        for (int b = 0; b < BANDS; b++) {
            if (frames == 0) avg[b] = smooth[b] = noise[b] = energy[b];
            // the 1 % of the frame's total keeps a nearly empty band from winning on noise
            ratio[b] = energy[b] / (avg[b] + total * 0.01 + 1e-9);
            avg[b] += (frames < 16 ? 1.0 / (frames + 2) : 0.03) * (energy[b] - avg[b]);
            smooth[b] += 0.5 * (energy[b] - smooth[b]);
            noise[b] += (smooth[b] < noise[b] ? 0.2 : 0.004) * (smooth[b] - noise[b]);
            excessDb[b] = 10 * Math.log10((smooth[b] + 1e-9) / (noise[b] + 1e-9));
            boolean standsOut = excessDb[b] >= bandGate + (b == 0 ? boost : 0);
            if (standsOut && energy[b] >= total * 0.02 && (best < 0 || ratio[b] > ratio[best])) best = b;
        }
        frames++;
        // the colour moves only on a frame that is itself sound (not the tail of a stop or a drop-out)
        if (quiet || best < 0 || db - floorDb < gate || db < avgDb - 6) return;
        if (band < 0 || best == band || ratio[best] > ratio[band] * 1.25) band = best;
    }

    /** in place, over the bit-reversed input */
    private void fft() {
        for (int size = 2; size <= N; size <<= 1) {
            int half = size >> 1, step = N / size;
            for (int i = 0; i < N; i += size) {
                for (int k = 0; k < half; k++) {
                    float wr = cos[k * step], wi = -sin[k * step];
                    int a = i + k, b = a + half;
                    float tr = re[b] * wr - im[b] * wi, ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
    }
}

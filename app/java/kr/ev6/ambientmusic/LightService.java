package kr.ev6.ambientmusic;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.util.Locale;

/**
 * Listens to the microphone and drives the ambient light while it runs (a foreground service,
 * so the system leaves it be), with the car's own light calls (CarLight), as the vehicle
 * settings app makes them. The owner's light is read from the car when it starts and put back
 * when it stops. Nothing is recorded or kept: each frame is analysed and dropped.
 */
public final class LightService extends Service {
    static final String TAG = "AmbientMusic";
    static final String ACTION_START = "kr.ev6.ambientmusic.START", ACTION_STOP = "kr.ev6.ambientmusic.STOP";

    // what the app shows (same process)
    static volatile boolean running;
    static volatile String state = "꺼짐", problem, mic = "", drive = "", meter = "";
    static volatile double level;
    static volatile int band = -1, color = -1, bright = -1, sent;
    /** the car's last light report (from any process's request: the settings app's too) */
    static volatile CarLight.Status carStatus;

    /**
     * The car's microphone goes through its voice DSP (FM1288: hands-free, Siri, voice
     * recognition), and CarPlay opens the capture device itself (tinyalsa, a few retries
     * while it's busy). Android 4.4 lets one recording run at a time, so whenever Siri, a
     * CarPlay call or the car's voice recognition may want the microphone (MicSignals), ours
     * is stopped at once (in the receiver, not at the worker's next turn) and left free for a
     * while; each signal starts the wait again.
     */
    static final long VOICE_PAUSE_MS = 30000;
    static volatile long micFreeUntil;
    /** what the microphone was left free for (shown on the screen) */
    static volatile String micReason = "";
    /** the recording now, stopped from the receiver; MIC_LOCK keeps stop and release apart */
    static final Object MIC_LOCK = new Object();
    static AudioRecord current;

    static final String EXTRA_AFTER_BOOT = "after_boot";

    /** the owner's on/off (the app's button, the star key): kept, so a restart comes back the same way */
    static void turn(Context c, boolean on) {
        Settings.setEnabled(c, on);
        if (on) start(c, false);
        else stop(c);
    }

    /** afterBoot: the head unit just started; the car's services get a moment first */
    static void start(Context c, boolean afterBoot) {
        c.startService(new Intent(c, LightService.class).setAction(ACTION_START).putExtra(EXTRA_AFTER_BOOT, afterBoot));
    }

    static void stop(Context c) {
        c.startService(new Intent(c, LightService.class).setAction(ACTION_STOP));
    }

    /** a receiver for the car's light report (one per registering context: the service, the app's screen) */
    static BroadcastReceiver statusReceiver() {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                CarLight.Status s = CarLight.Status.parse(i.getByteArrayExtra(CarLight.EXTRA_STATUS), SystemClock.elapsedRealtime());
                if (s != null) carStatus = s;
            }
        };
    }

    private final BroadcastReceiver status = statusReceiver();
    private final Handler main = new Handler();
    private Worker worker;
    private boolean restart;
    private final MicSignals signals = new MicSignals();
    private final BroadcastReceiver voiceKeys = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            long now = SystemClock.elapsedRealtime();
            if (!signals.wanted(i.getAction(), now)) return;   // a widget refresh echo, or only heard to tell those apart
            micFreeUntil = now + VOICE_PAUSE_MS;
            micReason = MicSignals.label(i.getAction());
            boolean stopped = false;
            synchronized (MIC_LOCK) {
                if (current != null) {
                    try {
                        current.stop();   // the worker's read returns, and it releases the microphone
                        stopped = true;
                    } catch (IllegalStateException ignored) {
                        // not recording
                    }
                }
            }
            // warning level: ev6kr.usbap.sync's debug logcat keeps warnings from every app
            Log.w(TAG, i.getAction() + ": microphone " + (stopped ? "stopped, " : "") + "left free for " + VOICE_PAUSE_MS / 1000 + " s");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter();
        for (String a : MicSignals.all()) f.addAction(a);
        registerReceiver(voiceKeys, f);
        registerReceiver(status, new IntentFilter(CarLight.ACTION_STATUS));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // a null intent: the system restarted us after killing the process
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            restart = false;
            if (worker != null) {
                worker.ownerStop = true;   // the owner turned it off: their light back, and no longer kept
                worker.finishing = true;
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (worker != null) {
            if (worker.finishing) restart = true;   // started again while stopping: go on once it's done
            return START_STICKY;
        }
        begin(intent != null && intent.getBooleanExtra(EXTRA_AFTER_BOOT, false));
        return START_STICKY;
    }

    private void begin(boolean afterBoot) {
        problem = null;
        running = true;
        state = afterBoot ? "시동 후 준비 중" : "시작하는 중";
        startForeground(1, notification());
        worker = new Worker(afterBoot);
        worker.start();
    }

    /** main thread: the worker is done (stopped, or the light service is gone) */
    private void ended(Worker w) {
        if (w != worker) return;
        worker = null;
        if (restart) {
            restart = false;
            begin(false);
            return;
        }
        running = false;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (worker != null) worker.finishing = true;   // it puts the owner's light back on its own thread
        running = false;
        unregisterReceiver(voiceKeys);
        unregisterReceiver(status);
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
        return new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("마이크로 소리를 듣고 무드램프를 바꾸는 중")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    /** the car's light, with the owner's light kept for putting back */
    private static final class CarSink implements Controller.Sink {
        final CarLight car;
        /** the owner's light: the car's report from before our first change, or a menu change since */
        volatile CarLight.Status owner;
        int lastColor = -1, lastBrightRaw = -1;
        long lastAt;

        CarSink(CarLight car) {
            this.car = car;
        }

        @Override
        public String color(int rgb) {
            try {
                car.color(rgb);
                lastColor = rgb;
                lastAt = SystemClock.elapsedRealtime();
                return null;
            } catch (Throwable t) {
                return why(t);
            }
        }

        @Override
        public String brightness(int level) {
            try {
                car.brightnessRaw(level + 1);
                lastBrightRaw = level + 1;
                lastAt = SystemClock.elapsedRealtime();
                return null;
            } catch (Throwable t) {
                return why(t);
            }
        }

        @Override
        public String restore() {
            CarLight.Status o = owner;
            if (o == null) return "원래 조명을 알 수 없어 되돌리지 못했습니다. 차량 설정에서 색을 다시 골라 주세요.";
            try {
                car.colorRaw(o.red, o.green, o.blue);
                SystemClock.sleep(150);
                car.brightnessRaw(o.brightness);
                lastColor = -1;
                lastBrightRaw = -1;
                lastAt = SystemClock.elapsedRealtime();
                Log.i(TAG, "owner's light back: " + o);
                return null;
            } catch (Throwable t) {
                return "원래 조명으로 되돌리지 못했습니다: " + why(t);
            }
        }

        /** the car's report of a light we set: what we sent shows (colour, brightness, whichever we sent) */
        boolean ours(CarLight.Status st) {
            if (lastColor < 0 && lastBrightRaw < 0) return false;
            boolean colorOk = lastColor < 0 || st.red[0] == 0 && st.green[0] == 0 && st.blue[0] == 0 && st.rgb() == lastColor;
            boolean brightOk = lastBrightRaw < 0 || st.brightness == lastBrightRaw;
            return colorOk && brightOk;
        }

        static String why(Throwable t) {
            if (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) t = t.getCause();
            return t.toString();
        }
    }

    private final class Worker extends Thread {
        volatile boolean finishing, ownerStop;
        final boolean afterBoot;

        Worker(boolean afterBoot) {
            super("AmbientMusic");
            this.afterBoot = afterBoot;
        }

        /** waits ms unless stopping; false when stopping */
        boolean pause(long ms) {
            long end = SystemClock.elapsedRealtime() + ms;
            while (!finishing && SystemClock.elapsedRealtime() < end) SystemClock.sleep(200);
            return !finishing;
        }

        @Override
        public void run() {
            CarSink sink = null;
            Throwable failed = null;
            // right after a start the car's services may not be up yet: 15 s first, then a minute of tries
            if (!afterBoot || pause(15000)) {
                for (int i = 0; i < 20 && !finishing; i++) {
                    try {
                        sink = new CarSink(new CarLight().connect());
                        break;
                    } catch (Throwable t) {
                        failed = t;
                        if (!afterBoot || !pause(3000)) break;
                    }
                }
            }
            if (sink == null) {
                if (failed != null) Log.e(TAG, "the car's light service", failed);
                problem = failed == null ? null : "차의 무드램프 서비스를 찾을 수 없습니다: " + CarSink.why(failed);
                state = "꺼짐";
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        ended(Worker.this);
                    }
                });
                return;
            }
            Controller ctl = new Controller(sink);
            // the owner's light: kept from before a restart (the car would now report the music's last
            // colour, which the vehicle settings app saved), else asked from the car and kept
            CarLight.Status kept = CarLight.Status.fromHex(Settings.ownerLight(LightService.this), SystemClock.elapsedRealtime());
            if (kept != null) {
                sink.owner = kept;
                Log.w(TAG, "start; owner's light kept from before: " + kept);
            } else {
                for (int i = 0; i < 3 && sink.owner == null && !finishing; i++) sink.owner = askLight(sink.car);
                if (sink.owner != null) Settings.setOwnerLight(LightService.this, sink.owner.hex());
                Log.w(TAG, "start; owner's light " + sink.owner);
            }
            Analyzer an = new Analyzer();
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            Settings s = Settings.load(LightService.this);
            int seen = Settings.version;
            short[] buf = new short[Analyzer.N];
            AudioRecord rec = null;
            String recSource = null, lastProblem = null;
            long nextSpeed = 0, micRetry = 0, statusSeen = sink.owner == null ? 0 : sink.owner.at;
            int zeroFrames = 0;
            Boolean moving = null;
            Integer speed = null;
            try {
                while (!finishing) {
                    long now = SystemClock.elapsedRealtime();
                    if (seen != Settings.version) {
                        seen = Settings.version;
                        s = Settings.load(LightService.this);
                    }
                    if (now >= nextSpeed) {
                        nextSpeed = now + 1000;
                        speed = speed(sink.car);
                        moving = speed == null ? null : speed >= 5;
                        drive = speed == null ? "알 수 없음" : speed >= 5 ? "주행 중 (" + speed + " km/h)" : "정차";
                    }
                    // a report that isn't the answer to one of our changes: the owner changed the light in the
                    // menu, and that is what stopping puts back
                    CarLight.Status st = carStatus;
                    if (st != null && st.at > statusSeen) {
                        statusSeen = st.at;
                        // only once we've changed the light: before that a report is the car's own light
                        if (sink.lastAt > 0 && st.at - sink.lastAt > 1500 && !sink.ours(st)) {
                            sink.owner = st;
                            Settings.setOwnerLight(LightService.this, st.hex());
                            Log.w(TAG, "the owner changed the light: " + st);
                        }
                    }
                    int mode = am.getMode();
                    if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                            || mode == AudioManager.MODE_RINGTONE) {
                        if (rec != null) {
                            close(rec);
                            rec = null;
                            Log.i(TAG, "call (audio mode " + mode + "): microphone released");
                        }
                        mic = "통화 중: 마이크를 비워 둠";
                        state = "통화 중 일시정지";
                        SystemClock.sleep(500);
                        continue;
                    }
                    if (now < micFreeUntil) {
                        if (rec != null) {
                            close(rec);
                            rec = null;
                            Log.w(TAG, "Siri, a call or voice recognition: microphone released");
                        }
                        mic = micReason + ": 마이크를 비워 둠 (" + ((micFreeUntil - now + 999) / 1000) + "초)";
                        state = "Siri·통화를 위해 잠시 멈춤";
                        SystemClock.sleep(300);
                        continue;
                    }
                    if (rec != null && !s.source.equals(recSource)) {
                        close(rec);
                        rec = null;
                    }
                    if (rec == null) {
                        if (now < micRetry) {
                            SystemClock.sleep(200);
                            continue;
                        }
                        rec = open(s.source);
                        synchronized (MIC_LOCK) {
                            current = rec;
                        }
                        if (rec == null) {
                            micRetry = now + 3000;
                            mic = "마이크를 열 수 없음 (3초 뒤 다시 시도)";
                            state = mic;
                            Log.w(TAG, "microphone (" + s.source + ") didn't open");
                            continue;
                        }
                        recSource = s.source;
                        zeroFrames = 0;
                        mic = "마이크 사용 중";
                        Log.w(TAG, "microphone open (" + s.source + ")");
                    }
                    if (!read(rec, buf)) {
                        close(rec);
                        rec = null;
                        micRetry = now + 2000;
                        mic = "마이크 읽기 실패 (다시 여는 중)";
                        Log.w(TAG, "microphone read failed");
                        continue;
                    }
                    zeroFrames = silent(buf) ? zeroFrames + 1 : 0;
                    if (zeroFrames == 47) {   // ~3 s of exact zeros: something else may have the microphone
                        mic = "마이크 입력이 0입니다 (다른 기능이 마이크를 쓰는 중일 수 있음)";
                        Log.w(TAG, "microphone gives only zeros");
                    } else if (zeroFrames == 0 && mic.startsWith("마이크 입력이 0")) {
                        mic = "마이크 사용 중";
                    }
                    an.process(buf, s.sensitivity, Settings.noiseBoostDb(speed, s.noise));
                    ctl.frame(SystemClock.elapsedRealtime(), an, s, moving);
                    level = an.level;
                    band = an.band;
                    color = ctl.sentColor;
                    bright = ctl.sentBright;
                    sent = ctl.sent;
                    state = ctl.state;
                    problem = ctl.problem;
                    meter = String.format(Locale.US, "%.0f dB (배경 %.0f dB)", an.db, an.floorDb);
                    if (ctl.problem != null && !ctl.problem.equals(lastProblem)) Log.w(TAG, ctl.problem);
                    lastProblem = ctl.problem;
                    if (ctl.fatal) break;
                }
            } catch (Throwable t) {
                Log.e(TAG, "worker stopped", t);
                problem = "오류로 멈췄습니다: " + t;
            } finally {
                if (rec != null) close(rec);
                String r = sink.restore();
                if (r != null) {
                    problem = r;
                    Log.w(TAG, "stop: " + r);
                } else if (ownerStop) {
                    Settings.setOwnerLight(LightService.this, null);   // given back: nothing to keep
                }
                if (ctl.fatal) problem = ctl.problem;
                state = "꺼짐";
                mic = "";
                meter = "";
                level = 0;
                color = -1;
                bright = -1;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        ended(Worker.this);
                    }
                });
            }
        }
    }

    /** asks the car for its light and waits up to 1.5 s for the report; the last one heard otherwise */
    static CarLight.Status askLight(CarLight car) {
        long asked = SystemClock.elapsedRealtime();
        try {
            car.requestStatus();
        } catch (Throwable t) {
            Log.w(TAG, "requestSMLStatus: " + t);
        }
        for (int i = 0; i < 15; i++) {
            CarLight.Status s = carStatus;
            if (s != null && s.at >= asked) return s;
            SystemClock.sleep(100);
        }
        return carStatus;
    }

    /** km/h as the car reports it; null when it doesn't answer */
    static Integer speed(CarLight car) {
        try {
            return car.speed();
        } catch (Throwable t) {
            return null;
        }
    }

    private static AudioRecord open(String source) {
        int src = Settings.SOURCE_VOICE.equals(source) ? MediaRecorder.AudioSource.VOICE_RECOGNITION
                : MediaRecorder.AudioSource.MIC;
        int min = AudioRecord.getMinBufferSize(Analyzer.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return null;
        AudioRecord r;
        try {
            r = new AudioRecord(src, Analyzer.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, Analyzer.RATE * 2 * 2));   // 2 s: a slow car call never loses audio
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            return null;
        }
        try {
            r.startRecording();
        } catch (IllegalStateException e) {
            r.release();
            return null;
        }
        if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            r.release();
            return null;
        }
        return r;
    }

    private static boolean read(AudioRecord r, short[] buf) {
        int n = 0;
        while (n < buf.length) {
            int k = r.read(buf, n, buf.length - n);
            if (k <= 0) return false;
            n += k;
        }
        return true;
    }

    private static boolean silent(short[] buf) {
        for (short v : buf) if (v != 0) return false;
        return true;
    }

    private static void close(AudioRecord r) {
        synchronized (MIC_LOCK) {
            if (current == r) current = null;
            try {
                r.stop();
            } catch (IllegalStateException ignored) {
                // not recording (stopped by the receiver already)
            }
            r.release();
        }
    }
}

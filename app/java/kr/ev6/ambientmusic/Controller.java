package kr.ev6.ambientmusic;

/**
 * Turns each analysed frame into at most one ambient light change. The car's light calls go
 * over the MICOM link the rest of the car shares, so changes are spaced (150-300 ms, never
 * under 100 ms), a colour is held for a while, and nothing is sent that's already showing.
 * Plain Java (no Android), tested off the car (tests/).
 */
final class Controller {
    interface Sink {
        /** each returns null when sent, or why it wasn't; never throws */
        String color(int rgb);

        /** the menu's level 1-10 */
        String brightness(int level);

        /** puts the owner's light back (pausing while driving, stopping) */
        String restore();
    }

    final Sink sink;
    /** what the light shows now (as far as we know); -1: not ours / unknown */
    int sentColor = -1, sentBright = -1;
    int targetColor = -1, targetBright = -1;
    /** shown in the app */
    String state = "";
    String problem;
    /** the car's light service is gone: stop */
    boolean fatal;
    int sent;
    long lastSendAt = Long.MIN_VALUE / 2;
    private long lastColor = Long.MIN_VALUE / 2, lastBright = Long.MIN_VALUE / 2;
    private long backoffUntil = Long.MIN_VALUE / 2;
    private boolean colorLast, holding;

    Controller(Sink sink) {
        this.sink = sink;
    }

    /** the least time between any two changes */
    static long gapMs(String speed) {
        return Settings.SPEED_FAST.equals(speed) ? 150 : Settings.SPEED_SLOW.equals(speed) ? 300 : 200;
    }

    /** how long a colour stays before the next one */
    static long colorHoldMs(String speed) {
        return Settings.SPEED_FAST.equals(speed) ? 300 : Settings.SPEED_SLOW.equals(speed) ? 1500 : 700;
    }

    static long brightHoldMs(String speed) {
        return Settings.SPEED_FAST.equals(speed) ? 150 : Settings.SPEED_SLOW.equals(speed) ? 400 : 250;
    }

    /** one frame. moving: null when the car didn't say (then the driving rule applies, to be safe) */
    void frame(long now, Analyzer a, Settings s, Boolean moving) {
        boolean driveRule = (moving == null || moving) && !Settings.DRIVE_NORMAL.equals(s.driving);
        if (driveRule && Settings.DRIVE_PAUSE.equals(s.driving)) {
            state = moving == null ? "주행 상태를 몰라 일시정지 (원래 조명)" : "주행 중 일시정지 (원래 조명)";
            if (holding) release(now);
            return;
        }
        if (Settings.MODE_PULSE.equals(s.mode)) targetColor = s.fixedColor;
        else if (a.band >= 0) targetColor = s.bandColors[a.band];
        else if (targetColor < 0 || !Palette.has(targetColor)) targetColor = s.bandColors[0];
        boolean steady = Settings.MODE_COLORS.equals(s.mode) || driveRule;
        targetBright = steady ? s.maxBright
                : a.quiet ? s.minBright
                : s.minBright + (int) Math.round(a.level * (s.maxBright - s.minBright));
        if (driveRule) state = moving == null ? "주행 상태를 몰라 색만 천천히" : "주행 중: 색만 천천히";
        else state = a.quiet ? "조용함: 소리를 기다리는 중" : "소리에 맞춰 동작 중";

        if (now < backoffUntil || now - lastSendAt < gapMs(s.speed)) return;
        long hold = driveRule ? Math.max(1500, colorHoldMs(s.speed)) : colorHoldMs(s.speed);
        boolean colorDue = targetColor != sentColor && now - lastColor >= hold;
        boolean brightDue = targetBright != sentBright && now - lastBright >= brightHoldMs(s.speed);
        if (!colorDue && !brightDue) return;
        // both due: take turns, the colour first while the light isn't ours yet
        boolean doColor = colorDue && (!brightDue || sentColor < 0 || !colorLast);
        lastSendAt = now;
        String r = doColor ? sink.color(targetColor) : sink.brightness(targetBright);
        if (r == null) {
            holding = true;
            problem = null;
            sent++;
            colorLast = doColor;
            if (doColor) {
                sentColor = targetColor;
                lastColor = now;
            } else {
                sentBright = targetBright;
                lastBright = now;
            }
        } else if (r.startsWith("missing")) {
            problem = "차의 무드램프 서비스를 찾을 수 없습니다 (" + r + ").";
            fatal = true;
        } else {
            problem = "무드램프 명령이 실패했습니다: " + r + " (3초 뒤 다시 시도)";
            backoffUntil = now + 3000;
        }
    }

    /** gives the light back: the owner's colour and brightness */
    void release(long now) {
        String r = sink.restore();
        if (r != null) problem = r;
        holding = false;
        sentColor = -1;
        sentBright = -1;
        lastSendAt = now;
    }

    boolean holding() {
        return holding;
    }
}

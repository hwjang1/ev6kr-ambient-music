package kr.ev6.ambientmusic;

/**
 * Which broadcasts mean the microphone may be wanted now (Siri, a CarPlay call, the car's
 * voice recognition), so ours is stopped at once. Plain Java, tested off the car (tests/).
 *
 * DiPO sends its Siri widget update when CarPlay's speech mode turns on and off
 * (DipoService onChangeSpeechMode), but it also sends it again, CarPlay connected or not, for
 * the home screen's widgets: when the launcher changes pages (PAGE_CHANGED, current_page) and
 * when a widget asks for an update (com.hkmc.request.widgetupdate[.carplay]); its CarPlay call
 * widget update the same way. Those echoes would keep the microphone paused while the home
 * screen is up, so a widget update within 1.5 s of such a request is taken for an echo.
 */
final class MicSignals {
    static final String SIRI = "com.hkmc.siri.widgetupdate", CALL = "com.hkmc.carplay.call.widgetupdate";
    static final String VR_BEGIN = "com.mobis.intent.action.VR_ACTIVITY_BEGIN";
    static final String[] KEYS = {
        "com.hkmc.intent.action.VR_PTT_SHORTCUT", "com.hkmc.intent.action.VR_PTT_SHORTCUT_LONG",
        "com.hkmc.intent.action.CARPLAY_CALL_SHORTCUT", "com.hkmc.intent.action.CARPLAY_CALL_SHORTCUT_LONG",
    };
    /** what makes DiPO send its widget updates again (heard only to tell the echoes apart) */
    static final String[] REFRESH = {
        "com.android.launcher3.PAGE_CHANGED", "com.android.launcher3.current_page",
        "com.hkmc.request.widgetupdate", "com.hkmc.request.widgetupdate.carplay",
    };
    static final long ECHO_MS = 1500;

    private long refreshAt = Long.MIN_VALUE / 2;

    /** every action to listen for */
    static String[] all() {
        String[] a = new String[3 + KEYS.length + REFRESH.length];
        a[0] = SIRI;
        a[1] = CALL;
        a[2] = VR_BEGIN;
        System.arraycopy(KEYS, 0, a, 3, KEYS.length);
        System.arraycopy(REFRESH, 0, a, 3 + KEYS.length, REFRESH.length);
        return a;
    }

    /** true when this broadcast means the microphone may be wanted now */
    boolean wanted(String action, long now) {
        if (action == null) return false;
        for (String r : REFRESH) {
            if (r.equals(action)) {
                refreshAt = now;
                return false;
            }
        }
        if (SIRI.equals(action) || CALL.equals(action)) return now - refreshAt >= ECHO_MS;
        if (VR_BEGIN.equals(action)) return true;
        for (String k : KEYS) if (k.equals(action)) return true;
        return false;
    }

    /** a short name for the screen */
    static String label(String action) {
        if (SIRI.equals(action)) return "Siri";
        if (CALL.equals(action)) return "카플레이 통화";
        if (VR_BEGIN.equals(action)) return "차 음성인식";
        if (action != null && action.contains("VR_PTT")) return "음성 버튼";
        if (action != null && action.contains("CARPLAY_CALL")) return "통화 버튼";
        return "마이크 요청";
    }
}

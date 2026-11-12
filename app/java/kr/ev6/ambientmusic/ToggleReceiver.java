package kr.ev6.ambientmusic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import hmgapi.client.HmgApi;

/**
 * The star key's "Music ambient light on/off" (mod.json provides.star_key), and coming back on
 * after the head unit restarts when the owner left it on (BOOT_COMPLETED; HMG-API's state
 * broadcast too, which it sends once it's up, in case the boot broadcast came too early).
 */
public final class ToggleReceiver extends BroadcastReceiver {
    static final String ACTION_TOGGLE = "kr.ev6.ambientmusic.TOGGLE";
    static final String ACTION_HMGAPI_STATE = "hmgapi.intent.action.STATE_CHANGED";

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || ACTION_HMGAPI_STATE.equals(action)) {
            if (Settings.load(c).enabled && !LightService.running) LightService.start(c, true);
        } else if (ACTION_TOGGLE.equals(action)) {
            boolean on = !LightService.running;
            LightService.turn(c, on);
            HmgApi.toast(c, on ? "뮤직 무드램프 켜짐" : "뮤직 무드램프 꺼짐");
        }
    }
}

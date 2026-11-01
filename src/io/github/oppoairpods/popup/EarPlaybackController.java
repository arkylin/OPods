package io.github.oppoairpods.popup;

import android.content.Context;
import android.media.AudioManager;
import android.view.KeyEvent;

/** Sends media keys from the already-running official process; best effort only. */
final class EarPlaybackController {
    private EarPlaybackController() {}

    static void onWearChanged(AirPodsController controller, int previous, int current) {
        if (!controller.earPlayback() || previous == current) return;
        if (current == 0 && previous > 0) {
            controller.setPausedByEar(dispatch(controller.context(), KeyEvent.KEYCODE_MEDIA_PAUSE));
        } else if (current > 0 && previous == 0 && controller.ownsPause()) {
            if (dispatch(controller.context(), KeyEvent.KEYCODE_MEDIA_PLAY)) controller.setPausedByEar(false);
        }
    }

    private static boolean dispatch(Context context, int code) {
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) return false;
            long now = android.os.SystemClock.uptimeMillis();
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
            Logx.i("media key dispatched: " + code);
            return true;
        } catch (Throwable error) {
            Logx.w("media key dispatch failed", error);
            return false;
        }
    }
}

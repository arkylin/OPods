package io.github.oppoairpods.popup;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.telecom.TelecomManager;
import android.view.KeyEvent;

import java.util.concurrent.Executor;

/**
 * Nod to answer / shake to decline while the phone rings (LibrePods "Head Gestures").
 * Ringing is detected through AudioManager mode changes inside the official process; no
 * receiver, service or extra process is created. The listener only exists while the feature is
 * enabled and an AACP session is up.
 */
final class CallGestureMonitor {
    interface Callback {
        void onRingingStarted();

        void onRingingStopped();
    }

    private final Context context;
    private final Callback callback;
    private AudioManager.OnModeChangedListener listener;
    private boolean ringing;

    CallGestureMonitor(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    synchronized void start(Executor executor) {
        if (listener != null) return;
        AudioManager audio = audio();
        if (audio == null) return;
        AudioManager.OnModeChangedListener next = mode -> onMode(mode);
        try {
            audio.addOnModeChangedListener(executor, next);
            listener = next;
            Logx.i("head gesture call monitor started");
            onMode(audio.getMode());
        } catch (Throwable error) {
            Logx.w("head gesture call monitor unavailable", error);
        }
    }

    synchronized void stop() {
        AudioManager audio = audio();
        if (listener != null && audio != null) {
            try {
                audio.removeOnModeChangedListener(listener);
            } catch (Throwable ignored) {
            }
            Logx.i("head gesture call monitor stopped");
        }
        listener = null;
        if (ringing) {
            ringing = false;
            callback.onRingingStopped();
        }
    }

    private void onMode(int mode) {
        boolean nowRinging = mode == AudioManager.MODE_RINGTONE;
        if (nowRinging == ringing) return;
        ringing = nowRinging;
        if (nowRinging) callback.onRingingStarted();
        else callback.onRingingStopped();
    }

    /** Answers through Telecom (MyDevices holds MODIFY_PHONE_STATE), else a headset hook press. */
    void answer() {
        try {
            TelecomManager telecom = context.getSystemService(TelecomManager.class);
            if (telecom != null) {
                telecom.acceptRingingCall();
                Logx.i("call answered by head gesture (Telecom)");
                return;
            }
        } catch (Throwable error) {
            Logx.w("Telecom answer unavailable; using headset hook", error);
        }
        headsetHook(false);
        Logx.i("call answered by head gesture (headset hook)");
    }

    /** Declines through Telecom, else a long headset hook press (Telecom rejects on long press). */
    void decline() {
        try {
            TelecomManager telecom = context.getSystemService(TelecomManager.class);
            if (telecom != null && telecom.endCall()) {
                Logx.i("call declined by head gesture (Telecom)");
                return;
            }
        } catch (Throwable error) {
            Logx.w("Telecom decline unavailable; using headset hook", error);
        }
        headsetHook(true);
        Logx.i("call declined by head gesture (headset hook)");
    }

    private void headsetHook(boolean longPress) {
        AudioManager audio = audio();
        if (audio == null) return;
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_HEADSETHOOK, 0));
        if (longPress) {
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now + 1000L, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_HEADSETHOOK, 1, 0, -1, 0, KeyEvent.FLAG_LONG_PRESS));
        }
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now + (longPress ? 1100L : 80L),
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0));
    }

    private AudioManager audio() {
        return (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }
}

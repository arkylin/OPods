package io.github.oppoairpods.popup;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Phone-side half of Conversation Awareness (LibrePods MediaController.startSpeaking /
 * stopSpeaking): when the AirPods report that the wearer started talking, lower media volume
 * (or pause), and restore it when they stop. The AirPods only send these events while their own
 * Conversation Awareness (AACP 0x28) is on.
 */
final class ConversationVolume {
    private static final int STEPS = 6;
    private static final long STEP_MS = 40L;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Integer initialVolume;
    private boolean pausedByUs;
    private int transition;

    ConversationVolume(Context context) {
        this.context = context;
    }

    /** status 1/2 start speaking; 6/8/9 stop speaking (LibrePods mapping). */
    static boolean isSpeakingStart(int status) {
        return status == 1 || status == 2;
    }

    static boolean isSpeakingStop(int status) {
        return status == 6 || status == 8 || status == 9;
    }

    /** Volume to use while talking, from the current level and the user's settings. */
    static int targetVolume(int current, int max, boolean relative, int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        if (relative) return current * clamped / 100;
        int cap = max * clamped / 100;
        return Math.min(current, cap);
    }

    /** Lowers media volume and/or pauses, like LibrePods (volume first, pause on top). */
    void startSpeaking(boolean lowerVolume, boolean pause, boolean relative, int percent) {
        main.post(() -> {
            AudioManager audio = audio();
            if (audio == null || initialVolume != null || pausedByUs) return;
            if (lowerVolume) {
                int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                int target = targetVolume(current, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                        relative, percent);
                initialVolume = current;
                animate(audio, current, target);
                Logx.i("conversation: media volume " + current + " -> " + target);
            }
            if (pause && audio.isMusicActive()) {
                dispatch(audio, KeyEvent.KEYCODE_MEDIA_PAUSE);
                pausedByUs = true;
                Logx.i("conversation: media paused");
            }
        });
    }

    void stopSpeaking() {
        main.post(() -> {
            AudioManager audio = audio();
            if (audio == null) return;
            if (pausedByUs) {
                pausedByUs = false;
                dispatch(audio, KeyEvent.KEYCODE_MEDIA_PLAY);
                Logx.i("conversation: media resumed");
            }
            if (initialVolume != null) {
                int restore = initialVolume;
                initialVolume = null;
                animate(audio, audio.getStreamVolume(AudioManager.STREAM_MUSIC), restore);
                Logx.i("conversation: media volume restored to " + restore);
            }
        });
    }

    private void animate(AudioManager audio, int from, int to) {
        int token = ++transition;
        for (int step = 1; step <= STEPS; step++) {
            int value = from + (to - from) * step / STEPS;
            main.postDelayed(() -> {
                if (token != transition) return;
                try {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
                } catch (Throwable error) {
                    Logx.w("conversation volume change failed", error);
                }
            }, STEP_MS * step);
        }
    }

    private static void dispatch(AudioManager audio, int code) {
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    private AudioManager audio() {
        return (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }
}

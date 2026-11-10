package io.github.oppoairpods.popup;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-wide state and actions shared by the popup, the official page and the AACP channel. */
final class AirPodsController implements AacpSession.Listener {
    interface Listener {
        void onAirPodsStateChanged();
    }

    private static volatile AirPodsController instance;

    static AirPodsController get(Context context) {
        AirPodsController local = instance;
        if (local == null) {
            synchronized (AirPodsController.class) {
                local = instance;
                if (local == null) {
                    local = new AirPodsController(context.getApplicationContext() == null ? context : context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final boolean aacpOwner;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<Integer, int[]> controls = Collections.synchronizedMap(new HashMap<>());
    private final Object sessionLock = new Object();
    private final ArrayDeque<int[]> pendingControls = new ArrayDeque<>();
    /** Bounded requeue budget for flushPending: a failed write must not spin on the main thread. */
    private int pendingRetryBudget;
    private volatile int[] pendingEqualizer;

    private static final int[] RESTORABLE_CONTROLS = {
            AacpProtocol.CONTROL_MICROPHONE,
            AacpProtocol.CONTROL_EAR_DETECTION,
            AacpProtocol.CONTROL_PRESS_SPEED,
            AacpProtocol.CONTROL_PRESS_HOLD,
            AacpProtocol.CONTROL_SINGLE_EAR_ANC,
            AacpProtocol.CONTROL_AUTO_ANSWER,
            AacpProtocol.CONTROL_TONE_VOLUME,
            AacpProtocol.CONTROL_AUTOMATIC_CONNECTION,
            AacpProtocol.CONTROL_VOLUME_SWIPE_DURATION,
            AacpProtocol.CONTROL_CALL_MANAGEMENT,
            AacpProtocol.CONTROL_VOLUME_SWIPE,
            AacpProtocol.CONTROL_ADAPTIVE_VOLUME,
            AacpProtocol.CONTROL_CONVERSATION,
            AacpProtocol.CONTROL_HEARING_AID,
            AacpProtocol.CONTROL_ADAPTIVE_STRENGTH,
            AacpProtocol.CONTROL_HPS_GAIN_SWIPE,
            AacpProtocol.CONTROL_HEARING_ASSIST,
            AacpProtocol.CONTROL_ALLOW_OFF,
            AacpProtocol.CONTROL_SLEEP_DETECTION,
            AacpProtocol.CONTROL_ALLOW_AUTO_CONNECT,
            AacpProtocol.CONTROL_PPE_TOGGLE,
            AacpProtocol.CONTROL_PPE_CAP_LEVEL,
            AacpProtocol.CONTROL_OPTIMIZED_CHARGING
    };

    private volatile String address = "";
    private volatile String modelName = "AirPods";
    private volatile String modelNumber = "";
    private volatile String firmware = "";
    private volatile int left = -1;
    private volatile int right = -1;
    private volatile int caseLevel = -1;
    private volatile int leftStatus = -1;
    private volatile int rightStatus = -1;
    private volatile int caseStatus = -1;
    private volatile int chargingMask;
    private volatile int wearPrimary = -1;
    private volatile int wearSecondary = -1;
    private volatile long lastPopupAt;
    private volatile long lastCaseAt;
    /** Last lid advert that got as far as parsing, matched or not: the ACL fallback uses it. */
    private volatile long lastAdvertAt;
    private volatile long lastConnectAt;
    // A listening mode the user just picked, kept on screen until the AirPods confirm it.
    private static final long MODE_CONFIRM_MS = 3000L;
    private static final long MODE_QUEUE_MS = 15000L;
    private final ListeningModeTracker modeTracker = new ListeningModeTracker(MODE_CONFIRM_MS);
    // Self-healing: what the user last asked for. If the pair answers with another mode, the
    // choice is re-sent a couple of times instead of silently reverting. "switches once, then
    // the selector does nothing" is exactly the case where one of those writes got lost.
    private int requestedMode;
    private long requestedAt;
    private int requestedReplays;
    private final CallGestureMonitor callGestures;
    private final ConversationVolume conversationVolume;
    private volatile boolean callGestureActive;
    private volatile boolean headTrackingForCall;
    private volatile boolean audioDisconnectedForWear;
    private Runnable pendingWearDisconnect;
    private volatile AacpSession session;
    private volatile boolean pausedByEar;
    private volatile boolean opened;
    private volatile boolean awaitingFirstEvent;
    private volatile boolean settingsPageOpen;
    private volatile boolean panelOpen;
    private volatile boolean popupVisible;
    private volatile int controlsRevision;
    private volatile String manufacturer;
    private volatile String serialNumber;
    private volatile String leftSerialNumber;
    private volatile String rightSerialNumber;
    private volatile String version1;
    private volatile String version2;
    private volatile String version3;
    private volatile String hardwareRevision;
    private volatile String updaterIdentifier;
    private volatile byte[] transparencyData = new byte[0];
    private volatile byte[] hearingAidData = new byte[0];
    private volatile boolean loudSoundReduction;
    private volatile boolean attConnected;
    private volatile AttSession attSession;
    private volatile String pendingRename;
    private volatile Boolean pendingHeadTracking;
    private volatile boolean headTrackingActive;
    private volatile byte[] lastHeadTracking = new byte[0];
    private volatile long lastHeadTrackingAt;
    private volatile int headTrackingPackets;
    private final HeadGestureTracker headGestureTracker = new HeadGestureTracker();
    private volatile int lastHeadGesture;
    private volatile long lastHeadGestureAt;

    private AirPodsController(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("oppo_airpods_enhanced", Context.MODE_PRIVATE);
        this.aacpOwner = isAacpOwnerProcess();
        this.conversationVolume = new ConversationVolume(context);
        this.callGestures = new CallGestureMonitor(context, new CallGestureMonitor.Callback() {
            @Override
            public void onRingingStarted() {
                startCallGestureTracking();
            }

            @Override
            public void onRingingStopped() {
                stopCallGestureTracking();
            }
        });
        Logx.setContext(context);
        Logx.i("controller process=" + processName() + " aacpOwner=" + aacpOwner);
    }

    private static String processName() {
        try {
            String name = Application.getProcessName();
            if (name != null && !name.isEmpty()) return name;
        } catch (Throwable ignored) {
        }
        try {
            Object name = Class.forName("android.app.ActivityThread")
                    .getMethod("currentProcessName").invoke(null);
            if (name instanceof String && !((String) name).isEmpty()) return (String) name;
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    private static boolean isAacpOwnerProcess() {
        return processName().endsWith(":cards");
    }

    Context context() {
        return context;
    }

    boolean earPlayback() {
        return prefs.getBoolean("ear_playback", true);
    }

    boolean batteryAlerts() {
        return prefs.getBoolean("battery_alerts", false);
    }

    void setEarPlayback(boolean value) {
        prefs.edit().putBoolean("ear_playback", value).apply();
    }

    // ---- lid open (official PTC) ----
    boolean autoConnect() {
        return CrossProcessState.autoConnect(context);
    }

    void setAutoConnect(boolean value) {
        CrossProcessState.setAutoConnect(context, value);
        notifyChanged();
    }

    boolean lidOpenFallback() {
        return CrossProcessState.lidOpenFallback(context);
    }

    void setLidOpenFallback(boolean value) {
        CrossProcessState.setLidOpenFallback(context, value);
        notifyChanged();
    }

    String proximityKeyStatus() {
        CrossProcessState.Keys keys = CrossProcessState.proximityKeys(context, address);
        if (keys == null || (keys.irk == null && keys.enc == null)) {
            return "未获取（AACP 连接后自动请求）";
        }
        return "已获取" + (keys.irk != null ? " IRK" : "") + (keys.enc != null ? " ENC" : "");
    }

    /** Asks the connected AirPods for their IRK/ENC again; the 0x31 reply replaces the stored ones. */
    boolean refetchProximityKeys() {
        AacpSession current = session;
        if (current == null || !current.ready()) return false;
        Logx.i("proximity keys requested again by the user");
        return current.write(AacpProtocol.keyRequest(AacpProtocol.KEY_IRK | AacpProtocol.KEY_ENC));
    }

    String lastAdvertSummary() {
        return stamped(CrossProcessState.lastAdvert(context), "尚未收到");
    }

    /** What the last lid / manual connect request did, step by step (panel diagnostics). */
    String lastConnectSummary() {
        return stamped(CrossProcessState.lastConnect(context), "尚未发生");
    }

    private static String stamped(String raw, String empty) {
        if (raw == null || raw.isEmpty()) return empty;
        int separator = raw.indexOf('|');
        try {
            long at = Long.parseLong(raw.substring(0, separator));
            return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
                    .format(new java.util.Date(at)) + " · " + raw.substring(separator + 1);
        } catch (Throwable ignored) {
            return raw;
        }
    }

    /** Whether the Accessory side (official resident scan) reported carrying the lid filter. */
    String accessoryLidStatus() {
        long at = CrossProcessState.accessoryReadyAt(context);
        if (at <= 0L) {
            return "未确认：accessory 还没加载新版模块（停止一次 com.heytap.accessory 后亮屏）";
        }
        return "已加入开盖过滤（" + new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
                .format(new java.util.Date(at)) + "）";
    }

    // ---- head gestures ----
    boolean headGesturesEnabled() {
        return prefs.getBoolean("head_gestures", true);
    }

    void setHeadGesturesEnabled(boolean value) {
        prefs.edit().putBoolean("head_gestures", value).apply();
        if (!value && headTrackingActive) setHeadTracking(false);
        syncCallGestureMonitor();
        notifyChanged();
    }

    boolean headGestureCalls() {
        return prefs.getBoolean("head_gesture_calls", false);
    }

    void setHeadGestureCalls(boolean value) {
        prefs.edit().putBoolean("head_gesture_calls", value).apply();
        syncCallGestureMonitor();
        notifyChanged();
    }

    boolean alternateHeadTracking() {
        return prefs.getBoolean("alternate_head_tracking", false);
    }

    void setAlternateHeadTracking(boolean value) {
        prefs.edit().putBoolean("alternate_head_tracking", value).apply();
        notifyChanged();
    }

    // ---- conversation awareness, phone side ----
    boolean conversationLowerVolume() {
        return prefs.getBoolean("ca_lower_volume", true);
    }

    void setConversationLowerVolume(boolean value) {
        prefs.edit().putBoolean("ca_lower_volume", value).apply();
        notifyChanged();
    }

    boolean conversationPauseMusic() {
        return prefs.getBoolean("ca_pause_music", false);
    }

    void setConversationPauseMusic(boolean value) {
        prefs.edit().putBoolean("ca_pause_music", value).apply();
        notifyChanged();
    }

    boolean conversationRelativeVolume() {
        return prefs.getBoolean("ca_relative_volume", true);
    }

    void setConversationRelativeVolume(boolean value) {
        prefs.edit().putBoolean("ca_relative_volume", value).apply();
        notifyChanged();
    }

    int conversationVolumePercent() {
        return prefs.getInt("ca_volume", 43);
    }

    void setConversationVolumePercent(int value) {
        prefs.edit().putInt("ca_volume", Math.max(0, Math.min(100, value))).apply();
    }

    // ---- wear ----
    boolean disconnectWhenNotWearing() {
        return prefs.getBoolean("disconnect_when_not_wearing", false);
    }

    void setDisconnectWhenNotWearing(boolean value) {
        prefs.edit().putBoolean("disconnect_when_not_wearing", value).apply();
        if (!value) cancelWearDisconnect();
        notifyChanged();
    }

    // ---- headphone accommodation EQ (AACP 0x53) ----
    float[] accommodationEq() {
        float[] result = new float[8];
        Arrays.fill(result, 0.5f);
        String value = prefs.getString(deviceKey("accommodation_eq"), null);
        if (value == null) return result;
        String[] parts = value.split(",");
        if (parts.length != 8) return result;
        try {
            for (int i = 0; i < 8; i++) result[i] = Math.max(0f, Math.min(1f, Float.parseFloat(parts[i])));
        } catch (NumberFormatException ignored) {
        }
        return result;
    }

    boolean accommodationPhone() {
        return prefs.getBoolean(deviceKey("accommodation_phone"), false);
    }

    boolean accommodationMedia() {
        return prefs.getBoolean(deviceKey("accommodation_media"), false);
    }

    /** Saves the values and writes them now; false when the control channel is not ready. */
    boolean sendHeadphoneAccommodation(float[] eq, boolean phone, boolean media) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < eq.length; i++) {
            if (i > 0) value.append(',');
            value.append(eq[i]);
        }
        prefs.edit().putString(deviceKey("accommodation_eq"), value.toString())
                .putBoolean(deviceKey("accommodation_phone"), phone)
                .putBoolean(deviceKey("accommodation_media"), media).apply();
        AacpSession current = session;
        if (current == null || !current.ready()) return false;
        return current.write(AacpProtocol.headphoneAccommodation(eq, phone, media));
    }

    // ---- raw controls ----
    /** Writes a control LibrePods defines but does not expose; not saved for reconnects. */
    boolean sendRawControl(int id, int value) {
        setControlState(id, value);
        return sendControl(id, value);
    }

    java.util.Map<Integer, int[]> controlsSnapshot() {
        synchronized (controls) {
            return new java.util.TreeMap<>(controls);
        }
    }

    void setBatteryAlerts(boolean value) {
        prefs.edit().putBoolean("battery_alerts", value).apply();
    }

    void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyChanged() {
        main.post(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.onAirPodsStateChanged();
                } catch (Throwable error) {
                    Logx.w("state listener failed", error);
                }
            }
        });
    }

    String address() {
        return address;
    }

    String modelName() {
        return modelName;
    }

    String modelNumber() {
        return modelNumber;
    }

    String firmware() {
        return firmware;
    }

    String manufacturer() {
        return manufacturer == null ? "Apple Inc." : manufacturer;
    }

    String serialNumber() {
        return serialNumber == null ? "-" : serialNumber;
    }

    String leftSerialNumber() {
        return leftSerialNumber == null ? "-" : leftSerialNumber;
    }

    String rightSerialNumber() {
        return rightSerialNumber == null ? "-" : rightSerialNumber;
    }

    String version1() {
        return version1 == null ? "-" : version1;
    }

    String version2() {
        return version2 == null ? "-" : version2;
    }

    String version3() {
        return version3 == null ? "-" : version3;
    }

    String hardwareRevision() {
        return hardwareRevision == null ? "-" : hardwareRevision;
    }

    String updaterIdentifier() {
        return updaterIdentifier == null ? "-" : updaterIdentifier;
    }

    byte[] transparencyData() {
        return transparencyData.clone();
    }

    byte[] hearingAidData() {
        return hearingAidData.clone();
    }

    boolean loudSoundReduction() {
        return loudSoundReduction;
    }

    boolean attConnected() {
        return attConnected;
    }

    /** Transparency values may only be written back over a template read from this connection. */
    boolean transparencyWritable() {
        return attConnected && AirPodsAttData.parseTransparency(transparencyData) != null;
    }

    boolean hearingAidWritable() {
        return attConnected && AirPodsAttData.parseHearingAid(hearingAidData) != null;
    }

    int controlsRevision() {
        return controlsRevision;
    }

    String equalizerPreset() {
        return prefs.getString(deviceKey("equalizer_preset"), "off");
    }

    void setEqualizerPreset(String preset) {
        prefs.edit().putString(deviceKey("equalizer_preset"), preset).apply();
    }

    AirPodsModels.Model model() {
        AirPodsModels.Model value = AirPodsModels.fromModelNumber(modelNumber);
        return value != null ? value : AirPodsModels.fromName(modelName);
    }

    void readAtt() {
        AttSession current = attSession;
        if (current != null) current.readAll();
    }

    // ATT writes go straight to a live bearer. There is no deferred queue, so a false return
    // means nothing was written and the local cache stays on the device values.
    boolean writeLoudSoundReduction(boolean enabled) {
        AttSession current = attSession;
        if (current == null || !current.writeLoudSoundReduction(enabled)) return false;
        loudSoundReduction = enabled;
        notifyChanged();
        return true;
    }

    boolean writeTransparency(AirPodsAttData.Settings value) {
        AttSession current = attSession;
        if (current == null || !transparencyWritable()) return false;
        byte[] data = AirPodsAttData.buildTransparency(value, transparencyData);
        if (!current.writeTransparency(data)) return false;
        transparencyData = data;
        notifyChanged();
        return true;
    }

    boolean writeHearingAid(AirPodsAttData.Settings value) {
        AttSession current = attSession;
        if (current == null || !hearingAidWritable()) return false;
        byte[] data = AirPodsAttData.buildHearingAid(value, hearingAidData);
        if (data == null || !current.writeHearingAid(data)) return false;
        hearingAidData = data;
        notifyChanged();
        return true;
    }

    void sendRename(String name) {
        if (name == null || name.trim().isEmpty()) return;
        String value = name.trim();
        AacpSession current = session;
        if (current != null && current.ready() && current.sendRename(value)) {
            pendingRename = null;
            modelName = value;
            notifyChanged();
            return;
        }
        pendingRename = value;
        ensureSession();
    }

    void setHeadTracking(boolean enabled) {
        headTrackingActive = enabled;
        AacpSession current = session;
        boolean alternate = prefs.getBoolean("alternate_head_tracking", false);
        if (current != null && current.ready() && current.sendHeadTracking(enabled, alternate)) {
            pendingHeadTracking = null;
        } else {
            pendingHeadTracking = enabled;
            ensureSession();
        }
        notifyChanged();
    }

    void disconnectDevice() {
        try {
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) return;
            BluetoothDevice device = adapter.getRemoteDevice(address);
            device.getClass().getMethod("disconnect").invoke(device);
        } catch (Throwable error) {
            Logx.w("disconnect failed", error);
        }
    }

    boolean headTrackingActive() {
        return headTrackingActive;
    }

    byte[] lastHeadTracking() {
        return lastHeadTracking.clone();
    }

    long lastHeadTrackingAt() {
        return lastHeadTrackingAt;
    }

    int headTrackingPackets() {
        return headTrackingPackets;
    }

    int lastHeadGesture() {
        return lastHeadGesture;
    }

    long lastHeadGestureAt() {
        return lastHeadGestureAt;
    }

    static final int ACTION_PLAY_PAUSE = 0;
    static final int ACTION_NEXT_TRACK = 1;
    static final int ACTION_PREVIOUS_TRACK = 2;
    static final int ACTION_ASSISTANT = 3;
    static final int ACTION_CYCLE_NOISE = 4;

    int stemAction(int bud, int pressType) {
        return prefs.getInt(stemKey(bud, pressType), defaultStemAction(pressType));
    }

    void setStemAction(int bud, int pressType, int action) {
        prefs.edit().putInt(stemKey(bud, pressType), action).apply();
        applyStemConfig();
        notifyChanged();
    }

    private static String stemKey(int bud, int pressType) {
        String side = bud == 1 ? "left" : "right";
        if (pressType == 5) return side + "_single_action";
        if (pressType == 6) return side + "_double_action";
        if (pressType == 7) return side + "_triple_action";
        return side + "_long_action";
    }

    private static int defaultStemAction(int pressType) {
        if (pressType == 5) return ACTION_PLAY_PAUSE;
        if (pressType == 6) return ACTION_NEXT_TRACK;
        if (pressType == 7) return ACTION_PREVIOUS_TRACK;
        return ACTION_CYCLE_NOISE;
    }

    private boolean stemTypeCustomized(int pressType) {
        int fallback = defaultStemAction(pressType);
        return stemAction(1, pressType) != fallback || stemAction(2, pressType) != fallback;
    }

    void applyStemConfig() {
        int mask = 0;
        for (int bud = 1; bud <= 2; bud++) {
            for (int press = 5; press <= 8; press++) {
                if (stemAction(bud, press) == defaultStemAction(press)) continue;
                if (press == 5) mask |= 0x01;
                else if (press == 6) mask |= 0x02;
                else if (press == 7) mask |= 0x04;
                else mask |= 0x08;
            }
        }
        sendControl(AacpProtocol.CONTROL_STEM_CONFIG, mask);
    }

    void executeStemAction(int bud, int pressType) {
        int action = stemAction(bud, pressType);
        if (action == ACTION_PLAY_PAUSE) dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        else if (action == ACTION_NEXT_TRACK) dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
        else if (action == ACTION_PREVIOUS_TRACK) dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        else if (action == ACTION_ASSISTANT) openAssistant();
        else if (action == ACTION_CYCLE_NOISE) cycleNoiseMode();
    }

    void cycleNoiseMode() {
        int allowed = controlValue(AacpProtocol.CONTROL_LISTENING_MODES, 0x0F);
        int current = controlValue(AacpProtocol.CONTROL_LISTENING_MODE, 1);
        int[] order = new int[]{1, 3, 4, 2};
        int next = current;
        for (int i = 0; i < order.length; i++) {
            if (order[i] != current) continue;
            for (int step = 1; step <= order.length; step++) {
                int candidate = order[(i + step) % order.length];
                int bit = candidate == 1 ? 0x01 : candidate == 2 ? 0x02
                        : candidate == 3 ? 0x04 : 0x08;
                if ((allowed & bit) != 0) {
                    next = candidate;
                    break;
                }
            }
            break;
        }
        selectListeningMode(next);
    }

    /**
     * The user picked a listening mode in any UI. Show it at once and send it; reports of the
     * previous mode that are still in flight (state dumps, the ~1 s switch latency) must not
     * flip the UI back.
     */
    void selectListeningMode(int mode) {
        if (mode < 1 || mode > 4) return;
        modeTracker.select(mode);
        setControlState(AacpProtocol.CONTROL_LISTENING_MODE, mode);
        sendControl(AacpProtocol.CONTROL_LISTENING_MODE, mode);
        requestedMode = mode;
        requestedAt = System.currentTimeMillis();
        requestedReplays = 0;
        // Never keep a choice that could not even be sent pinned on screen forever.
        main.postDelayed(() -> modeTracker.dropIfUnsent(mode), MODE_QUEUE_MS);
    }

    /** Mode the official UI should show; 0 when nothing better than the ROM's value is known. */
    int displayedListeningMode() {
        int pending = modeTracker.pending();
        if (pending != 0) return pending;
        AacpSession current = session;
        if (current == null || !current.ready()) return 0;
        return controlValue(AacpProtocol.CONTROL_LISTENING_MODE, 0);
    }

    private void noteListeningModeSent(int id, int[] values) {
        if (id != AacpProtocol.CONTROL_LISTENING_MODE || values == null || values.length == 0) return;
        if (modeTracker.sent(values[0], System.currentTimeMillis())) {
            main.postDelayed(this::expirePendingListeningMode, MODE_CONFIRM_MS + 50L);
        }
    }

    /** True for a listening-mode report that predates the user's latest choice. */
    private boolean ignoreStaleListeningMode(int[] values) {
        return values != null && values.length > 0
                && modeTracker.report(values[0], System.currentTimeMillis());
    }

    private void expirePendingListeningMode() {
        int kept = modeTracker.expire(System.currentTimeMillis());
        if (kept == 0) return;
        // The AirPods answered with another mode (for example not worn): show what they use.
        controls.put(AacpProtocol.CONTROL_LISTENING_MODE, new int[]{kept, 0, 0, 0});
        controlsRevision++;
        Logx.i("listening mode not confirmed; AirPods stay on " + kept);
        notifyChanged();
        replayRequestedMode(kept);
    }

    /** Re-sends the user's fresh choice when the pair kept something else. Bounded, not a loop. */
    private void replayRequestedMode(int kept) {
        if (requestedMode == 0 || kept == requestedMode) return;
        if (requestedReplays >= 2) return;
        if (System.currentTimeMillis() - requestedAt > 60_000L) return;
        requestedReplays++;
        Logx.i("listening mode " + requestedMode + " not applied (pair stays " + kept
                + "); resending " + requestedReplays + "/2");
        modeTracker.select(requestedMode);
        sendControl(AacpProtocol.CONTROL_LISTENING_MODE, requestedMode);
        main.postDelayed(() -> modeTracker.dropIfUnsent(requestedMode), MODE_QUEUE_MS);
    }

    private void resetListeningModeTracking() {
        modeTracker.reset();
    }

    private void dispatchMediaKey(int code) {
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) return;
            long now = android.os.SystemClock.uptimeMillis();
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
        } catch (Throwable error) {
            Logx.w("media key dispatch failed", error);
        }
    }

    private void openAssistant() {
        try {
            Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable error) {
            Logx.w("voice assistant launch failed", error);
        }
    }

    int left() {
        return left;
    }

    int right() {
        return right;
    }

    int caseLevel() {
        return caseLevel;
    }

    int chargingMask() {
        return chargingMask;
    }

    synchronized int[] officialBatteryArray() {
        boolean bothInEar = earsBothIn();
        return new int[]{
                left, right, caseLevel,
                officialBatteryStatus(bothInEar ? 2 : leftStatus, left),
                officialBatteryStatus(bothInEar ? 2 : rightStatus, right),
                officialBatteryStatus(caseStatus, caseLevel)
        };
    }

    synchronized boolean hasBatterySnapshot() {
        // Require a real battery level. Status alone is not enough: on reset/disconnect the
        // status fields are set to 4 (the disconnected sentinel), which is >= 0 and would
        // otherwise make us publish an empty [-1,-1,-1,4,4,4] snapshot over a live tile the
        // moment the AACP channel opens (before any battery packet arrives).
        return left >= 0 || right >= 0 || caseLevel >= 0;
    }

    BluetoothDevice currentDevice() {
        if (address == null || address.isEmpty()) return null;
        try {
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            return adapter == null ? null : adapter.getRemoteDevice(address);
        } catch (Throwable ignored) {
            return null;
        }
    }

    int wearPrimary() {
        return wearPrimary;
    }

    int wearSecondary() {
        return wearSecondary;
    }

    boolean opened() {
        return opened;
    }

    int[] control(int id) {
        int[] value = controls.get(id);
        return value == null ? null : value.clone();
    }

    boolean controlOn(int id) {
        int[] value = controls.get(id);
        return value != null && value.length > 0 && value[0] == 1;
    }

    int controlValue(int id, int fallback) {
        int[] value = controls.get(id);
        return value != null && value.length > 0 ? value[0] : fallback;
    }

    void setControlState(int id, int... values) {
        controls.put(id, values.clone());
        notifyChanged();
    }

    synchronized void attachDevice(BluetoothDevice device) {
        if (device == null) return;
        String next = device.getAddress();
        String alias = null;
        try {
            alias = device.getAlias();
        } catch (Throwable ignored) {
        }
        if (alias == null || alias.isEmpty()) {
            try {
                alias = device.getName();
            } catch (Throwable ignored) {
            }
        }
        boolean changed = !next.equalsIgnoreCase(address);
        if (changed) {
            stopSession();
            resetDeviceState();
        }
        address = next;
        migrateLegacyDeviceState();
        AirPodsModels.Model model = AirPodsModels.fromName(alias);
        if (model != null) modelName = model.name;
        ensureSession();
        notifyChanged();
    }

    /** Forgets everything learned from the previous pair so nothing is written to the wrong one. */
    private void resetDeviceState() {
        controls.clear();
        left = right = caseLevel = -1;
        leftStatus = rightStatus = caseStatus = -1;
        chargingMask = 0;
        modelName = "AirPods";
        modelNumber = "";
        firmware = "";
        manufacturer = serialNumber = leftSerialNumber = rightSerialNumber = null;
        version1 = version2 = version3 = null;
        hardwareRevision = updaterIdentifier = null;
        wearPrimary = wearSecondary = -1;
        opened = false;
        pausedByEar = false;
        transparencyData = new byte[0];
        hearingAidData = new byte[0];
        loudSoundReduction = false;
        resetListeningModeTracking();
        pendingRename = null;
        pendingHeadTracking = null;
        pendingEqualizer = null;
        synchronized (pendingControls) {
            pendingControls.clear();
        }
        controlsRevision++;
    }

    void onSettingsPageOpened(BluetoothDevice device) {
        settingsPageOpen = true;
        CrossProcessState.setSettingsOpen(context, true);
        attachDevice(device);
        syncAacpRetries();
        if (opened) ensureAttSession();
        notifyChanged();
    }

    void onSettingsPageClosed() {
        settingsPageOpen = false;
        CrossProcessState.setSettingsOpen(context, false);
        onOfficialUiClosed();
    }

    void onPanelOpened(BluetoothDevice device) {
        panelOpen = true;
        attachDevice(device);
        syncAacpRetries();
        if (opened) ensureAttSession();
        notifyChanged();
    }

    void onPanelClosed() {
        panelOpen = false;
        onOfficialUiClosed();
    }

    /** The official popup is on screen: keep the battery channel alive and allow link retries. */
    void onPopupVisible(BluetoothDevice device) {
        popupVisible = true;
        ensureBatterySession(device);
        syncAacpRetries();
    }

    void onPopupHidden() {
        popupVisible = false;
        syncAacpRetries();
    }

    /** Starts the battery channel when the official popup appears, without marking a page visible. */
    void ensureBatterySession(BluetoothDevice device) {
        if (!aacpOwner || device == null) return;
        if (address == null || !address.equalsIgnoreCase(device.getAddress())) {
            attachDevice(device);
        } else {
            ensureSession();
        }
    }

    // Single source of truth for AACP link retries: only while an official AirPods surface is
    // visible. Without one, a broken link stops the session until the next popup or page.
    private boolean wantsAacpRetries() {
        if (settingsPageOpen || panelOpen || popupVisible) return true;
        // A tapped control that is still waiting in the queue deserves a rebuilt channel: without
        // this, a link that died while no official surface was up silently swallowed the tap.
        synchronized (pendingControls) {
            return !pendingControls.isEmpty();
        }
    }

    private void syncAacpRetries() {
        AacpSession current = session;
        if (current != null) current.setAllowRetries(wantsAacpRetries());
    }

    private void onOfficialUiClosed() {
        syncAacpRetries();
        if (settingsPageOpen || panelOpen) return;
        stopAttSession();
        if (aacpOwner && session != null) {
            Logx.i("official AirPods UI closed; keeping AACP battery channel on official ACL");
        }
    }

    void onAclConnected(BluetoothDevice device) {
        if (!isAirPods(device)) return;
        boolean hadAddress = !address.isEmpty() && address.equalsIgnoreCase(device.getAddress());
        attachDevice(device);
        Logx.i("AirPods link connected; controller address refreshed");
        long now = System.currentTimeMillis();
        // Second chance for a lid opening whose popup never made it: the pair brought its ACL up
        // on its own and an official popup is not on screen yet. When a popup is already visible
        // it is the official back-link popup and onBackLinkPopup() owns the connect 0.8 s later;
        // fighting it from here only produced "弹窗在、但一直不连".
        boolean recentLid = lastAdvertAt > 0L && now - lastAdvertAt <= 10000L;
        if (recentLid && !popupVisible && wantsOfficialConnect(device, CONNECT_IF_DOWN)) {
            lastAdvertAt = 0L;
            Logx.i("lid advert without a popup; using the ACL to run the official connect");
            launchOfficialPopup(device, CONNECT_IF_DOWN);
            return;
        }
        scheduleOfficialPopup(device, hadAddress ? 250L : 0L);
    }

    void onAclDisconnected(BluetoothDevice device) {
        if (device == null || address.isEmpty() || !address.equalsIgnoreCase(device.getAddress())) return;
        if (aacpOwner) {
            OfficialCoreBridge.publish(context, address, new int[]{-1, -1, -1, 4, 4, 4});
        }
        stopSession();
        clearLinkState();
        notifyChanged();
    }

    /** The earbuds are gone: drop link state and repaint the official surfaces as disconnected. */
    private void clearLinkState() {
        controls.clear();
        left = right = caseLevel = -1;
        leftStatus = rightStatus = caseStatus = 4;
        chargingMask = 0;
        wearPrimary = wearSecondary = -1;
        opened = false;
        pausedByEar = false;
        audioDisconnectedForWear = false;
        cancelWearDisconnect();
        conversationVolume.stopSpeaking();
        resetListeningModeTracking();
        controlsRevision++;
        OfficialBatteryBridge.clear();
        OfficialDeviceStateBridge.clear(this);
    }

    /** Result of matching a lid-open advert against the bonded pairs. */
    private static final class LidMatch {
        final BluetoothDevice device;
        final CrossProcessState.Keys keys;
        final String label;
        /** The advert's address resolved to this pair (stack or IRK), not the no-key fallback. */
        final boolean verified;

        LidMatch(BluetoothDevice device, CrossProcessState.Keys keys, String label) {
            this(device, keys, label, false);
        }

        LidMatch(BluetoothDevice device, CrossProcessState.Keys keys, String label, boolean verified) {
            this.device = device;
            this.keys = keys;
            this.label = label;
            this.verified = verified;
        }
    }

    /**
     * Lid-open advert from the official PTC scan. Only the pair whose IRK resolves the advert's
     * private address pops up; pairs that never handed out an IRK fall back to unverified matching
     * when the user allows it.
     */
    void onCaseAdvertisement(String bleAddress, AppleAdvertisement.LidInfo advert) {
        if (advert == null || !advert.lidOpen) return;
        lastAdvertAt = System.currentTimeMillis();
        if (!autoConnect()) {
            Logx.i("case-open ignored because auto-connect is disabled");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCaseAt < 4500L) return;
        LidMatch match = matchLidAdvert(bleAddress);
        AppleAdvertisement.LidInfo shown = advert;
        if (match.keys != null && match.keys.enc != null) {
            shown = advert.withDecrypted(ProximityCrypto.decryptPayload(advert.payload, match.keys.enc));
        }
        CrossProcessState.setLastAdvert(context, shown.model + " · " + shown.batteryText()
                + " · " + shown.connectionLabel() + " · " + match.label);
        if (match.device == null) {
            Logx.i("case-open ignored: " + match.label);
            return;
        }
        lastCaseAt = now;
        if (!"AirPods".equals(advert.model)) modelName = advert.model;
        Logx.i("Case-open advertisement for " + shown.model + " (" + match.label + ")");
        if (match.verified) seedAdvertBattery(match.device, shown);
        BluetoothDevice target = match.device;
        main.post(() -> {
            try {
                launchOfficialPopup(target, CONNECT_IF_DOWN);
            } catch (Throwable error) {
                Logx.w("official popup scheduling failed", error);
            }
        });
    }

    /**
     * Like the ROM's own earbuds, the popup shows the battery the moment the lid opens: the
     * verified advert's levels (exact once the ENC key is known) stand in until AACP reports.
     */
    private void seedAdvertBattery(BluetoothDevice device, AppleAdvertisement.LidInfo info) {
        if (!aacpOwner || device == null || info == null) return;
        // Same pair the popup will attach, so its onResume keeps these values.
        attachDevice(device);
        synchronized (this) {
            AacpSession current = session;
            if (hasBatterySnapshot() && current != null && current.ready()) return;
            boolean any = false;
            if (info.leftBattery >= 0) {
                left = info.leftBattery;
                leftStatus = info.leftCharging ? 1 : 2;
                any = true;
            }
            if (info.rightBattery >= 0) {
                right = info.rightBattery;
                rightStatus = info.rightCharging ? 1 : 2;
                any = true;
            }
            if (info.caseBattery >= 0) {
                caseLevel = info.caseBattery;
                caseStatus = info.caseCharging ? 1 : 2;
                any = true;
            }
            if (!any) return;
            recomputeChargingMask();
        }
        Logx.i("lid advert battery -> official popup " + info.batteryText());
        OfficialBatteryBridge.schedule(this);
        notifyChanged();
    }

    private LidMatch matchLidAdvert(String bleAddress) {
        java.util.List<BluetoothDevice> bonded = bondedAirPods();
        if (bonded.isEmpty()) return new LidMatch(null, null, "没有已配对的 AirPods");
        java.util.Map<String, CrossProcessState.Keys> stored = CrossProcessState.allProximityKeys(context);
        java.util.List<BluetoothDevice> withoutIrk = new java.util.ArrayList<>();
        for (BluetoothDevice device : bonded) {
            CrossProcessState.Keys keys = stored.get(device.getAddress().toUpperCase(Locale.ROOT));
            // The stack reports the identity address when it can resolve the RPA itself.
            if (bleAddress != null && device.getAddress().equalsIgnoreCase(bleAddress)) {
                return new LidMatch(device, keys, "已验证（系统解析地址）", true);
            }
            if (keys == null || keys.irk == null) {
                withoutIrk.add(device);
            } else if (bleAddress != null && ProximityCrypto.verifyRpa(bleAddress, keys.irk)) {
                return new LidMatch(device, keys, "已验证（IRK）", true);
            }
        }
        if (withoutIrk.isEmpty()) return new LidMatch(null, null, "不是本机的 AirPods（IRK 不匹配）");
        if (!lidOpenFallback()) return new LidMatch(null, null, "未获取识别密钥，且未允许未验证开盖");
        BluetoothDevice choice = withoutIrk.get(0);
        for (BluetoothDevice device : withoutIrk) {
            if (isDeviceConnected(device) || device.getAddress().equalsIgnoreCase(address)) {
                choice = device;
                break;
            }
        }
        return new LidMatch(choice, null, "未验证（这副耳机还没有识别密钥）");
    }

    private java.util.List<BluetoothDevice> bondedAirPods() {
        java.util.List<BluetoothDevice> result = new java.util.ArrayList<>();
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) return result;
        try {
            Set<BluetoothDevice> devices = adapter.getBondedDevices();
            if (devices != null) {
                for (BluetoothDevice device : devices) if (isAirPods(device)) result.add(device);
            }
        } catch (Throwable error) {
            Logx.w("bonded device scan failed", error);
        }
        return result;
    }

    /** User-initiated ("通过官方弹窗连接"): shown even over the settings page; connects if audio is down. */
    void launchOfficialPopupNow(BluetoothDevice device) {
        long now = System.currentTimeMillis();
        lastPopupAt = now;
        boolean connect = wantsOfficialConnect(device, CONNECT_IF_DOWN);
        if (connect) lastConnectAt = now;
        startOfficialPopup(device, connect, "手动");
    }

    private void scheduleOfficialPopup(BluetoothDevice device, long delayMs) {
        main.postDelayed(() -> {
            try {
                launchOfficialPopup(device, CONNECT_NEVER);
            } catch (Throwable error) {
                Logx.w("official popup scheduling failed", error);
            }
        }, delayMs);
    }

    void launchOfficialPopup(BluetoothDevice device) {
        launchOfficialPopup(device, CONNECT_NEVER);
    }

    /** Status only: the official back-link popup. */
    private static final int CONNECT_NEVER = 0;
    /** Case opened / user asked: run the official connect unless the pair's audio is already up. */
    private static final int CONNECT_IF_DOWN = 1;
    /** Lid advert, button and re-wear may ask at once: one official connect per window. */
    private static final long CONNECT_REPEAT_MS = 3500L;

    private void launchOfficialPopup(BluetoothDevice device, int mode) {
        if (settingsPageOpen || CrossProcessState.settingsOpen(context)) {
            noteLidBlocked(mode, "开盖被拦：设置页打开");
            return;
        }
        boolean connect = wantsOfficialConnect(device, mode);
        long now = System.currentTimeMillis();
        if (connect) {
            // A status popup already on screen must not swallow a connect: on a lid opening the
            // AirPods often bring their ACL up before the lid advert gets here, and that link's
            // popup used to win. The singleInstance popup takes this one through onNewIntent.
            if (now - lastConnectAt < CONNECT_REPEAT_MS) {
                noteLidBlocked(mode, "开盖被拦：连接请求去重");
                return;
            }
            lastConnectAt = now;
        } else {
            // Already showing this pair (opened on the lid, then the link came up): keep it.
            if (popupVisible && device != null && device.getAddress().equalsIgnoreCase(address)) return;
            if (now - lastPopupAt < 3500L) {
                noteLidBlocked(mode, "开盖被拦：状态弹窗去重");
                return;
            }
        }
        lastPopupAt = now;
        startOfficialPopup(device, connect, mode == CONNECT_IF_DOWN ? "开盖" : null);
    }

    /** Lid path used to drop these silently; the panel's "最近开盖连接" now names the reason. */
    private void noteLidBlocked(int mode, String reason) {
        if (mode != CONNECT_IF_DOWN) return;
        CrossProcessState.setLastConnect(context, reason);
        Logx.i(reason);
    }

    /**
     * Whether a popup for this pair should run the official connect. The ACL is not the test:
     * on a lid opening AirPods bring only their ACL (and their own control channel) up to the
     * phone they used last and leave A2DP/HFP to the phone. 3.4.51 took that ACL for
     * "connected" and showed the status-only popup, so the pair never got its audio.
     */
    private boolean wantsOfficialConnect(BluetoothDevice device, int mode) {
        return device != null && mode == CONNECT_IF_DOWN && !isAudioConnected(device);
    }

    /** Time the AirPods get to bring audio up by themselves before the popup connects it. */
    private static final long BACKLINK_CONNECT_DELAY_MS = 800L;

    /**
     * The official back-link popup is on screen: the ROM opens it when AirPods bring their ACL up
     * by themselves (lid opened next to the phone they used last). That popup only shows status,
     * and AirPods do not connect A2DP/HFP to this phone on their own (11-04 dump: remote-initiated
     * ACL, then the phone's own CONNECT), so the pair stayed without audio. When its audio is
     * still down shortly after, the same popup is switched to its connect mode, like a tap on
     * its "连接" button. One request per back-link popup; nothing runs once it is closed.
     */
    void onBackLinkPopup(BluetoothDevice device) {
        if (device == null || !autoConnect() || !isDeviceConnected(device)) return;
        main.postDelayed(() -> {
            try {
                if (!popupVisible || !isDeviceConnected(device) || isAudioConnected(device)) return;
                long now = System.currentTimeMillis();
                if (now - lastConnectAt < CONNECT_REPEAT_MS) return;
                lastConnectAt = now;
                lastPopupAt = now;
                startOfficialPopup(device, true, "回连");
            } catch (Throwable error) {
                Logx.w("back-link connect failed", error);
            }
        }, BACKLINK_CONNECT_DELAY_MS);
    }

    /**
     * Media or call audio of this pair is routed (A2DP / HFP output known to the audio service).
     * A pair that is connected but not the active one reads as down; the official connect then
     * finds its profiles already up and just shows it connected.
     */
    private boolean isAudioConnected(BluetoothDevice device) {
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) return false;
            for (AudioDeviceInfo info : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int type = info.getType();
                if (type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        && type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) continue;
                if (sameAddress(device.getAddress(), info.getAddress())) return true;
            }
        } catch (Throwable error) {
            Logx.w("audio route check failed", error);
        }
        return false;
    }

    /** Equal Bluetooth addresses; also accepts the anonymized "XX:XX:XX:XX:ab:cd" form. */
    private static boolean sameAddress(String expected, String reported) {
        if (expected == null || reported == null || expected.length() != reported.length()) return false;
        if (reported.equalsIgnoreCase(expected)) return true;
        return expected.length() > 12
                && reported.regionMatches(true, 0, "XX:XX:XX:XX:", 0, 12)
                && reported.regionMatches(true, 12, expected, 12, expected.length() - 12);
    }

    /** Extra marking a popup that should run the official connect once it is on screen. */
    static final String EXTRA_LID_CONNECT = "opods_lid_connect";
    /** The pair such a popup belongs to (the official extras carry it only as DeviceInfo). */
    static final String EXTRA_LID_DEVICE = "opods_lid_device";
    /** Official popup extra (FeiNiuIpcContract.KEY_DEVICE_INFO). */
    private static final String OFFICIAL_DEVICE_INFO = "device_info";
    /** DeviceInfo.broadcastType of a pair the popup may connect (AirpodsScanHelper type 2). */
    private static final int OFFICIAL_BROADCAST_PAIR = 2;

    /**
     * @param source "开盖" / "手动" / "戴回" / "回连": recorded for the panel's "最近开盖连接" line;
     *               null for the status popup of an ACL that came up.
     */
    private void startOfficialPopup(BluetoothDevice device, boolean connectWanted, String source) {
        boolean acl = device != null && isDeviceConnected(device);
        try {
            Intent intent = new Intent("com.oplus.mydevices.AIRPODS_DISCOVERY");
            intent.setClassName("com.heytap.mydevices",
                    "com.heytap.mydevices.plugin.airpods.ui.AirpodsDiscoveryActivity");
            // The official back-link popup only reports a link that is already up. A pair without
            // audio gets the official discovery popup instead, which connects through
            // AirpodsDiscoveryViewModel.startConnect() (setDevice + startBound -> connect()) and
            // shows connecting / connected / retry itself.
            boolean connect = connectWanted && device != null && putOfficialPairInfo(intent, device);
            if (connect) {
                intent.putExtra(EXTRA_LID_CONNECT, true);
                intent.putExtra(EXTRA_LID_DEVICE, device);
            } else {
                intent.putExtra("bluetooth_device", device);
                intent.putExtra("device_model", modelName);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
            if (source != null) {
                CrossProcessState.setLastConnect(context, source + "（ACL " + (acl ? "已连" : "未连") + "）"
                        + (connect ? "音频未连，请求官方连接"
                        : connectWanted ? "官方配对信息不可用，只显示状态" : "音频已连接，只显示状态"));
            }
            Logx.i((connect ? "official AirPods popup requested (official connect"
                    : "official AirPods popup requested (status") + ", acl=" + acl + ")");
        } catch (Throwable error) {
            Logx.w("official popup failed", error);
            if (source != null) CrossProcessState.setLastConnect(context, source + "：弹窗启动失败 " + error);
        }
    }

    /** Builds the official popup's own DeviceInfo for a bonded pair; false when it cannot. */
    private boolean putOfficialPairInfo(Intent intent, BluetoothDevice device) {
        try {
            Class<?> type = Class.forName("com.oplus.mydevices.domain.entities.airpods.DeviceInfo",
                    true, context.getClassLoader());
            java.lang.reflect.Constructor<?> constructor = type.getConstructor(String.class, String.class,
                    String.class, int.class, int.class, String.class, long.class, boolean.class);
            String model = modelName == null || modelName.isEmpty() ? "AirPods" : modelName;
            String name = null;
            try {
                name = device.getAlias();
            } catch (Throwable ignored) {
            }
            if (name == null || name.isEmpty()) name = model;
            Object info = constructor.newInstance(device.getAddress(), model, name,
                    OFFICIAL_BROADCAST_PAIR, 0, "0", System.currentTimeMillis(), true);
            intent.putExtra(OFFICIAL_DEVICE_INFO, (android.os.Parcelable) info);
            return true;
        } catch (Throwable error) {
            Logx.w("official popup DeviceInfo unavailable; showing back-link popup", error);
            return false;
        }
    }

    /**
     * Fallback when the official UI does not say which pair it shows: prefer the bonded AirPods
     * that hold an ACL link, then the pair already attached, then the first bonded one.
     */
    BluetoothDevice findActiveAirPods() {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) return null;
        try {
            Set<BluetoothDevice> devices = adapter.getBondedDevices();
            if (devices == null) return null;
            BluetoothDevice attached = null;
            BluetoothDevice first = null;
            for (BluetoothDevice device : devices) {
                if (!isAirPods(device)) continue;
                if (isDeviceConnected(device)) return device;
                if (first == null) first = device;
                if (device.getAddress().equalsIgnoreCase(address)) attached = device;
            }
            return attached != null ? attached : first;
        } catch (Throwable error) {
            Logx.w("bonded device scan failed", error);
        }
        return null;
    }

    boolean isAirPods(BluetoothDevice device) {
        if (device == null) return false;
        String alias = null;
        try {
            alias = device.getAlias();
        } catch (Throwable ignored) {
        }
        if (alias == null || alias.isEmpty()) {
            try {
                alias = device.getName();
            } catch (Throwable ignored) {
            }
        }
        if (alias != null && alias.toLowerCase(Locale.ROOT).contains("airpods")) return true;
        try {
            if (device.getUuids() != null) {
                for (android.os.ParcelUuid uuid : device.getUuids()) {
                    if ("74ec2172-0bad-4d01-8f77-997b2be0722a".equalsIgnoreCase(uuid.toString())) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    void ensureSession() {
        if (!aacpOwner || address == null || address.isEmpty()) return;
        synchronized (sessionLock) {
            AacpSession current = session;
            if (current == null || !current.isRunning()) {
                AacpSession next = new AacpSession(context, address, this);
                next.setAllowRetries(wantsAacpRetries());
                next.setRequestProximityKeys(needsProximityKeys());
                session = next;
                Logx.i("AACP owner starting session #" + next.sessionId());
                next.start();
            }
        }
    }

    void stopSession() {
        synchronized (sessionLock) {
            AacpSession current = session;
            session = null;
            if (current != null) current.stop();
        }
        opened = false;
        awaitingFirstEvent = false;
        stopAttSession();
        callGestures.stop();
    }

    private boolean needsProximityKeys() {
        CrossProcessState.Keys keys = CrossProcessState.proximityKeys(context, address);
        return keys == null || keys.irk == null || keys.enc == null;
    }

    // ---- head gestures during calls (LibrePods handleIncomingCall) ----
    private void syncCallGestureMonitor() {
        boolean wanted = aacpOwner && opened && session != null
                && headGesturesEnabled() && headGestureCalls();
        if (wanted) callGestures.start(main::post);
        else callGestures.stop();
    }

    private void startCallGestureTracking() {
        if (!headGesturesEnabled() || !headGestureCalls()) return;
        AacpSession current = session;
        if (current == null || !current.ready()) return;
        headGestureTracker.reset();
        callGestureActive = true;
        if (!headTrackingActive && current.sendHeadTracking(true, alternateHeadTracking())) {
            headTrackingActive = true;
            headTrackingForCall = true;
        }
        Logx.i("incoming call: listening for nod/shake");
    }

    private void stopCallGestureTracking() {
        callGestureActive = false;
        if (!headTrackingForCall) return;
        headTrackingForCall = false;
        headTrackingActive = false;
        AacpSession current = session;
        // Only stop over a live channel; never start a session from teardown paths.
        if (current != null && current.ready()) current.sendHeadTracking(false, alternateHeadTracking());
    }

    // ---- disconnect audio when not worn (LibrePods disconnect_when_not_wearing) ----
    private void handleWearForAudio(int previousCount, int currentCount) {
        if (!disconnectWhenNotWearing()) return;
        boolean bothInCase = wearPrimary == 2 && wearSecondary == 2;
        if (currentCount == 0 && previousCount > 0 && !bothInCase) {
            cancelWearDisconnect();
            Runnable task = () -> {
                pendingWearDisconnect = null;
                BluetoothDevice device = currentDevice();
                if (inEarCount() != 0 || device == null) return;
                BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                AudioProfiles.disconnectAudio(context, manager == null ? null : manager.getAdapter(), device);
                audioDisconnectedForWear = true;
            };
            pendingWearDisconnect = task;
            main.postDelayed(task, 3000L);
        } else if (currentCount > 0) {
            cancelWearDisconnect();
            if (audioDisconnectedForWear) {
                audioDisconnectedForWear = false;
                BluetoothDevice device = currentDevice();
                // Audio comes back through the official popup's own connect, not our own path:
                // the ACL is still up, so only connect() brings A2DP/HFP back.
                if (device != null) {
                    main.post(() -> {
                        long now = System.currentTimeMillis();
                        lastPopupAt = now;
                        lastConnectAt = now;
                        startOfficialPopup(device, true, "戴回");
                    });
                }
            }
        }
    }

    private void cancelWearDisconnect() {
        Runnable pending = pendingWearDisconnect;
        pendingWearDisconnect = null;
        if (pending != null) main.removeCallbacks(pending);
    }

    private void ensureAttSession() {
        if (address == null || address.isEmpty()) return;
        AirPodsModels.Model model = model();
        if (model != null && !model.has(AirPodsModels.CAP_HEARING_AID)
                && !model.has(AirPodsModels.CAP_LOUD_SOUND)) return;
        synchronized (sessionLock) {
            AttSession current = attSession;
            // A running session reconnects by itself; replacing it would abort a pending connect.
            if (current != null && current.isRunning()) return;
            AttSession next = new AttSession(context, address, new AttSession.Listener() {
                @Override
                public void onAttConnected() {
                    attConnected = true;
                    // Fresh values are read right after connect; never write over stale ones.
                    transparencyData = new byte[0];
                    hearingAidData = new byte[0];
                    notifyChanged();
                }

                @Override
                public void onAttDisconnected() {
                    attConnected = false;
                    notifyChanged();
                }

                // Values read from the device redraw open pages; echoes of our own writes match
                // the cache already and therefore do not.
                @Override
                public void onTransparency(byte[] data) {
                    if (!Arrays.equals(transparencyData, data)) {
                        transparencyData = data.clone();
                        controlsRevision++;
                    }
                    notifyChanged();
                }

                @Override
                public void onHearingAid(byte[] data) {
                    if (!Arrays.equals(hearingAidData, data)) {
                        hearingAidData = data.clone();
                        controlsRevision++;
                    }
                    notifyChanged();
                }

                @Override
                public void onLoudSoundReduction(byte[] data) {
                    boolean enabled = data.length > 0 && data[0] == 1;
                    if (enabled != loudSoundReduction) {
                        loudSoundReduction = enabled;
                        controlsRevision++;
                    }
                    notifyChanged();
                }
            });
            attSession = next;
            next.start();
        }
    }

    private void stopAttSession() {
        synchronized (sessionLock) {
            AttSession current = attSession;
            attSession = null;
            attConnected = false;
            if (current != null) current.stop();
        }
    }

    boolean sendControl(int id, int... values) {
        if (isRestorableControl(id)) persistControl(id, values);
        AacpSession current = session;
        boolean sent = current != null && current.canWrite() && current.sendControl(id, values);
        if (!sent) {
            ensureSession();
            current = session;
            sent = current != null && current.canWrite() && current.sendControl(id, values);
        }
        if (sent) {
            Logx.i("AACP control -> id=0x" + Integer.toHexString(id) + " values="
                    + Arrays.toString(values) + " via #" + current.sessionId());
            noteListeningModeSent(id, values);
            return true;
        }
        if (!aacpOwner) {
            Logx.w("AACP control skipped outside :cards id=0x" + Integer.toHexString(id), null);
            return false;
        }
        synchronized (pendingControls) {
            // One entry per control id: tapping noise on/off during the handshake must not leave a
            // backlog that the earbuds replay in a burst once the channel is up.
            java.util.Iterator<int[]> existing = pendingControls.iterator();
            while (existing.hasNext()) {
                if (existing.next()[0] == id) existing.remove();
            }
            if (pendingControls.size() >= 32) pendingControls.removeFirst();
            pendingControls.addLast(valuesWithId(id, values));
        }
        Logx.i("AACP control queued until AACP opens: 0x" + Integer.toHexString(id));
        return true;
    }

    private static int[] valuesWithId(int id, int[] values) {
        int[] result = new int[values.length + 1];
        result[0] = id;
        System.arraycopy(values, 0, result, 1, values.length);
        return result;
    }

    boolean sendEqualizer(int enabled, int low, int mid, int high) {
        persistEqualizer(enabled, low, mid, high);
        setControlState(AacpProtocol.OP_EQUALIZER, enabled, low, mid, high);
        AacpSession current = session;
        if (current != null && current.ready()
                && current.sendEqualizer(enabled, low, mid, high)) return true;
        ensureSession();
        current = session;
        if (current != null && current.ready()
                && current.sendEqualizer(enabled, low, mid, high)) return true;
        if (!aacpOwner) return false;
        pendingEqualizer = new int[]{enabled, low, mid, high};
        return true;
    }

    @Override
    public void onOpened() {
        opened = true;
        awaitingFirstEvent = true;
        AacpSession current = session;
        Logx.i("AACP ready session=" + (current == null ? -1 : current.sessionId()));
        publishBatterySnapshot();
        if (settingsPageOpen || panelOpen) ensureAttSession();
        main.postDelayed(this::flushPending, 250L);
        main.postDelayed(this::applyStemConfig, 500L);
        main.postDelayed(this::applySavedControls, 1200L);
        main.post(this::syncCallGestureMonitor);
        notifyChanged();
    }

    private static boolean isRestorableControl(int id) {
        for (int candidate : RESTORABLE_CONTROLS) {
            if (candidate == id) return true;
        }
        return false;
    }

    private void persistControl(int id, int[] values) {
        if (values == null || values.length == 0 || address.isEmpty()) return;
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) value.append(',');
            value.append(values[i]);
        }
        prefs.edit().putString(controlKey(id), value.toString()).apply();
    }

    private void persistEqualizer(int enabled, int low, int mid, int high) {
        if (address.isEmpty()) return;
        prefs.edit().putString(deviceKey("device_equalizer"),
                enabled + "," + low + "," + mid + "," + high).apply();
    }

    /** Saved device settings are kept per pair; another pair must never receive them. */
    private String deviceKey(String name) {
        return name + "_" + address.replace(":", "");
    }

    private String controlKey(int id) {
        return deviceKey("device_control_" + Integer.toHexString(id));
    }

    /** Settings saved before 3.4.48 were global; they move to the first pair attached since. */
    private void migrateLegacyDeviceState() {
        if (address.isEmpty() || prefs.getBoolean("device_state_migrated", false)) return;
        SharedPreferences.Editor editor = prefs.edit();
        for (int id : RESTORABLE_CONTROLS) {
            String legacyKey = "device_control_" + Integer.toHexString(id);
            String value = prefs.getString(legacyKey, null);
            if (value != null) editor.putString(controlKey(id), value).remove(legacyKey);
        }
        for (String name : new String[]{"device_equalizer", "equalizer_preset"}) {
            String value = prefs.getString(name, null);
            if (value != null) editor.putString(deviceKey(name), value).remove(name);
        }
        editor.putBoolean("device_state_migrated", true).apply();
        Logx.i("legacy device settings moved to the attached AirPods");
    }

    private int[] savedControl(int id) {
        String value = prefs.getString(controlKey(id), null);
        if (value == null || value.isEmpty()) return null;
        String[] parts = value.split(",");
        int[] result = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) result[i] = Integer.parseInt(parts[i]);
            return result;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int[] savedEqualizer() {
        String value = prefs.getString(deviceKey("device_equalizer"), null);
        if (value == null || value.isEmpty()) return null;
        String[] parts = value.split(",");
        if (parts.length != 4) return null;
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3])};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void applySavedControls() {
        if (!opened || !aacpOwner) return;
        AacpSession current = session;
        if (current == null || !current.ready()) return;
        for (int id : RESTORABLE_CONTROLS) {
            int[] desired = savedControl(id);
            if (desired == null || AacpProtocol.sameControlValues(controls.get(id), desired)) continue;
            setControlState(id, desired);
            current.sendControl(id, desired);
            Logx.i("AACP restored user control id=0x" + Integer.toHexString(id)
                    + " values=" + Arrays.toString(desired));
        }
        int[] equalizer = savedEqualizer();
        int[] activeEqualizer = controls.get(AacpProtocol.OP_EQUALIZER);
        if (equalizer != null && !Arrays.equals(activeEqualizer, equalizer)) {
            current.sendEqualizer(equalizer[0], equalizer[1], equalizer[2], equalizer[3]);
            setControlState(AacpProtocol.OP_EQUALIZER, equalizer);
            Logx.i("AACP restored equalizer values=" + Arrays.toString(equalizer));
        }
    }

    private void flushPending() {
        AacpSession current = session;
        // canWrite(), not ready(): the queue may drain as soon as the handshake is out, long before
        // the earbuds send their first report.
        if (current == null || !current.canWrite()) return;
        boolean retry = false;
        synchronized (pendingControls) {
            int[] next;
            while ((next = pendingControls.pollFirst()) != null) {
                int[] values = Arrays.copyOfRange(next, 1, next.length);
                if (current.sendControl(next[0], values)) {
                    noteListeningModeSent(next[0], values);
                } else {
                    // Put it back instead of dropping it: the socket can be swapped under us.
                    pendingControls.addFirst(next);
                    retry = true;
                    break;
                }
            }
        }
        if (retry) {
            if (pendingRetryBudget < 6) {
                pendingRetryBudget++;
                main.postDelayed(this::flushPending, 500L);
            }
        } else {
            pendingRetryBudget = 0;
        }
        String rename = pendingRename;
        if (rename != null) {
            pendingRename = null;
            current.sendRename(rename);
        }
        Boolean head = pendingHeadTracking;
        if (head != null) {
            pendingHeadTracking = null;
            current.sendHeadTracking(head, prefs.getBoolean("alternate_head_tracking", false));
        }
        int[] eq = pendingEqualizer;
        if (eq != null) {
            pendingEqualizer = null;
            current.sendEqualizer(eq[0], eq[1], eq[2], eq[3]);
        }
    }

    @Override
    public void onClosed(boolean sessionStopping) {
        opened = false;
        awaitingFirstEvent = false;
        stopAttSession();
        // The AACP socket closing is NOT the same as the earbuds disconnecting. A silent
        // channel (no ACK within the watchdog window) with retries disabled also arrives here
        // with sessionStopping=true while the BR/EDR ACL is still up. Only wipe the battery
        // state and clear the official tile/popup bridges when the link is genuinely down;
        // otherwise a transient socket recycle would blank a correct charging readout.
        boolean linkDown = sessionStopping && !isAclConnected();
        if (linkDown) {
            Logx.i("AACP official ACL lost; stopping session");
            stopSession();
            clearLinkState();
            notifyChanged();
            return;
        }
        if (sessionStopping) {
            Logx.i("AACP session stopping but ACL still up; keeping battery snapshot");
            stopSession();
            notifyChanged();
            return;
        }
        AacpSession current = session;
        if (current != null && !current.hadData() && !isAclConnected()) {
            clearLinkState();
            Logx.i("AACP silent snapshot cleared");
        }
        notifyChanged();
    }

    private boolean isAclConnected() {
        BluetoothDevice device = currentDevice();
        return device != null && isDeviceConnected(device);
    }

    private static boolean isDeviceConnected(BluetoothDevice device) {
        try {
            Object value = BluetoothDevice.class.getMethod("isConnected").invoke(device);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void publishBatterySnapshot() {
        if (!aacpOwner || address == null || address.isEmpty() || !hasBatterySnapshot()) return;
        OfficialCoreBridge.publish(context, address, officialBatteryArray());
    }

    @Override
    public void onEvent(AacpProtocol.Event event) {
        if (event == null) return;
        if (awaitingFirstEvent) {
            awaitingFirstEvent = false;
            Logx.i("AACP first device event received");
            main.postDelayed(this::flushPending, 50L);
            main.postDelayed(this::applySavedControls, 350L);
        }
        switch (event.type) {
            case AacpProtocol.Event.TYPE_BATTERY:
                if (event.leftStatus >= 0) {
                    leftStatus = event.leftStatus;
                    left = event.left;
                }
                if (event.rightStatus >= 0) {
                    rightStatus = event.rightStatus;
                    right = event.right;
                }
                if (event.caseStatus >= 0) {
                    caseStatus = event.caseStatus;
                    caseLevel = event.caseLevel;
                }
                recomputeChargingMask();
                Logx.i("AACP battery L=" + left + "/" + leftStatus
                        + " R=" + right + "/" + rightStatus
                        + " C=" + caseLevel + "/" + caseStatus);
                BatteryNotifier.onBattery(this, left, right, caseLevel, chargingMask);
                OfficialBatteryBridge.schedule(this);
                OfficialDeviceStateBridge.schedule(this);
                publishBatterySnapshot();
                break;
            case AacpProtocol.Event.TYPE_CONTROL:
                if (event.controlId == AacpProtocol.CONTROL_LISTENING_MODE
                        && ignoreStaleListeningMode(event.values)) {
                    Logx.i("AACP control <- id=0xd values=" + Arrays.toString(event.values)
                            + " ignored while switching to " + modeTracker.pending());
                    // No UI refresh: the chosen mode stays on screen until confirmed or expired.
                    return;
                }
                int[] previousControl = controls.put(event.controlId, event.values);
                Logx.i("AACP control <- id=0x" + Integer.toHexString(event.controlId)
                        + " values=" + Arrays.toString(event.values));
                if (!AacpProtocol.sameControlValues(previousControl, event.values)) controlsRevision++;
                break;
            case AacpProtocol.Event.TYPE_WEAR:
                int previous = inEarCount();
                wearPrimary = event.primaryWear;
                wearSecondary = event.secondaryWear;
                if (wearPrimary == 0 && wearSecondary == 0) {
                    leftStatus = 2;
                    rightStatus = 2;
                    recomputeChargingMask();
                }
                EarPlaybackController.onWearChanged(this, previous, inEarCount());
                handleWearForAudio(previous, inEarCount());
                OfficialBatteryBridge.schedule(this);
                OfficialDeviceStateBridge.schedule(this);
                publishBatterySnapshot();
                break;
            case AacpProtocol.Event.TYPE_INFO:
                if (event.name != null && !event.name.isEmpty()) modelName = event.name;
                if (event.modelNumber != null && !event.modelNumber.isEmpty()) modelNumber = event.modelNumber;
                if (event.firmware != null) firmware = event.firmware;
                if (event.manufacturer != null && !event.manufacturer.isEmpty()) manufacturer = event.manufacturer;
                if (event.serialNumber != null && !event.serialNumber.isEmpty()) serialNumber = event.serialNumber;
                if (event.leftSerialNumber != null && !event.leftSerialNumber.isEmpty()) leftSerialNumber = event.leftSerialNumber;
                if (event.rightSerialNumber != null && !event.rightSerialNumber.isEmpty()) rightSerialNumber = event.rightSerialNumber;
                if (event.version1 != null && !event.version1.isEmpty()) version1 = event.version1;
                if (event.version2 != null && !event.version2.isEmpty()) version2 = event.version2;
                if (event.version3 != null && !event.version3.isEmpty()) version3 = event.version3;
                if (event.hardwareRevision != null && !event.hardwareRevision.isEmpty()) hardwareRevision = event.hardwareRevision;
                if (event.updaterIdentifier != null && !event.updaterIdentifier.isEmpty()) updaterIdentifier = event.updaterIdentifier;
                AirPodsModels.Model model = AirPodsModels.fromModelNumber(modelNumber);
                if (model != null) modelName = model.name;
                controlsRevision++;
                if (settingsPageOpen || panelOpen) ensureAttSession();
                break;
            case AacpProtocol.Event.TYPE_EQUALIZER:
                int[] equalizer = {event.eqState, event.eqLow, event.eqMid, event.eqHigh};
                if (!Arrays.equals(controls.put(AacpProtocol.OP_EQUALIZER, equalizer), equalizer)) {
                    controlsRevision++;
                }
                break;
            case AacpProtocol.Event.TYPE_STEM_PRESS:
                executeStemAction(event.stemPressBud, event.stemPressType);
                break;
            case AacpProtocol.Event.TYPE_HEAD_TRACKING:
                lastHeadTracking = event.raw.clone();
                lastHeadTrackingAt = System.currentTimeMillis();
                headTrackingPackets++;
                int gesture = headGestureTracker.accept(event.raw, lastHeadTrackingAt);
                if (gesture != HeadGestureTracker.NONE) {
                    lastHeadGesture = gesture;
                    lastHeadGestureAt = lastHeadTrackingAt;
                    Logx.i("head gesture recognized: " + (gesture == HeadGestureTracker.NOD ? "nod" : "shake"));
                    if (callGestureActive) {
                        callGestureActive = false;
                        if (gesture == HeadGestureTracker.NOD) callGestures.answer();
                        else callGestures.decline();
                        stopCallGestureTracking();
                    }
                    notifyChanged();
                }
                // Pose packets stream continuously; refreshing every UI listener for each one
                // would flood the main thread.
                return;
            case AacpProtocol.Event.TYPE_CONVERSATION:
                controls.put(0x4B, new int[]{event.conversationSubtype, event.conversationStatus});
                if (ConversationVolume.isSpeakingStart(event.conversationStatus)) {
                    conversationVolume.startSpeaking(conversationLowerVolume(), conversationPauseMusic(),
                            conversationRelativeVolume(), conversationVolumePercent());
                } else if (ConversationVolume.isSpeakingStop(event.conversationStatus)) {
                    conversationVolume.stopSpeaking();
                }
                break;
            case AacpProtocol.Event.TYPE_PROXIMITY_KEYS:
                CrossProcessState.setProximityKeys(context, address, event.irk, event.encKey);
                AacpSession keyed = session;
                if (keyed != null && !needsProximityKeys()) keyed.setRequestProximityKeys(false);
                // Never log key material, only which keys arrived.
                Logx.i("proximity keys stored: IRK=" + (event.irk != null) + " ENC=" + (event.encKey != null));
                controlsRevision++;
                break;
            default:
                break;
        }
        notifyChanged();
    }

    int inEarCount() {
        int count = 0;
        if (wearPrimary == 0) count++;
        if (wearSecondary == 0) count++;
        return count;
    }

    private void recomputeChargingMask() {
        int mask = 0;
        if (!earsBothIn()) {
            if (isChargingStatus(leftStatus)) mask |= 1;
            if (isChargingStatus(rightStatus)) mask |= 2;
        }
        if (isChargingStatus(caseStatus)) mask |= 4;
        chargingMask = mask;
    }

    private boolean earsBothIn() {
        return wearPrimary == 0 && wearSecondary == 0;
    }

    private static boolean isChargingStatus(int status) {
        return status == 1 || status == 5;
    }

    private static int officialBatteryStatus(int status, int level) {
        if (level < 0 || status == 4) return 4;
        return isChargingStatus(status) ? 1 : 2;
    }

    boolean ownsPause() {
        return pausedByEar;
    }

    void setPausedByEar(boolean value) {
        pausedByEar = value;
    }
}


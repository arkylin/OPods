package io.github.oppoairpods.popup;

import android.app.Application;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
    private volatile int[] pendingEqualizer;
    private volatile boolean restoringControls;

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
    private volatile AacpSession session;
    private volatile boolean pausedByEar;
    private volatile boolean opened;
    private volatile boolean settingsPageOpen;
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

    SharedPreferences prefs() {
        return prefs;
    }

    Context context() {
        return context;
    }
    boolean autoConnect() {
        return prefs.getBoolean("auto_connect", true);
    }

    boolean earPlayback() {
        return prefs.getBoolean("ear_playback", true);
    }

    boolean batteryAlerts() {
        return prefs.getBoolean("battery_alerts", false);
    }

    void setAutoConnect(boolean value) {
        prefs.edit().putBoolean("auto_connect", value).apply();
    }

    void setEarPlayback(boolean value) {
        prefs.edit().putBoolean("ear_playback", value).apply();
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

    AirPodsModels.Model model() {
        AirPodsModels.Model value = AirPodsModels.fromModelNumber(modelNumber);
        return value != null ? value : AirPodsModels.fromName(modelName);
    }

    void readAtt() {
        AttSession current = attSession;
        if (current != null) current.readAll();
    }

    void writeLoudSoundReduction(boolean enabled) {
        loudSoundReduction = enabled;
        AttSession current = attSession;
        if (current != null) current.writeLoudSoundReduction(enabled);
        notifyChanged();
    }

    void writeTransparency(AirPodsAttData.Settings value) {
        byte[] data = AirPodsAttData.buildTransparency(value, transparencyData);
        transparencyData = data;
        AttSession current = attSession;
        if (current != null) current.writeTransparency(data);
        notifyChanged();
    }

    void writeHearingAid(AirPodsAttData.Settings value) {
        byte[] data = AirPodsAttData.buildHearingAid(value, hearingAidData);
        hearingAidData = data;
        AttSession current = attSession;
        if (current != null) current.writeHearingAid(data);
        notifyChanged();
    }

    void sendRename(String name) {
        if (name == null || name.trim().isEmpty()) return;
        String value = name.trim();
        AacpSession current = session;
        if (current != null && current.sendRename(value)) {
            pendingRename = null;
            modelName = value;
            notifyChanged();
            return;
        }
        pendingRename = value;
        ensureSession();
    }

    void setHeadTracking(boolean enabled) {
        prefs.edit().putBoolean("head_tracking_active", enabled).apply();
        AacpSession current = session;
        boolean alternate = prefs.getBoolean("alternate_head_tracking", false);
        if (current != null && current.sendHeadTracking(enabled, alternate)) {
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
        return prefs.getBoolean("head_tracking_active", false);
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
        setControlState(AacpProtocol.CONTROL_LISTENING_MODE, next);
        sendControl(AacpProtocol.CONTROL_LISTENING_MODE, next);
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
        return left >= 0 || right >= 0 || caseLevel >= 0
                || leftStatus >= 0 || rightStatus >= 0 || caseStatus >= 0;
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

    String batterySummary() {
        if (left < 0 && right < 0 && caseLevel < 0) return "正在读取电量";
        StringBuilder builder = new StringBuilder();
        builder.append("左 ").append(left >= 0 ? left + "%" : "--");
        builder.append(" · 右 ").append(right >= 0 ? right + "%" : "--");
        builder.append(" · 盒 ").append(caseLevel >= 0 ? caseLevel + "%" : "--");
        return builder.toString();
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
        address = next;
        AirPodsModels.Model model = AirPodsModels.fromName(alias);
        if (model != null) modelName = model.name;
        if (changed) {
            controls.clear();
            left = right = caseLevel = -1;
            leftStatus = rightStatus = caseStatus = -1;
            chargingMask = 0;
            modelNumber = "";
            firmware = "";
            wearPrimary = wearSecondary = -1;
            opened = false;
            stopSession();
        }
        ensureSession();
        notifyChanged();
    }

    void onSettingsPageOpened(BluetoothDevice device) {
        settingsPageOpen = true;
        attachDevice(device);
        notifyChanged();
    }

    void onSettingsPageClosed() {
        settingsPageOpen = false;
    }

    void onAclConnected(BluetoothDevice device) {
        if (!isAirPods(device)) return;
        boolean hadAddress = !address.isEmpty() && address.equalsIgnoreCase(device.getAddress());
        attachDevice(device);
        Logx.i("AirPods link connected; controller address refreshed");
        scheduleConnectAndPopup(device, hadAddress ? 250L : 0L);
    }

    void onAclDisconnected(BluetoothDevice device) {
        if (device == null || address.isEmpty() || !address.equalsIgnoreCase(device.getAddress())) return;
        left = right = caseLevel = -1;
        leftStatus = rightStatus = caseStatus = 4;
        chargingMask = 0;
        wearPrimary = wearSecondary = -1;
        opened = false;
        controls.clear();
        stopSession();
        OfficialBatteryBridge.schedule(this);
        notifyChanged();
    }

    void onCaseAdvertisement(BluetoothDevice device, String model, boolean lidOpen) {
        if (!lidOpen) return;
        long now = System.currentTimeMillis();
        if (now - lastCaseAt < 4500L) return;
        lastCaseAt = now;
        if (device != null) attachDevice(device);
        if (device == null) device = findBondedAirPods();
        if (device == null) return;
        if (model != null && !model.isEmpty()) modelName = AirPodsModels.displayName(null, model);
        Logx.i("Case-open advertisement for " + modelName);
        scheduleConnectAndPopup(device, 0);
    }

    private void scheduleConnectAndPopup(BluetoothDevice device, long delayMs) {
        main.postDelayed(() -> {
            try {
                if (autoConnect()) connectProfiles(device);
                launchOfficialPopup(device);
            } catch (Throwable error) {
                Logx.w("case-open handling failed", error);
            }
        }, delayMs);
        // A headset held by an iPhone may accept the audio profiles a moment later.
        main.postDelayed(() -> {
            if (autoConnect() && !isAudioConnected(device)) connectProfiles(device);
        }, 1800L);
        main.postDelayed(() -> {
            if (autoConnect() && !isAudioConnected(device)) connectProfiles(device);
        }, 4200L);
    }

    void launchOfficialPopup(BluetoothDevice device) {
        if (settingsPageOpen) return;
        long now = System.currentTimeMillis();
        if (now - lastPopupAt < 3500L) return;
        lastPopupAt = now;
        try {
            Intent intent = new Intent("com.oplus.mydevices.AIRPODS_DISCOVERY");
            intent.setClassName("com.heytap.mydevices",
                    "com.heytap.mydevices.plugin.airpods.ui.AirpodsDiscoveryActivity");
            intent.putExtra("bluetooth_device", device);
            intent.putExtra("device_model", modelName);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
            Logx.i("official AirPods popup requested");
        } catch (Throwable error) {
            Logx.w("official popup failed", error);
        }
    }

    void connectProfiles(BluetoothDevice device) {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled() || device == null) return;
        requestProfile(adapter, device, BluetoothProfile.A2DP);
        requestProfile(adapter, device, BluetoothProfile.HEADSET);
    }

    private void requestProfile(BluetoothAdapter adapter, BluetoothDevice device, int profile) {
        try {
            boolean requested = adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int connectedProfile, BluetoothProfile proxy) {
                    if (connectedProfile != profile) return;
                    try {
                        if (proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) return;
                        try {
                            Method policy = proxy.getClass().getMethod("setConnectionPolicy", BluetoothDevice.class, int.class);
                            policy.invoke(proxy, device, 100);
                        } catch (Throwable ignored) {
                        }
                        Method connect = proxy.getClass().getMethod("connect", BluetoothDevice.class);
                        connect.invoke(proxy, device);
                        Logx.i("requested profile " + profile + " connect");
                    } catch (Throwable error) {
                        Logx.w("profile " + profile + " connect failed", error);
                    } finally {
                        try {
                            adapter.closeProfileProxy(profile, proxy);
                        } catch (Throwable ignored) {
                        }
                    }
                }

                @Override
                public void onServiceDisconnected(int disconnectedProfile) {
                }
            }, profile);
            if (!requested) Logx.w("profile proxy unavailable: " + profile, null);
        } catch (Throwable error) {
            Logx.w("profile proxy request failed", error);
        }
    }

    boolean isAudioConnected(BluetoothDevice device) {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || device == null) return false;
        for (int profile : new int[]{BluetoothProfile.A2DP, BluetoothProfile.HEADSET}) {
            final boolean[] connected = {false};
            final Object lock = new Object();
            try {
                adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int connectedProfile, BluetoothProfile proxy) {
                        try {
                            connected[0] = proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED;
                        } finally {
                            synchronized (lock) {
                                lock.notifyAll();
                            }
                            try {
                                adapter.closeProfileProxy(profile, proxy);
                            } catch (Throwable ignored) {
                            }
                        }
                    }

                    @Override
                    public void onServiceDisconnected(int disconnectedProfile) {
                        synchronized (lock) {
                            lock.notifyAll();
                        }
                    }
                }, profile);
                synchronized (lock) {
                    lock.wait(350L);
                }
            } catch (Throwable ignored) {
            }
            if (connected[0]) return true;
        }
        return false;
    }

    BluetoothDevice findBondedAirPods() {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) return null;
        try {
            Set<BluetoothDevice> devices = adapter.getBondedDevices();
            if (devices == null) return null;
            for (BluetoothDevice device : devices) {
                if (isAirPods(device)) return device;
            }
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
                session = new AacpSession(context, address, this);
                Logx.i("AACP owner starting session #" + session.sessionId());
                session.start();
            }
        }
    }

    void stopSession() {
        synchronized (sessionLock) {
            AacpSession current = session;
            session = null;
            if (current != null) current.stop();
        }
        stopAttSession();
    }

    private void ensureAttSession() {
        if (address == null || address.isEmpty()) return;
        AirPodsModels.Model model = model();
        if (model != null && !model.has(AirPodsModels.CAP_HEARING_AID)
                && !model.has(AirPodsModels.CAP_LOUD_SOUND)) return;
        synchronized (sessionLock) {
            AttSession current = attSession;
            if (current != null && current.isConnected()) return;
            if (current != null) current.stop();
            AttSession next = new AttSession(context, address, new AttSession.Listener() {
                @Override
                public void onAttConnected() {
                    attConnected = true;
                    notifyChanged();
                }

                @Override
                public void onAttDisconnected() {
                    attConnected = false;
                    notifyChanged();
                }

                @Override
                public void onTransparency(byte[] data) {
                    transparencyData = data.clone();
                    notifyChanged();
                }

                @Override
                public void onHearingAid(byte[] data) {
                    hearingAidData = data.clone();
                    notifyChanged();
                }

                @Override
                public void onLoudSoundReduction(byte[] data) {
                    loudSoundReduction = data.length > 0 && data[0] == 1;
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
        if (!restoringControls && isRestorableControl(id)) persistControl(id, values);
        AacpSession current = session;
        if (current != null && current.sendControl(id, values)) {
            Logx.i("AACP control -> id=0x" + Integer.toHexString(id) + " values="
                    + Arrays.toString(values) + " via #" + current.sessionId());
            return true;
        }
        ensureSession();
        current = session;
        if (current != null && current.sendControl(id, values)) {
            Logx.i("AACP control -> id=0x" + Integer.toHexString(id) + " values="
                    + Arrays.toString(values) + " via #" + current.sessionId());
            return true;
        }
        if (!aacpOwner) {
            Logx.w("AACP control skipped outside :cards id=0x" + Integer.toHexString(id), null);
            return false;
        }
        synchronized (pendingControls) {
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
        AacpSession current = session;
        if (current != null && current.sendEqualizer(enabled, low, mid, high)) return true;
        ensureSession();
        current = session;
        if (current != null && current.sendEqualizer(enabled, low, mid, high)) return true;
        if (!aacpOwner) return false;
        pendingEqualizer = new int[]{enabled, low, mid, high};
        return true;
    }

    @Override
    public void onOpened() {
        opened = true;
        AacpSession current = session;
        Logx.i("AACP ready session=" + (current == null ? -1 : current.sessionId()));
        ensureAttSession();
        main.postDelayed(this::flushPending, 250L);
        main.postDelayed(this::applyStemConfig, 500L);
        main.postDelayed(this::applySavedControls, 1200L);
        notifyChanged();
    }

    private static boolean isRestorableControl(int id) {
        for (int candidate : RESTORABLE_CONTROLS) {
            if (candidate == id) return true;
        }
        return false;
    }

    private void persistControl(int id, int[] values) {
        if (values == null || values.length == 0) return;
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) value.append(',');
            value.append(values[i]);
        }
        prefs.edit().putString(controlKey(id), value.toString()).apply();
    }

    private void persistEqualizer(int enabled, int low, int mid, int high) {
        prefs.edit().putString("device_equalizer", enabled + "," + low + "," + mid + "," + high).apply();
    }

    private static String controlKey(int id) {
        return "device_control_" + Integer.toHexString(id);
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
        String value = prefs.getString("device_equalizer", null);
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
        if (current == null) return;
        restoringControls = true;
        try {
            for (int id : RESTORABLE_CONTROLS) {
                int[] desired = savedControl(id);
                if (desired == null || Arrays.equals(controls.get(id), desired)) continue;
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
        } finally {
            restoringControls = false;
        }
    }

    private void flushPending() {
        AacpSession current = session;
        if (current == null) return;
        synchronized (pendingControls) {
            int[] next;
            while ((next = pendingControls.pollFirst()) != null) {
                int[] values = Arrays.copyOfRange(next, 1, next.length);
                current.sendControl(next[0], values);
            }
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
    public void onClosed() {
        opened = false;
        stopAttSession();
        notifyChanged();
    }

    @Override
    public void onEvent(AacpProtocol.Event event) {
        if (event == null) return;
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
                break;
            case AacpProtocol.Event.TYPE_CONTROL:
                int[] previousControl = controls.put(event.controlId, event.values);
                Logx.i("AACP control <- id=0x" + Integer.toHexString(event.controlId)
                        + " values=" + Arrays.toString(event.values));
                if (!Arrays.equals(previousControl, event.values)) notifyChanged();
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
                OfficialBatteryBridge.schedule(this);
                OfficialDeviceStateBridge.schedule(this);
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
                break;
            case AacpProtocol.Event.TYPE_EQUALIZER:
                controls.put(AacpProtocol.OP_EQUALIZER, new int[]{event.eqState, event.eqLow, event.eqMid, event.eqHigh});
                break;
            case AacpProtocol.Event.TYPE_STEM_PRESS:
                executeStemAction(event.stemPressBud, event.stemPressType);
                break;
            case AacpProtocol.Event.TYPE_HEAD_TRACKING:
                lastHeadTracking = event.raw.clone();
                lastHeadTrackingAt = System.currentTimeMillis();
                break;
            case AacpProtocol.Event.TYPE_CONVERSATION:
                controls.put(0x4B, new int[]{event.conversationSubtype, event.conversationStatus});
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


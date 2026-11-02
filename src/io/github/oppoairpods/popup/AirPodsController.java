package io.github.oppoairpods.popup;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

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
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<Integer, int[]> controls = Collections.synchronizedMap(new HashMap<>());
    private final Object sessionLock = new Object();
    private final ArrayDeque<int[]> pendingControls = new ArrayDeque<>();
    private volatile int[] pendingEqualizer;

    private volatile String address = "";
    private volatile String modelName = "AirPods";
    private volatile String modelNumber = "";
    private volatile String firmware = "";
    private volatile int left = -1;
    private volatile int right = -1;
    private volatile int caseLevel = -1;
    private volatile int chargingMask;
    private volatile int wearPrimary = -1;
    private volatile int wearSecondary = -1;
    private volatile long lastPopupAt;
    private volatile long lastCaseAt;
    private volatile AacpSession session;
    private volatile boolean pausedByEar;
    private volatile boolean opened;
    private volatile boolean settingsPageOpen;

    private AirPodsController(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("oppo_airpods_enhanced", Context.MODE_PRIVATE);
        Logx.setContext(context);
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
        wearPrimary = wearSecondary = -1;
        opened = false;
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
        if (address == null || address.isEmpty()) return;
        synchronized (sessionLock) {
            AacpSession current = session;
            if (current != null && current.isRunning()) return;
            session = new AacpSession(context, address, this);
            session.start();
        }
    }

    void stopSession() {
        synchronized (sessionLock) {
            AacpSession current = session;
            session = null;
            if (current != null) current.stop();
        }
    }

    boolean sendControl(int id, int... values) {
        AacpSession current = session;
        if (current != null && current.sendControl(id, values)) return true;
        ensureSession();
        current = session;
        if (current != null && current.sendControl(id, values)) return true;
        synchronized (pendingControls) {
            if (pendingControls.size() >= 32) pendingControls.removeFirst();
            pendingControls.addLast(valuesWithId(id, values));
        }
        Logx.i("control queued until AACP opens: " + Integer.toHexString(id));
        return true;
    }

    private static int[] valuesWithId(int id, int[] values) {
        int[] result = new int[values.length + 1];
        result[0] = id;
        System.arraycopy(values, 0, result, 1, values.length);
        return result;
    }

    boolean sendEqualizer(int enabled, int low, int mid, int high) {
        AacpSession current = session;
        if (current != null && current.sendEqualizer(enabled, low, mid, high)) return true;
        ensureSession();
        current = session;
        if (current != null && current.sendEqualizer(enabled, low, mid, high)) return true;
        pendingEqualizer = new int[]{enabled, low, mid, high};
        return true;
    }

    @Override
    public void onOpened() {
        opened = true;
        flushPending();
        notifyChanged();
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
        int[] eq = pendingEqualizer;
        if (eq != null) {
            pendingEqualizer = null;
            current.sendEqualizer(eq[0], eq[1], eq[2], eq[3]);
        }
    }

    @Override
    public void onClosed() {
        opened = false;
        notifyChanged();
    }

    @Override
    public void onEvent(AacpProtocol.Event event) {
        if (event == null) return;
        switch (event.type) {
            case AacpProtocol.Event.TYPE_BATTERY:
                if (event.left >= 0) left = event.left;
                if (event.right >= 0) right = event.right;
                if (event.caseLevel >= 0) caseLevel = event.caseLevel;
                chargingMask = event.chargingMask;
                BatteryNotifier.onBattery(this, left, right, caseLevel, chargingMask);
                break;
            case AacpProtocol.Event.TYPE_CONTROL:
                controls.put(event.controlId, event.values);
                break;
            case AacpProtocol.Event.TYPE_WEAR:
                int previous = inEarCount();
                wearPrimary = event.primaryWear;
                wearSecondary = event.secondaryWear;
                EarPlaybackController.onWearChanged(this, previous, inEarCount());
                break;
            case AacpProtocol.Event.TYPE_INFO:
                if (event.name != null && !event.name.isEmpty()) modelName = event.name;
                if (event.modelNumber != null && !event.modelNumber.isEmpty()) modelNumber = event.modelNumber;
                if (event.firmware != null) firmware = event.firmware;
                AirPodsModels.Model model = AirPodsModels.fromModelNumber(modelNumber);
                if (model != null) modelName = model.name;
                break;
            case AacpProtocol.Event.TYPE_EQUALIZER:
                controls.put(AacpProtocol.OP_EQUALIZER, new int[]{event.eqState, event.eqLow, event.eqMid, event.eqHigh});
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

    boolean ownsPause() {
        return pausedByEar;
    }

    void setPausedByEar(boolean value) {
        pausedByEar = value;
    }
}


package io.github.oppoairpods.popup;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Feeds the AACP battery snapshot into the official AirpodsScanPolicy update path.
 * That DeviceInfo update is what Control Center consumes for its AirPods device tile.
 */
final class OfficialDeviceStateBridge {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean APPLYING = new AtomicBoolean();
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean();
    private static final AtomicBoolean CLEAR_SCHEDULED = new AtomicBoolean();
    private static final AtomicBoolean ERROR_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean POLICY_MISSING_LOGGED = new AtomicBoolean();
    private static volatile WeakReference<Object> policy = new WeakReference<>(null);
    private static volatile WeakReference<Context> context = new WeakReference<>(null);
    private static volatile String lastApplied = "";
    private static volatile String pendingAddress;
    private static volatile int[] pendingBattery;

    private OfficialDeviceStateBridge() {
    }

    static void register(Object value, Context appContext) {
        if (value == null) return;
        boolean firstRegistration = policy.get() != value;
        policy = new WeakReference<>(value);
        if (appContext != null) {
            Context app = appContext.getApplicationContext();
            context = new WeakReference<>(app == null ? appContext : app);
        }
        Context current = context.get();
        if (firstRegistration && current != null) {
            String payload = CrossProcessState.batteryPayload(current);
            if (payload != null) applyPayload(current, payload);
        }
    }

    static boolean applying() {
        return APPLYING.get();
    }

    static void schedule(AirPodsController controller) {
        if (controller == null || APPLYING.get() || !controller.hasBatterySnapshot()) return;
        schedule(controller, false, SCHEDULED);
    }

    static void clear(AirPodsController controller) {
        if (controller == null || APPLYING.get()) return;
        schedule(controller, true, CLEAR_SCHEDULED);
    }

    /** Applies a battery snapshot received from the UI (:cards) process over the official IPC. */
    static void applyPayload(Context appContext, String payload) {
        if (payload == null) return;
        int separator = payload.indexOf('|');
        if (separator <= 0 || separator >= payload.length() - 1) return;
        String address = payload.substring(0, separator).trim();
        String[] parts = payload.substring(separator + 1).split(",");
        if (address.isEmpty() || parts.length != 6) return;
        int[] battery = new int[6];
        try {
            for (int index = 0; index < battery.length; index++) {
                battery[index] = Integer.parseInt(parts[index]);
            }
        } catch (NumberFormatException ignored) {
            return;
        }
        for (int index = 0; index < 3; index++) {
            if (battery[index] < -1 || battery[index] > 100) return;
        }
        for (int index = 3; index < 6; index++) {
            if (battery[index] < -1 || battery[index] > 8) return;
        }
        Logx.i("core IPC battery <- " + Arrays.toString(battery));
        applyExternal(appContext, address, battery);
    }

    private static void applyExternal(Context appContext, String address, int[] battery) {
        if (appContext != null) {
            Context app = appContext.getApplicationContext();
            context = new WeakReference<>(app == null ? appContext : app);
        }
        pendingAddress = address;
        pendingBattery = battery.clone();
        MAIN.post(OfficialDeviceStateBridge::applyPending);
    }

    private static void applyPending() {
        Object target = policy.get();
        String address = pendingAddress;
        int[] battery = pendingBattery;
        if (target == null) {
            if (!POLICY_MISSING_LOGGED.getAndSet(true)) {
                Logx.i("official AirpodsScanPolicy not registered; battery snapshot kept");
            }
            return;
        }
        if (address == null || battery == null) return;
        POLICY_MISSING_LOGGED.set(false);
        Context current = context.get();
        if (current == null) return;
        BluetoothDevice device = remoteDevice(current, address);
        if (device == null) return;
        applyTarget(target, device, battery);
    }

    private static void schedule(AirPodsController controller, boolean clear, AtomicBoolean flag) {
        if (!flag.compareAndSet(false, true)) return;
        MAIN.post(() -> {
            flag.set(false);
            apply(controller, clear);
        });
    }

    private static void apply(AirPodsController controller, boolean clear) {
        Object target = policy.get();
        BluetoothDevice device = controller.currentDevice();
        if (target == null || device == null || (!clear && !controller.hasBatterySnapshot())) return;
        int[] battery = clear ? new int[]{-1, -1, -1, 4, 4, 4}
                : controller.officialBatteryArray();
        applyTarget(target, device, battery);
    }

    private static void applyTarget(Object target, BluetoothDevice device, int[] battery) {
        String compact = Arrays.toString(battery);
        APPLYING.set(true);
        try {
            Method method = target.getClass().getMethod(
                    "onBatteryLevelChanged", BluetoothDevice.class, int[].class);
            method.invoke(target, device, battery);
            if (!compact.equals(lastApplied)) {
                lastApplied = compact;
                Logx.i("AACP battery -> official DeviceInfo " + compact);
            }
        } catch (Throwable error) {
            if (!ERROR_LOGGED.getAndSet(true)) Logx.w("official DeviceInfo refresh failed", error);
        } finally {
            APPLYING.set(false);
        }
    }

    private static BluetoothDevice remoteDevice(Context appContext, String address) {
        try {
            BluetoothManager manager = (BluetoothManager) appContext.getSystemService(
                    Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            return adapter == null ? null : adapter.getRemoteDevice(address);
        } catch (Throwable ignored) {
            return null;
        }
    }
}

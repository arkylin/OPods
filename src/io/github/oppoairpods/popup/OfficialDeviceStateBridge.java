package io.github.oppoairpods.popup;

import android.bluetooth.BluetoothDevice;
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
    private static final AtomicBoolean ERROR_LOGGED = new AtomicBoolean();
    private static volatile WeakReference<Object> policy = new WeakReference<>(null);
    private static volatile String lastApplied = "";

    private OfficialDeviceStateBridge() {
    }

    static void register(Object value) {
        if (value != null) policy = new WeakReference<>(value);
    }

    static boolean applying() {
        return APPLYING.get();
    }

    static void schedule(AirPodsController controller) {
        if (controller == null || APPLYING.get() || !controller.hasBatterySnapshot()) return;
        schedule(controller, false);
    }

    static void clear(AirPodsController controller) {
        if (controller == null || APPLYING.get()) return;
        schedule(controller, true);
    }

    private static void schedule(AirPodsController controller, boolean clear) {
        if (!SCHEDULED.compareAndSet(false, true)) return;
        MAIN.post(() -> {
            SCHEDULED.set(false);
            apply(controller, clear);
        });
    }

    private static void apply(AirPodsController controller, boolean clear) {
        Object target = policy.get();
        BluetoothDevice device = controller.currentDevice();
        if (target == null || device == null || (!clear && !controller.hasBatterySnapshot())) return;
        int[] battery = clear ? new int[]{-1, -1, -1, 4, 4, 4}
                : controller.officialBatteryArray();
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
}

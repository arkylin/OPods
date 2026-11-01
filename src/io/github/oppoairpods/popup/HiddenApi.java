package io.github.oppoairpods.popup;

import java.lang.reflect.Method;

/** Best-effort exemption for the framework classes the ROM itself already uses. */
final class HiddenApi {
    private static volatile boolean applied;

    private HiddenApi() {}

    static synchronized void exemptBluetooth() {
        if (applied) return;
        applied = true;
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            Method setExemptions = vmRuntime.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setExemptions.setAccessible(true);
            setExemptions.invoke(runtime, (Object) new String[]{
                    "Landroid/bluetooth/BluetoothSocket;",
                    "Landroid/bluetooth/BluetoothDevice;",
                    "Landroid/bluetooth/BluetoothA2dp;",
                    "Landroid/bluetooth/BluetoothHeadset;"
            });
            Logx.i("hidden API exemptions installed");
        } catch (Throwable error) {
            Logx.w("hidden API exemption unavailable", error);
        }
    }
}
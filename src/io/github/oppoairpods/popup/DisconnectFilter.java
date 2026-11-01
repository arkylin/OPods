package io.github.oppoairpods.popup;

import android.bluetooth.BluetoothDevice;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/** Keeps the official page from greying out when only an idle HFP link drops. */
final class DisconnectFilter {
    private static final long USER_WINDOW_MS = 8000L;
    private static volatile long userDisconnectAt;

    private DisconnectFilter() {}

    static void install(XposedModule module, ClassLoader loader) {
        try {
            Class<?> manager = Class.forName(
                    "com.heytap.mydevices.plugin.airpods.core.AirpodsBluetoothManager",
                    false, loader);
            Method dispatch = manager.getDeclaredMethod("i0", String.class, BluetoothDevice.class);
            Method connected = manager.getDeclaredMethod("P");
            Method disconnect = manager.getDeclaredMethod("C");
            module.hook(disconnect).intercept(chain -> {
                userDisconnectAt = System.currentTimeMillis();
                return chain.proceed();
            });
            module.hook(dispatch).intercept(chain -> {
                try {
                    String state = (String) chain.getArg(0);
                    if ("STATE_DISCONNECTED".equals(state)
                            && System.currentTimeMillis() - userDisconnectAt > USER_WINDOW_MS) {
                        Object value = connected.invoke(chain.getThisObject());
                        if (value instanceof Boolean && (Boolean) value) {
                            Logx.i("blocked false STATE_DISCONNECTED");
                            return null;
                        }
                    }
                } catch (Throwable error) {
                    Logx.w("disconnect filter failed", error);
                }
                return chain.proceed();
            });
            Logx.i("MyDevices disconnect filter installed");
        } catch (Throwable error) {
            Logx.w("disconnect filter unavailable", error);
        }
    }
}
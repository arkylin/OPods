package io.github.oppoairpods.popup;

import android.bluetooth.BluetoothDevice;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Predicate;

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
            Method dispatch = findMethod(manager, "i0",
                    method -> method.getParameterCount() == 2
                            && method.getParameterTypes()[0] == String.class
                            && method.getParameterTypes()[1] == BluetoothDevice.class);
            Method connected = null;
            try {
                connected = findMethod(manager, "P",
                        method -> method.getParameterCount() == 0
                                && method.getReturnType() == boolean.class
                                && !Modifier.isStatic(method.getModifiers()));
            } catch (Throwable error) {
                Logx.w("disconnect connected-state fallback unavailable", error);
            }
            final Method connectedMethod = connected;
            try {
                Method disconnect = manager.getDeclaredMethod("C");
                module.hook(disconnect).intercept(chain -> {
                    userDisconnectAt = System.currentTimeMillis();
                    return chain.proceed();
                });
            } catch (Throwable error) {
                Logx.w("disconnect intent hook unavailable", error);
            }
            module.hook(dispatch).intercept(chain -> {
                try {
                    String state = (String) chain.getArg(0);
                    if ("STATE_DISCONNECTED".equals(state)
                            && System.currentTimeMillis() - userDisconnectAt > USER_WINDOW_MS) {
                        BluetoothDevice device = (BluetoothDevice) chain.getArg(1);
                        if (isConnected(device) || isConnected(chain.getThisObject(), connectedMethod)) {
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

    private static Method findMethod(Class<?> owner, String preferred, Predicate<Method> predicate)
            throws NoSuchMethodException {
        for (Method method : owner.getDeclaredMethods()) {
            if (preferred.equals(method.getName()) && predicate.test(method)) return method;
        }
        for (Method method : owner.getDeclaredMethods()) {
            if (predicate.test(method)) return method;
        }
        throw new NoSuchMethodException(owner.getName() + "." + preferred);
    }

    private static boolean isConnected(BluetoothDevice device) {
        if (device == null) return false;
        try {
            Method method = BluetoothDevice.class.getDeclaredMethod("isConnected");
            method.setAccessible(true);
            Object value = method.invoke(device);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isConnected(Object manager, Method method) {
        if (manager == null || method == null) return false;
        try {
            Object value = method.invoke(manager);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            return false;
        }
    }
}

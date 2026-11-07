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
                String state = null;
                try {
                    state = (String) chain.getArg(0);
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
                traceConnect(state);
                return chain.proceed();
            });
            Logx.i("MyDevices disconnect filter installed");
        } catch (Throwable error) {
            Logx.w("disconnect filter unavailable", error);
        }
    }

    /** The official connect's progress, appended to the panel's "最近开盖连接" line. */
    private static void traceConnect(String state) {
        String step;
        if ("STATE_START_BOND".equals(state)) step = "官方开始连接";
        else if ("STATE_CONNECTED".equals(state)) step = "已连接";
        else if ("STATE_START_BOND_TIMEOUT".equals(state)) step = "官方连接失败/超时（弹窗显示重试）";
        else if ("STATE_DISCONNECTED".equals(state)) step = "已断开";
        else return;
        try {
            android.content.Context context = AirPodsMyDevicesModule.context();
            if (context != null) {
                CrossProcessState.appendLastConnect(context, step, AirPodsMyDevicesModule.CONNECT_TRACE_MS);
            }
        } catch (Throwable ignored) {
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

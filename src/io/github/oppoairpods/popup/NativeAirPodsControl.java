package io.github.oppoairpods.popup;

import android.bluetooth.BluetoothDevice;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Uses the OPPO Bluetooth extension already present in ColorOS for noise/wear/conversation. */
final class NativeAirPodsControl {
    static final int FEATURE_NOISE = 4;
    static final int FEATURE_CONVERSATION = 16;
    static final int FEATURE_WEAR = 32;

    private static volatile Constructor<?> constructor;
    private static volatile Method getter;
    private static volatile Method setter;
    private static volatile boolean unavailable;

    private NativeAirPodsControl() {}

    static Integer getStatus(BluetoothDevice device, int type) {
        try {
            Object target = target(device);
            if (target == null || getter == null) return null;
            Object value = getter.invoke(target, type);
            return value instanceof Integer ? (Integer) value : null;
        } catch (Throwable error) {
            Logx.w("OPPO feature read failed: " + type, error);
            return null;
        }
    }

    static boolean setStatus(BluetoothDevice device, int type, int value) {
        try {
            Object target = target(device);
            if (target == null || setter == null) return false;
            Object result = setter.invoke(target, type, value);
            Logx.i("OPPO feature write type=" + type + " value=" + value + " result=" + result);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable error) {
            Logx.w("OPPO feature write failed: " + type + "=" + value, error);
            return false;
        }
    }

    private static Object target(BluetoothDevice device) throws Exception {
        if (device == null || unavailable) return null;
        if (constructor == null) {
            synchronized (NativeAirPodsControl.class) {
                if (constructor == null && !unavailable) {
                    try {
                        Class<?> type;
                        try {
                            type = Class.forName("android.bluetooth.OplusBluetoothDevice");
                        } catch (ClassNotFoundException missing) {
                            type = Class.forName("com.oplus.bluetooth.OplusBluetoothDevice");
                        }
                        constructor = type.getConstructor(BluetoothDevice.class);
                        getter = type.getMethod("getExtendFeatureStatus", int.class);
                        setter = type.getMethod("setExtendFeatureStatus", int.class, int.class);
                    } catch (Throwable error) {
                        unavailable = true;
                        Logx.w("OPPO Bluetooth extension unavailable", error);
                        return null;
                    }
                }
            }
        }
        return constructor == null ? null : constructor.newInstance(device);
    }
}

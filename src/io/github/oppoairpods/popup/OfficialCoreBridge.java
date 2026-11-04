package io.github.oppoairpods.popup;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends the AACP battery snapshot from the UI (:cards) process to the ROM core process.
 * The core process owns AirpodsScanPolicy and the DeviceInfo that Control Center reads.
 */
final class OfficialCoreBridge {
    static final String IPC_METHOD = "airpods";
    static final String PARAM_BATTERY = "OPODS_AACP_BATTERY_V1";
    private static final long DUPLICATE_IPC_MS = 1500L;
    private static final AtomicBoolean CONNECTION_ERROR_LOGGED = new AtomicBoolean();

    private static volatile Object ipcManager;
    private static volatile Object sendTarget;
    private static volatile Method sendMethod;
    private static volatile String lastPayload = "";
    private static volatile long lastPayloadAt;

    private OfficialCoreBridge() {
    }

    static void registerIpcManager(Object value) {
        if (value == null || ipcManager == value) return;
        ipcManager = value;
        sendTarget = null;
        sendMethod = null;
        Logx.i("official core IPC manager captured: " + value.getClass().getName());
    }

    static void capture(Object owner) {
        if (owner == null) return;
        capture(owner, 0, new IdentityHashMap<>());
    }

    private static void capture(Object owner, int depth, Map<Object, Boolean> seen) {
        if (owner == null || depth > 2 || seen.put(owner, Boolean.TRUE) != null) return;
        try {
            Method getter = owner.getClass().getMethod("getUiIpcManager");
            Object value = getter.invoke(owner);
            if (value != null) {
                registerIpcManager(value);
                return;
            }
        } catch (Throwable ignored) {
        }
        for (Class<?> type = owner.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value == null) continue;
                    String name = value.getClass().getName();
                    if (name.equals("com.oplus.mydevices.deviceui.ipc.InnerIpcManager")
                            || name.endsWith(".IInnerIpcManager")) {
                        registerIpcManager(value);
                        return;
                    }
                    if (depth < 2 && (name.contains("Activity") || name.contains("Fragment"))) {
                        capture(value, depth + 1, seen);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    static void publish(Context context, String address, int[] battery) {
        if (context == null || address == null || address.isEmpty()
                || battery == null || battery.length < 6) return;
        CrossProcessState.setBattery(context, address, battery);
        String payload = address + "|" + battery[0] + "," + battery[1] + ","
                + battery[2] + "," + battery[3] + "," + battery[4] + "," + battery[5];
        long now = System.currentTimeMillis();
        if (payload.equals(lastPayload) && now - lastPayloadAt < DUPLICATE_IPC_MS) return;
        if (send(payload)) {
            lastPayload = payload;
            lastPayloadAt = now;
            Logx.i("AACP battery -> core IPC " + batteryText(battery));
        }
    }

    private static boolean send(String payload) {
        if (sendOnce(payload)) return true;
        sendTarget = null;
        sendMethod = null;
        return sendOnce(payload);
    }

    private static boolean sendOnce(String payload) {
        Object manager = ipcManager;
        if (manager == null) {
            if (!CONNECTION_ERROR_LOGGED.getAndSet(true)) {
                Logx.i("official core IPC manager not captured yet; battery snapshot saved");
            }
            return false;
        }
        try {
            Object target = sendTarget;
            Method method = sendMethod;
            if (target == null || method == null) {
                Object connection = findServiceConnection(manager);
                if (connection == null) throw new NoSuchFieldException("IServiceConnManager");
                Class<?> connectionType = Class.forName(
                        "com.oplus.mydevices.domain.ports.ipc.IServiceConnManager",
                        false, manager.getClass().getClassLoader());
                target = connectionType.getMethod("getInterfaceLock").invoke(connection);
                if (target == null) throw new IllegalStateException("core service not bound");
                method = findSendMethod(target.getClass());
                sendTarget = target;
                sendMethod = method;
            }
            Bundle extra = new Bundle();
            extra.putString("CALL_PROCESS_NAME", Application.getProcessName());
            method.invoke(target, IPC_METHOD, PARAM_BATTERY, payload, extra);
            CONNECTION_ERROR_LOGGED.set(false);
            return true;
        } catch (Throwable error) {
            if (!CONNECTION_ERROR_LOGGED.getAndSet(true)) {
                Logx.w("official core IPC send failed", error);
            }
            return false;
        }
    }

    private static Object findServiceConnection(Object manager) throws Exception {
        Class<?> connectionType = Class.forName(
                "com.oplus.mydevices.domain.ports.ipc.IServiceConnManager",
                false, manager.getClass().getClassLoader());
        for (Class<?> type = manager.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(manager);
                if (value != null && connectionType.isInstance(value)) return value;
            }
        }
        return null;
    }

    private static Method findSendMethod(Class<?> type) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals("G")
                    && method.getParameterCount() == 4
                    && method.getReturnType() == Bundle.class
                    && method.getParameterTypes()[0] == String.class
                    && method.getParameterTypes()[1] == String.class
                    && method.getParameterTypes()[2] == String.class
                    && method.getParameterTypes()[3] == Bundle.class) {
                method.setAccessible(true);
                return method;
            }
        }
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() == 4
                    && method.getReturnType() == Bundle.class
                    && method.getParameterTypes()[0] == String.class
                    && method.getParameterTypes()[1] == String.class
                    && method.getParameterTypes()[2] == String.class
                    && method.getParameterTypes()[3] == Bundle.class) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException("core IPC request method");
    }

    private static String batteryText(int[] battery) {
        return "[" + battery[0] + ", " + battery[1] + ", " + battery[2]
                + ", " + battery[3] + ", " + battery[4] + ", " + battery[5] + "]";
    }
}

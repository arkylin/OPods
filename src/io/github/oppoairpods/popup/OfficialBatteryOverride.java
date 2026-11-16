package io.github.oppoairpods.popup;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the latest AACP snapshot available to the official MyDevices data getters.
 *
 * The ROM builds the Control Center PanelDevice from DeviceInfo.getBatteryList(). The AirPods
 * plugin updates its own DeviceInfo copy, but the device-space producer reads another copy whose
 * batteryList stays empty, so the AirPods tile has no battery. This cache fills that gap without
 * creating another process, service or receiver.
 */
final class OfficialBatteryOverride {
    private static volatile ClassLoader hostLoader;
    private static volatile String address = "";
    private static volatile int[] battery;
    private static volatile String cachedKey = "";
    private static volatile List<Object> cachedList;

    private OfficialBatteryOverride() {
    }

    static void setLoader(ClassLoader loader) {
        if (loader != null) hostLoader = loader;
    }

    static void set(String mac, int[] values) {
        if (mac == null || mac.isEmpty() || values == null || values.length < 6) return;
        String normalized = mac.toUpperCase(Locale.ROOT);
        int[] copy = values.clone();
        synchronized (OfficialBatteryOverride.class) {
            address = normalized;
            battery = copy;
            cachedKey = "";
            cachedList = null;
        }
    }

    static void clear() {
        synchronized (OfficialBatteryOverride.class) {
            address = "";
            battery = null;
            cachedKey = "";
            cachedList = null;
        }
    }

    static boolean hasSnapshot() {
        int[] values = battery;
        return values != null && (values[0] >= 0 || values[1] >= 0 || values[2] >= 0);
    }

    static boolean matches(String mac, String typeName) {
        int[] values = battery;
        return values != null && "BT_AIRPODS".equals(typeName)
                && mac != null && address.equalsIgnoreCase(mac)
                && (values[0] >= 0 || values[1] >= 0 || values[2] >= 0);
    }

    /** Builds the official BatteryInfo list once per snapshot; returns null when unavailable. */
    static List<Object> batteryList() {
        int[] values = battery;
        if (values == null) return null;
        String key = Arrays.toString(values);
        List<Object> cached = cachedList;
        if (cached != null && key.equals(cachedKey)) return cached;
        ClassLoader loader = hostLoader;
        if (loader == null) loader = OfficialBatteryOverride.class.getClassLoader();
        try {
            Class<?> helper = Class.forName(
                    "com.heytap.mydevices.plugin.airpods.core.a", false, loader);
            for (Method method : helper.getMethods()) {
                if (!method.getName().equals("f") || method.getParameterCount() != 2
                        || method.getParameterTypes()[0] != int.class
                        || method.getParameterTypes()[1] != int[].class
                        || !List.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                Object result = method.invoke(null, 2001, values);
                if (!(result instanceof List)) return null;
                List<Object> cleaned = new ArrayList<>();
                for (Object item : (List<?>) result) {
                    if (item != null) cleaned.add(item);
                }
                if (cleaned.isEmpty()) return null;
                synchronized (OfficialBatteryOverride.class) {
                    if (Arrays.equals(values, battery)) {
                        cachedKey = key;
                        cachedList = cleaned;
                    }
                }
                return cleaned;
            }
        } catch (Throwable error) {
            Logx.w("official battery list build failed", error);
        }
        return null;
    }

    /** Refreshes from the persisted cards-process snapshot when the tile asks for its data. */
    static void refresh(Context context) {
        if (context == null) return;
        String payload = CrossProcessState.batteryPayload(context);
        if (payload == null) return;
        int separator = payload.indexOf('|');
        if (separator <= 0 || separator >= payload.length() - 1) return;
        String mac = payload.substring(0, separator).trim();
        String[] parts = payload.substring(separator + 1).split(",");
        if (mac.isEmpty() || parts.length != 6) return;
        int[] values = new int[6];
        try {
            for (int index = 0; index < values.length; index++) {
                values[index] = Integer.parseInt(parts[index]);
            }
        } catch (NumberFormatException ignored) {
            return;
        }
        if (values[0] < 0 && values[1] < 0 && values[2] < 0) return;
        set(mac, values);
    }
}
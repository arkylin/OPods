package io.github.oppoairpods.popup;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/** Small file-backed state shared by the main and :cards MyDevices processes. */
final class CrossProcessState {
    private static final String FILE_NAME = "oppo_airpods_state.properties";
    private static final String AUTO_CONNECT = "auto_connect";
    private static final String SETTINGS_OPEN = "settings_open";
    private static final String SETTINGS_OPEN_AT = "settings_open_at";
    private static final String BATTERY = "battery";
    private static final String BATTERY_ADDRESS = "battery_address";
    private static final String BATTERY_AT = "battery_at";
    private static final long SETTINGS_VISIBLE_MS = 10L * 60L * 1000L;
    private static final long BATTERY_VISIBLE_MS = 12L * 60L * 60L * 1000L;
    private static final Object LOCK = new Object();

    private CrossProcessState() {}

    static boolean autoConnect(Context context) {
        String value = read(context, AUTO_CONNECT);
        if (value != null) return "1".equals(value);
        SharedPreferences prefs = context.getSharedPreferences("oppo_airpods_enhanced", Context.MODE_PRIVATE);
        return prefs.getBoolean("auto_connect", true);
    }

    static void setAutoConnect(Context context, boolean enabled) {
        context.getSharedPreferences("oppo_airpods_enhanced", Context.MODE_PRIVATE)
                .edit().putBoolean("auto_connect", enabled).apply();
        write(context, AUTO_CONNECT, enabled ? "1" : "0");
    }

    static boolean settingsOpen(Context context) {
        String value = read(context, SETTINGS_OPEN);
        if (!"1".equals(value)) return false;
        String timestamp = read(context, SETTINGS_OPEN_AT);
        try {
            return timestamp != null
                    && System.currentTimeMillis() - Long.parseLong(timestamp) < SETTINGS_VISIBLE_MS;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    static void setSettingsOpen(Context context, boolean open) {
        synchronized (LOCK) {
            Properties values = load(context);
            values.setProperty(SETTINGS_OPEN, open ? "1" : "0");
            values.setProperty(SETTINGS_OPEN_AT, String.valueOf(System.currentTimeMillis()));
            save(context, values);
        }
    }

    static void setBattery(Context context, String address, int[] battery) {
        if (address == null || address.isEmpty() || battery == null || battery.length < 6) return;
        synchronized (LOCK) {
            Properties values = load(context);
            values.setProperty(BATTERY_ADDRESS, address);
            values.setProperty(BATTERY, battery[0] + "," + battery[1] + "," + battery[2]
                    + "," + battery[3] + "," + battery[4] + "," + battery[5]);
            values.setProperty(BATTERY_AT, String.valueOf(System.currentTimeMillis()));
            save(context, values);
        }
    }

    static String batteryAddress(Context context) {
        return read(context, BATTERY_ADDRESS);
    }

    static int[] battery(Context context) {
        synchronized (LOCK) {
            Properties values = load(context);
            String value = values.getProperty(BATTERY);
            String timestamp = values.getProperty(BATTERY_AT);
            if (value == null || value.isEmpty() || timestamp == null) return null;
            try {
                if (System.currentTimeMillis() - Long.parseLong(timestamp) >= BATTERY_VISIBLE_MS) {
                    return null;
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
            String[] parts = value.split(",");
            if (parts.length != 6) return null;
            int[] parsed = new int[6];
            try {
                for (int index = 0; index < parsed.length; index++) {
                    parsed[index] = Integer.parseInt(parts[index]);
                }
                return parsed;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    static String batteryPayload(Context context) {
        synchronized (LOCK) {
            Properties values = load(context);
            String value = values.getProperty(BATTERY);
            String address = values.getProperty(BATTERY_ADDRESS);
            String timestamp = values.getProperty(BATTERY_AT);
            if (value == null || value.isEmpty() || address == null || address.isEmpty()
                    || timestamp == null) return null;
            try {
                if (System.currentTimeMillis() - Long.parseLong(timestamp) >= BATTERY_VISIBLE_MS) {
                    return null;
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
            return address + "|" + value;
        }
    }

    private static String read(Context context, String key) {
        synchronized (LOCK) {
            return load(context).getProperty(key);
        }
    }

    private static void write(Context context, String key, String value) {
        synchronized (LOCK) {
            Properties values = load(context);
            values.setProperty(key, value);
            save(context, values);
        }
    }

    private static Properties load(Context context) {
        Properties values = new Properties();
        File file = file(context);
        if (!file.exists()) return values;
        try (FileInputStream input = new FileInputStream(file)) {
            values.load(input);
        } catch (Throwable ignored) {
        }
        return values;
    }

    private static void save(Context context, Properties values) {
        File target = file(context);
        File temp = new File(target.getParentFile(), FILE_NAME + "." + Process.myPid() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp, false)) {
            values.store(output, null);
            output.getFD().sync();
        } catch (Throwable error) {
            Logx.w("cross-process state write failed", error);
            return;
        }
        if (!temp.renameTo(target)) {
            try (FileOutputStream output = new FileOutputStream(target, false)) {
                values.store(output, null);
            } catch (Throwable error) {
                Logx.w("cross-process state replace failed", error);
            }
        }
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }
}

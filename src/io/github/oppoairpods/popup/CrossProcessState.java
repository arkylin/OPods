package io.github.oppoairpods.popup;

import android.content.Context;
import android.content.SharedPreferences;

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
    private static final long SETTINGS_VISIBLE_MS = 10L * 60L * 1000L;
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
        try (FileOutputStream output = new FileOutputStream(file(context), false)) {
            values.store(output, null);
        } catch (Throwable error) {
            Logx.w("cross-process state write failed", error);
        }
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }
}

package io.github.oppoairpods.popup;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Small file-backed state shared by the main and :cards MyDevices processes. */
final class CrossProcessState {
    private static final String FILE_NAME = "oppo_airpods_state.properties";
    private static final String AUTO_CONNECT = "auto_connect";
    private static final String LID_FALLBACK = "lid_open_unverified_fallback";
    private static final String LAST_ADVERT = "last_lid_advert";
    private static final String LAST_CONNECT = "last_lid_connect";
    private static final String ACCESSORY_READY = "accessory_lid_filter_ready_at";
    private static final String IRK_PREFIX = "irk_";
    private static final String ENC_PREFIX = "enc_";
    private static final String SETTINGS_OPEN = "settings_open";
    private static final String SETTINGS_OPEN_AT = "settings_open_at";
    private static final String BATTERY = "battery";
    private static final String BATTERY_ADDRESS = "battery_address";
    private static final String BATTERY_AT = "battery_at";
    private static final long SETTINGS_VISIBLE_MS = 10L * 60L * 1000L;
    private static final long BATTERY_VISIBLE_MS = 12L * 60L * 60L * 1000L;
    private static final Object LOCK = new Object();

    private CrossProcessState() {}

    /** Proximity keys the AirPods handed out over AACP, kept per pair. */
    static final class Keys {
        final String address;
        final byte[] irk;
        final byte[] enc;

        Keys(String address, byte[] irk, byte[] enc) {
            this.address = address;
            this.irk = irk;
            this.enc = enc;
        }
    }

    // Lid-open settings are read in whichever MyDevices process receives the PTC result, so
    // they live in this shared file rather than in one process's SharedPreferences.
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

    /** Whether lid-open adverts may trigger the popup while no pair has handed out its IRK yet. */
    static boolean lidOpenFallback(Context context) {
        String value = read(context, LID_FALLBACK);
        return value == null || "1".equals(value);
    }

    static void setLidOpenFallback(Context context, boolean enabled) {
        write(context, LID_FALLBACK, enabled ? "1" : "0");
    }

    static void setLastAdvert(Context context, String summary) {
        write(context, LAST_ADVERT, System.currentTimeMillis() + "|" + summary);
    }

    static String lastAdvert(Context context) {
        return read(context, LAST_ADVERT);
    }

    /** Starts the trace of one official connect request ("time|step"), shown on the panel. */
    static void setLastConnect(Context context, String step) {
        write(context, LAST_CONNECT, System.currentTimeMillis() + "|" + step);
    }

    /**
     * Adds a later step (popup / official state) to a trace started within {@code windowMs};
     * a step equal to the last one is not repeated.
     */
    static void appendLastConnect(Context context, String step, long windowMs) {
        synchronized (LOCK) {
            Properties values = load(context);
            String current = values.getProperty(LAST_CONNECT);
            if (current == null || current.endsWith(step)) return;
            int separator = current.indexOf('|');
            try {
                long at = Long.parseLong(current.substring(0, separator));
                if (System.currentTimeMillis() - at > windowMs) return;
            } catch (RuntimeException ignored) {
                return;
            }
            values.setProperty(LAST_CONNECT, current + " → " + step);
            save(context, values);
        }
    }

    static String lastConnect(Context context) {
        return read(context, LAST_CONNECT);
    }

    /** When the Accessory scan last reported carrying the lid filter (0 when never). */
    static long accessoryReadyAt(Context context) {
        try {
            String value = read(context, ACCESSORY_READY);
            return value == null ? 0L : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    static void setAccessoryReady(Context context, long at) {
        write(context, ACCESSORY_READY, String.valueOf(at));
    }

    static void setProximityKeys(Context context, String address, byte[] irk, byte[] enc) {
        if (address == null || address.isEmpty()) return;
        String key = address.toUpperCase(Locale.ROOT);
        synchronized (LOCK) {
            Properties values = load(context);
            if (irk != null) values.setProperty(IRK_PREFIX + key, Base64.getEncoder().encodeToString(irk));
            if (enc != null) values.setProperty(ENC_PREFIX + key, Base64.getEncoder().encodeToString(enc));
            save(context, values);
        }
    }

    static Keys proximityKeys(Context context, String address) {
        if (address == null || address.isEmpty()) return null;
        return allProximityKeys(context).get(address.toUpperCase(Locale.ROOT));
    }

    static Map<String, Keys> allProximityKeys(Context context) {
        Map<String, Keys> result = new LinkedHashMap<>();
        synchronized (LOCK) {
            Properties values = load(context);
            for (String name : values.stringPropertyNames()) {
                if (!name.startsWith(IRK_PREFIX) && !name.startsWith(ENC_PREFIX)) continue;
                String address = name.substring(name.indexOf('_') + 1);
                if (result.containsKey(address)) continue;
                result.put(address, new Keys(address,
                        decode(values.getProperty(IRK_PREFIX + address)),
                        decode(values.getProperty(ENC_PREFIX + address))));
            }
        }
        return result;
    }

    private static byte[] decode(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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

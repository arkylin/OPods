package io.github.oppoairpods.popup;

import android.app.Application;
import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class Logx {
    static final String TAG = "OPPOAirPods";
    private static final Object LOCK = new Object();
    private static volatile File file;
    private static final String PROCESS = processName();

    private Logx() {}

    static void setContext(Context context) {
        if (context == null || file != null) return;
        synchronized (LOCK) {
            if (file != null) return;
            try {
                File dir = context.getExternalFilesDir("oppo_airpods");
                if (dir == null) dir = new File(context.getFilesDir(), "oppo_airpods");
                if (!dir.exists()) dir.mkdirs();
                file = new File(dir, "debug.log");
            } catch (Throwable ignored) {
            }
        }
    }

    static void i(String message) {
        Log.i(TAG, message);
        write("I", message, null);
    }

    static void w(String message, Throwable error) {
        Log.w(TAG, message, error);
        write("W", message, error);
    }

    static void e(String message, Throwable error) {
        Log.e(TAG, message, error);
        write("E", message, error);
    }

    private static void write(String level, String message, Throwable error) {
        File target = file;
        if (target == null) return;
        synchronized (LOCK) {
            try {
                if (target.length() > 512L * 1024L) {
                    File old = new File(target.getParentFile(), "debug.log.1");
                    if (old.exists()) old.delete();
                    target.renameTo(old);
                }
                String time = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
                StringBuilder line = new StringBuilder();
                line.append(time).append(' ').append(level).append(" [")
                        .append(PROCESS).append('/').append(Process.myPid()).append("] ")
                        .append(message).append('\n');
                if (error != null) {
                    line.append(error.getClass().getName()).append(": ").append(error.getMessage()).append('\n');
                    StackTraceElement[] trace = error.getStackTrace();
                    for (int i = 0; i < Math.min(12, trace.length); i++) {
                        line.append("  at ").append(trace[i]).append('\n');
                    }
                }
                FileOutputStream output = new FileOutputStream(target, true);
                try {
                    output.write(line.toString().getBytes(StandardCharsets.UTF_8));
                } finally {
                    output.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static String processName() {
        try {
            String name = Application.getProcessName();
            if (name != null && !name.isEmpty()) return name;
        } catch (Throwable ignored) {
        }
        return "unknown";
    }
}

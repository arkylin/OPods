package io.github.oppoairpods.popup;

import java.io.FilterOutputStream;
import java.io.OutputStream;

import io.github.libxposed.api.XposedModule;

/** Drops the ColorOS frame that disables AirPods "Off" mode on connect. */
final class AapOffFilter {
    private static final byte[] DISABLE_OFF = {
            0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x34, 0x02, 0x00, 0x00, 0x00
    };

    private AapOffFilter() {}

    static void install(XposedModule module, ClassLoader loader) {
        try {
            Class<?> socket = Class.forName("android.bluetooth.BluetoothSocket", false, loader);
            java.lang.reflect.Method getOutputStream = socket.getDeclaredMethod("getOutputStream");
            module.hook(getOutputStream).intercept(chain -> {
                Object result = chain.proceed();
                if (result instanceof OutputStream) {
                    return new BlockingOutputStream((OutputStream) result);
                }
                return result;
            });
            Logx.i("AirPods Off AAP filter installed");
        } catch (Throwable error) {
            Logx.w("AirPods Off AAP filter failed", error);
        }
    }

    private static final class BlockingOutputStream extends FilterOutputStream {
        BlockingOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void write(byte[] data) throws java.io.IOException {
            write(data, 0, data == null ? 0 : data.length);
        }

        @Override
        public void write(byte[] data, int offset, int length) throws java.io.IOException {
            if (isDisableOff(data, offset, length)) {
                Logx.i("blocked ColorOS DISABLE_OFF packet");
                return;
            }
            out.write(data, offset, length);
        }

        @Override
        public void write(int value) throws java.io.IOException {
            out.write(value);
        }
    }

    private static boolean isDisableOff(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length != DISABLE_OFF.length || offset + length > data.length) return false;
        for (int i = 0; i < DISABLE_OFF.length; i++) {
            if (data[offset + i] != DISABLE_OFF[i]) return false;
        }
        return true;
    }
}
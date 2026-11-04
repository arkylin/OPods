package io.github.oppoairpods.popup;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.ParcelUuid;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One AACP control channel. It runs as a daemon thread inside the official MyDevices process. */
final class AacpSession {
    interface Listener {
        void onOpened();

        void onEvent(AacpProtocol.Event event);

        void onClosed();
    }

    private static final UUID AACP_UUID = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a");
    private static final ParcelUuid AACP_PARCEL_UUID = new ParcelUuid(AACP_UUID);
    private static final AtomicInteger NEXT_SESSION = new AtomicInteger(1);

    private final Context context;
    private final String address;
    private final Listener listener;
    private final int sessionId = NEXT_SESSION.getAndIncrement();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object writeLock = new Object();
    private volatile BluetoothSocket socket;
    private volatile Thread thread;
    private volatile long lastReceiveAt;

    AacpSession(Context context, String address, Listener listener) {
        this.context = context.getApplicationContext() == null ? context : context.getApplicationContext();
        this.address = address;
        this.listener = listener;
    }

    void start() {
        if (!running.compareAndSet(false, true)) return;
        Thread worker = new Thread(this::loop, "OPPO-AirPods-AACP");
        worker.setDaemon(true);
        thread = worker;
        worker.start();
    }

    void stop() {
        running.set(false);
        closeSocket();
        Thread worker = thread;
        if (worker != null) worker.interrupt();
    }

    boolean isRunning() {
        return running.get();
    }

    boolean ready() {
        return running.get() && socket != null && lastReceiveAt > 0L;
    }

    boolean hadData() {
        return lastReceiveAt > 0L;
    }

    int sessionId() {
        return sessionId;
    }

    boolean sendControl(int id, int... values) {
        return write(AacpProtocol.control(id, values));
    }

    boolean sendEqualizer(int enabled, int low, int mid, int high) {
        return write(AacpProtocol.equalizer(enabled, low, mid, high));
    }

    boolean sendRename(String name) {
        return write(AacpProtocol.rename(name));
    }

    boolean sendHeadTracking(boolean start, boolean alternate) {
        return write(AacpProtocol.headTracking(start, alternate));
    }

    boolean sendPacket(byte[] packet) {
        return write(packet);
    }

    boolean write(byte[] packet) {
        BluetoothSocket channel = socket;
        if (channel == null) {
            Logx.i("AACP tx #" + sessionId + " skipped: channel not open " + hex(packet));
            return false;
        }
        try {
            synchronized (writeLock) {
                OutputStream output = channel.getOutputStream();
                output.write(packet);
                output.flush();
            }
            Logx.i("AACP tx #" + sessionId + " " + hex(packet));
            return true;
        } catch (Throwable error) {
            Logx.w("AACP tx #" + sessionId + " failed " + hex(packet), error);
            closeSocket();
            return false;
        }
    }

    private void loop() {
        int failures = 0;
        while (running.get()) {
            try {
                openAndRead();
                failures = 0;
            } catch (Throwable error) {
                if (running.get()) Logx.w("AACP channel #" + sessionId + " closed", error);
            } finally {
                closeSocket();
                if (running.get()) listener.onClosed();
            }
            if (!running.get()) break;
            failures++;
            long wait = Math.min(60000L, 2000L << Math.min(5, failures - 1));
            try {
                Thread.sleep(wait);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void openAndRead() throws Exception {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IOException("Bluetooth unavailable");
        BluetoothDevice device = adapter.getRemoteDevice(address);
        if (!isConnected(device)) {
            // Do not page a sleeping case; the controller restarts us on ACL/battery evidence.
            Thread.sleep(5000L);
            return;
        }
        BluetoothSocket channel = createSocket(adapter, device, AacpProtocol.PSM, AACP_PARCEL_UUID);
        lastReceiveAt = 0L;
        socket = channel;
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(12000L);
                if (socket == channel && !channel.isConnected()) channel.close();
            } catch (Throwable ignored) {
            }
        }, "OPPO-AirPods-AACP-connect-timeout");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            channel.connect();
        } finally {
            watchdog.interrupt();
        }
        if (!running.get()) return;
        sendInitialization(channel, "initial");
        listener.onOpened();
        Logx.i("AACP channel #" + sessionId + " opened");
        scheduleInitializationRetry(channel);
        startSilenceWatchdog(channel);
        AacpProtocol.Framer framer = new AacpProtocol.Framer();
        InputStream input = channel.getInputStream();
        byte[] buffer = new byte[8192];
        while (running.get()) {
            int count = input.read(buffer);
            if (count <= 0) throw new IOException("AACP stream ended");
            byte[] raw = java.util.Arrays.copyOf(buffer, count);
            logIncoming(raw);
            if (AacpProtocol.isHeadTracking(raw)) {
                AacpProtocol.Event head = AacpProtocol.parse(raw);
                if (head != null) {
                    lastReceiveAt = System.currentTimeMillis();
                    listener.onEvent(head);
                }
                continue;
            }
            List<byte[]> packets = framer.feed(raw);
            for (byte[] packet : packets) {
                AacpProtocol.Event event = AacpProtocol.parse(packet);
                if (event != null) {
                    lastReceiveAt = System.currentTimeMillis();
                    listener.onEvent(event);
                }
            }
        }
    }

    private void sendInitialization(BluetoothSocket channel, String phase) throws Exception {
        synchronized (writeLock) {
            if (!running.get() || socket != channel) return;
            OutputStream output = channel.getOutputStream();
            output.write(AacpProtocol.HANDSHAKE);
            output.flush();
            Thread.sleep(180L);
            output.write(AacpProtocol.features());
            output.flush();
            Thread.sleep(180L);
            output.write(AacpProtocol.notifications());
            output.flush();
            Thread.sleep(120L);
            output.write(AacpProtocol.keyRequest(1));
            output.write(AacpProtocol.keyRequest(4));
            output.flush();
        }
        Logx.i("AACP init #" + sessionId + " " + phase);
    }

    private void scheduleInitializationRetry(BluetoothSocket channel) {
        Thread retry = new Thread(() -> {
            try {
                Thread.sleep(600L);
                sendInitialization(channel, "retry-1");
                Thread.sleep(4400L);
                sendInitialization(channel, "retry-2");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable ignored) {
            }
        }, "OPPO-AirPods-AACP-init");
        retry.setDaemon(true);
        retry.start();
    }

    private void startSilenceWatchdog(BluetoothSocket channel) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(6500L);
                if (running.get() && socket == channel && lastReceiveAt == 0L) {
                    Logx.w("AACP channel #" + sessionId + " silent; recycling", null);
                    closeSocket();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "OPPO-AirPods-AACP-silence");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void logIncoming(byte[] data) {
        if (data == null || data.length < 5 || data[0] != 4 || data[1] != 0
                || data[2] != 4 || data[3] != 0) return;
        int opcode = data[4] & 0xFF;
        if (opcode == 0x06 || opcode == 0x09 || opcode == 0x19 || opcode == 0x1D) {
            Logx.i("AACP rx #" + sessionId + " op=0x" + Integer.toHexString(opcode)
                    + " len=" + data.length);
        }
    }

    private static String hex(byte[] data) {
        if (data == null) return "null";
        StringBuilder value = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) value.append(' ');
            String part = Integer.toHexString(data[i] & 0xFF).toUpperCase(java.util.Locale.ROOT);
            if (part.length() == 1) value.append('0');
            value.append(part);
        }
        return value.toString();
    }

    private static boolean isConnected(BluetoothDevice device) {
        try {
            Object value = BluetoothDevice.class.getMethod("isConnected").invoke(device);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            return true;
        }
    }

    static BluetoothSocket createSocket(BluetoothAdapter adapter, BluetoothDevice device, int psm, ParcelUuid uuid) throws Exception {
        Object[][] attempts = new Object[][]{
                {adapter, device, 3, true, true, psm, uuid},
                {device, 3, true, true, psm, uuid},
                {device, 3, 1, true, true, psm, uuid},
                {3, 1, true, true, device, psm, uuid},
                {3, true, true, device, psm, uuid}
        };
        Throwable last = null;
        for (Object[] values : attempts) {
            try {
                Class<?>[] types = new Class<?>[values.length];
                for (int i = 0; i < values.length; i++) {
                    Object value = values[i];
                    types[i] = value instanceof Integer ? Integer.TYPE
                            : value instanceof Boolean ? Boolean.TYPE
                            : value.getClass();
                }
                Constructor<BluetoothSocket> constructor = BluetoothSocket.class.getDeclaredConstructor(types);
                constructor.setAccessible(true);
                return constructor.newInstance(values);
            } catch (Throwable error) {
                last = error;
            }
        }
        throw new IOException("No compatible BluetoothSocket constructor", last);
    }

    private void closeSocket() {
        BluetoothSocket channel = socket;
        socket = null;
        if (channel != null) {
            try {
                channel.close();
            } catch (Throwable ignored) {
            }
        }
    }
}


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
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Raw ATT bearer on Apple's fixed PSM 31. It runs only inside the official process. */
final class AttSession {
    interface Listener {
        void onAttConnected();

        void onAttDisconnected();

        void onTransparency(byte[] data);

        void onHearingAid(byte[] data);

        void onLoudSoundReduction(byte[] data);
    }

    private static final ParcelUuid APPLE_ATT_UUID =
            ParcelUuid.fromString("00000000-0000-0000-0000-000000000000");
    private static final int PSM = 31;

    private final Context context;
    private final String address;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object writeLock = new Object();
    private final ConcurrentHashMap<Integer, LinkedBlockingQueue<byte[]>> responses = new ConcurrentHashMap<>();
    private final ExecutorService requests = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "OPPO-AirPods-ATT-request");
        thread.setDaemon(true);
        return thread;
    });
    private volatile BluetoothSocket socket;
    private volatile Thread reader;
    private volatile boolean connected;
    private volatile long lastReceiveAt;

    AttSession(Context context, String address, Listener listener) {
        this.context = context.getApplicationContext() == null ? context : context.getApplicationContext();
        this.address = address;
        this.listener = listener;
    }

    void start() {
        if (!running.compareAndSet(false, true)) return;
        Thread thread = new Thread(this::loop, "OPPO-AirPods-ATT");
        thread.setDaemon(true);
        reader = thread;
        thread.start();
    }

    void stop() {
        running.set(false);
        connected = false;
        closeSocket();
        Thread thread = reader;
        if (thread != null) thread.interrupt();
        requests.shutdownNow();
    }

    boolean isRunning() {
        return running.get();
    }

    boolean isConnected() {
        return connected;
    }

    void readAll() {
        submit(() -> {
            readHandle(AirPodsAttData.HANDLE_TRANSPARENCY, listener::onTransparency);
            readHandle(AirPodsAttData.HANDLE_LOUD_SOUND_REDUCTION, listener::onLoudSoundReduction);
            readHandle(AirPodsAttData.HANDLE_HEARING_AID, listener::onHearingAid);
        });
    }

    boolean writeTransparency(byte[] data) {
        return writeAsync(AirPodsAttData.HANDLE_TRANSPARENCY, data);
    }

    boolean writeHearingAid(byte[] data) {
        return writeAsync(AirPodsAttData.HANDLE_HEARING_AID, data);
    }

    boolean writeLoudSoundReduction(boolean enabled) {
        return writeAsync(AirPodsAttData.HANDLE_LOUD_SOUND_REDUCTION, new byte[]{(byte) (enabled ? 1 : 0)});
    }

    /** False means nothing was queued: the bearer is down, so the caller must not assume success. */
    private boolean writeAsync(int handle, byte[] data) {
        if (!connected) return false;
        return submit(() -> writeHandle(handle, data));
    }

    private boolean submit(Runnable task) {
        if (!running.get()) return false;
        try {
            requests.execute(task);
            return true;
        } catch (RejectedExecutionException stopped) {
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
                if (running.get()) Logx.w("ATT channel closed", error);
            } finally {
                connected = false;
                closeSocket();
                if (running.get()) listener.onAttDisconnected();
            }
            if (!running.get()) break;
            failures++;
            long wait = Math.min(30000L, 1200L << Math.min(5, failures - 1));
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
            Thread.sleep(1500L);
            return;
        }
        BluetoothSocket channel = AacpSession.createSocket(adapter, device, PSM, APPLE_ATT_UUID);
        socket = channel;
        Thread connectTimeout = new Thread(() -> {
            try {
                Thread.sleep(8000L);
                if (socket == channel && !connected) {
                    Logx.w("ATT connect timeout; recycling", null);
                    closeSocket();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "OPPO-AirPods-ATT-connect-timeout");
        connectTimeout.setDaemon(true);
        connectTimeout.start();
        try {
            channel.connect();
        } finally {
            connectTimeout.interrupt();
        }
        if (!running.get()) return;
        lastReceiveAt = 0L;
        connected = true;
        listener.onAttConnected();
        startSilenceWatchdog(channel);
        InputStream input = channel.getInputStream();
        requests.execute(() -> {
            try {
                Thread.sleep(120L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running.get() || socket != channel) return;
            writeHandle(AirPodsAttData.CCCD_TRANSPARENCY, new byte[]{1});
            writeHandle(AirPodsAttData.CCCD_HEARING_AID, new byte[]{1});
            readHandle(AirPodsAttData.HANDLE_TRANSPARENCY, listener::onTransparency);
            readHandle(AirPodsAttData.HANDLE_LOUD_SOUND_REDUCTION, listener::onLoudSoundReduction);
            readHandle(AirPodsAttData.HANDLE_HEARING_AID, listener::onHearingAid);
        });
        byte[] buffer = new byte[1024];
        while (running.get()) {
            int count = input.read(buffer);
            if (count <= 0) throw new IOException("ATT stream ended");
            handlePdu(Arrays.copyOf(buffer, count));
        }
    }

    private void handlePdu(byte[] pdu) {
        if (pdu.length < 1) return;
        lastReceiveAt = System.currentTimeMillis();
        int opcode = pdu[0] & 0xFF;
        if (opcode == 0x1B && pdu.length >= 3) {
            int handle = (pdu[1] & 0xFF) | ((pdu[2] & 0xFF) << 8);
            byte[] value = pdu.length > 3 ? Arrays.copyOfRange(pdu, 3, pdu.length) : new byte[0];
            if (handle == AirPodsAttData.HANDLE_TRANSPARENCY) listener.onTransparency(value);
            else if (handle == AirPodsAttData.HANDLE_HEARING_AID) listener.onHearingAid(value);
            else if (handle == AirPodsAttData.HANDLE_LOUD_SOUND_REDUCTION) listener.onLoudSoundReduction(value);
            return;
        }
        queue(opcode).offer(pdu);
    }

    private void startSilenceWatchdog(BluetoothSocket channel) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(8000L);
                if (running.get() && socket == channel && lastReceiveAt == 0L) {
                    Logx.w("ATT channel silent; recycling", null);
                    closeSocket();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "OPPO-AirPods-ATT-silence");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void readHandle(int handle, ValueConsumer consumer) {
        byte[] response = request(0x0A, new byte[]{0x0A, (byte) handle, 0}, 0x0B);
        if (response != null && response.length > 1 && running.get()) {
            consumer.accept(Arrays.copyOfRange(response, 1, response.length));
        }
    }

    private void writeHandle(int handle, byte[] value) {
        byte[] pdu = new byte[3 + value.length];
        pdu[0] = 0x12;
        pdu[1] = (byte) handle;
        pdu[2] = 0;
        System.arraycopy(value, 0, pdu, 3, value.length);
        if (request(0x12, pdu, 0x13) == null && running.get()) {
            Logx.w("ATT write not acknowledged handle=0x" + Integer.toHexString(handle), null);
        }
    }

    private byte[] request(int requestOpcode, byte[] pdu, int responseOpcode) {
        BluetoothSocket channel = socket;
        if (channel == null) return null;
        try {
            LinkedBlockingQueue<byte[]> queue = queue(responseOpcode);
            queue.clear();
            synchronized (writeLock) {
                OutputStream output = channel.getOutputStream();
                output.write(pdu);
                output.flush();
            }
            return queue.poll(1800L, TimeUnit.MILLISECONDS);
        } catch (Throwable error) {
            Logx.w("ATT request failed opcode=" + requestOpcode, error);
            return null;
        }
    }

    private LinkedBlockingQueue<byte[]> queue(int opcode) {
        return responses.computeIfAbsent(opcode, key -> new LinkedBlockingQueue<>());
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

    private static boolean isConnected(BluetoothDevice device) {
        try {
            Object value = BluetoothDevice.class.getMethod("isConnected").invoke(device);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private interface ValueConsumer {
        void accept(byte[] value);
    }
}

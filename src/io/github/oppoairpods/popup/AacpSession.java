package io.github.oppoairpods.popup;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One AACP control channel. It runs as a daemon thread inside the official MyDevices process. */
final class AacpSession {
    interface Listener {
        void onOpened();

        void onEvent(AacpProtocol.Event event);

        void onClosed(boolean linkDown);
    }

    private static final UUID AACP_UUID = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a");
    private static final ParcelUuid AACP_PARCEL_UUID = new ParcelUuid(AACP_UUID);
    private static final AtomicInteger NEXT_SESSION = new AtomicInteger(1);
    // Bounded warm-up retries to capture the first battery snapshot on a live ACL. Not a
    // background watchdog (see loop()).
    private static final int WARMUP_RETRY_LIMIT = 3;
    private static final long WARMUP_RETRY_DELAY_MS = 1200L;
    private static final long INIT_RETRY_1_MS = 1080L;
    private static final long INIT_RETRY_2_MS = 5960L;
    /** A control written before the pair acknowledged anything may be the last one it obeys. */
    private static final long INIT_GRACE_MS = 3000L;
    private static final long SILENCE_TIMEOUT_MS = 7000L;
    /** A silent link is re-handshaked once before it is dropped; see startSilenceWatchdog. */
    private static final long SILENCE_RECYCLE_MS = 14000L;

    private final Context context;
    private final String address;
    private final Listener listener;
    private final int sessionId = NEXT_SESSION.getAndIncrement();
    private final AtomicBoolean running = new AtomicBoolean(false);
    // The only thread that writes to the socket, so callers (often the main thread) never block
    // on Bluetooth I/O or on the pauses between initialization packets.
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread worker = new Thread(task, "OPPO-AirPods-AACP-tx");
        worker.setDaemon(true);
        return worker;
    });
    private volatile BluetoothSocket socket;
    private volatile Thread thread;
    private volatile long lastReceiveAt;
    /** The initial handshake went out on the current socket: controls may be written. */
    private volatile boolean handshakeSent;
    /** elapsedRealtime() when that handshake finished; bounds the blind-write grace period. */
    private volatile long initSentAt;
    private volatile boolean allowRetries;
    private volatile boolean requestProximityKeys;

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
        writer.shutdownNow();
        Thread worker = thread;
        if (worker != null) worker.interrupt();
    }

    boolean isRunning() {
        return running.get();
    }

    void setAllowRetries(boolean value) {
        allowRetries = value;
    }

    /** Ask for the IRK/ENC keys during initialization until they are stored for this pair. */
    void setRequestProximityKeys(boolean value) {
        requestProximityKeys = value;
    }

    boolean ready() {
        return running.get() && socket != null && lastReceiveAt > 0L;
    }

    /**
     * The channel can take a control packet right now. Weaker than {@link #ready()} only by a
     * bounded grace period: normally the packet waits for the pair's first report (the handshake
     * acknowledgement), and only when that report never comes within INIT_GRACE_MS do we write
     * blind. Writing straight after the handshake without the ack is what made the first noise
     * switch work and every later one die.
     */
    boolean canWrite() {
        BluetoothSocket channel = socket;
        if (!running.get() || channel == null || !channel.isConnected() || !handshakeSent) {
            return false;
        }
        return lastReceiveAt > 0L || SystemClock.elapsedRealtime() - initSentAt >= INIT_GRACE_MS;
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

    /** Queues the packet for the writer thread; false means the channel cannot take it right now. */
    boolean write(byte[] packet) {
        BluetoothSocket channel = socket;
        if (channel == null || !channel.isConnected()) {
            Logx.i("AACP tx #" + sessionId + " skipped: channel not open " + hex(packet));
            return false;
        }
        try {
            writer.execute(() -> writeNow(channel, packet));
            return true;
        } catch (RejectedExecutionException stopped) {
            return false;
        }
    }

    private void writeNow(BluetoothSocket channel, byte[] packet) {
        if (socket != channel) return;
        try {
            OutputStream output = channel.getOutputStream();
            output.write(packet);
            output.flush();
            Logx.i("AACP tx #" + sessionId + " " + hex(packet));
        } catch (Throwable error) {
            Logx.w("AACP tx #" + sessionId + " failed " + hex(packet), error);
            if (socket == channel) closeSocket();
        }
    }

    private void loop() {
        int failures = 0;
        int warmupRetries = 0;
        try {
            while (running.get()) {
                boolean linkDown = false;
                try {
                    openAndRead();
                } catch (LinkLostException lost) {
                    linkDown = true;
                    if (running.get()) Logx.i("AACP channel #" + sessionId + " ACL lost");
                } catch (Throwable error) {
                    if (running.get()) Logx.w("AACP channel #" + sessionId + " closed", error);
                }
                boolean gotData = lastReceiveAt > 0L;
                closeSocket();
                if (!running.get()) break;
                // Warm-up retry: the very first connect often opens the socket but goes silent
                // before the earbuds push the first battery/event packet. Retry a bounded number
                // of times *while the ACL is still up and this attempt got no data* so we at
                // least capture the initial battery snapshot. This is not a background reconnect
                // watchdog: without an official page open it never runs after data arrived, after
                // the ACL drops, or past the small budget; it only bridges the handshake gap.
                if (!linkDown && !gotData && warmupRetries < WARMUP_RETRY_LIMIT && aclUp()) {
                    warmupRetries++;
                    Logx.i("AACP channel #" + sessionId + " warm-up retry "
                            + warmupRetries + "/" + WARMUP_RETRY_LIMIT + " (no battery yet, ACL up)");
                    if (!pause(WARMUP_RETRY_DELAY_MS)) break;
                    continue;
                }
                boolean stopping = linkDown || !allowRetries;
                if (stopping && !linkDown) {
                    Logx.i("AACP channel #" + sessionId + " background retry disabled; stopping");
                }
                listener.onClosed(stopping);
                if (stopping) break;
                failures = gotData ? 1 : failures + 1;
                if (!pause(Math.min(60000L, 2000L << Math.min(5, failures - 1)))) break;
            }
        } finally {
            // A finished loop must never look alive, or ensureSession() would not replace it.
            running.set(false);
            writer.shutdownNow();
        }
    }

    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean aclUp() {
        try {
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) return false;
            return isConnected(adapter.getRemoteDevice(address));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void openAndRead() throws Exception {
        lastReceiveAt = 0L;
        handshakeSent = false;
        initSentAt = 0L;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IOException("Bluetooth unavailable");
        BluetoothDevice device = adapter.getRemoteDevice(address);
        if (!isConnected(device)) {
            throw new LinkLostException("ACL disconnected");
        }
        BluetoothSocket channel = createSocket(adapter, device, AacpProtocol.PSM, AACP_PARCEL_UUID);
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
        scheduleInitialization(channel);
        listener.onOpened();
        Logx.i("AACP channel #" + sessionId + " opened");
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
                if (head != null && running.get()) {
                    lastReceiveAt = System.currentTimeMillis();
                    listener.onEvent(head);
                }
                continue;
            }
            List<byte[]> packets = framer.feed(raw);
            for (byte[] packet : packets) {
                AacpProtocol.Event event = AacpProtocol.parse(packet);
                if (event != null && running.get()) {
                    lastReceiveAt = System.currentTimeMillis();
                    listener.onEvent(event);
                }
            }
        }
    }

    private void scheduleInitialization(BluetoothSocket channel) {
        writer.execute(() -> sendInitialization(channel, "initial"));
        writer.schedule(() -> retryInitialization(channel, "retry-1"),
                INIT_RETRY_1_MS, TimeUnit.MILLISECONDS);
        writer.schedule(() -> retryInitialization(channel, "retry-2"),
                INIT_RETRY_2_MS, TimeUnit.MILLISECONDS);
    }

    private void retryInitialization(BluetoothSocket channel, String phase) {
        if (lastReceiveAt > 0L) return;
        sendInitialization(channel, phase);
    }

    /** Runs on the writer thread; packets queued meanwhile simply wait behind it. */
    private void sendInitialization(BluetoothSocket channel, String phase) {
        if (!running.get() || socket != channel) return;
        try {
            OutputStream output = channel.getOutputStream();
            output.write(AacpProtocol.HANDSHAKE);
            output.flush();
            Thread.sleep(180L);
            output.write(AacpProtocol.features());
            output.flush();
            Thread.sleep(180L);
            output.write(AacpProtocol.notifications());
            initSentAt = SystemClock.elapsedRealtime();
            handshakeSent = true;
            output.flush();
            if (requestProximityKeys) {
                // Lid-open identity check needs the IRK; the ENC key decrypts advert battery.
                Thread.sleep(120L);
                output.write(AacpProtocol.keyRequest(AacpProtocol.KEY_IRK | AacpProtocol.KEY_ENC));
                output.flush();
            }
            Logx.i("AACP init #" + sessionId + " " + phase);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            if (running.get()) Logx.w("AACP init #" + sessionId + " " + phase + " failed", error);
            if (socket == channel) closeSocket();
        }
    }

    private void startSilenceWatchdog(BluetoothSocket channel) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(SILENCE_TIMEOUT_MS);
                if (!running.get() || socket != channel || lastReceiveAt > 0L) return;
                // Keep the L2CAP link instead of throwing it away: a first connect often opens the
                // socket and stays quiet for a few seconds, and every rebuild costs another connect
                // plus a fresh handshake (~7 s each). Two rebuilds are exactly the "noise control
                // does nothing for the first ~15 s after connecting" window.
                Logx.w("AACP channel #" + sessionId + " silent; re-sending handshake", null);
                try {
                    writer.execute(() -> sendInitialization(channel, "silence-retry"));
                } catch (Throwable ignored) {
                    return;
                }
                Thread.sleep(SILENCE_RECYCLE_MS - SILENCE_TIMEOUT_MS);
                if (running.get() && socket == channel && lastReceiveAt == 0L) {
                    Logx.w("AACP channel #" + sessionId + " still silent; recycling", null);
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

    private static final class LinkLostException extends IOException {
        LinkLostException(String message) {
            super(message);
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

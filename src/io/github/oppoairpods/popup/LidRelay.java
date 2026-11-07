package io.github.oppoairpods.popup;

import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lid-open path on the ROM's own persistent BLE scan, the one that already runs for OPPO's own
 * earbuds in the always-resident Accessory process while the screen is on.
 *
 * <p>Accessory side: one extra hardware filter that only passes AirPods proximity messages with
 * the lid open, and {@link LidGate} so a lid opening wakes MyDevices once instead of on every
 * repeat. The wake goes to MyDevices :cards through the official Accessory keep-alive binding
 * ({@code OafPersistentService}); :cards checks the pair with its IRK and opens the official popup.
 * No scanner, Service, Receiver, alarm or wakelock is added.
 */
final class LidRelay {
    static final String MYDEVICES = "com.heytap.mydevices";
    static final String OAF_SERVICE = "com.oplus.mydevices.keepalive.oaf.OafPersistentService";
    private static final String OAF_ACTION = "com.heytap.accessory.PERSISTENT_BIND";
    private static final String CORE_ACTION = "com.oplus.mydevices.CoreService";
    static final String EXTRA_DATA = "opods_lid_data";
    static final String EXTRA_ADDRESS = "opods_lid_address";
    static final String EXTRA_RSSI = "opods_lid_rssi";
    /** Readiness ping: wall-clock time the Accessory scan first carried the lid filter. */
    static final String EXTRA_READY = "opods_lid_ready";
    private static final int APPLE = 0x004C;
    /**
     * The binding only carries the wake and is dropped as soon as :cards has seen it; this bound
     * covers a cold :cards start that never answers.
     */
    private static final long UNBIND_TIMEOUT_MS = 10000L;

    private static final LidGate GATE = new LidGate();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicLong WAKES = new AtomicLong();
    private static final AtomicBoolean FILTER_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean LEGACY_LOGGED = new AtomicBoolean();
    private static volatile ScanFilter lidFilter;

    private LidRelay() {}

    private static ScanFilter lidFilter() {
        ScanFilter local = lidFilter;
        if (local == null) {
            local = new ScanFilter.Builder()
                    .setManufacturerData(APPLE, LidGate.pattern(), LidGate.mask())
                    .build();
            lidFilter = local;
        }
        return local;
    }

    /**
     * FusionProvider.d() result: the persistent filters the ROM merges into its screen-on scan
     * and matches every result against. Adds the lid filter and drops the two-byte Apple filters
     * 3.4.48/3.4.49 registered, which matched every AirPods message nearby.
     */
    @SuppressWarnings("unchecked")
    static void adjustPersistentFilters(List<?> filters, Context context) {
        List<Object> list = (List<Object>) filters;
        boolean present = false;
        for (Iterator<Object> it = list.iterator(); it.hasNext(); ) {
            Object item = it.next();
            if (!(item instanceof ScanFilter)) continue;
            ScanFilter filter = (ScanFilter) item;
            if (isLegacyBroadFilter(filter)) {
                it.remove();
                if (LEGACY_LOGGED.compareAndSet(false, true)) {
                    Logx.i("official persistent scan: dropped broad legacy AirPods filter");
                }
            } else if (filter.equals(lidFilter())) {
                present = true;
            }
        }
        if (!present) list.add(lidFilter());
        if (FILTER_LOGGED.compareAndSet(false, true)) {
            Logx.i("official persistent scan: AirPods lid-open filter added (hardware mask 07 19 + lid bit)");
            // Once per Accessory process: lets the MyDevices panel show that this side is live.
            if (context != null) {
                Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                Intent hello = new Intent(OAF_ACTION)
                        .setComponent(new ComponentName(MYDEVICES, OAF_SERVICE))
                        .setIdentifier("opods-ready-" + SystemClock.elapsedRealtimeNanos())
                        .putExtra(EXTRA_READY, System.currentTimeMillis());
                MAIN.post(() -> bind(app, hello));
            }
        }
    }

    private static boolean isLegacyBroadFilter(ScanFilter filter) {
        if (filter.getManufacturerId() != APPLE) return false;
        byte[] data = filter.getManufacturerData();
        return data != null && data.length == 2 && data[0] == 0x07 && (data[1] == 25 || data[1] == 15);
    }

    /**
     * A persistent-scan result reaching the ROM's FusionProvider output, with the persistent
     * filter the ROM matched it with. Results matched by the lid filter (or a leftover broad
     * legacy one) that carry an AirPods status message are handled here and return true: the
     * ROM has no use for them (its AirPods code ignores paired pairs on lid-open), so its
     * FusionWakeup is not run for each repeat. Anything else goes on to the ROM untouched.
     */
    static boolean offer(Context context, ScanResult result, Object matched) {
        if (result == null) return false;
        if (matched instanceof ScanFilter) {
            ScanFilter filter = (ScanFilter) matched;
            if (!filter.equals(lidFilter()) && !isLegacyBroadFilter(filter)) return false;
        }
        ScanRecord record = result.getScanRecord();
        byte[] data = record == null ? null : record.getManufacturerSpecificData(APPLE);
        String address = result.getDevice() == null ? null : result.getDevice().getAddress();
        LidGate.Verdict verdict = GATE.offer(address, result.getRssi(), data, SystemClock.elapsedRealtime());
        if (verdict == LidGate.Verdict.NOT_OURS) return false;
        if (verdict == LidGate.Verdict.WAKE && context != null) {
            Logx.i("official scan heard an AirPods lid opening rssi=" + result.getRssi()
                    + "; waking MyDevices :cards (#" + WAKES.incrementAndGet() + ")");
            Intent intent = new Intent(OAF_ACTION)
                    .setComponent(new ComponentName(MYDEVICES, OAF_SERVICE))
                    // A distinct identity gives every wake its own onBind, even next to the
                    // ROM's own keep-alive binding of the same service.
                    .setIdentifier("opods-lid-" + SystemClock.elapsedRealtimeNanos())
                    .putExtra(EXTRA_DATA, data)
                    .putExtra(EXTRA_ADDRESS, address)
                    .putExtra(EXTRA_RSSI, result.getRssi());
            Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            MAIN.post(() -> bind(app, intent));
        }
        return true;
    }

    private static void bind(Context context, Intent intent) {
        OneShot connection = new OneShot(context);
        boolean bound;
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Throwable error) {
            Logx.w("lid wake binding failed", error);
            bound = false;
        }
        if (!bound) {
            connection.release();
            wakeCore(context, intent);
            return;
        }
        MAIN.postDelayed(connection::release, UNBIND_TIMEOUT_MS);
    }

    /** Fallback when :cards cannot be bound: the ROM's own wake target, the MyDevices CoreService. */
    private static void wakeCore(Context context, Intent wake) {
        try {
            Intent intent = new Intent(CORE_ACTION).setPackage(MYDEVICES);
            if (wake.hasExtra(EXTRA_READY)) {
                intent.putExtra(EXTRA_READY, wake.getLongExtra(EXTRA_READY, 0L));
            } else {
                intent.putExtra(EXTRA_DATA, wake.getByteArrayExtra(EXTRA_DATA))
                        .putExtra(EXTRA_ADDRESS, wake.getStringExtra(EXTRA_ADDRESS))
                        .putExtra(EXTRA_RSSI, wake.getIntExtra(EXTRA_RSSI, 0));
            }
            context.startService(intent);
            Logx.i("lid wake sent to MyDevices CoreService instead");
        } catch (Throwable error) {
            Logx.w("lid wake could not reach MyDevices", error);
        }
    }

    private static final class OneShot implements ServiceConnection {
        private final Context context;
        private final AtomicBoolean released = new AtomicBoolean();

        OneShot(Context context) {
            this.context = context;
        }

        void release() {
            if (!released.compareAndSet(false, true)) return;
            try {
                context.unbindService(this);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // :cards ran onBind, which is where the wake was handled.
            release();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }

        @Override
        public void onBindingDied(ComponentName name) {
            release();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            release();
        }
    }

    /** MyDevices side (:cards, or CoreService as fallback): a wake built by {@link #offer}. */
    static boolean handleWake(Context context, Intent intent) {
        if (context == null || intent == null) return false;
        long readyAt;
        byte[] data;
        try {
            readyAt = intent.getLongExtra(EXTRA_READY, 0L);
            data = intent.getByteArrayExtra(EXTRA_DATA);
        } catch (Throwable ignored) {
            return false;
        }
        if (readyAt > 0L) {
            CrossProcessState.setAccessoryReady(context, readyAt);
            Logx.i("Accessory reports the lid-open filter in the official scan");
            return true;
        }
        if (data == null) return false;
        String address = intent.getStringExtra(EXTRA_ADDRESS);
        AppleAdvertisement.LidInfo info = AppleAdvertisement.parse(data);
        if (info == null) return true;
        Logx.i("lid wake from Accessory rssi=" + intent.getIntExtra(EXTRA_RSSI, 0) + " model=" + info.model);
        AirPodsController.get(context).onCaseAdvertisement(address, info);
        return true;
    }
}

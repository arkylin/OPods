package io.github.oppoairpods.popup;

import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the two official AirPods battery surfaces on the same AACP snapshot.
 * It only posts a UI refresh; it never opens a connection or starts a process.
 */
final class OfficialBatteryBridge {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Class<?>, Method> POPUP_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> DETAIL_METHODS = new ConcurrentHashMap<>();
    private static final AtomicBoolean APPLYING = new AtomicBoolean();
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean();
    private static final AtomicBoolean CLEAR_SCHEDULED = new AtomicBoolean();
    private static final AtomicBoolean POPUP_ERROR_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean DETAIL_ERROR_LOGGED = new AtomicBoolean();
    private static volatile WeakReference<Object> popup = new WeakReference<>(null);
    private static volatile WeakReference<Object> detail = new WeakReference<>(null);
    private static volatile String lastOfficialPopup = "";
    private static volatile String lastOfficialDetail = "";
    private static volatile String lastApplied = "";

    private OfficialBatteryBridge() {
    }

    /** True while this bridge itself repaints an official surface. */
    static boolean applying() {
        return APPLYING.get();
    }

    static void registerPopup(Object value) {
        if (value != null) popup = new WeakReference<>(value);
    }

    static void registerDetail(Object value) {
        if (value != null) detail = new WeakReference<>(value);
    }

    static void onOfficialPopup(Object value, int[] battery, AirPodsController controller) {
        if (APPLYING.get()) return;
        registerPopup(value);
        logOfficial("popup", battery, lastOfficialPopup, true);
        schedule(controller);
        OfficialDeviceStateBridge.schedule(controller);
    }

    static void onOfficialDetail(Object value, int[] battery, AirPodsController controller) {
        if (APPLYING.get()) return;
        registerDetail(value);
        logOfficial("detail", battery, lastOfficialDetail, false);
        schedule(controller);
        OfficialDeviceStateBridge.schedule(controller);
    }

    static void schedule(AirPodsController controller) {
        if (controller == null || APPLYING.get() || !controller.hasBatterySnapshot()) return;
        if (!SCHEDULED.compareAndSet(false, true)) return;
        MAIN.post(() -> {
            SCHEDULED.set(false);
            apply(controller);
        });
    }

    static void clear() {
        if (!CLEAR_SCHEDULED.compareAndSet(false, true)) return;
        MAIN.post(() -> {
            CLEAR_SCHEDULED.set(false);
            applyBattery(new int[]{-1, -1, -1, 4, 4, 4});
        });
    }

    private static void apply(AirPodsController controller) {
        if (!controller.hasBatterySnapshot()) return;
        applyBattery(controller.officialBatteryArray());
    }

    private static void applyBattery(int[] battery) {
        APPLYING.set(true);
        try {
            Object popupTarget = popup.get();
            if (popupTarget != null) {
                invoke(popupTarget, battery, false, POPUP_METHODS, POPUP_ERROR_LOGGED, "popup");
            }
            Object detailTarget = detail.get();
            if (detailTarget != null) {
                invoke(detailTarget, battery, true, DETAIL_METHODS, DETAIL_ERROR_LOGGED, "detail");
            }
        } finally {
            APPLYING.set(false);
        }
        String compact = Arrays.toString(battery);
        if (!compact.equals(lastApplied)) {
            lastApplied = compact;
            Logx.i("AACP battery -> official UI " + compact);
        }
    }

    private static void invoke(Object target, int[] battery, boolean detail,
                               Map<Class<?>, Method> cache, AtomicBoolean errorLogged,
                               String surface) {
        Method method = cache.get(target.getClass());
        if (method == null) {
            method = detail ? findDetailMethod(target.getClass()) : findPopupMethod(target.getClass());
            if (method != null) cache.put(target.getClass(), method);
        }
        if (method == null) {
            if (!errorLogged.getAndSet(true)) Logx.w("official battery " + surface + " method missing", null);
            return;
        }
        try {
            if (detail) method.invoke(target, battery, false);
            else method.invoke(target, (Object) battery);
        } catch (Throwable error) {
            if (!errorLogged.getAndSet(true)) Logx.w("official battery " + surface + " refresh failed", error);
        }
    }

    private static Method findPopupMethod(Class<?> type) {
        try {
            Method method = type.getDeclaredMethod("t0", int[].class);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
        }
        return findMethod(type, int[].class);
    }

    private static Method findDetailMethod(Class<?> type) {
        try {
            Method method = type.getDeclaredMethod("a", int[].class, boolean.class);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
        }
        return findMethod(type, int[].class, boolean.class);
    }

    private static Method findMethod(Class<?> type, Class<?>... parameters) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getReturnType() != void.class || method.getParameterCount() != parameters.length) continue;
            Class<?>[] actual = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < parameters.length; i++) {
                if (actual[i] != parameters[i]) {
                    matches = false;
                    break;
                }
            }
            if (!matches) continue;
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    private static void logOfficial(String surface, int[] battery, String previous, boolean popupSurface) {
        if (battery == null) return;
        String compact = Arrays.toString(battery);
        if (compact.equals(previous)) return;
        if (popupSurface) lastOfficialPopup = compact;
        else lastOfficialDetail = compact;
        Logx.i("official " + surface + " battery " + compact);
    }
}

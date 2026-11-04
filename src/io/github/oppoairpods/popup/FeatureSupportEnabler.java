package io.github.oppoairpods.popup;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** Makes the ROM's existing noise selector and sections usable for AirPods 5 / Pro 3. */
final class FeatureSupportEnabler {
    private static final Map<Object, AirPodsController.Listener> OBSERVERS = new WeakHashMap<>();

    private FeatureSupportEnabler() {}

    static void afterFeatureSupport(Object activity) {
        if (activity == null) return;
        new Handler(Looper.getMainLooper()).post(() -> apply(activity));
    }

    static void attach(Object activity) {
        if (!(activity instanceof Context)) return;
        AirPodsController controller = AirPodsController.get((Context) activity);
        synchronized (OBSERVERS) {
            if (OBSERVERS.containsKey(activity)) {
                syncNoiseMode(activity);
                return;
            }
            WeakReference<Object> target = new WeakReference<>(activity);
            AirPodsController.Listener listener = () -> {
                Object value = target.get();
                if (value != null) syncNoiseMode(value);
            };
            controller.addListener(listener);
            OBSERVERS.put(activity, listener);
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> syncNoiseMode(activity), 180L);
    }

    static void release(Object activity) {
        if (!(activity instanceof Context)) return;
        AirPodsController.Listener listener;
        synchronized (OBSERVERS) {
            listener = OBSERVERS.remove(activity);
        }
        if (listener != null) AirPodsController.get((Context) activity).removeListener(listener);
    }

    private static void apply(Object activity) {
        Logx.i("feature support apply: " + activity.getClass().getName());
        try {
            setField(activity, "supportsNoiseCancellation", true);
            setField(activity, "supportsAdaptiveNoise", true);
            setField(activity, "supportsTransparencyMode", true);
            setField(activity, "supportsNoiseOff", true);
            Object selector = call(activity, "getNoiseSelector");
            if (selector != null) {
                selector.getClass().getMethod("setVisibility", int.class).invoke(selector, 0);
                for (Method method : selector.getClass().getMethods()) {
                    if ("setSelectionGuard".equals(method.getName()) && method.getParameterCount() == 1) {
                        method.invoke(selector, new Object[]{null});
                        break;
                    }
                }
                Logx.i("official noise selector shown");
            }
            call(activity, "updateNoiseSelectorItems");
            attach(activity);
            Object viewModel = call(activity, "getViewModel");
            if (viewModel != null) {
                viewModel.getClass().getMethod("setIsSupportWearCheck", boolean.class).invoke(viewModel, true);
            }
            Object fragment = call(activity, "preferencesFragment");
            if (fragment != null) {
                call(fragment, "setSpatialSectionVisible", new Class<?>[]{boolean.class}, new Object[]{true});
                call(fragment, "setWearDetectionVisible", new Class<?>[]{boolean.class}, new Object[]{true});
                call(fragment, "setConversationVisible", new Class<?>[]{boolean.class}, new Object[]{true});
            }
            Logx.i("official feature support forced for AirPods");
        } catch (Throwable error) {
            Logx.w("feature support extension failed", error);
        }
    }

    private static void syncNoiseMode(Object activity) {
        if (!(activity instanceof Context)) return;
        try {
            AirPodsController controller = AirPodsController.get((Context) activity);
            int[] current = controller.control(AacpProtocol.CONTROL_LISTENING_MODE);
            if (current == null || current.length == 0 || current[0] < 1 || current[0] > 4) return;
            call(activity, "updateCurrentFeature", new Class<?>[]{int.class},
                    new Object[]{current[0]});
            Logx.i("official noise synced mode=" + current[0]);
        } catch (Throwable error) {
            Logx.w("official noise sync failed", error);
        }
    }

    private static void setField(Object target, String name, boolean value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.setBoolean(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
    }

    private static Object call(Object target, String name) throws Exception {
        return call(target, name, new Class<?>[0], new Object[0]);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object[] args) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(name);
    }
}

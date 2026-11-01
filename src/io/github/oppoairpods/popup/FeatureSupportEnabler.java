package io.github.oppoairpods.popup;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Makes the ROM's existing noise selector and sections usable for AirPods 5 / Pro 3. */
final class FeatureSupportEnabler {
    private FeatureSupportEnabler() {}

    static void afterFeatureSupport(Object activity) {
        if (activity == null) return;
        new Handler(Looper.getMainLooper()).post(() -> apply(activity));
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
                Logx.i("official noise selector shown");
            }
            call(activity, "updateNoiseSelectorItems");
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

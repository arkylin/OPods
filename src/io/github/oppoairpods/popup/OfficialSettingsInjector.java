package io.github.oppoairpods.popup;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Extends the existing MyDevices AirPods preference groups with official COUI rows. */
final class OfficialSettingsInjector {
    private static final Map<Object, Injection> INSTALLED = new WeakHashMap<>();

    private OfficialSettingsInjector() {}

    static void install(Object fragment) {
        if (fragment == null) return;
        if (!"com.heytap.mydevices.plugin.airpods.ui.AirpodsSettingPreferencesFragment"
                .equals(fragment.getClass().getName())) return;
        synchronized (INSTALLED) {
            Injection existing = INSTALLED.get(fragment);
            if (existing != null) {
                existing.refresh();
                return;
            }
        }
        try {
            Injection injection = new Injection(fragment);
            if (injection.inject()) {
                synchronized (INSTALLED) {
                    INSTALLED.put(fragment, injection);
                }
            }
        } catch (Throwable error) {
            Logx.e("official settings injection failed", error);
        }
    }

    private static final class Injection implements AirPodsController.Listener {
        private final WeakReference<Object> fragment;
        private final Context context;
        private final AirPodsController controller;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final List<Runnable> refreshers = new ArrayList<>();
        private Runnable refresh;

        Injection(Object fragment) throws Exception {
            this.fragment = new WeakReference<>(fragment);
            Object value = call(fragment, "getContext");
            if (value == null) value = call(fragment, "getActivity");
            this.context = (Context) value;
            if (context == null) throw new IllegalStateException("fragment context unavailable");
            this.controller = AirPodsController.get(context);
        }

        boolean inject() throws Exception {
            Object screen = call(fragment.get(), "getPreferenceScreen");
            Logx.i("MyDevices preference screen: " + (screen != null));
            if (screen == null) return false;
            BluetoothDevice device = resolveDevice();
            if (device == null) return false;
            if (!controller.isAirPods(device)) return false;
            controller.onSettingsPageOpened(device);
            controller.addListener(this);
            FeatureSupportEnabler.afterFeatureSupport(call(fragment.get(), "getActivity"));

            Object sound = find(screen, "cat_airpods_sound_quality");
            Object settings = find(screen, "cat_airpods_settings");
            if (sound == null) sound = screen;
            if (settings == null) settings = screen;

            try {
                addAdaptiveSlider(screen,
                        () -> 100 - controller.controlValue(AacpProtocol.CONTROL_ADAPTIVE_STRENGTH, 50),
                        value -> controller.sendControl(AacpProtocol.CONTROL_ADAPTIVE_STRENGTH, 100 - value));
            } catch (Throwable error) {
                Logx.w("adaptive slider unavailable", error);
            }

            addDashboard(settings);

            refresh = () -> {
                for (Runnable runnable : refreshers) {
                    try {
                        runnable.run();
                    } catch (Throwable ignored) {
                    }
                }
            };
            refresh();
            Logx.i("MyDevices controls injected into existing categories");
            return true;
        }

        private BluetoothDevice resolveDevice() {
            try {
                Object viewModel = call(fragment.get(), "getViewModel");
                if (viewModel != null) {
                    Object device = viewModel.getClass().getMethod("getBluetoothDevice").invoke(viewModel);
                    if (device instanceof BluetoothDevice) return (BluetoothDevice) device;
                }
            } catch (Throwable ignored) {
            }
            return controller.findActiveAirPods();
        }

        private void addDashboard(Object group) throws Exception {
            Object preference = newPreference("com.coui.appcompat.preference.COUIJumpPreference",
                    new Class<?>[]{Context.class}, new Object[]{context});
            set(preference, "setKey", "oppo_airpods_dashboard");
            set(preference, "setTitle", "AirPods 增强");
            set(preference, "setPersistent", false);
            setClickListener(preference, () -> {
                AirPodsDashboard.show(context, controller);
                return true;
            });
            add(group, preference);
            refreshers.add(() -> {
                String model = controller.modelName();
                set(preference, "setSummary", model + " · 音频、操控、连接、听力与设备信息");
            });
        }

        private Object addSlider(Object group, String key, String title, String summary, int max,
                               Supplier<Integer> getter, Consumer<Integer> setter) throws Exception {
            Object preference = newPreference("androidx.preference.SeekBarPreference",
                    new Class<?>[]{Context.class, AttributeSet.class}, new Object[]{context, null});
            set(preference, "setKey", "oppo_airpods_" + key);
            set(preference, "setTitle", title);
            if (!summary.isEmpty()) set(preference, "setSummary", summary);
            set(preference, "setPersistent", false);
            invoke(preference, "V0", new Class<?>[]{int.class}, new Object[]{max});
            try {
                setField(preference, "Z", true);
            } catch (Throwable error) {
                Logx.w("seekbar value label unavailable", error);
            }
            int initial = getter.get();
            invoke(preference, "X0", new Class<?>[]{int.class}, new Object[]{initial});
            setChangeListener(preference, value -> {
                if (!(value instanceof Number)) return false;
                int progress = ((Number) value).intValue();
                setter.accept(progress);
                if (!summary.isEmpty()) set(preference, "setSummary", summary + " · " + progress);
                return true;
            });
            SliderRegistry.register(preference);
            add(group, preference);
            refreshers.add(() -> {
                try {
                    int current = getter.get();
                    invoke(preference, "X0", new Class<?>[]{int.class}, new Object[]{current});
                } catch (Throwable error) {
                    Logx.w("adaptive slider refresh failed", error);
                }
            });
            return preference;
        }

        private void addAdaptiveSlider(Object screen, Supplier<Integer> getter, Consumer<Integer> setter) throws Exception {
            Object category = newPreference("com.coui.appcompat.preference.COUIPreferenceCategory",
                    new Class<?>[]{Context.class, AttributeSet.class}, new Object[]{context, null});
            set(category, "setKey", "oppo_airpods_adaptive_container");
            invoke(category, "I0", new Class<?>[]{int.class}, new Object[]{-1000});
            add(screen, category);
            Object slider = addSlider(category, "adaptive_strength_slider", "自适应降噪强度",
                    "", 100, getter, setter);
            final boolean[] lastVisible = new boolean[]{true};
            refreshers.add(() -> {
                try {
                    boolean adaptive = controller.controlValue(AacpProtocol.CONTROL_LISTENING_MODE, 1) == 4;
                    invoke(category, "P0", new Class<?>[]{boolean.class}, new Object[]{adaptive});
                    invoke(slider, "P0", new Class<?>[]{boolean.class}, new Object[]{adaptive});
                    if (adaptive != lastVisible[0]) {
                        lastVisible[0] = adaptive;
                        main.post(this::forceListRefresh);
                    }
                } catch (Throwable error) {
                    Logx.w("adaptive slider visibility failed", error);
                }
            });
        }

        private void forceListRefresh() {
            try {
                Object list = call(fragment.get(), "getListView");
                if (list == null) return;
                Object adapter = list.getClass().getMethod("getAdapter").invoke(list);
                if (adapter != null) {
                    adapter.getClass().getMethod("notifyDataSetChanged").invoke(adapter);
                }
            } catch (Throwable error) {
                Logx.w("adaptive slider list refresh failed", error);
            }
        }

        private Object newPreference(String name, Class<?>[] types, Object[] args) throws Exception {
            ClassLoader loader = fragment.get().getClass().getClassLoader();
            return Class.forName(name, false, loader).getConstructor(types).newInstance(args);
        }

        private static void add(Object group, Object preference) throws Exception {
            for (String name : new String[]{"addPreference", "V0"}) {
                for (Method method : group.getClass().getMethods()) {
                    if (method.getName().equals(name) && method.getParameterCount() == 1) {
                        method.invoke(group, preference);
                        return;
                    }
                }
            }
            Logx.w("no addPreference method on " + group.getClass().getName(), null);
        }

        private static void set(Object target, String name, Object value) {
            String[] candidates = name.equals("setTitle") ? new String[]{"setTitle", "O0"}
                    : name.equals("setSummary") ? new String[]{"setSummary", "L0"}
                    : name.equals("setKey") ? new String[]{"setKey", "D0"}
                    : name.equals("setChecked") ? new String[]{"setChecked", "W0"}
                    : new String[]{name};
            for (String candidate : candidates) {
                for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                    for (Method method : type.getMethods()) {
                        if (!method.getName().equals(candidate) || method.getParameterCount() != 1) continue;
                        try {
                            method.invoke(target, value);
                            return;
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }

        private static void setChangeListener(Object preference, ChangeHandler handler) throws Exception {
            Method setter = findSetter(preference, "setOnPreferenceChangeListener", "G0");
            if (setter == null) {
                Logx.w("no change listener method on " + preference.getClass().getName(), null);
                return;
            }
            Class<?> listener = setter.getParameterTypes()[0];
            Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener},
                    (proxy1, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy1, method, args);
                        }
                        String name = method.getName();
                        if (("onPreferenceChange".equals(name) || "a".equals(name))
                                && args != null && args.length > 1) {
                            return handler.onChange(args[1]);
                        }
                        return defaultValue(method.getReturnType());
                    });
            setter.invoke(preference, proxy);
        }

        private static void setClickListener(Object preference, ClickHandler handler) throws Exception {
            Method setter = findSetter(preference, "setOnPreferenceClickListener", "H0");
            if (setter == null) {
                Logx.w("no click listener method on " + preference.getClass().getName(), null);
                return;
            }
            Class<?> listener = setter.getParameterTypes()[0];
            Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener},
                    (proxy1, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy1, method, args);
                        }
                        String name = method.getName();
                        if ("onPreferenceClick".equals(name) || "a".equals(name)) {
                            return handler.onClick();
                        }
                        return defaultValue(method.getReturnType());
                    });
            setter.invoke(preference, proxy);
        }

        private static Method findSetter(Object preference, String... names) {
            for (String name : names) {
                for (Method method : preference.getClass().getMethods()) {
                    if (method.getName().equals(name) && method.getParameterCount() == 1) {
                        return method;
                    }
                }
            }
            return null;
        }

        private static void invoke(Object target, String name, Class<?>[] types, Object[] args) throws Exception {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Method method = type.getDeclaredMethod(name, types);
                    method.setAccessible(true);
                    method.invoke(target, args);
                    return;
                } catch (NoSuchMethodException ignored) {
                }
            }
            throw new NoSuchMethodException(name);
        }

        private static void setField(Object target, String name, boolean value) throws Exception {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    field.setBoolean(target, value);
                    return;
                } catch (NoSuchFieldException ignored) {
                }
            }
            throw new NoSuchFieldException(name);
        }

        private static Object objectMethod(Object proxy, Method method, Object[] args) {
            if ("toString".equals(method.getName())) return "AirPodsPreferenceListener";
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) return args != null && args.length == 1 && proxy == args[0];
            return null;
        }

        private static Object defaultValue(Class<?> type) {
            return type == boolean.class ? Boolean.FALSE : null;
        }

        private static Object find(Object screen, String key) {
            try {
                Method method = screen.getClass().getMethod("findPreference", CharSequence.class);
                return method.invoke(screen, key);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Object call(Object target, String name) throws Exception {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Method method = type.getDeclaredMethod(name);
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (NoSuchMethodException ignored) {
                }
            }
            return null;
        }

        @Override
        public void onAirPodsStateChanged() {
            if (fragment.get() == null) {
                controller.removeListener(this);
                return;
            }
            main.post(this::refresh);
        }

        void refresh() {
            if (refresh != null) refresh.run();
        }
    }

    private interface ChangeHandler {
        boolean onChange(Object value);
    }

    private interface ClickHandler {
        boolean onClick();
    }
}


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

/** Adds AirPods controls into the ROM's own "耳机功能" category on the Bluetooth device page. */
final class WirelessSettingsInjector {
    private static final Map<Object, Injection> INSTALLED = new WeakHashMap<>();

    private WirelessSettingsInjector() {}

    static void install(Object fragment) {
        if (fragment == null) return;
        if (!"com.oplus.wirelesssettings.bluetooth.B".equals(fragment.getClass().getName())) return;
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
            Logx.e("headphone function injection failed", error);
        }
    }

    private static final class Injection implements AirPodsController.Listener {
        private final WeakReference<Object> fragment;
        private final Context context;
        private final AirPodsController controller;
        private BluetoothDevice device;
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
            Object category = call(fragment.get(), "findPreference", new Class<?>[]{CharSequence.class},
                    new Object[]{"headphone_function_key"});
            if (category == null) {
                Logx.w("headphone_function_key category missing", null);
                return false;
            }
            BluetoothDevice device = resolveDevice();
            if (device == null) {
                Logx.w("AirPods device unavailable on Bluetooth page", null);
                return false;
            }
            if (!controller.isAirPods(device)) return false;
            this.device = device;
            Logx.i("injecting AirPods controls into 耳机功能");
            controller.onSettingsPageOpened(device);
            controller.addListener(this);

            addList(category, "noise_control", "噪声控制", new String[]{"关闭", "降噪", "通透", "自适应"},
                    new String[]{"1", "2", "3", "4"},
                    () -> {
                        Integer nativeMode = NativeAirPodsControl.getStatus(device, NativeAirPodsControl.FEATURE_NOISE);
                        return String.valueOf(nativeMode != null && nativeMode >= 1 && nativeMode <= 4 ? nativeMode : controller.controlValue(AacpProtocol.CONTROL_LISTENING_MODE, 1));
                    },
                    value -> {
                        int mode = Integer.parseInt(value);
                        if (!NativeAirPodsControl.setStatus(device, NativeAirPodsControl.FEATURE_NOISE, mode)) {
                            controller.sendControl(AacpProtocol.CONTROL_LISTENING_MODE, mode);
                        }
                    });
            addList(category, "adaptive_strength", "自适应降噪强度", new String[]{"0", "20", "40", "60", "80", "100"},
                    new String[]{"0", "20", "40", "60", "80", "100"},
                    () -> String.valueOf(controller.controlValue(AacpProtocol.CONTROL_ADAPTIVE_STRENGTH, 50)),
                    value -> controller.sendControl(AacpProtocol.CONTROL_ADAPTIVE_STRENGTH, Integer.parseInt(value)));
            addSwitch(category, "conversation", "对话感知",
                    () -> {
                        Integer nativeConversation = NativeAirPodsControl.getStatus(device, NativeAirPodsControl.FEATURE_CONVERSATION);
                        return nativeConversation != null ? nativeConversation == 1 : controller.controlOn(AacpProtocol.CONTROL_CONVERSATION);
                    },
                    value -> {
                        int status = value ? 1 : 0;
                        if (!NativeAirPodsControl.setStatus(device, NativeAirPodsControl.FEATURE_CONVERSATION, status)) {
                            controller.sendControl(AacpProtocol.CONTROL_CONVERSATION, value ? 1 : 2);
                        }
                    });
            addSwitch(category, "adaptive_volume", "自适应音量",
                    () -> controller.controlOn(AacpProtocol.CONTROL_ADAPTIVE_VOLUME),
                    value -> controller.sendControl(AacpProtocol.CONTROL_ADAPTIVE_VOLUME, value ? 1 : 2));
            addSwitch(category, "single_ear_anc", "单耳降噪",
                    () -> controller.controlOn(AacpProtocol.CONTROL_SINGLE_EAR_ANC),
                    value -> controller.sendControl(AacpProtocol.CONTROL_SINGLE_EAR_ANC, value ? 1 : 2));
            addSwitch(category, "volume_swipe", "音量滑动",
                    () -> controller.controlOn(AacpProtocol.CONTROL_VOLUME_SWIPE),
                    value -> controller.sendControl(AacpProtocol.CONTROL_VOLUME_SWIPE, value ? 1 : 2));
            addSwitch(category, "sleep_detection", "睡眠检测",
                    () -> controller.controlOn(AacpProtocol.CONTROL_SLEEP_DETECTION),
                    value -> controller.sendControl(AacpProtocol.CONTROL_SLEEP_DETECTION, value ? 1 : 2));
            addSwitch(category, "optimized_charging", "优化充电",
                    () -> controller.controlOn(AacpProtocol.CONTROL_OPTIMIZED_CHARGING),
                    value -> controller.sendControl(AacpProtocol.CONTROL_OPTIMIZED_CHARGING, value ? 1 : 2));
            addList(category, "microphone", "麦克风", new String[]{"自动", "始终右耳", "始终左耳"},
                    new String[]{"0", "1", "2"},
                    () -> String.valueOf(controller.controlValue(AacpProtocol.CONTROL_MICROPHONE, 0)),
                    value -> controller.sendControl(AacpProtocol.CONTROL_MICROPHONE, Integer.parseInt(value)));
            addSwitch(category, "ear_detection", "自动人耳检测",
                    () -> controller.controlOn(AacpProtocol.CONTROL_EAR_DETECTION),
                    value -> controller.sendControl(AacpProtocol.CONTROL_EAR_DETECTION, value ? 1 : 2));
            addJump(category, "spatial_audio", "个性化空间音频", "打开官方空间音频设置", this::openAirPodsSettings);
            addJump(category, "disconnect", "断开连接", null, this::disconnect);
            addInfo(category);
            refresh = () -> {
                for (Runnable runnable : refreshers) {
                    try {
                        runnable.run();
                    } catch (Throwable ignored) {
                    }
                }
            };
            refresh();
            Logx.i("AirPods controls added to 耳机功能");
            return true;
        }

        private BluetoothDevice resolveDevice() {
            try {
                Object value = field(fragment.get(), "f8832a0");
                if (value instanceof BluetoothDevice) return (BluetoothDevice) value;
            } catch (Throwable ignored) {
            }
            try {
                Object arguments = call(fragment.get(), "getArguments");
                if (arguments instanceof android.os.Bundle) {
                    Object value = ((android.os.Bundle) arguments).getParcelable("device", BluetoothDevice.class);
                    if (value instanceof BluetoothDevice) return (BluetoothDevice) value;
                }
            } catch (Throwable ignored) {
            }
            return controller.findBondedAirPods();
        }

        private void addSwitch(Object category, String key, String title, Supplier<Boolean> getter,
                               Consumer<Boolean> setter) throws Exception {
            Object preference = newPreference("com.coui.appcompat.preference.COUISwitchPreference",
                    new Class<?>[]{Context.class}, new Object[]{context});
            set(preference, "setKey", "oppo_airpods_" + key);
            set(preference, "setTitle", title);
            set(preference, "setOrder", 100 + refreshers.size());
            set(preference, "setPersistent", false);
            set(preference, "setChecked", getter.get());
            setChangeListener(preference, value -> {
                setter.accept(value instanceof Boolean && (Boolean) value);
                return true;
            });
            add(category, preference);
            refreshers.add(() -> set(preference, "setChecked", getter.get()));
        }

        private void addList(Object category, String key, String title, String[] labels, String[] values,
                             Supplier<String> getter, Consumer<String> setter) throws Exception {
            Object preference = newPreference("com.coui.appcompat.preference.COUIListPreference",
                    new Class<?>[]{Context.class, AttributeSet.class}, new Object[]{context, null});
            set(preference, "setKey", "oppo_airpods_" + key);
            set(preference, "setTitle", title);
            set(preference, "setEntries", (Object) labels);
            set(preference, "setEntryValues", (Object) values);
            set(preference, "setOrder", 100 + refreshers.size());
            set(preference, "setPersistent", false);
            set(preference, "setValue", getter.get());
            setChangeListener(preference, value -> {
                if (value != null) setter.accept(String.valueOf(value));
                return true;
            });
            add(category, preference);
            refreshers.add(() -> {
                String current = getter.get();
                set(preference, "setValue", current);
                set(preference, "setSummary", label(labels, values, current));
            });
        }

        private void addInfo(Object category) throws Exception {
            Object preference = newPreference("com.coui.appcompat.preference.COUIJumpPreference",
                    new Class<?>[]{Context.class}, new Object[]{context});
            set(preference, "setKey", "oppo_airpods_info");
            set(preference, "setTitle", "AirPods 状态");
            set(preference, "setOrder", 200);
            set(preference, "setPersistent", false);
            add(category, preference);
            refreshers.add(() -> {
                String model = controller.modelName();
                if (!controller.modelNumber().isEmpty()) model += " (" + controller.modelNumber() + ")";
                set(preference, "setSummary", model + " · " + controller.batterySummary()
                        + " · " + (controller.opened() ? "控制已连接" : "控制未连接"));
            });
        }

        private void addJump(Object category, String key, String title, String summary, ClickHandler handler) throws Exception {
            Object preference = newPreference("com.coui.appcompat.preference.COUIJumpPreference",
                    new Class<?>[]{Context.class}, new Object[]{context});
            set(preference, "setKey", "oppo_airpods_" + key);
            set(preference, "setTitle", title);
            if (summary != null) set(preference, "setSummary", summary);
            set(preference, "setOrder", 100 + refreshers.size());
            set(preference, "setPersistent", false);
            setClickListener(preference, handler);
            add(category, preference);
        }

        private void setClickListener(Object preference, ClickHandler handler) throws Exception {
            Class<?> listener = null;
            Method setter = null;
            for (Method method : preference.getClass().getMethods()) {
                if (method.getName().equals("setOnPreferenceClickListener") && method.getParameterCount() == 1) {
                    listener = method.getParameterTypes()[0];
                    setter = method;
                    break;
                }
            }
            if (listener == null || setter == null) return;
            Object proxy = Proxy.newProxyInstance(preference.getClass().getClassLoader(), new Class<?>[]{listener},
                    (proxy1, method, args) -> method.getName().equals("onPreferenceClick") ? handler.onClick() : null);
            setter.invoke(preference, proxy);
        }

        private boolean openAirPodsSettings() {
            try {
                android.content.Intent intent = new android.content.Intent("com.oplus.mydevices.AIRPODS_SETTING");
                intent.setPackage("com.heytap.mydevices");
                intent.putExtra("device", device);
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            } catch (Throwable error) {
                Logx.w("open AirPods settings failed", error);
                return true;
            }
        }

        private boolean disconnect() {
            try {
                Object cached = field(fragment.get(), "f8839e");
                if (cached != null) cached.getClass().getMethod("disconnect").invoke(cached);
            } catch (Throwable error) {
                Logx.w("disconnect failed", error);
            }
            return true;
        }

        private static String label(String[] labels, String[] values, String current) {
            for (int i = 0; i < values.length; i++) if (values[i].equals(current)) return labels[i];
            return labels.length == 0 ? "" : labels[0];
        }

        private Object newPreference(String name, Class<?>[] types, Object[] args) throws Exception {
            ClassLoader loader = fragment.get().getClass().getClassLoader();
            return Class.forName(name, false, loader).getConstructor(types).newInstance(args);
        }

        private void add(Object category, Object preference) throws Exception {
            Class<?> type = Class.forName("androidx.preference.Preference", false, category.getClass().getClassLoader());
            category.getClass().getMethod("addPreference", type).invoke(category, preference);
        }

        private static void set(Object target, String name, Object value) {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                for (Method method : type.getMethods()) {
                    if (!method.getName().equals(name) || method.getParameterCount() != 1) continue;
                    try {
                        method.invoke(target, value);
                        return;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        private void setChangeListener(Object preference, ChangeHandler handler) throws Exception {
            Class<?> listener = null;
            Method setter = null;
            for (Method method : preference.getClass().getMethods()) {
                if (method.getName().equals("setOnPreferenceChangeListener") && method.getParameterCount() == 1) {
                    listener = method.getParameterTypes()[0];
                    setter = method;
                    break;
                }
            }
            if (listener == null || setter == null) throw new NoSuchMethodException("setOnPreferenceChangeListener");
            Object proxy = Proxy.newProxyInstance(preference.getClass().getClassLoader(), new Class<?>[]{listener},
                    (proxy1, method, args) -> method.getName().equals("onPreferenceChange")
                            ? handler.onChange(args != null && args.length > 1 ? args[1] : null) : null);
            setter.invoke(preference, proxy);
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
            return null;
        }

        private static Object call(Object target, String name) throws Exception {
            return call(target, name, new Class<?>[0], new Object[0]);
        }

        private static Object field(Object target, String name) throws Exception {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (NoSuchFieldException ignored) {
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

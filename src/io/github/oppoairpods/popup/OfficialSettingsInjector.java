package io.github.oppoairpods.popup;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.widget.Toast;

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
                addSlider(sound, "adaptive_strength", "自适应降噪强度", "拖动调节自适应降噪幅度", 100,
                        () -> controller.controlValue(AacpProtocol.CONTROL_ADAPTIVE_STRENGTH, 50),
                        value -> controller.sendControl(AacpProtocol.CONTROL_ADAPTIVE_STRENGTH, value));
            } catch (Throwable error) {
                Logx.w("adaptive slider unavailable, falling back to list", error);
                addList(sound, "adaptive_strength", "自适应降噪强度",
                        new String[]{"0", "20", "40", "60", "80", "100"},
                        new String[]{"0", "20", "40", "60", "80", "100"},
                        () -> String.valueOf(controller.controlValue(AacpProtocol.CONTROL_ADAPTIVE_STRENGTH, 50)),
                        value -> controller.sendControl(AacpProtocol.CONTROL_ADAPTIVE_STRENGTH, Integer.parseInt(value)));
            }

            addSwitch(settings, "auto_connect", "自动连接", "开盖后自动连接并显示官方弹窗",
                    controller::autoConnect, controller::setAutoConnect);
            addSwitch(settings, "ear_playback", "入耳自动播放", "戴上播放，取下暂停",
                    controller::earPlayback, controller::setEarPlayback);
            addSwitch(settings, "battery_alerts", "低电量提醒", "电量低于 20% 时通知",
                    controller::batteryAlerts, controller::setBatteryAlerts);
            addSwitch(settings, "adaptive_volume", "自适应音量", "根据环境自动调整媒体音量",
                    () -> controller.controlOn(AacpProtocol.CONTROL_ADAPTIVE_VOLUME),
                    value -> controller.sendControl(AacpProtocol.CONTROL_ADAPTIVE_VOLUME, value ? 1 : 2));
            addSwitch(settings, "single_ear", "单耳降噪", "只戴一只耳机时仍启用降噪",
                    () -> controller.controlOn(AacpProtocol.CONTROL_SINGLE_EAR_ANC),
                    value -> controller.sendControl(AacpProtocol.CONTROL_SINGLE_EAR_ANC, value ? 1 : 2));
            addSwitch(settings, "volume_swipe", "音量滑动", "在耳机柄上滑动调节音量",
                    () -> controller.controlOn(AacpProtocol.CONTROL_VOLUME_SWIPE),
                    value -> controller.sendControl(AacpProtocol.CONTROL_VOLUME_SWIPE, value ? 1 : 2));
            addSwitch(settings, "sleep", "睡眠检测", "检测到入睡后暂停播放",
                    () -> controller.controlOn(AacpProtocol.CONTROL_SLEEP_DETECTION),
                    value -> controller.sendControl(AacpProtocol.CONTROL_SLEEP_DETECTION, value ? 1 : 2));
            addSwitch(settings, "charging", "优化充电", "减缓充电以延长电池寿命",
                    () -> controller.controlOn(AacpProtocol.CONTROL_OPTIMIZED_CHARGING),
                    value -> controller.sendControl(AacpProtocol.CONTROL_OPTIMIZED_CHARGING, value ? 1 : 2));
            addSwitch(settings, "allow_off", "允许关闭聆听模式", "保留噪声控制里的“关闭”选项",
                    () -> controller.controlOn(AacpProtocol.CONTROL_ALLOW_OFF),
                    value -> controller.sendControl(AacpProtocol.CONTROL_ALLOW_OFF, value ? 1 : 2));

            addList(settings, "microphone", "麦克风", new String[]{"自动", "始终右耳", "始终左耳"},
                    new String[]{"0", "1", "2"},
                    () -> String.valueOf(controller.controlValue(AacpProtocol.CONTROL_MICROPHONE, 0)),
                    value -> controller.sendControl(AacpProtocol.CONTROL_MICROPHONE, Integer.parseInt(value)));
            addList(settings, "press_speed", "按压速度", new String[]{"默认", "慢", "最慢"},
                    new String[]{"0", "1", "2"},
                    () -> String.valueOf(controller.controlValue(AacpProtocol.CONTROL_PRESS_SPEED, 0)),
                    value -> controller.sendControl(AacpProtocol.CONTROL_PRESS_SPEED, Integer.parseInt(value)));
            addList(settings, "press_hold", "长按时长", new String[]{"默认", "较短", "最短"},
                    new String[]{"0", "1", "2"},
                    () -> String.valueOf(controller.controlValue(AacpProtocol.CONTROL_PRESS_HOLD, 0)),
                    value -> controller.sendControl(AacpProtocol.CONTROL_PRESS_HOLD, Integer.parseInt(value)));
            addList(settings, "swipe_time", "音量滑动时长", new String[]{"默认", "较长", "最长"},
                    new String[]{"0", "1", "2"},
                    () -> String.valueOf(controller.controlValue(AacpProtocol.CONTROL_VOLUME_SWIPE_DURATION, 0)),
                    value -> controller.sendControl(AacpProtocol.CONTROL_VOLUME_SWIPE_DURATION, Integer.parseInt(value)));
            addList(settings, "tone", "提示音音量",
                    new String[]{"15", "25", "35", "50", "65", "80", "100"},
                    new String[]{"15", "25", "35", "50", "65", "80", "100"},
                    () -> String.valueOf(controller.controlValue(AacpProtocol.CONTROL_TONE_VOLUME, 50)),
                    value -> controller.sendControl(AacpProtocol.CONTROL_TONE_VOLUME, Integer.parseInt(value)));
            addEq(settings);
            addInfo(settings);

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
            return controller.findBondedAirPods();
        }

        private void addSwitch(Object group, String key, String title, String summary,
                               Supplier<Boolean> getter, Consumer<Boolean> setter) throws Exception {
            Object preference = newPreference("com.coui.appcompat.preference.COUISwitchPreference",
                    new Class<?>[]{Context.class}, new Object[]{context});
            set(preference, "setKey", "oppo_airpods_" + key);
            set(preference, "setTitle", title);
            set(preference, "setSummary", summary);
            set(preference, "setChecked", getter.get());
            set(preference, "setPersistent", false);
            setChangeListener(preference, value -> {
                setter.accept(value instanceof Boolean && (Boolean) value);
                return true;
            });
            add(group, preference);
            refreshers.add(() -> set(preference, "setChecked", getter.get()));
        }

        private void addList(Object group, String key, String title, String[] labels, String[] values,
                             Supplier<String> getter, Consumer<String> setter) throws Exception {
            Object preference = newPreference("com.coui.appcompat.preference.COUIListPreference",
                    new Class<?>[]{Context.class, AttributeSet.class}, new Object[]{context, null});
            set(preference, "setKey", "oppo_airpods_" + key);
            set(preference, "setTitle", title);
            setListEntries(preference, labels, values);
            
            set(preference, "setValue", getter.get());
            set(preference, "setPersistent", false);
            setChangeListener(preference, value -> {
                if (value != null) setter.accept(String.valueOf(value));
                return true;
            });
            add(group, preference);
            refreshers.add(() -> {
                String current = getter.get();
                set(preference, "setValue", current);
                set(preference, "setSummary", label(labels, values, current));
            });
        }

        private void addSlider(Object group, String key, String title, String summary, int max,
                               Supplier<Integer> getter, Consumer<Integer> setter) throws Exception {
            Object preference = newPreference("androidx.preference.SeekBarPreference",
                    new Class<?>[]{Context.class, AttributeSet.class}, new Object[]{context, null});
            set(preference, "setKey", "oppo_airpods_" + key);
            set(preference, "setTitle", title);
            set(preference, "setSummary", summary);
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
                set(preference, "setSummary", summary + " · " + progress);
                return true;
            });
            add(group, preference);
            refreshers.add(() -> {
                try {
                    int current = getter.get();
                    invoke(preference, "X0", new Class<?>[]{int.class}, new Object[]{current});
                } catch (Throwable error) {
                    Logx.w("adaptive slider refresh failed", error);
                }
            });
        }

        private void addEq(Object group) throws Exception {
            Object preference = newPreference("com.coui.appcompat.preference.COUIListPreference",
                    new Class<?>[]{Context.class, AttributeSet.class}, new Object[]{context, null});
            String[] labels = {"关闭", "流行", "摇滚", "古典", "人声", "低音"};
            String[] values = {"off", "pop", "rock", "classical", "vocal", "bass"};
            String saved = controller.prefs().getString("equalizer_preset", "off");
            set(preference, "setKey", "oppo_airpods_equalizer");
            set(preference, "setTitle", "均衡器");
            setListEntries(preference, labels, values);
            
            set(preference, "setValue", saved);
            set(preference, "setSummary", label(labels, values, saved));
            set(preference, "setPersistent", false);
            setChangeListener(preference, value -> {
                String preset = value == null ? "off" : String.valueOf(value);
                controller.prefs().edit().putString("equalizer_preset", preset).apply();
                int[] eq = eqFor(preset);
                controller.sendEqualizer(eq[0], eq[1], eq[2], eq[3]);
                set(preference, "setSummary", label(labels, values, preset));
                return true;
            });
            add(group, preference);
        }

        private void addInfo(Object group) throws Exception {
            Object preference = newPreference("com.coui.appcompat.preference.COUIJumpPreference",
                    new Class<?>[]{Context.class}, new Object[]{context});
            set(preference, "setKey", "oppo_airpods_info");
            set(preference, "setTitle", "设备信息");
            set(preference, "setPersistent", false);
            setClickListener(preference, () -> {
                Toast.makeText(context, controller.modelName() + "\n" + controller.batterySummary(),
                        Toast.LENGTH_LONG).show();
                return true;
            });
            add(group, preference);
            refreshers.add(() -> {
                String model = controller.modelName();
                if (!controller.modelNumber().isEmpty()) model += " (" + controller.modelNumber() + ")";
                set(preference, "setSummary", model + " · " + controller.batterySummary()
                        + " · " + (controller.opened() ? "控制已连接" : "控制未连接"));
            });
        }

        private static int[] eqFor(String preset) {
            switch (preset) {
                case "pop": return new int[]{2, 55, 50, 45};
                case "rock": return new int[]{2, 60, 45, 55};
                case "classical": return new int[]{2, 45, 50, 60};
                case "vocal": return new int[]{2, 40, 55, 55};
                case "bass": return new int[]{2, 70, 45, 40};
                default: return new int[]{1, 50, 50, 50};
            }
        }

        private static String label(String[] labels, String[] values, String current) {
            for (int i = 0; i < values.length; i++) if (values[i].equals(current)) return labels[i];
            return labels.length == 0 ? "" : labels[0];
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

        private static void setListEntries(Object preference, String[] labels, String[] values) {
            try {
                java.lang.reflect.Field entries = null;
                java.lang.reflect.Field entryValues = null;
                for (Class<?> type = preference.getClass(); type != null; type = type.getSuperclass()) {
                    for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                        if (field.getType() != CharSequence[].class) continue;
                        if (field.getName().equals("X")) entries = field;
                        if (field.getName().equals("Y")) entryValues = field;
                    }
                }
                if (entries != null) {
                    entries.setAccessible(true);
                    entries.set(preference, labels);
                }
                if (entryValues != null) {
                    entryValues.setAccessible(true);
                    entryValues.set(preference, values);
                }
            } catch (Throwable error) {
                Logx.w("list entries injection failed", error);
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


package io.github.oppoairpods.popup;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** Reuses the hidden native IconTextSelector on the MyDevices device page. */
final class QuickNoiseInjector {
    private static final String SELECTOR_CLASS =
            "com.oplus.mydevices.deviceui.devicecard.view.IconTextSelector";
    private static final String SELECTOR_SETTINGS_CLASS =
            "com.oplus.mydevices.deviceui.devicecard.view.IconTextSelectorSettings";
    private static final String ITEM_CLASS =
            "com.oplus.mydevices.domain.entities.airpods.IconTextItem";
    private static final String TAG = "opods_quick_noise";
    private static final int[] MODE_BY_INDEX = {2, 4, 1, 3};
    private static final Map<Object, Injection> INSTALLED = new WeakHashMap<>();

    private QuickNoiseInjector() {}

    static void install(Object fragment, View root) {
        if (fragment == null || root == null) return;
        synchronized (INSTALLED) {
            Injection existing = INSTALLED.get(fragment);
            if (existing != null) {
                existing.show();
                return;
            }
        }
        View selector = findByClass(root, SELECTOR_CLASS);
        if (selector == null) return;
        Context context = selector.getContext();
        AirPodsController controller = AirPodsController.get(context);
        BluetoothDevice device = controller.findBondedAirPods();
        if (device == null || !controller.isAirPods(device)) return;
        controller.attachDevice(device);
        try {
            ClassLoader loader = fragment.getClass().getClassLoader();
            Class<?> selectorClass = Class.forName(SELECTOR_SETTINGS_CLASS, false, loader);
            View ownSelector = (View) selectorClass.getConstructor(Context.class, AttributeSet.class)
                    .newInstance(context, null);
            ownSelector.setTag(TAG);
            ownSelector.setVisibility(View.VISIBLE);
            ownSelector.setAlpha(1.0f);
            int padding = Math.round(context.getResources().getDisplayMetrics().density * 24.0f);
            ownSelector.setPadding(padding, 0, padding, 0);
            Class<?> itemClass = Class.forName(ITEM_CLASS, false, loader);
            ArrayList<Object> items = new ArrayList<>();
            items.add(newItem(itemClass, 2, "降噪", "noise", icon(loader, "w"), icon(loader, "x")));
            items.add(newItem(itemClass, 4, "自适应", "adaptive_noise", icon(loader, "a"), icon(loader, "b")));
            items.add(newItem(itemClass, 1, "关闭", "off_noise", icon(loader, "u"), icon(loader, "v")));
            items.add(newItem(itemClass, 3, "通透", "trans_noise", icon(loader, "y"), icon(loader, "z")));

            Injection injection = new Injection(fragment, controller, ownSelector, items, device);
            injection.install(loader);
            ViewGroup parent = (ViewGroup) selector.getParent();
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int margin = Math.round(context.getResources().getDisplayMetrics().density * 16.0f);
            params.setMargins(0, 0, 0, margin);
            parent.addView(ownSelector, parent.indexOfChild(selector), params);
            parent.removeView(selector);
            controller.addListener(injection);
            synchronized (INSTALLED) {
                INSTALLED.put(fragment, injection);
            }
            Logx.i("quick noise selector installed");
        } catch (Throwable error) {
            Logx.w("quick noise selector failed", error);
        }
    }

    static void release(Object fragment) {
        Injection injection;
        synchronized (INSTALLED) {
            injection = INSTALLED.remove(fragment);
        }
        if (injection != null) injection.release();
    }

    private static Object newItem(Class<?> itemClass, int id, String text, String action,
                                  int normalIcon, int selectedIcon) throws Exception {
        return itemClass.getConstructor(int.class, String.class, String.class, int.class, int.class)
                .newInstance(id, text, action, normalIcon, selectedIcon);
    }

    private static int icon(ClassLoader loader, String fieldName) throws Exception {
        Class<?> icons;
        try {
            icons = Class.forName("aa.p20", false, loader);
        } catch (ClassNotFoundException missing) {
            icons = Class.forName("aa.AbstractC2229p20", false, loader);
        }
        return icons.getField(fieldName).getInt(null);
    }

    private static View findByClass(View root, String className) {
        if (className.equals(root.getClass().getName())) return root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findByClass(group.getChildAt(i), className);
            if (found != null) return found;
        }
        return null;
    }

    private static final class Injection implements AirPodsController.Listener {
        private final WeakReference<View> selector;
        private final AirPodsController controller;
        private final ArrayList<Object> items;
        private final BluetoothDevice device;
        private int lastMode = -1;

        Injection(Object fragment, AirPodsController controller, View selector,
                  ArrayList<Object> items, BluetoothDevice device) {
            this.selector = new WeakReference<>(selector);
            this.controller = controller;
            this.items = items;
            this.device = device;
        }

        void install(ClassLoader loader) throws Exception {
            View view = selector.get();
            if (view == null) throw new IllegalStateException("selector unavailable");
            Method setItems = findMethod(view.getClass(), "d",
                    method -> method.getParameterCount() == 2
                            && method.getParameterTypes()[0] == ArrayList.class
                            && method.getParameterTypes()[1] == int.class);
            setItems.invoke(view, items, selectedIndex(controller.controlValue(AacpProtocol.CONTROL_LISTENING_MODE, 1)));
            view.getClass().getMethod("setEnableClick", boolean.class).invoke(view, true);

            Class<?> listenerType = null;
            Method registerListener = null;
            for (Method method : view.getClass().getMethods()) {
                if (method.getParameterCount() == 3
                        && method.getParameterTypes()[0] == String.class
                        && method.getParameterTypes()[1] == int.class
                        && method.getParameterTypes()[2].isInterface()) {
                    registerListener = method;
                    listenerType = method.getParameterTypes()[2];
                    break;
                }
            }
            if (listenerType == null || registerListener == null) {
                throw new NoSuchMethodException("IconTextSelector listener setter");
            }
            Object unit = kotlinUnit(loader);
            Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(),
                    new Class<?>[]{listenerType}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy, method, args);
                        }
                        if ("invoke".equals(method.getName()) && args != null && args.length > 0) {
                            onSelected(((Number) args[0]).intValue());
                            return unit;
                        }
                        return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                    });
            registerListener.invoke(view, device.getAddress(), 0, listener);
        }

        private void onSelected(int index) {
            if (index < 0 || index >= MODE_BY_INDEX.length) return;
            int mode = MODE_BY_INDEX[index];
            lastMode = mode;
            controller.setControlState(AacpProtocol.CONTROL_LISTENING_MODE, mode);
            controller.sendControl(AacpProtocol.CONTROL_LISTENING_MODE, mode);
            Logx.i("quick noise mode=" + mode);
        }

        @Override
        public void onAirPodsStateChanged() {
            View view = selector.get();
            if (view == null) return;
            int mode = controller.controlValue(AacpProtocol.CONTROL_LISTENING_MODE, 1);
            if (mode == lastMode) return;
            lastMode = mode;
            try {
                java.lang.reflect.Method select = view.getClass()
                        .getDeclaredMethod("setSelectedPosition", int.class);
                select.setAccessible(true);
                select.invoke(view, selectedIndex(mode));
                Logx.i("quick noise synced mode=" + mode);
            } catch (Throwable error) {
                Logx.w("quick noise refresh failed", error);
            }
        }

        void release() {
            controller.removeListener(this);
        }

        void show() {
            View view = selector.get();
            if (view == null) return;
            view.setVisibility(View.VISIBLE);
            view.setAlpha(1.0f);
        }
    }

    private static int selectedIndex(int mode) {
        for (int i = 0; i < MODE_BY_INDEX.length; i++) {
            if (MODE_BY_INDEX[i] == mode) return i;
        }
        return 0;
    }

    private static Object kotlinUnit(ClassLoader loader) {
        try {
            return Class.forName("kotlin.Unit", false, loader).getField("INSTANCE").get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> owner, String preferred,
                                     java.util.function.Predicate<Method> predicate)
            throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (preferred.equals(method.getName()) && predicate.test(method)) return method;
        }
        for (Method method : owner.getMethods()) {
            if (predicate.test(method)) return method;
        }
        throw new NoSuchMethodException(owner.getName() + "." + preferred);
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        if ("toString".equals(method.getName())) return "QuickNoiseListener";
        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
        if ("equals".equals(method.getName())) return args != null && args.length == 1 && proxy == args[0];
        return null;
    }
}

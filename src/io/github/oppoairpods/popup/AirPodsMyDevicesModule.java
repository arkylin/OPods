package io.github.oppoairpods.popup;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.SeekBar;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * ColorOS AirPods integration. It lives only inside the ROM's own processes and extends the
 * existing popup/settings instead of shipping an Activity, Service, scanner or second app process.
 */
public final class AirPodsMyDevicesModule extends XposedModule {
    private static final String MYDEVICES = "com.heytap.mydevices";
    private static final String WIRELESS = "com.oplus.wirelesssettings";
    private static final String BLUETOOTH = "com.android.bluetooth";
    private static final String ACCESSORY = "com.heytap.accessory";
    private static final Object INSTALL_LOCK = new Object();
    private static boolean myDevicesHooksInstalled;
    private static boolean wirelessHooksInstalled;
    private static boolean aapOffFilterInstalled;
    private static volatile Context appContext;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Logx.i("module loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        ClassLoader loader = param.getDefaultClassLoader();
        if (BLUETOOTH.equals(pkg) || ACCESSORY.equals(pkg)) {
            installAapOff(loader);
            return;
        }
        if (MYDEVICES.equals(pkg)) {
            installMyDevices(loader);
        }
    }

    private void installMyDevices(ClassLoader loader) {
        synchronized (INSTALL_LOCK) {
            if (myDevicesHooksInstalled) return;
            myDevicesHooksInstalled = true;
        }
        HiddenApi.exemptBluetooth();
        appContext = currentApplication();
        hookApplication(loader);
        hookAirPodsSupport(loader);
        hookOfficialPopup(loader);
        hookScanCallback(loader);
        hookSettingsPage(loader);
        hookSettingsActivity(loader);
        hookAdaptiveSlider(loader);
        hookDetailPanel(loader);
        DisconnectFilter.install(this, loader);
        Logx.i("MyDevices hooks installed");
    }

    private void installAapOff(ClassLoader loader) {
        synchronized (INSTALL_LOCK) {
            if (aapOffFilterInstalled) return;
            aapOffFilterInstalled = true;
        }
        AapOffFilter.install(this, loader);
    }

    private void installWirelessSettings(ClassLoader loader) {
        synchronized (INSTALL_LOCK) {
            if (wirelessHooksInstalled) return;
            wirelessHooksInstalled = true;
        }
        try {
            Class<?> feature = Class.forName("com.oplus.wirelesssettings.k", false, loader);
            hookNoArg(feature, "D", true, "WirelessSettings AirPods feature");
            try {
                Class<?> cached = Class.forName("com.android.settingslib.bluetooth.CachedBluetoothDevice", false, loader);
                for (Constructor<?> constructor : cached.getDeclaredConstructors()) {
                    if (constructor.getParameterCount() != 3) continue;
                    hook(constructor).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object value = chain.getArg(2);
                            if (value instanceof BluetoothDevice) {
                                BluetoothDevice device = (BluetoothDevice) value;
                                String name = null;
                                try { name = device.getAlias(); } catch (Throwable ignored) { }
                                if (name == null || name.isEmpty()) name = device.getName();
                                if (name != null && name.toLowerCase(java.util.Locale.ROOT).contains("airpods")) {
                                    setAirPodsFlag(chain.getThisObject(), true);
                                }
                            }
                        } catch (Throwable error) {
                            Logx.w("CachedBluetoothDevice flag fix failed", error);
                        }
                        return result;
                    });
                }
                Method support = cached.getDeclaredMethod("isTotallySupportAirPods", Context.class);
                hook(support).intercept(chain -> {
                    try {
                        Object value = call(chain.getThisObject(), "getDevice");
                        if (value instanceof BluetoothDevice) {
                            BluetoothDevice device = (BluetoothDevice) value;
                            String name = null;
                            try { name = device.getAlias(); } catch (Throwable ignored) { }
                            if (name == null || name.isEmpty()) name = device.getName();
                            if (name != null && name.toLowerCase(java.util.Locale.ROOT).contains("airpods")) return true;
                        }
                    } catch (Throwable error) {
                        Logx.w("AirPods support probe failed", error);
                    }
                    return chain.proceed();
                });
            } catch (Throwable error) {
                Logx.w("CachedBluetoothDevice AirPods hook failed", error);
            }
            try {
                Class<?> profile = Class.forName("com.oplus.wirelesssettings.bluetooth.B", false, loader);
                Method onCreate = profile.getDeclaredMethod("onCreate", android.os.Bundle.class);
                hook(onCreate).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        WirelessSettingsInjector.install(chain.getThisObject());
                    } catch (Throwable error) {
                        Logx.w("headphone function injection failed", error);
                    }
                    return result;
                });
                Logx.i("DeviceProfilesSettings hook installed");
            } catch (Throwable error) {
                Logx.w("DeviceProfilesSettings hook failed", error);
            }
        } catch (Throwable error) {
            Logx.w("WirelessSettings feature hook failed", error);
        }
    }

    private void hookApplication(ClassLoader loader) {
        try {
            Class<?> app = Class.forName("com.heytap.mydevices.DeviceApplication", false, loader);
            Method onCreate = app.getDeclaredMethod("onCreate");
            hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    appContext = (Context) chain.getThisObject();
                    Logx.setContext(appContext);
                } catch (Throwable ignored) {
                }
                return result;
            });
        } catch (Throwable error) {
            Logx.w("DeviceApplication hook failed", error);
        }
    }

    private void hookAirPodsSupport(ClassLoader loader) {
        try {
            Class<?> support = Class.forName("aa.b2$a", false, loader);
            hookNoArg(support, "r", true, "AirPods feature support");
            try {
                Method mask = support.getDeclaredMethod("J", int.class);
                hook(mask).intercept(chain -> {
                    int value = (int) chain.getArg(0);
                    return chain.proceed(new Object[]{value | 0x200});
                });
                Logx.i("AirPods Off capability bit 0x200 enabled");
            } catch (Throwable error) {
                Logx.w("AirPods Off mask hook failed", error);
            }
        } catch (Throwable error) {
            Logx.w("AirPods support hook failed", error);
        }
        try {
            Class<?> policy = Class.forName("com.heytap.mydevices.plugin.airpods.core.AirpodsScanPolicy", false, loader);
            for (Constructor<?> constructor : policy.getDeclaredConstructors()) {
                if (constructor.getParameterCount() < 5) continue;
                hook(constructor).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Field managerField = policy.getDeclaredField("e");
                        managerField.setAccessible(true);
                        Object manager = managerField.get(chain.getThisObject());
                        if (manager != null) manager.getClass().getMethod("registerPtc").invoke(manager);
                    } catch (Throwable error) {
                        Logx.w("registerPtc fallback failed", error);
                    }
                    return result;
                });
            }
        } catch (Throwable error) {
            Logx.w("AirpodsScanPolicy hook failed", error);
        }
    }

    private void hookOfficialPopup(ClassLoader loader) {
        try {
            Class<?> receiver = Class.forName("com.heytap.mydevices.plugin.airpods.core.AirpodsManager$c", false, loader);
            Method onReceive = receiver.getDeclaredMethod("onReceive", Context.class, Intent.class);
            hook(onReceive).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    handleBroadcast((Context) chain.getArg(0), (Intent) chain.getArg(1));
                } catch (Throwable error) {
                    Logx.w("AirPods broadcast handling failed", error);
                }
                return result;
            });
        } catch (Throwable error) {
            Logx.w("AirpodsManager receiver hook failed", error);
        }
    }

    private void hookScanCallback(ClassLoader loader) {
        try {
            Class<?> listener = Class.forName(
                    "com.heytap.mydevices.plugin.airpods.core.AirpodsScanHelper$registerPtc$2$a",
                    false, loader);
            Method onServiceFound = null;
            for (Method method : listener.getDeclaredMethods()) {
                if ("onServiceFound".equals(method.getName()) && method.getParameterCount() == 1) {
                    onServiceFound = method;
                    break;
                }
            }
            if (onServiceFound == null) throw new NoSuchMethodException("onServiceFound");
            hook(onServiceFound).intercept(chain -> {
                try {
                    handleServiceNode(chain.getArg(0));
                } catch (Throwable error) {
                    Logx.w("PTC advertisement handling failed", error);
                }
                return chain.proceed();
            });
        } catch (Throwable error) {
            Logx.w("PTC listener hook failed", error);
        }
    }

    private void hookSettingsPage(ClassLoader loader) {
        try {
            Class<?> fragment = Class.forName(
                    "com.heytap.mydevices.plugin.airpods.ui.AirpodsSettingPreferencesFragment",
                    false, loader);
            Method onCreatePreferences = fragment.getDeclaredMethod("onCreatePreferences",
                    android.os.Bundle.class, String.class);
            hook(onCreatePreferences).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    OfficialSettingsInjector.install(chain.getThisObject());
                } catch (Throwable error) {
                    Logx.w("official settings injection failed", error);
                }
                return result;
            });
        } catch (Throwable error) {
            Logx.w("settings page hook failed", error);
        }
    }

    private void hookDetailPanel(ClassLoader loader) {
        try {
            Class<?> panel = Class.forName(
                    "com.heytap.mydevices.plugin.airpods.ui.AirpodsDetailPanelFragment",
                    false, loader);
            Method bind = panel.getDeclaredMethod("bindPanelBodyViews", View.class);
            hook(bind).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    QuickNoiseInjector.install(chain.getThisObject(), (View) chain.getArg(0));
                } catch (Throwable error) {
                    Logx.w("quick noise panel injection failed", error);
                }
                return result;
            });
            Method init = panel.getDeclaredMethod("initView", View.class);
            hook(init).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    QuickNoiseInjector.install(chain.getThisObject(), (View) chain.getArg(0));
                } catch (Throwable error) {
                    Logx.w("quick noise panel init failed", error);
                }
                return result;
            });
            Method clear = panel.getDeclaredMethod("clearPanelBodyViewRefs");
            hook(clear).intercept(chain -> {
                QuickNoiseInjector.release(chain.getThisObject());
                return chain.proceed();
            });
            Logx.i("detail panel hook installed");
        } catch (Throwable error) {
            Logx.w("detail panel hook failed", error);
        }
    }

    private void hookAdaptiveSlider(ClassLoader loader) {
        try {
            Class<?> seekPreference = Class.forName(
                    "androidx.preference.SeekBarPreference", false, loader);
            Method bind = null;
            for (Method method : seekPreference.getDeclaredMethods()) {
                if ("Z".equals(method.getName()) && method.getParameterCount() == 1) {
                    bind = method;
                    break;
                }
            }
            if (bind == null) throw new NoSuchMethodException("SeekBarPreference.Z");
            hook(bind).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object preference = chain.getThisObject();
                    Object key = call(preference, "w");
                    if ("oppo_airpods_adaptive_strength_slider".equals(key)) {
                        Field field = seekPreference.getField("W");
                        Object value = field.get(preference);
                        if (value instanceof SeekBar) {
                            SeekBar bar = (SeekBar) value;
                            float density = bar.getResources().getDisplayMetrics().density;
                            int radius = Math.max(2, Math.round(density * 2.0f));
                            int trackHeight = Math.max(6, Math.round(density * 9.0f));
                            bar.setBackground(new SliderTickDrawable(bar, radius, trackHeight));
                            bar.setProgressDrawable(new ColorDrawable(Color.TRANSPARENT));
                            GradientDrawable thumb = new GradientDrawable();
                            thumb.setShape(GradientDrawable.OVAL);
                            thumb.setColor(Color.WHITE);
                            thumb.setStroke(Math.max(2, Math.round(density * 2.0f)), 0xFF3478F6);
                            int thumbSize = Math.round(density * 20.0f);
                            thumb.setSize(thumbSize, thumbSize);
                            bar.setThumb(thumb);
                            bar.setKeyProgressIncrement(25);
                            bar.setSplitTrack(false);
                            Object holder = chain.getArg(0);
                            View item = (View) holder.getClass().getField("itemView").get(holder);
                            GradientDrawable card = new GradientDrawable();
                            card.setColor(Color.WHITE);
                            card.setCornerRadius(density * 24.0f);
                            item.setBackground(card);
                            int horizontal = Math.round(density * 24.0f);
                            int top = Math.round(density * 12.0f);
                            int bottom = Math.round(density * 16.0f);
                            item.setPadding(horizontal, top, horizontal, bottom);

                            View title = item.findViewById(android.R.id.title);
                            Field valueField = seekPreference.getDeclaredField("X");
                            valueField.setAccessible(true);
                            Object valueView = valueField.get(preference);
                            if (title != null && valueView instanceof View
                                    && title.getParent() instanceof RelativeLayout
                                    && ((View) valueView).getParent() instanceof ViewGroup) {
                                RelativeLayout titleRow = (RelativeLayout) title.getParent();
                                ViewGroup.LayoutParams rowParams = titleRow.getLayoutParams();
                                rowParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                                titleRow.setLayoutParams(rowParams);
                                View valueText = (View) valueView;
                                ((ViewGroup) valueText.getParent()).removeView(valueText);
                                RelativeLayout.LayoutParams valueParams = new RelativeLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT);
                                valueParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
                                valueParams.addRule(RelativeLayout.CENTER_VERTICAL);
                                titleRow.addView(valueText, valueParams);
                            }
                            Logx.i("adaptive slider tick marks installed");
                        }
                    }
                } catch (Throwable error) {
                    Logx.w("adaptive slider binding decoration failed", error);
                }
                return result;
            });
            Logx.i("adaptive slider binding hook installed");
        } catch (Throwable error) {
            Logx.w("adaptive slider binding hook failed", error);
        }
    }

    private void hookSettingsActivity(ClassLoader loader) {
        try {
            Class<?> activity = Class.forName(
                    "com.heytap.mydevices.plugin.airpods.ui.AirpodsSettingActivity",
                    false, loader);
            Method updateFeatureSupport = activity.getDeclaredMethod("updateFeatureSupport", int.class);
            hook(updateFeatureSupport).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    FeatureSupportEnabler.afterFeatureSupport(chain.getThisObject());
                } catch (Throwable error) {
                    Logx.w("feature support extension failed", error);
                }
                return result;
            });
            Method onResume = activity.getDeclaredMethod("onResume");
            hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    attachSettingsDevice(chain.getThisObject());
                } catch (Throwable error) {
                    Logx.w("settings device attach failed", error);
                }
                return result;
            });
            try {
                Method onDestroy = activity.getDeclaredMethod("onDestroy");
                hook(onDestroy).intercept(chain -> {
                    try {
                        Context context = appContext != null ? appContext : currentApplication();
                        if (context != null) AirPodsController.get(context).onSettingsPageClosed();
                    } catch (Throwable error) {
                        Logx.w("settings close handling failed", error);
                    }
                    return chain.proceed();
                });
            } catch (Throwable error) {
                Logx.w("settings onDestroy hook failed", error);
            }
            try {
                Method onNoise = activity.getDeclaredMethod("onNoiseControlClick", int.class);
                hook(onNoise).intercept(chain -> {
                    Object result = chain.proceed();
                    int mode = (int) chain.getArg(0);
                    try {
                        if (mode >= 1 && mode <= 4) {
                            Context context = appContext != null ? appContext : currentApplication();
                            if (context != null) AirPodsController.get(context).sendControl(AacpProtocol.CONTROL_LISTENING_MODE, mode);
                            call(chain.getThisObject(), "updateCurrentFeature",
                                    new Class<?>[]{int.class}, new Object[]{mode});
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                });
            } catch (Throwable error) {
                Logx.w("noise click hook failed", error);
            }
            try {
                Class<?> viewModel = Class.forName(
                        "com.heytap.mydevices.plugin.airpods.ui.viewmodel.AirpodsSettingViewModel",
                        false, loader);
                Method setFeature = viewModel.getDeclaredMethod("setExtendFeatureStatus", int.class, int.class);
                hook(setFeature).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        int type = (int) chain.getArg(0);
                        int value = (int) chain.getArg(1);
                        if (type == 4) {
                            Context context = appContext != null ? appContext : currentApplication();
                            if (context != null) {
                                AirPodsController.get(context).sendControl(AacpProtocol.CONTROL_LISTENING_MODE, value);
                            }
                        }
                    } catch (Throwable error) {
                        Logx.w("AACP noise fallback failed", error);
                    }
                    return result;
                });
            } catch (Throwable error) {
                Logx.w("view model noise hook failed", error);
            }
        } catch (Throwable error) {
            Logx.w("settings activity hook failed", error);
        }
    }

    private void handleBroadcast(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!"android.bluetooth.device.action.ACL_CONNECTED".equals(action)
                && !"android.bluetooth.device.action.ACL_DISCONNECTED".equals(action)) return;
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        if (device == null) return;
        AirPodsController controller = AirPodsController.get(context);
        if ("android.bluetooth.device.action.ACL_CONNECTED".equals(action)) {
            controller.onAclConnected(device);
        } else {
            controller.onAclDisconnected(device);
        }
    }

    private void handleServiceNode(Object node) throws Exception {
        if (node == null) return;
        Object info = node.getClass().getMethod("getServiceInfo").invoke(node);
        if (info == null) return;
        Object raw = info.getClass().getMethod("getServiceData").invoke(info);
        if (!(raw instanceof byte[])) return;
        AppleAdvertisement.LidInfo lid = AppleAdvertisement.parse((byte[]) raw);
        if (lid == null || !lid.lidOpen) return;
        Context context = appContext != null ? appContext : currentApplication();
        if (context == null) return;
        AirPodsController controller = AirPodsController.get(context);
        BluetoothDevice device = controller.findBondedAirPods();
        controller.onCaseAdvertisement(device, lid.model, true);
    }

    private void attachSettingsDevice(Object activity) throws Exception {
        Object viewModel = call(activity, "getViewModel");
        if (viewModel == null) return;
        Object value = viewModel.getClass().getMethod("getBluetoothDevice").invoke(viewModel);
        if (!(value instanceof BluetoothDevice)) return;
        BluetoothDevice device = (BluetoothDevice) value;
        AirPodsController controller = AirPodsController.get((Context) activity);
        controller.onSettingsPageOpened(device);
    }

    private void hookNoArg(Class<?> owner, String name, boolean result, String label) {
        try {
            Method method = owner.getDeclaredMethod(name);
            hook(method).intercept(chain -> result);
            Logx.i(label + " forced true");
        } catch (Throwable error) {
            Logx.w(label + " hook failed", error);
        }
    }

    private static void setAirPodsFlag(Object target, boolean value) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("mIsAirPods");
                field.setAccessible(true);
                field.setBoolean(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
                return;
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
        return null;
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            return (Context) currentApplication.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}

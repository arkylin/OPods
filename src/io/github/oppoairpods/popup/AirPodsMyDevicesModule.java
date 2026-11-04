package io.github.oppoairpods.popup;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.function.Predicate;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * ColorOS AirPods integration. It lives only inside the ROM's own processes and extends the
 * existing popup/settings instead of shipping an Activity, Service, scanner or second app process.
 */
public final class AirPodsMyDevicesModule extends XposedModule {
    private static final String MYDEVICES = "com.heytap.mydevices";
    private static final String BLUETOOTH = "com.android.bluetooth";
    private static final String ACCESSORY = "com.heytap.accessory";
    private static final Object INSTALL_LOCK = new Object();
    private static boolean myDevicesHooksInstalled;
    private static boolean aapOffFilterInstalled;
    private static final java.util.concurrent.atomic.AtomicBoolean DETAIL_BATTERY_HOOKED =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile Context appContext;
    private static final java.util.concurrent.atomic.AtomicInteger PTC_LOGS =
            new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Logx.i("module loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        ClassLoader loader = param.getDefaultClassLoader();
        if (BLUETOOTH.equals(pkg)) {
            installAapOff(loader);
            return;
        }
        if (ACCESSORY.equals(pkg)) {
            installAccessoryPtc(loader);
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
        hookAirPodsScanFilter(loader);
        hookPtcRegistration(loader);
        hookAirPodsSupport(loader);
        hookOfficialPopup(loader);
        hookScanCallback(loader);
        hookFusionEvents(loader);
        hookCoreService(loader);
        hookSettingsPage(loader);
        hookSettingsActivity(loader);
        hookAdaptiveSlider(loader);
        hookDetailPanel(loader);
        hookOfficialBatteryUi(loader);
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

    /**
     * The ROM's PTC filter is pinned to AirPods 4 era proximity prefixes. Keep the
     * manufacturer id and subtype, but let newer AirPods (for example AirPods 5)
     * match by the first two bytes.
     */
    private void hookAirPodsScanFilter(ClassLoader loader) {
        try {
            Class<?> builder = Class.forName("android.bluetooth.le.ScanFilter$Builder", false, loader);
            for (Method method : builder.getDeclaredMethods()) {
                if (!"setManufacturerData".equals(method.getName())
                        || method.getParameterCount() != 3
                        || method.getParameterTypes()[0] != int.class
                        || method.getParameterTypes()[1] != byte[].class) continue;
                hook(method).intercept(chain -> {
                    int manufacturer = (int) chain.getArg(0);
                    Object raw = chain.getArg(1);
                    if (manufacturer == 76 && raw instanceof byte[]) {
                        byte[] data = (byte[]) raw;
                        if (data.length >= 2 && data[0] == 7 && (data[1] == 25 || data[1] == 15)) {
                            byte[] broadened = new byte[]{7, data[1]};
                            byte[] mask = new byte[]{(byte) 0xFF, (byte) 0xFF};
                            Logx.i("AirPods scan filter broadened for subtype " + (data[1] & 0xFF));
                            return chain.proceed(new Object[]{manufacturer, broadened, mask});
                        }
                    }
                    return chain.proceed();
                });
                Logx.i("AirPods scan filter hook installed");
            }
        } catch (Throwable error) {
            Logx.w("AirPods scan filter hook failed", error);
        }
    }

    private void hookPtcRegistration(ClassLoader loader) {
        try {
            Class<?> helper = Class.forName(
                    "com.heytap.mydevices.plugin.airpods.core.AirpodsScanHelper", false, loader);
            for (Method method : helper.getDeclaredMethods()) {
                if (!"registerPtc".equals(method.getName()) || method.getParameterCount() != 0) continue;
                hook(method).intercept(chain -> {
                    Logx.i("official AirPods PTC registration requested");
                    Object result = chain.proceed();
                    try {
                        Field registered = helper.getDeclaredField("mIsPtcRegistered");
                        registered.setAccessible(true);
                        Logx.i("official AirPods PTC registered=" + registered.getBoolean(chain.getThisObject()));
                    } catch (Throwable error) {
                        Logx.w("PTC registration state unavailable", error);
                    }
                    return result;
                });
                Logx.i("AirPods PTC registration hook installed");
            }
        } catch (Throwable error) {
            Logx.w("AirPods PTC registration hook failed", error);
        }
    }

    private void hookAirPodsSupport(ClassLoader loader) {
        try {
            Class<?> support = findClass(loader, "aa.b2$a", "aa.AbstractC0958b2$a");
            hookNoArg(support, "r", true, "AirPods feature support");
            try {
                Method mask = findMethod(support, "J",
                        method -> method.getParameterCount() == 1
                                && method.getParameterTypes()[0] == int.class
                                && !Modifier.isStatic(method.getModifiers()));
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
                    OfficialDeviceStateBridge.register(chain.getThisObject());
                    try {
                        Field managerField = findFieldWithMethod(policy, "registerPtc");
                        Object manager = managerField.get(chain.getThisObject());
                        if (manager != null) manager.getClass().getMethod("registerPtc").invoke(manager);
                    } catch (Throwable error) {
                        Logx.w("registerPtc fallback failed", error);
                    }
                    return result;
                });
            }
            try {
                Method battery = policy.getDeclaredMethod(
                        "onBatteryLevelChanged", BluetoothDevice.class, int[].class);
                hook(battery).intercept(chain -> {
                    Object target = chain.getThisObject();
                    OfficialDeviceStateBridge.register(target);
                    if (!OfficialDeviceStateBridge.applying()) {
                        try {
                            Context context = appContext != null ? appContext : currentApplication();
                            if (context != null) {
                                AirPodsController controller = AirPodsController.get(context);
                                if (controller.hasBatterySnapshot()) {
                                    Logx.i("official device battery replaced with AACP state");
                                    return chain.proceed(new Object[]{
                                            chain.getArg(0), controller.officialBatteryArray()});
                                }
                            }
                        } catch (Throwable error) {
                            Logx.w("official device battery replacement failed", error);
                        }
                    }
                    return chain.proceed();
                });
                Logx.i("official device battery hook installed");
            } catch (Throwable error) {
                Logx.w("official device battery hook failed", error);
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
            Logx.i("PTC listener hook installed");
        } catch (Throwable error) {
            Logx.w("PTC listener hook failed", error);
        }
    }

    private void hookFusionEvents(ClassLoader loader) {
        try {
            Class<?> impl = Class.forName(
                    "com.oplus.pantaconnect.sdk.discovery.fusion.FusionServiceImpl", false, loader);
            Method handleEvents = null;
            for (Method method : impl.getDeclaredMethods()) {
                if ("handleEvents".equals(method.getName()) && method.getParameterCount() == 2) {
                    handleEvents = method;
                    break;
                }
            }
            if (handleEvents == null) throw new NoSuchMethodException("FusionServiceImpl.handleEvents");
            hook(handleEvents).intercept(chain -> {
                try {
                    Object event = chain.getArg(1);
                    if (event != null) {
                        String type = (String) event.getClass().getMethod("getType").invoke(event);
                        if ("onServiceFound".equals(type)) {
                            Object args = event.getClass().getMethod("getArgs").invoke(event);
                            Class<?> params = Class.forName(
                                    "com.oplus.pantaconnect.fusionservice.ServiceNodeParams", false, loader);
                            Class<?> bytes = Class.forName("com.google.protobuf.ByteString", false, loader);
                            Object parsed = params.getMethod("parseFrom", bytes).invoke(null, args);
                            Class<?> nodeKt = Class.forName(
                                    "com.oplus.pantaconnect.sdk.discovery.fusion.ServiceNodeKt", false, loader);
                            Object node = nodeKt.getMethod("toServiceNode", params).invoke(null, parsed);
                            handleServiceNode(node);
                        }
                    }
                } catch (Throwable error) {
                    Logx.w("PTC fusion event handling failed", error);
                }
                return chain.proceed();
            });
            Logx.i("PTC fusion event hook installed");
        } catch (Throwable error) {
            Logx.w("PTC fusion event hook failed", error);
        }
    }

    private void installAccessoryPtc(ClassLoader loader) {
        try {
            Class<?> output = Class.forName(
                    "com.oplus.pantaconnect.discovery.core.discover.result.output.m", false, loader);
            Method handle = null;
            for (Method method : output.getDeclaredMethods()) {
                if ("a".equals(method.getName()) && method.getParameterCount() == 1) {
                    handle = method;
                    break;
                }
            }
            if (handle == null) throw new NoSuchMethodException("Accessory BLE output.a");
            hook(handle).intercept(chain -> {
                try {
                    Object scanResult = findFieldValue(chain.getArg(0), ScanResult.class);
                    if (scanResult instanceof ScanResult && isAppleProximity((ScanResult) scanResult)) {
                        Logx.i("official Accessory Apple BLE result");
                        detachAirPodsListener(chain.getThisObject());
                    }
                } catch (Throwable error) {
                    Logx.w("Accessory BLE handling failed", error);
                }
                return chain.proceed();
            });
            Context context = currentApplication();
            if (context != null) Logx.setContext(context);
            Logx.i("official Accessory PTC output hook installed");
        } catch (Throwable error) {
            Logx.w("Accessory PTC output hook failed", error);
        }
    }

    private static boolean isAppleProximity(ScanResult result) {
        ScanRecord record = result == null ? null : result.getScanRecord();
        if (record == null) return false;
        byte[] data = record.getManufacturerSpecificData(76);
        return data != null && data.length >= 2 && data[0] == 7;
    }

    private static void detachAirPodsListener(Object output) throws Exception {
        Object provider = readField(output, "f10092b");
        if (provider == null) return;
        Object raw = readField(provider, "f10294e");
        if (!(raw instanceof Map)) return;
        int detached = 0;
        for (Object value : ((Map<?, ?>) raw).values()) {
            if (value == null || !isAppleDiscovery(value)) continue;
            Object listener = readField(value, "f10462c");
            if (listener != null) {
                writeField(value, "f10462c", null);
                detached++;
            }
        }
        if (detached > 0) Logx.i("official Accessory PTC listener detached=" + detached);
    }

    private static boolean isAppleDiscovery(Object value) {
        try {
            Object options = readField(value, "f10461b");
            Object filter = call(options, "getDiscoveryFilter");
            Object filters = call(filter, "getLeScanFilter");
            if (!(filters instanceof Iterable)) return false;
            for (Object item : (Iterable<?>) filters) {
                if (item instanceof ScanFilter && ((ScanFilter) item).getManufacturerId() == 76) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void hookCoreService(ClassLoader loader) {
        try {
            Class<?> core = Class.forName("com.heytap.mydevices.core.service.CoreService", false, loader);
            Method onStart = core.getDeclaredMethod("onStartCommand", Intent.class, int.class, int.class);
            hook(onStart).intercept(chain -> {
                try {
                    Object arg = chain.getArg(0);
                    if (arg instanceof Intent) {
                        Bundle bundle = ((Intent) arg).getBundleExtra("serviceNode");
                        if (bundle != null) {
                            Class<?> nodeKt = Class.forName(
                                    "com.oplus.pantaconnect.sdk.discovery.fusion.ServiceNodeKt", false, loader);
                            Object node = nodeKt.getMethod("toServiceNode", Bundle.class).invoke(null, bundle);
                            handleServiceNode(node);
                        }
                    }
                } catch (Throwable error) {
                    Logx.w("CoreService PTC wakeup handling failed", error);
                }
                return chain.proceed();
            });
            Logx.i("CoreService PTC wakeup hook installed");
        } catch (Throwable error) {
            Logx.w("CoreService PTC wakeup hook failed", error);
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
            Method bind = findMethod(seekPreference, "Z",
                    method -> method.getParameterCount() == 1
                            && method.getReturnType() == void.class
                            && !Modifier.isStatic(method.getModifiers()));
            if (bind == null) throw new NoSuchMethodException("SeekBarPreference.Z");
            hook(bind).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object preference = chain.getThisObject();
                    if (SliderRegistry.isOurs(preference)) {
                        Field field = findField(seekPreference, SeekBar.class);
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
                            int top = Math.round(density * 12.0f);
                            int bottom = Math.round(density * 16.0f);
                            item.setPadding(0, top, 0, bottom);
                            if (bar.getParent() != null && bar.getParent().getParent() instanceof View) {
                                final View content = (View) bar.getParent().getParent();
                                final int inset = Math.round(density * 24.0f);
                                item.post(() -> {
                                    int targetWidth = item.getWidth() - inset * 2;
                                    if (targetWidth <= 0) return;
                                    ViewGroup.LayoutParams params = content.getLayoutParams();
                                    params.width = targetWidth;
                                    content.setLayoutParams(params);
                                    item.post(() -> content.setTranslationX(inset - content.getLeft()));
                                });
                            }

                            View title = item.findViewById(android.R.id.title);
                            Field valueField = findField(seekPreference, TextView.class);
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
                                int rowPadding = Math.round(density * 4.0f);
                                titleRow.setPadding(rowPadding, 0, rowPadding, 0);
                                RelativeLayout.LayoutParams valueParams = new RelativeLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT);
                                valueParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
                                valueParams.addRule(RelativeLayout.CENTER_VERTICAL);
                                titleRow.addView(valueText, valueParams);
                            }
                            if (bar.getParent() instanceof View) {
                                View sliderRow = (View) bar.getParent();
                                ViewGroup.LayoutParams rowParams = sliderRow.getLayoutParams();
                                rowParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                                sliderRow.setLayoutParams(rowParams);
                                sliderRow.setPadding(0, sliderRow.getPaddingTop(),
                                        0, sliderRow.getPaddingBottom());
                                ViewGroup.LayoutParams barParams = bar.getLayoutParams();
                                barParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                                barParams.height = Math.round(density * 32.0f);
                                bar.setLayoutParams(barParams);
                                bar.setPadding(0, bar.getPaddingTop(), 0, bar.getPaddingBottom());
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
                    FeatureSupportEnabler.attach(chain.getThisObject());
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
                        if (context != null) {
                            AirPodsController.get(context).onSettingsPageClosed();
                            FeatureSupportEnabler.release(chain.getThisObject());
                        }
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
                    int mode = (int) chain.getArg(0);
                    try {
                        if (mode >= 1 && mode <= 4) {
                            Context context = appContext != null ? appContext : currentApplication();
                            if (context != null) {
                                AirPodsController controller = AirPodsController.get(context);
                                controller.setControlState(AacpProtocol.CONTROL_LISTENING_MODE, mode);
                                controller.sendControl(AacpProtocol.CONTROL_LISTENING_MODE, mode);
                            }
                            call(chain.getThisObject(), "updateCurrentFeature",
                                    new Class<?>[]{int.class}, new Object[]{mode});
                            return null;
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
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
                    int type = (int) chain.getArg(0);
                    int value = (int) chain.getArg(1);
                    int controlId = type == 4 ? AacpProtocol.CONTROL_LISTENING_MODE
                            : type == 16 ? AacpProtocol.CONTROL_CONVERSATION
                            : type == 32 ? AacpProtocol.CONTROL_EAR_DETECTION : -1;
                    if (controlId >= 0) {
                        Context context = appContext != null ? appContext : currentApplication();
                        if (context != null) {
                            AirPodsController controller = AirPodsController.get(context);
                            int controlValue = type == 4 ? value : (value == 1 ? 1 : 2);
                            controller.setControlState(controlId, controlValue);
                            controller.sendControl(controlId, controlValue);
                            Logx.i("official feature -> AACP type=" + type
                                    + " id=0x" + Integer.toHexString(controlId)
                                    + " value=" + controlValue);
                        }
                        return true;
                    }
                    try {
                        return chain.proceed();
                    } catch (Throwable error) {
                        Logx.w("AACP noise fallback failed", error);
                        return false;
                    }
                });
            } catch (Throwable error) {
                Logx.w("view model noise hook failed", error);
            }
        } catch (Throwable error) {
            Logx.w("settings activity hook failed", error);
        }
    }

    private void hookOfficialBatteryUi(ClassLoader loader) {
        try {
            Class<?> content = Class.forName(
                    "com.heytap.mydevices.plugin.airpods.ui.AirPodsDialogFragment$AirPodsDialogContentFragment",
                    false, loader);
            Method update = content.getDeclaredMethod("t0", int[].class);
            hook(update).intercept(chain -> {
                Object target = chain.getThisObject();
                int[] battery = (int[]) chain.getArg(0);
                Object result = chain.proceed();
                try {
                    Context context = appContext != null ? appContext : currentApplication();
                    if (context != null) {
                        OfficialBatteryBridge.onOfficialPopup(
                                target, battery, AirPodsController.get(context));
                    }
                } catch (Throwable error) {
                    Logx.w("official popup battery bridge failed", error);
                }
                return result;
            });
            Logx.i("official popup battery hook installed");
        } catch (Throwable error) {
            Logx.w("official popup battery hook failed", error);
        }

        try {
            Class<?> fragment = Class.forName(
                    "com.heytap.mydevices.plugin.airpods.fragment.AirpodsDetailPreferencesFragment",
                    false, loader);
            Method init = fragment.getDeclaredMethod("initAirpodsCardAppearance");
            hook(init).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object binder = readField(chain.getThisObject(), "airpodsBatteryBinder");
                    OfficialBatteryBridge.registerDetail(binder);
                    hookDetailBatteryBinder(binder);
                    Context context = appContext != null ? appContext : currentApplication();
                    if (context != null) {
                        OfficialBatteryBridge.schedule(AirPodsController.get(context));
                    }
                } catch (Throwable error) {
                    Logx.w("official detail battery bridge failed", error);
                }
                return result;
            });
            Logx.i("official detail battery hook installed");
        } catch (Throwable error) {
            Logx.w("official detail battery hook failed", error);
        }
    }

    private void hookDetailBatteryBinder(Object binder) {
        if (binder == null || DETAIL_BATTERY_HOOKED.get()) return;
        try {
            Method update = findMethod(binder.getClass(), "a", method ->
                    method.getReturnType() == void.class
                            && method.getParameterCount() == 2
                            && method.getParameterTypes()[0] == int[].class
                            && method.getParameterTypes()[1] == boolean.class);
            hook(update).intercept(chain -> {
                Object target = chain.getThisObject();
                int[] battery = (int[]) chain.getArg(0);
                Object result = chain.proceed();
                try {
                    Context context = appContext != null ? appContext : currentApplication();
                    if (context != null) {
                        OfficialBatteryBridge.onOfficialDetail(
                                target, battery, AirPodsController.get(context));
                    }
                } catch (Throwable error) {
                    Logx.w("official detail battery refresh bridge failed", error);
                }
                return result;
            });
            DETAIL_BATTERY_HOOKED.set(true);
            Logx.i("official detail battery binder hook installed");
        } catch (Throwable error) {
            Logx.w("official detail battery binder hook failed", error);
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
        byte[] data = (byte[]) raw;
        int logIndex = PTC_LOGS.getAndIncrement();
        if (logIndex < 20) {
            Logx.i("PTC service data len=" + data.length + " hex=" + hexPrefix(data));
        }
        AppleAdvertisement.LidInfo lid = AppleAdvertisement.parse(data);
        if (logIndex < 20 && lid == null) {
            Logx.i("PTC service data not parsed as AirPods proximity");
        }
        if (lid == null || !lid.lidOpen) {
            if (logIndex < 20 && lid != null) {
                Logx.i("PTC service data parsed product=0x"
                        + Integer.toHexString(lid.product) + " lidOpen=" + lid.lidOpen);
            }
            return;
        }
        Context context = appContext != null ? appContext : currentApplication();
        if (context == null) return;
        AirPodsController controller = AirPodsController.get(context);
        BluetoothDevice device = controller.findBondedAirPods();
        controller.onCaseAdvertisement(device, lid.model, true);
    }

    private static String hexPrefix(byte[] data) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < Math.min(24, data.length); i++) {
            if (i > 0) value.append(' ');
            String part = Integer.toHexString(data[i] & 0xFF).toUpperCase(java.util.Locale.ROOT);
            if (part.length() == 1) value.append('0');
            value.append(part);
        }
        return value.toString();
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

    private static Class<?> findClass(ClassLoader loader, String... names) throws ClassNotFoundException {
        ClassNotFoundException last = null;
        for (String name : names) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException error) {
                last = error;
            }
        }
        throw last == null ? new ClassNotFoundException("no candidate") : last;
    }

    private static Method findMethod(Class<?> owner, String preferred, Predicate<Method> predicate)
            throws NoSuchMethodException {
        for (Method method : owner.getDeclaredMethods()) {
            if (preferred.equals(method.getName()) && predicate.test(method)) return method;
        }
        for (Method method : owner.getDeclaredMethods()) {
            if (predicate.test(method)) return method;
        }
        throw new NoSuchMethodException(owner.getName() + "." + preferred);
    }

    private static Field findField(Class<?> owner, Class<?> fieldType) throws NoSuchFieldException {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() == fieldType && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        throw new NoSuchFieldException(owner.getName() + " field of " + fieldType.getName());
    }

    private static Object findFieldValue(Object target, Class<?> fieldType) {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != fieldType) continue;
                try {
                    field.setAccessible(true);
                    return field.get(target);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Object readField(Object target, String name) {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void writeField(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Field findFieldWithMethod(Class<?> owner, String methodName) throws NoSuchFieldException {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.getType().getMethod(methodName);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        throw new NoSuchFieldException(owner.getName() + " field providing " + methodName);
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

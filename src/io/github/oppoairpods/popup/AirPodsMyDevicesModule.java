package io.github.oppoairpods.popup;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
    private static final String COUI_SLIDER_TAG = "opods_coui_slider_card";
    private static final Object INSTALL_LOCK = new Object();
    private static boolean myDevicesHooksInstalled;
    private static boolean aapOffFilterInstalled;
    private static final java.util.concurrent.atomic.AtomicBoolean DETAIL_BATTERY_HOOKED =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile Context appContext;
    private static final java.util.concurrent.atomic.AtomicInteger PTC_LOGS =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicBoolean ADJUST_ERROR_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

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
        hookPtcRegistration(loader);
        hookAirPodsSupport(loader);
        hookOfficialPopup(loader);
        hookScanCallback(loader);
        hookFusionEvents(loader);
        hookCoreService(loader);
        hookLidWake(loader);
        hookInnerIpc(loader);
        hookAirpodsIpc(loader);
        hookDiscoveryActivity(loader);
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

    // The ROM's own AirPods filters ({7,25,7} / {7,15,0}) only match pairing-mode messages and
    // stay as they are, so its new-device popup keeps working. Lid opening of a paired pair is
    // matched by LidRelay's filter inside the Accessory scan instead of widening these: a
    // two-byte filter passed every AirPods message nearby and woke MyDevices for each of them.

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
                    Context context = appContext != null ? appContext : currentApplication();
                    OfficialDeviceStateBridge.register(chain.getThisObject(), context);
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
                    Context context = appContext != null ? appContext : currentApplication();
                    OfficialDeviceStateBridge.register(target, context);
                    if (!OfficialDeviceStateBridge.applying()) {
                        try {
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

    /**
     * Accessory: the ROM's always-resident persistent BLE scan (the one OPPO's own earbuds use,
     * screen-on only) gets LidRelay's lid-open filter, and its FusionProvider output hands AirPods
     * status messages to LidRelay, which wakes MyDevices :cards once per lid opening.
     */
    private void installAccessoryPtc(ClassLoader loader) {
        Context context = currentApplication();
        if (context != null) Logx.setContext(context);
        try {
            Class<?> provider = Class.forName(
                    "com.oplus.pantaconnect.discovery.fusionprovider.FusionProvider", false, loader);
            // d(): the persistent filters merged into the screen-on scan and matched per result.
            // No signature fallback: a renamed d() must fail here rather than get a filter added
            // to some other list.
            Method filters = provider.getDeclaredMethod("d");
            if (!java.util.List.class.isAssignableFrom(filters.getReturnType())) {
                throw new NoSuchMethodException("FusionProvider.d() no longer returns a List");
            }
            hook(filters).intercept(chain -> {
                Object result = chain.proceed();
                if (result instanceof java.util.List) {
                    try {
                        LidRelay.adjustPersistentFilters((java.util.List<?>) result, accessoryContext());
                    } catch (Throwable error) {
                        if (ADJUST_ERROR_LOGGED.compareAndSet(false, true)) {
                            Logx.w("persistent filter adjust failed", error);
                        }
                    }
                }
                return result;
            });
            Logx.i("official Accessory persistent filter hook installed");
        } catch (Throwable error) {
            Logx.w("official Accessory persistent filter hook failed", error);
        }
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
                    Object result = chain.getArg(0);
                    Object scanResult = findFieldValue(result, ScanResult.class);
                    if (scanResult instanceof ScanResult) {
                        // The persistent filter the ROM matched this result with (n0.f).
                        Object matched = findFieldValue(result, android.bluetooth.le.ScanFilter.class);
                        if (LidRelay.offer(accessoryContext(), (ScanResult) scanResult, matched)) return null;
                    }
                } catch (Throwable error) {
                    Logx.w("Accessory BLE handling failed", error);
                }
                return chain.proceed();
            });
            Logx.i("official Accessory PTC output hook installed");
        } catch (Throwable error) {
            Logx.w("Accessory PTC output hook failed", error);
        }
    }

    private static volatile Context accessoryContext;

    private static Context accessoryContext() {
        Context local = accessoryContext;
        if (local == null) {
            local = currentApplication();
            if (local != null) {
                accessoryContext = local;
                Logx.setContext(local);
            }
        }
        return local;
    }

    /**
     * :cards: LidRelay's wake arrives as a bind of the ROM's Accessory keep-alive service. The
     * official binder is still returned; only the wake extras are read.
     */
    private void hookLidWake(ClassLoader loader) {
        try {
            Class<?> service = Class.forName(LidRelay.OAF_SERVICE, false, loader);
            Method onBind = service.getDeclaredMethod("onBind", Intent.class);
            hook(onBind).intercept(chain -> {
                try {
                    Object arg = chain.getArg(0);
                    if (arg instanceof Intent) LidRelay.handleWake((Context) chain.getThisObject(), (Intent) arg);
                } catch (Throwable error) {
                    Logx.w("lid wake handling failed", error);
                }
                return chain.proceed();
            });
            Logx.i("official keep-alive lid wake hook installed");
        } catch (Throwable error) {
            Logx.w("official keep-alive lid wake hook failed", error);
        }
    }

    private void hookCoreService(ClassLoader loader) {
        try {
            Class<?> core = Class.forName("com.heytap.mydevices.core.service.CoreService", false, loader);
            Method onStart = core.getDeclaredMethod("onStartCommand", Intent.class, int.class, int.class);
            hook(onStart).intercept(chain -> {
                try {
                    Object arg = chain.getArg(0);
                    // LidRelay's fallback wake when :cards could not be bound.
                    if (arg instanceof Intent && LidRelay.handleWake((Context) chain.getThisObject(), (Intent) arg)) {
                        return chain.proceed();
                    }
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

    private void hookInnerIpc(ClassLoader loader) {
        try {
            Class<?> ipc = Class.forName("com.oplus.mydevices.deviceui.ipc.InnerIpcManager", false, loader);
            for (Constructor<?> constructor : ipc.getDeclaredConstructors()) {
                if (constructor.getParameterCount() != 2) continue;
                hook(constructor).intercept(chain -> {
                    Object result = chain.proceed();
                    OfficialCoreBridge.registerIpcManager(chain.getThisObject());
                    return result;
                });
            }
            Logx.i("official core IPC manager hook installed");
        } catch (Throwable error) {
            Logx.w("official core IPC manager hook failed", error);
        }
    }

    private void hookAirpodsIpc(ClassLoader loader) {
        try {
            Class<?> manager = Class.forName(
                    "com.heytap.mydevices.plugin.airpods.core.AirpodsManager", false, loader);
            Method process = findMethod(manager, "process", method ->
                    method.getReturnType() == String.class
                            && method.getParameterCount() == 4
                            && method.getParameterTypes()[0] == String.class
                            && method.getParameterTypes()[1] == String.class
                            && method.getParameterTypes()[2] == Bundle.class
                            && method.getParameterTypes()[3] == int.class);
            hook(process).intercept(chain -> {
                if (OfficialCoreBridge.PARAM_BATTERY.equals(chain.getArg(0))) {
                    Context context = appContext != null ? appContext : currentApplication();
                    if (context != null) {
                        OfficialDeviceStateBridge.applyPayload(context, (String) chain.getArg(1));
                    }
                    return "";
                }
                return chain.proceed();
            });
            Logx.i("AirPods core battery IPC hook installed");
        } catch (Throwable error) {
            Logx.w("AirPods core battery IPC hook failed", error);
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

    private void hookDiscoveryActivity(ClassLoader loader) {
        try {
            Class<?> activity = Class.forName(
                    "com.heytap.mydevices.plugin.airpods.ui.AirpodsDiscoveryActivity", false, loader);
            // Resolve both first: a visible flag without its matching onPause hook would stay set.
            Method onResume = activity.getDeclaredMethod("onResume");
            Method onPause = activity.getDeclaredMethod("onPause");
            hook(onResume).intercept(chain -> {
                OfficialCoreBridge.capture(chain.getThisObject());
                Object result = chain.proceed();
                try {
                    Context context = appContext != null ? appContext : currentApplication();
                    if (context != null) {
                        AirPodsController controller = AirPodsController.get(context);
                        controller.onPopupVisible(discoveryDevice(chain.getThisObject(), controller));
                    }
                } catch (Throwable error) {
                    Logx.w("discovery battery session failed", error);
                }
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) requestOfficialConnect(self, ((Activity) self).getIntent());
                } catch (Throwable error) {
                    Logx.w("official popup connect failed", error);
                }
                return result;
            });
            hook(onPause).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Context context = appContext != null ? appContext : currentApplication();
                    if (context != null) AirPodsController.get(context).onPopupHidden();
                } catch (Throwable error) {
                    Logx.w("discovery battery pause handling failed", error);
                }
                return result;
            });
            try {
                // singleInstance: a second request reaches the open popup here, not in onCreate.
                Method onNewIntent = activity.getDeclaredMethod("onNewIntent", Intent.class);
                hook(onNewIntent).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object arg = chain.getArg(0);
                        if (arg instanceof Intent) requestOfficialConnect(chain.getThisObject(), (Intent) arg);
                    } catch (Throwable error) {
                        Logx.w("official popup connect failed", error);
                    }
                    return result;
                });
            } catch (Throwable error) {
                Logx.w("official popup onNewIntent hook failed", error);
            }
            Logx.i("official AirPods discovery battery hook installed");
        } catch (Throwable error) {
            Logx.w("official AirPods discovery battery hook failed", error);
        }
    }

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * A popup opened for a pair that is not connected: run the popup's own connect, the same
     * call as its "连接" / "重试" buttons (AirpodsDiscoveryViewModel.startConnect ->
     * AirpodsBluetoothManager setDevice + startBound -> connect() for a bonded device). The
     * official UI then shows connecting, connected with battery, or the retry panel.
     */
    private static void requestOfficialConnect(Object activity, Intent intent) {
        if (activity == null || intent == null) return;
        if (!intent.getBooleanExtra(AirPodsController.EXTRA_LID_CONNECT, false)) return;
        intent.removeExtra(AirPodsController.EXTRA_LID_CONNECT); // once per request
        // After the popup's own onResume / fragment commit, like a tap on its button.
        MAIN.post(() -> {
            try {
                Object viewModel = call(activity, "getViewModel");
                if (viewModel == null) throw new IllegalStateException("no popup view model");
                viewModel.getClass().getMethod("startConnect").invoke(viewModel);
                Logx.i("official popup connect started");
            } catch (Throwable error) {
                Logx.w("official popup connect failed", error);
            }
        });
    }

    /** The pair the official popup was started for: its Intent first, then its view model. */
    private static BluetoothDevice discoveryDevice(Object activity, AirPodsController controller) {
        BluetoothDevice device = null;
        try {
            if (activity instanceof Activity) {
                Intent intent = ((Activity) activity).getIntent();
                if (intent != null) {
                    device = intent.getParcelableExtra("bluetooth_device", BluetoothDevice.class);
                    if (device == null) {
                        device = intent.getParcelableExtra(AirPodsController.EXTRA_LID_DEVICE, BluetoothDevice.class);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (device == null) device = viewModelDevice(activity);
        return officialDevice(device, controller);
    }

    private static BluetoothDevice viewModelDevice(Object owner) {
        try {
            Object viewModel = call(owner, "getViewModel");
            if (viewModel == null) return null;
            Object value = viewModel.getClass().getMethod("getBluetoothDevice").invoke(viewModel);
            return value instanceof BluetoothDevice ? (BluetoothDevice) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static BluetoothDevice officialDevice(BluetoothDevice candidate, AirPodsController controller) {
        if (candidate != null && controller.isAirPods(candidate)) return candidate;
        return controller.findActiveAirPods();
    }

    private void hookDetailPanel(ClassLoader loader) {
        try {
            Class<?> panel = Class.forName(
                    "com.heytap.mydevices.plugin.airpods.ui.AirpodsDetailPanelFragment",
                    false, loader);
            Method bind = panel.getDeclaredMethod("bindPanelBodyViews", View.class);
            Method init = panel.getDeclaredMethod("initView", View.class);
            Method clear = panel.getDeclaredMethod("clearPanelBodyViewRefs");
            hook(bind).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    QuickNoiseInjector.install(chain.getThisObject(), (View) chain.getArg(0));
                } catch (Throwable error) {
                    Logx.w("quick noise panel injection failed", error);
                }
                return result;
            });
            hook(init).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    QuickNoiseInjector.install(chain.getThisObject(), (View) chain.getArg(0));
                } catch (Throwable error) {
                    Logx.w("quick noise panel init failed", error);
                }
                return result;
            });
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
                            bar.setBackground(new SliderTickDrawable(bar, density));
                            bar.setProgressDrawable(new ColorDrawable(Color.TRANSPARENT));
                            bar.setThumb(new CouiSliderThumbDrawable(density));
                            bar.setThumbOffset(0);
                            int sidePadding = Math.round(density * 2.0f);
                            bar.setPadding(sidePadding, 0, sidePadding, 0);
                            bar.setKeyProgressIncrement(25);
                            bar.setSplitTrack(false);
                            Object holder = chain.getArg(0);
                            View item = (View) holder.getClass().getField("itemView").get(holder);
                            boolean needsWrap = !COUI_SLIDER_TAG.equals(item.getTag());
                            GradientDrawable card = new GradientDrawable();
                            card.setColor(Color.WHITE);
                            card.setCornerRadius(density * 24.0f);
                            if (needsWrap) {
                                item.setBackground(null);
                                item.setPadding(0, 0, 0, 0);
                                View content = (View) bar.getParent().getParent();
                                if (content.getParent() instanceof LinearLayout) {
                                    LinearLayout cardHolder = (LinearLayout) content.getParent();
                                    cardHolder.removeAllViews();
                                    cardHolder.setOrientation(LinearLayout.VERTICAL);
                                    cardHolder.setClipChildren(false);
                                    cardHolder.setClipToPadding(false);
                                    LinearLayout cardView = new LinearLayout(bar.getContext());
                                    cardView.setOrientation(LinearLayout.VERTICAL);
                                    cardView.setBackground(card);
                                    int horizontal = Math.round(density * 16.0f);
                                    int vertical = Math.round(density * 5.0f);
                                    cardView.setPadding(horizontal, vertical, horizontal, vertical);
                                    cardView.addView(content, new LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT));
                                    LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT);
                                    cardParams.setMargins(horizontal, 0, horizontal, 0);
                                    cardHolder.addView(cardView, cardParams);
                                    item.setTag(COUI_SLIDER_TAG);
                                }
                            }
                            item.setTranslationY(-density * 12.0f);

                            View title = item.findViewById(android.R.id.title);
                            Field valueField = findField(seekPreference, TextView.class);
                            Object valueView = valueField.get(preference);
                            if (title instanceof TextView) {
                                TextView titleText = (TextView) title;
                                titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                                titleText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                                titleText.setTextColor(Color.BLACK);
                            }
                            if (valueView instanceof TextView) {
                                TextView valueText = (TextView) valueView;
                                valueText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                                valueText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                                valueText.setTextColor(0xFF7A7E85);
                            }
                            if (title != null && valueView instanceof View
                                    && title.getParent() instanceof RelativeLayout
                                    && ((View) valueView).getParent() instanceof ViewGroup) {
                                RelativeLayout titleRow = (RelativeLayout) title.getParent();
                                ViewGroup.LayoutParams rowParams = titleRow.getLayoutParams();
                                rowParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                                titleRow.setLayoutParams(rowParams);
                                View valueText = (View) valueView;
                                ((ViewGroup) valueText.getParent()).removeView(valueText);
                                titleRow.setPadding(0, 0, 0, 0);
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
                                if (rowParams instanceof LinearLayout.LayoutParams) {
                                    ((LinearLayout.LayoutParams) rowParams).topMargin =
                                            Math.round(density * 6.0f);
                                }
                                sliderRow.setLayoutParams(rowParams);
                                sliderRow.setPadding(0, sliderRow.getPaddingTop(),
                                        0, sliderRow.getPaddingBottom());
                                ViewGroup.LayoutParams barParams = bar.getLayoutParams();
                                barParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                                barParams.height = Math.round(density * 28.0f);
                                bar.setLayoutParams(barParams);
                                bar.setPadding(sidePadding, bar.getPaddingTop(),
                                        sidePadding, bar.getPaddingBottom());
                            }
                            if (needsWrap) Logx.i("COUI slider installed");
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
                OfficialCoreBridge.capture(chain.getThisObject());
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
                Method onStop = activity.getDeclaredMethod("onStop");
                hook(onStop).intercept(chain -> {
                    try {
                        Context context = appContext != null ? appContext : currentApplication();
                        if (context != null) AirPodsController.get(context).onSettingsPageClosed();
                    } catch (Throwable error) {
                        Logx.w("settings stop handling failed", error);
                    }
                    return chain.proceed();
                });
            } catch (Throwable error) {
                Logx.w("settings onStop hook failed", error);
            }
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
                                AirPodsController.get(context).selectListeningMode(mode);
                            }
                            // The bar already shows the tapped item; only sync the ROM's state.
                            if (!FeatureSupportEnabler.showMode(chain.getThisObject(), mode)) {
                                call(chain.getThisObject(), "updateCurrentFeature",
                                        new Class<?>[]{int.class}, new Object[]{mode});
                            }
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
                // The ROM refreshes the noise bar from its own Bluetooth callback and on resume;
                // its value lags the AACP report and updateCurrentFeature rebuilds every item.
                Method updateFeature = activity.getDeclaredMethod("updateCurrentFeature", int.class);
                hook(updateFeature).intercept(chain -> {
                    int requested = (int) chain.getArg(0);
                    int target = requested;
                    try {
                        Context context = appContext != null ? appContext : currentApplication();
                        if (context != null) {
                            int ours = AirPodsController.get(context).displayedListeningMode();
                            if (ours >= 1 && ours <= 4) target = ours;
                        }
                        if (FeatureSupportEnabler.showMode(chain.getThisObject(), target)) {
                            if (target != requested) {
                                Logx.i("official noise update " + requested + " -> AACP mode " + target);
                            }
                            return null;
                        }
                    } catch (Throwable error) {
                        Logx.w("official noise update redirect failed", error);
                    }
                    return target == requested ? chain.proceed() : chain.proceed(new Object[]{target});
                });
            } catch (Throwable error) {
                Logx.w("noise bar update hook failed", error);
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
                            if (type == 4) {
                                controller.selectListeningMode(controlValue);
                            } else {
                                controller.setControlState(controlId, controlValue);
                                controller.sendControl(controlId, controlValue);
                            }
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
                // Our own repaint carries nothing new from the ROM.
                if (OfficialBatteryBridge.applying()) return chain.proceed();
                Object target = chain.getThisObject();
                int[] battery = (int[]) chain.getArg(0);
                OfficialCoreBridge.capture(target);
                Object result = chain.proceed();
                try {
                    Context context = appContext != null ? appContext : currentApplication();
                    if (context != null) {
                        AirPodsController controller = AirPodsController.get(context);
                        controller.ensureBatterySession(officialDevice(viewModelDevice(target), controller));
                        OfficialBatteryBridge.onOfficialPopup(target, battery, controller);
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
        String bleAddress = terminalAddress(node);
        int logIndex = PTC_LOGS.getAndIncrement();
        if (logIndex < 20) {
            Logx.i("PTC service data len=" + data.length + " hex=" + hexPrefix(data));
        }
        AppleAdvertisement.LidInfo lid = AppleAdvertisement.parse(data);
        if (lid == null) {
            if (logIndex < 20) Logx.i("PTC service data not parsed as AirPods proximity");
            return;
        }
        if (!lid.lidOpen) {
            if (logIndex < 20) {
                Logx.i("PTC service data parsed product=0x" + Integer.toHexString(lid.product) + " lidOpen=false");
            }
            return;
        }
        Context context = appContext != null ? appContext : currentApplication();
        if (context == null) return;
        AirPodsController.get(context).onCaseAdvertisement(bleAddress, lid);
    }

    /** BLE address the official scanner reported (an RPA unless the stack resolved it). */
    private static String terminalAddress(Object node) {
        try {
            Object terminal = node.getClass().getMethod("getTerminalInfo").invoke(node);
            Object value = terminal == null ? null : terminal.getClass().getMethod("getDeviceAddress").invoke(terminal);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
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

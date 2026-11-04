package io.github.oppoairpods.popup;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Card dashboard shown from the official MyDevices AirPods settings page. */
final class AirPodsDashboard {
    private static final String[] TAB_NAMES = {"常用", "音频", "操控", "连接", "健康", "设备", "关于"};
    private static final String[] ACTION_NAMES = {"播放/暂停", "下一首", "上一首", "语音助手", "噪声模式"};
    private static final int[] PRESS_TYPES = {5, 6, 7, 8};
    private static final String[] PRESS_LABELS = {"单击", "双击", "三击", "长按"};

    private final Context context;
    private final AirPodsController controller;
    private final DashboardUi ui;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout content;
    private final ScrollView scrollView;
    private final TextView subtitle;
    private final LinearLayout tabBar;
    private final Dialog dialog;
    private int page;

    static void show(Context context, AirPodsController controller) {
        new AirPodsDashboard(context, controller).open();
    }

    private AirPodsDashboard(Context context, AirPodsController controller) {
        this.context = context;
        this.controller = controller;
        this.ui = new DashboardUi(context);
        this.dialog = new Dialog(context);
        this.content = new LinearLayout(context);
        this.scrollView = new ScrollView(context);
        this.subtitle = ui.text("", 12, ui.subTextColor, false);
        this.tabBar = new LinearLayout(context);
    }

    private void open() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(14));
        root.setBackground(ui.rounded(ui.cardColor, 28));
        root.setElevation(ui.dp(10));
        root.addView(buildHeader());
        root.addView(buildTabs());
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(0, ui.dp(6), 0, ui.dp(8));
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        FrameLayout wrapper = new FrameLayout(context);
        wrapper.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        wrapper.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setContentView(wrapper);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.35f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
            int height = Math.min((int) (screenHeight * 0.88f), screenHeight - ui.dp(80));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, height);
        }
        selectPage(0);
        refreshHeader();
        dialog.show();
        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
            int height = Math.min((int) (screenHeight * 0.88f), screenHeight - ui.dp(80));
            shownWindow.setLayout(WindowManager.LayoutParams.MATCH_PARENT, height);
        }
        if (controller.attConnected()) main.postDelayed(controller::readAtt, 250);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBox = new LinearLayout(context);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = ui.text("AirPods 增强", 23, ui.textColor, true);
        titleBox.addView(title);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = ui.dp(4);
        titleBox.addView(subtitle, subtitleParams);
        header.addView(titleBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = ui.text("完成", 14, ui.accent, true);
        close.setPadding(ui.dp(12), ui.dp(10), ui.dp(4), ui.dp(10));
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close);
        return header;
    }

    private View buildTabs() {
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(0, ui.dp(10), 0, ui.dp(8));
        return tabBar;
    }

    private void selectPage(int index) {
        page = index;
        tabBar.removeAllViews();
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int target = i;
            TextView tab = ui.text(TAB_NAMES[i], 11, i == page ? Color.WHITE : ui.subTextColor, true);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(ui.dp(4), ui.dp(9), ui.dp(4), ui.dp(9));
            tab.setBackground(ui.rounded(i == page ? ui.accent : ui.groupColor, 16));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < TAB_NAMES.length - 1) params.rightMargin = ui.dp(4);
            tabBar.addView(tab, params);
            tab.setOnClickListener(v -> selectPage(target));
        }
        rebuild();
        scrollView.scrollTo(0, 0);
        scrollView.post(() -> {
            scrollView.scrollTo(0, 0);
            scrollView.fullScroll(ScrollView.FOCUS_UP);
        });
        scrollView.postDelayed(() -> {
            scrollView.scrollTo(0, 0);
            scrollView.fullScroll(ScrollView.FOCUS_UP);
        }, 120);
    }

    private void rebuild() {
        content.removeAllViews();
        if (page == 0) buildCommon();
        else if (page == 1) buildAudio();
        else if (page == 2) buildControls();
        else if (page == 3) buildConnection();
        else if (page == 4) buildHealth();
        else if (page == 5) buildDevice();
        else buildAbout();
    }

    private void refreshHeader() {
        String name = controller.modelName();
        if (name == null || name.isEmpty()) name = "AirPods";
        subtitle.setText(name + "  ·  " + batterySummary() + "  ·  "
                + (controller.opened() ? (controller.attConnected() ? "控制与听力已连接" : "控制已连接")
                : "控制未连接"));
    }

    private String batterySummary() {
        String left = controller.left() >= 0 ? controller.left() + "%" : "--";
        String right = controller.right() >= 0 ? controller.right() + "%" : "--";
        String box = controller.caseLevel() >= 0 ? controller.caseLevel() + "%" : "--";
        return "左 " + left + "  右 " + right + "  盒 " + box;
    }

    private void buildCommon() {
        LinearLayout hero = ui.card();
        ui.cardTitle(hero, controller.modelName(), "型号 " + controller.modelNumber()
                + "  ·  " + controller.manufacturer());
        LinearLayout battery = new LinearLayout(context);
        battery.setOrientation(LinearLayout.HORIZONTAL);
        battery.setGravity(Gravity.CENTER_VERTICAL);
        battery.setPadding(0, ui.dp(4), 0, ui.dp(8));
        hero.addView(battery);
        battery.addView(batteryChip("L", controller.left()), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View s1 = new View(context); battery.addView(s1, new LinearLayout.LayoutParams(ui.dp(8), 1));
        battery.addView(batteryChip("R", controller.right()), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View s2 = new View(context); battery.addView(s2, new LinearLayout.LayoutParams(ui.dp(8), 1));
        battery.addView(batteryChip("盒", controller.caseLevel()), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ui.cardTitle(hero, "噪声控制", "与首屏官方横条保持同步");
        int mode = controller.controlValue(AacpProtocol.CONTROL_LISTENING_MODE, 1);
        ui.segment(hero, new String[]{"关闭", "降噪", "通透", "自适应"},
                Math.max(0, mode - 1), index -> setListeningMode(index + 1));
        ui.detail(hero, ProtocolInfo.aacp(AacpProtocol.CONTROL_LISTENING_MODE, "01|02|03|04"));
        if (mode == 4) {
            int shown = 100 - controller.controlValue(AacpProtocol.CONTROL_ADAPTIVE_STRENGTH, 50);
            ui.slider(hero, "自适应降噪强度", "0 更安静 · 100 更通透", 0, 100, shown,
                    value -> value + "%", value -> controller.sendControl(
                            AacpProtocol.CONTROL_ADAPTIVE_STRENGTH, 100 - value));
            ui.detail(hero, ProtocolInfo.aacp(AacpProtocol.CONTROL_ADAPTIVE_STRENGTH, "<00..64 取反>"));
        }
        content.addView(hero);

        LinearLayout quick = ui.section(content, "常用开关");
        List<DashboardUi.ToggleSpec> specs = new ArrayList<>();
        specs.add(new DashboardUi.ToggleSpec("自适应音量", "根据环境调整媒体音量",
                ProtocolInfo.aacpSwitch(AacpProtocol.CONTROL_ADAPTIVE_VOLUME),
                controller.controlOn(AacpProtocol.CONTROL_ADAPTIVE_VOLUME), true,
                (button, checked) -> setOnOff(AacpProtocol.CONTROL_ADAPTIVE_VOLUME, checked)));
        specs.add(new DashboardUi.ToggleSpec("对话感知", "与 OPPO 原生开关同步 · AACP 0x28",
                ProtocolInfo.aacpSwitch(AacpProtocol.CONTROL_CONVERSATION),
                controller.controlOn(AacpProtocol.CONTROL_CONVERSATION), true,
                (button, checked) -> setOnOff(AacpProtocol.CONTROL_CONVERSATION, checked)));
        specs.add(new DashboardUi.ToggleSpec("单耳降噪", "只戴一只耳机时仍启用降噪",
                ProtocolInfo.aacpSwitch(AacpProtocol.CONTROL_SINGLE_EAR_ANC),
                controller.controlOn(AacpProtocol.CONTROL_SINGLE_EAR_ANC), true,
                (button, checked) -> setOnOff(AacpProtocol.CONTROL_SINGLE_EAR_ANC, checked)));
        specs.add(new DashboardUi.ToggleSpec("入耳播放", "戴上播放，取下暂停",
                ProtocolInfo.localEvent("ear_playback", "AACP 06 佩戴事件 + 媒体键"),
                controller.earPlayback(), true,
                (button, checked) -> controller.setEarPlayback(checked)));
        specs.add(new DashboardUi.ToggleSpec("开盖自动连接", "开盖后连接并显示官方弹窗",
                ProtocolInfo.localEvent("auto_connect", "PTC 开盖广播 + Bluetooth"),
                controller.autoConnect(), true,
                (button, checked) -> controller.setAutoConnect(checked)));
        specs.add(new DashboardUi.ToggleSpec("低电量提醒", "电量低于 20% 时通知",
                ProtocolInfo.local("battery_alerts"),
                controller.batteryAlerts(), true,
                (button, checked) -> controller.setBatteryAlerts(checked)));
        ui.toggleGrid(quick, specs);

        LinearLayout spatial = ui.section(content, "空间音频");
        ui.cardTitle(spatial, "个性化空间音频", "打开 OPPO 官方的空间音频设置页");
        TextView open = ui.actionButton("打开官方空间音频设置", true, v -> openSpatialAudio());
        spatial.addView(open, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ui.detail(spatial, ProtocolInfo.official("com.oplus.mydevices.AIRPODS_SPATIAL_AUDIO"));
    }

    private View batteryChip(String label, int level) {
        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9));
        chip.setBackground(ui.rounded(ui.cardColor, 16));
        TextView name = ui.text(label, 11, ui.subTextColor, true);
        name.setGravity(Gravity.CENTER);
        TextView value = ui.text(level >= 0 ? level + "%" : "--", 18,
                level >= 0 && level <= 20 ? ui.dangerColor : ui.textColor, true);
        value.setGravity(Gravity.CENTER);
        chip.addView(name);
        chip.addView(value);
        return chip;
    }

    private void buildAudio() {
        LinearLayout adaptive = ui.section(content, "自适应音频");
        ui.cardTitle(adaptive, "自适应降噪强度", "0 更安静 · 100 更通透；与首屏滑杆同步");
        int shown = 100 - controller.controlValue(AacpProtocol.CONTROL_ADAPTIVE_STRENGTH, 50);
        ui.slider(adaptive, "强度", null, 0, 100, shown, value -> value + "%",
                value -> controller.sendControl(AacpProtocol.CONTROL_ADAPTIVE_STRENGTH, 100 - value));
        ui.detail(adaptive, ProtocolInfo.aacp(AacpProtocol.CONTROL_ADAPTIVE_STRENGTH, "<00..64 取反>"));

        LinearLayout eq = ui.section(content, "均衡器");
        ui.cardTitle(eq, "音效", "预设与自定义三段 EQ");
        String saved = controller.prefs().getString("equalizer_preset", "off");
        boolean custom = "custom".equals(saved);
        ui.detail(eq, ProtocolInfo.eq(2, "<low> <mid> <high>") + "；" + ProtocolInfo.local("equalizer_preset"));
        ui.segment(eq, new String[]{"预设", "自定义"}, custom ? 1 : 0, index -> {
            if (index == 0) {
                controller.prefs().edit().putString("equalizer_preset", "off").apply();
                controller.sendEqualizer(1, 50, 50, 50);
            } else {
                controller.prefs().edit().putString("equalizer_preset", "custom").apply();
                int[] current = controller.control(AacpProtocol.OP_EQUALIZER);
                controller.sendEqualizer(2, current != null && current.length > 3 ? current[1] : 50,
                        current != null && current.length > 3 ? current[2] : 50,
                        current != null && current.length > 3 ? current[3] : 50);
            }
            rebuild();
        });
        if (custom) {
            int[] current = controller.control(AacpProtocol.OP_EQUALIZER);
            int low = current != null && current.length > 3 ? current[1] : 50;
            int mid = current != null && current.length > 3 ? current[2] : 50;
            int high = current != null && current.length > 3 ? current[3] : 50;
            final int[] values = {low, mid, high};
            ui.slider(eq, "低频", null, 0, 100, low, value -> value + "%", value -> {
                values[0] = value;
                sendCustomEq(values);
            });
            ui.slider(eq, "中频", null, 0, 100, mid, value -> value + "%", value -> {
                values[1] = value;
                sendCustomEq(values);
            });
            ui.slider(eq, "高频", null, 0, 100, high, value -> value + "%", value -> {
                values[2] = value;
                sendCustomEq(values);
            });
            TextView reset = ui.actionButton("重置为平直", false, v -> {
                sendCustomEq(new int[]{50, 50, 50});
                rebuild();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = ui.dp(10);
            eq.addView(reset, params);
        } else {
            String[] names = {"关闭", "流行", "摇滚", "古典", "人声", "低音"};
            String[] keys = {"off", "pop", "rock", "classical", "vocal", "bass"};
            List<TextView> chips = new ArrayList<>();
            for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (rowIndex > 0) rowParams.topMargin = ui.dp(8);
                eq.addView(row, rowParams);
                for (int col = 0; col < 3; col++) {
                    int index = rowIndex * 3 + col;
                    TextView chip = ui.text(names[index], 12,
                            keys[index].equals(saved) ? Color.WHITE : ui.subTextColor, true);
                    chip.setGravity(Gravity.CENTER);
                    chip.setPadding(ui.dp(8), ui.dp(9), ui.dp(8), ui.dp(9));
                    chip.setBackground(ui.rounded(keys[index].equals(saved) ? ui.accent : ui.cardColor, 14));
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    if (col > 0) params.leftMargin = ui.dp(8);
                    row.addView(chip, params);
                    chip.setOnClickListener(v -> {
                        controller.prefs().edit().putString("equalizer_preset", keys[index]).apply();
                        int[] eqValues = eqFor(keys[index]);
                        controller.sendEqualizer(eqValues[0], eqValues[1], eqValues[2], eqValues[3]);
                        rebuild();
                    });
                }
            }
        }

        LinearLayout tone = ui.section(content, "提示音");
        ui.cardTitle(tone, "提示音音量", null);
        int toneValue = controller.controlValue(AacpProtocol.CONTROL_TONE_VOLUME, 50);
        ui.slider(tone, "音量", "使用 LibrePods 相同的双字节载荷", 0, 100, toneValue,
                value -> value + "%", value -> controller.sendControl(
                        AacpProtocol.CONTROL_TONE_VOLUME, value, 0x50));
        ui.detail(tone, ProtocolInfo.aacp(AacpProtocol.CONTROL_TONE_VOLUME, "<00..64> 50"));
    }

    private void sendCustomEq(int[] values) {
        controller.prefs().edit().putString("equalizer_preset", "custom").apply();
        controller.sendEqualizer(2, values[0], values[1], values[2]);
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

    private void buildControls() {
        LinearLayout press = ui.section(content, "按压与滑动");
        ui.cardTitle(press, "按压节奏", "适配默认/较慢/最慢");
        ui.segment(press, new String[]{"默认", "较慢", "最慢"},
                Math.max(0, Math.min(2, controller.controlValue(AacpProtocol.CONTROL_PRESS_SPEED, 0))),
                index -> controller.sendControl(AacpProtocol.CONTROL_PRESS_SPEED, index));
        ui.detail(press, ProtocolInfo.aacp(AacpProtocol.CONTROL_PRESS_SPEED, "00|01|02"));
        ui.divider(press);
        ui.cardTitle(press, "长按判定", "长按触发所需时间");
        ui.segment(press, new String[]{"默认", "较慢", "最慢"},
                Math.max(0, Math.min(2, controller.controlValue(AacpProtocol.CONTROL_PRESS_HOLD, 0))),
                index -> controller.sendControl(AacpProtocol.CONTROL_PRESS_HOLD, index));
        ui.detail(press, ProtocolInfo.aacp(AacpProtocol.CONTROL_PRESS_HOLD, "00|01|02"));

        LinearLayout swipe = ui.section(content, "音量滑动");
        ui.cardTitle(swipe, "耳机柄滑动", "使用 AACP 0x25 / 0x23");
        List<DashboardUi.ToggleSpec> swipeSpecs = new ArrayList<>();
        swipeSpecs.add(new DashboardUi.ToggleSpec("启用音量滑动", "在耳机柄上上下滑动调节音量",
                ProtocolInfo.aacpSwitch(AacpProtocol.CONTROL_VOLUME_SWIPE),
                controller.controlOn(AacpProtocol.CONTROL_VOLUME_SWIPE),
                hasCapability(AirPodsModels.CAP_SWIPE_VOLUME),
                (button, checked) -> setOnOff(AacpProtocol.CONTROL_VOLUME_SWIPE, checked)));
        ui.toggleGrid(swipe, swipeSpecs);
        int swipeDuration = controller.controlValue(AacpProtocol.CONTROL_VOLUME_SWIPE_DURATION, 1);
        ui.segment(swipe, new String[]{"默认", "较长", "最长"},
                Math.max(0, Math.min(2, swipeDuration - 1)),
                index -> controller.sendControl(AacpProtocol.CONTROL_VOLUME_SWIPE_DURATION, index + 1));
        ui.detail(swipe, ProtocolInfo.aacp(AacpProtocol.CONTROL_VOLUME_SWIPE_DURATION, "01|02|03"));

        LinearLayout stem = ui.section(content, "耳机柄动作");
        ui.cardTitle(stem, "单击 / 双击 / 三击 / 长按", "点按任一动作可循环切换；仅自定义项会改写 AirPods");
        ui.detail(stem, ProtocolInfo.aacp(0x39, "<mask>") + "；SP left/right_*_action · 手机动作映射");
        addStemGrid(stem, 1, "左耳");
        ui.divider(stem);
        addStemGrid(stem, 2, "右耳");
        ui.divider(stem);
        TextView cycleTitle = ui.text("长按循环模式", 13, ui.textColor, true);
        stem.addView(cycleTitle);
        int modes = controller.controlValue(AacpProtocol.CONTROL_LISTENING_MODES, 0x0F);
        List<DashboardUi.ToggleSpec> cycleSpecs = new ArrayList<>();
        cycleSpecs.add(modeToggle("关闭", 0x01, modes));
        cycleSpecs.add(modeToggle("降噪", 0x02, modes));
        cycleSpecs.add(modeToggle("通透", 0x04, modes));
        cycleSpecs.add(modeToggle("自适应", 0x08, modes));
        ui.toggleGrid(stem, cycleSpecs);

        LinearLayout call = ui.section(content, "通话控制");
        ui.cardTitle(call, "按键映射", "接听固定为按一次；下面切换静音与挂断");
        int[] values = controller.control(AacpProtocol.CONTROL_CALL_MANAGEMENT);
        boolean flipped = values != null && values.length > 1 && values[1] == 2;
        ui.segment(call, new String[]{"静音 / 挂断", "挂断 / 静音"}, flipped ? 1 : 0, index ->
                controller.sendControl(AacpProtocol.CONTROL_CALL_MANAGEMENT, 0, index == 1 ? 2 : 3));
        ui.detail(call, ProtocolInfo.aacp(AacpProtocol.CONTROL_CALL_MANAGEMENT, "00 02|03"));
        List<DashboardUi.ToggleSpec> callSpecs = new ArrayList<>();
        callSpecs.add(new DashboardUi.ToggleSpec("自动接听", "来电时自动接听",
                ProtocolInfo.aacpSwitch(AacpProtocol.CONTROL_AUTO_ANSWER),
                controller.controlOn(AacpProtocol.CONTROL_AUTO_ANSWER), true,
                (button, checked) -> setOnOff(AacpProtocol.CONTROL_AUTO_ANSWER, checked)));
        ui.toggleGrid(call, callSpecs);

        LinearLayout head = ui.section(content, "头部手势");
        ui.cardTitle(head, "点头 / 摇头", "点头接听、摇头拒接需要系统通话状态；当前先提供姿态接收测试");
        List<DashboardUi.ToggleSpec> headSpecs = new ArrayList<>();
        boolean gestures = controller.prefs().getBoolean("head_gestures", true);
        headSpecs.add(new DashboardUi.ToggleSpec("启用头部手势", "允许 AirPods 上报头部姿态",
                ProtocolInfo.localEvent("head_gestures", "AACP 17 姿态包"),
                gestures, hasCapability(AirPodsModels.CAP_HEAD_GESTURES),
                (button, checked) -> {
                    controller.prefs().edit().putBoolean("head_gestures", checked).apply();
                    controller.setHeadTracking(checked);
                }));
        headSpecs.add(new DashboardUi.ToggleSpec("头部控制通话", "识别到点头/摇头时发送接听键",
                ProtocolInfo.localEvent("head_gesture_calls", "KEYCODE_HEADSETHOOK"),
                controller.prefs().getBoolean("head_gesture_calls", false),
                hasCapability(AirPodsModels.CAP_HEAD_GESTURES),
                (button, checked) -> {
                    controller.prefs().edit().putBoolean("head_gesture_calls", checked).apply();
                    if (checked) controller.setHeadTracking(true);
                }));
        ui.toggleGrid(head, headSpecs);
        TextView headTest = ui.actionButton(controller.headTrackingActive() ? "停止姿态接收" : "测试姿态接收", false, v -> {
            if (controller.headTrackingActive()) {
                controller.setHeadTracking(false);
                ui.toast("已停止头部姿态接收");
                rebuild();
            } else {
                int before = controller.headTrackingPackets();
                controller.setHeadTracking(true);
                ui.toast("已开始接收，请点头或摇头");
                main.postDelayed(() -> {
                    controller.setHeadTracking(false);
                    int received = controller.headTrackingPackets() - before;
                    ui.toast("收到 " + received + " 个姿态包");
                    if (dialog.isShowing()) rebuild();
                }, 10000);
                rebuild();
            }
        });
        LinearLayout.LayoutParams testParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        testParams.topMargin = ui.dp(10);
        head.addView(headTest, testParams);
        ui.detail(head, "AACP 17 00 ... · 设备姿态包；手机做阈值识别；SP head_gestures · 手机本地");
        ui.infoRow(head, "已接收姿态包", controller.headTrackingPackets() + " 个");
        ui.infoRow(head, "最近识别", lastGestureLabel());
    }

    private DashboardUi.ToggleSpec modeToggle(String label, int bit, int current) {
        return new DashboardUi.ToggleSpec(label, null,
                ProtocolInfo.aacp(AacpProtocol.CONTROL_LISTENING_MODES, "<mask>"),
                (current & bit) != 0, true, (button, checked) -> {
            int value = controller.controlValue(AacpProtocol.CONTROL_LISTENING_MODES, 0x0F);
            int next = checked ? (value | bit) : (value & ~bit);
            if (Integer.bitCount(next) < 2) {
                ui.toast("至少保留两个聆听模式");
                main.post(this::rebuild);
                return;
            }
            controller.setControlState(AacpProtocol.CONTROL_LISTENING_MODES, next);
            controller.sendControl(AacpProtocol.CONTROL_LISTENING_MODES, next);
        });
    }

    private void addStemGrid(LinearLayout parent, int bud, String title) {
        TextView side = ui.text(title, 13, ui.textColor, true);
        LinearLayout.LayoutParams sideParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sideParams.topMargin = ui.dp(6);
        parent.addView(side, sideParams);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = ui.dp(7);
        parent.addView(row, rowParams);
        for (int i = 0; i < PRESS_TYPES.length; i++) {
            final int pressType = PRESS_TYPES[i];
            LinearLayout cell = new LinearLayout(context);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(ui.dp(7), ui.dp(9), ui.dp(7), ui.dp(9));
            cell.setBackground(ui.rounded(ui.cardColor, 14));
            LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) cellParams.leftMargin = ui.dp(6);
            row.addView(cell, cellParams);
            TextView label = ui.text(PRESS_LABELS[i], 10, ui.subTextColor, false);
            label.setGravity(Gravity.CENTER);
            TextView value = ui.text(actionLabel(controller.stemAction(bud, pressType)), 12,
                    ui.textColor, true);
            value.setGravity(Gravity.CENTER);
            cell.addView(label);
            cell.addView(value);
            cell.setOnClickListener(v -> {
                int next = (controller.stemAction(bud, pressType) + 1) % ACTION_NAMES.length;
                controller.setStemAction(bud, pressType, next);
                value.setText(actionLabel(next));
            });
        }
    }

    private static String actionLabel(int action) {
        if (action < 0 || action >= ACTION_NAMES.length) return ACTION_NAMES[0];
        return ACTION_NAMES[action];
    }

    private void buildConnection() {
        LinearLayout wear = ui.section(content, "佩戴与连接");
        ui.cardTitle(wear, "佩戴检测", "自动人耳检测与本地入耳播放可以独立开关");
        List<DashboardUi.ToggleSpec> wearSpecs = new ArrayList<>();
        wearSpecs.add(new DashboardUi.ToggleSpec("自动人耳检测", "与 OPPO 原生『佩戴检测』同步 · AACP 0x0A",
                ProtocolInfo.aacpSwitch(AacpProtocol.CONTROL_EAR_DETECTION),
                controller.controlOn(AacpProtocol.CONTROL_EAR_DETECTION), true,
                (button, checked) -> setOnOff(AacpProtocol.CONTROL_EAR_DETECTION, checked)));
        wearSpecs.add(new DashboardUi.ToggleSpec("入耳播放/取下暂停", "OPods 本地媒体键控制",
                ProtocolInfo.localEvent("ear_playback", "AACP 06 佩戴事件 + 媒体键"),
                controller.earPlayback(), true,
                (button, checked) -> controller.setEarPlayback(checked)));
        ui.toggleGrid(wear, wearSpecs);

        LinearLayout conn = ui.section(content, "自动连接");
        ui.cardTitle(conn, "设备侧与本地策略", "设备侧 0x20 与开盖连接策略分开控制");
        List<DashboardUi.ToggleSpec> connSpecs = new ArrayList<>();
        connSpecs.add(new DashboardUi.ToggleSpec("AirPods 自动连接", "AACP 0x20 设备侧配置",
                ProtocolInfo.aacpSwitch(AacpProtocol.CONTROL_AUTOMATIC_CONNECTION),
                controller.controlOn(AacpProtocol.CONTROL_AUTOMATIC_CONNECTION), true,
                (button, checked) -> setOnOff(AacpProtocol.CONTROL_AUTOMATIC_CONNECTION, checked)));
        connSpecs.add(new DashboardUi.ToggleSpec("开盖自动连接", "本机开盖后主动请求 A2DP/HFP",
                ProtocolInfo.localEvent("auto_connect", "PTC 开盖广播 + Bluetooth"),
                controller.autoConnect(), true,
                (button, checked) -> controller.setAutoConnect(checked)));
        ui.toggleGrid(conn, connSpecs);

        LinearLayout mic = ui.section(content, "麦克风");
        ui.cardTitle(mic, "麦克风模式", "自动选择或固定左/右耳");
        int micMode = controller.controlValue(AacpProtocol.CONTROL_MICROPHONE, 0);
        ui.segment(mic, new String[]{"自动", "始终右耳", "始终左耳"},
                Math.max(0, Math.min(2, micMode)), index -> {
                    controller.setControlState(AacpProtocol.CONTROL_MICROPHONE, index);
                    controller.sendControl(AacpProtocol.CONTROL_MICROPHONE, index);
                });
        ui.detail(mic, ProtocolInfo.aacp(AacpProtocol.CONTROL_MICROPHONE, "00|01|02"));

        LinearLayout power = ui.section(content, "电源与睡眠");
        ui.cardTitle(power, "续航", null);
        List<DashboardUi.ToggleSpec> powerSpecs = new ArrayList<>();
        powerSpecs.add(new DashboardUi.ToggleSpec("睡眠检测", "检测到入睡后暂停播放",
                ProtocolInfo.aacpSwitch(AacpProtocol.CONTROL_SLEEP_DETECTION),
                controller.controlOn(AacpProtocol.CONTROL_SLEEP_DETECTION),
                hasCapability(AirPodsModels.CAP_SLEEP),
                (button, checked) -> setOnOff(AacpProtocol.CONTROL_SLEEP_DETECTION, checked)));
        powerSpecs.add(new DashboardUi.ToggleSpec("优化充电", "减缓充电以延长电池寿命",
                ProtocolInfo.aacpSwitch(AacpProtocol.CONTROL_OPTIMIZED_CHARGING),
                controller.controlOn(AacpProtocol.CONTROL_OPTIMIZED_CHARGING), true,
                (button, checked) -> setOnOff(AacpProtocol.CONTROL_OPTIMIZED_CHARGING, checked)));
        powerSpecs.add(new DashboardUi.ToggleSpec("低电量提醒", "低于 20% 时发送通知",
                ProtocolInfo.local("battery_alerts"),
                controller.batteryAlerts(), true,
                (button, checked) -> controller.setBatteryAlerts(checked)));
        powerSpecs.add(new DashboardUi.ToggleSpec("允许关闭", "在噪声控制中保留“关闭”",
                ProtocolInfo.aacpSwitch(AacpProtocol.CONTROL_ALLOW_OFF),
                controller.controlOn(AacpProtocol.CONTROL_ALLOW_OFF), true,
                (button, checked) -> setOnOff(AacpProtocol.CONTROL_ALLOW_OFF, checked)));
        ui.toggleGrid(power, powerSpecs);

        LinearLayout info = ui.section(content, "连接状态");
        ui.cardTitle(info, "控制通道", "AACP 与 ATT 是两个独立通道");
        ui.infoRow(info, "AACP 控制", controller.opened() ? "已连接" : "未连接");
        ui.infoRow(info, "ATT 听力通道", controller.attConnected() ? "已连接" : "未连接");
        ui.infoRow(info, "设备地址", controller.address());
        TextView reconnect = ui.actionButton("重新读取听力与通透数据", false, v -> {
            controller.readAtt();
            ui.toast("已请求重新读取 ATT 数据");
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ui.dp(10);
        info.addView(reconnect, params);
        ui.detail(info, ProtocolInfo.attRead(0x18) + "；" + ProtocolInfo.attRead(0x1B) + "；" + ProtocolInfo.attRead(0x2A));
    }

    private void buildHealth() {
        boolean hearingAidSupported = hasCapability(AirPodsModels.CAP_HEARING_AID);
        boolean loudSupported = hasCapability(AirPodsModels.CAP_LOUD_SOUND);
        if (!hearingAidSupported && !loudSupported) {
            LinearLayout note = ui.section(content, "听力健康");
            ui.cardTitle(note, "当前机型未声明听力能力", "AirPods 5 不支持助听/大声降低；Pro 3 才开放完整选项。");
            return;
        }

        LinearLayout protection = ui.section(content, "听力保护");
        ui.cardTitle(protection, "环境噪声", "ATT 0x1B / PPE 0x37");
        List<DashboardUi.ToggleSpec> protectionSpecs = new ArrayList<>();
        protectionSpecs.add(new DashboardUi.ToggleSpec("大声降低", "透明与自适应模式下降低突发大声",
                ProtocolInfo.attWrite(0x1B, "<100B>"),
                controller.loudSoundReduction(), loudSupported && controller.attConnected(),
                (button, checked) -> {
                    controller.writeLoudSoundReduction(checked);
                    ui.toast(checked ? "已开启大声降低" : "已关闭大声降低");
                }));
        protectionSpecs.add(new DashboardUi.ToggleSpec("EN 352 工作保护", "限制最大媒体音量 82 dBA",
                ProtocolInfo.aacpSwitch(AacpProtocol.CONTROL_PPE_TOGGLE),
                controller.controlOn(AacpProtocol.CONTROL_PPE_TOGGLE), hearingAidSupported,
                (button, checked) -> setOnOff(AacpProtocol.CONTROL_PPE_TOGGLE, checked)));
        ui.toggleGrid(protection, protectionSpecs);

        AirPodsAttData.Settings transparency = AirPodsAttData.parseTransparency(controller.transparencyData());
        if (transparency == null) transparency = AirPodsAttData.Settings.defaults();
        LinearLayout tr = ui.section(content, "通透模式自定义");
        ui.cardTitle(tr, "通透模式", controller.attConnected() ? "ATT 已连接" : "ATT 未连接，修改会在通道建立后写入");
        final AirPodsAttData.Settings trValue = transparency;
        List<DashboardUi.ToggleSpec> trSpecs = new ArrayList<>();
        trSpecs.add(new DashboardUi.ToggleSpec("启用自定义通透", "写入 AirPods 通透参数",
                ProtocolInfo.attWrite(0x18, "<100B>"),
                trValue.transparencyEnabled, controller.attConnected(), (button, checked) -> {
                    trValue.transparencyEnabled = checked;
                    controller.writeTransparency(trValue);
                }));
        trSpecs.add(new DashboardUi.ToggleSpec("对话增强", "通透模式下增强前方语音",
                ProtocolInfo.attWrite(0x18, "<100B>"),
                trValue.leftConversationBoost || trValue.rightConversationBoost,
                controller.attConnected(), (button, checked) -> {
                    trValue.leftConversationBoost = checked;
                    trValue.rightConversationBoost = checked;
                    controller.writeTransparency(trValue);
                }));
        ui.toggleGrid(tr, trSpecs);
        addAttAdjustmentSliders(tr, trValue, false);
        addEqSliders(tr, trValue, true, "双耳 EQ");
        ui.detail(tr, ProtocolInfo.attWrite(0x18, "<100B> 双耳 8 段 EQ"));

        AirPodsAttData.Settings hearing = AirPodsAttData.parseHearingAid(controller.hearingAidData());
        if (hearing == null) hearing = AirPodsAttData.Settings.defaults();
        LinearLayout aid = ui.section(content, "助听器");
        ui.cardTitle(aid, "助听模式", controller.hearingAidData().length >= 104
                ? "已读取助听参数" : "尚未读取到助听参数，修改时使用默认值");
        final AirPodsAttData.Settings hearingValue = hearing;
        boolean aidEnabled = hearingAidEnabled();
        List<DashboardUi.ToggleSpec> aidSpecs = new ArrayList<>();
        aidSpecs.add(new DashboardUi.ToggleSpec("启用助听", "0x2C + 0x33",
                "AACP 09 00 2C 01 01|02 + 09 00 33 01|02 · 设备；ATT 12 2A 00 <104B> · 设备",
                aidEnabled, hearingAidSupported, (button, checked) -> setHearingAidEnabled(checked)));
        aidSpecs.add(new DashboardUi.ToggleSpec("滑动调节增益", "透明模式无媒体时滑动调节放大",
                ProtocolInfo.aacpSwitch(AacpProtocol.CONTROL_HPS_GAIN_SWIPE),
                controller.controlOn(AacpProtocol.CONTROL_HPS_GAIN_SWIPE), hearingAidSupported,
                (button, checked) -> setOnOff(AacpProtocol.CONTROL_HPS_GAIN_SWIPE, checked)));
        ui.toggleGrid(aid, aidSpecs);
        addAttAdjustmentSliders(aid, hearingValue, true);
        ui.divider(aid);
        TextView leftTitle = ui.text("左耳 8 段 EQ", 13, ui.textColor, true);
        aid.addView(leftTitle);
        addEqSliders(aid, hearingValue, false, null, true, true);
        ui.divider(aid);
        TextView rightTitle = ui.text("右耳 8 段 EQ", 13, ui.textColor, true);
        aid.addView(rightTitle);
        addEqSliders(aid, hearingValue, false, null, true, false);
        ui.detail(aid, ProtocolInfo.attWrite(0x2A, "<104B> 左右耳 8 段 EQ"));

        LinearLayout test = ui.section(content, "听力测试");
        ui.cardTitle(test, "更新听力图", "输入 dBHL 损失值；应用后会写回助听 EQ 数据");
        ui.detail(test, ProtocolInfo.attWrite(0x2A, "<104B> 双耳 EQ"));
        String[] frequencies = {"250Hz", "500Hz", "1kHz", "2kHz", "3kHz", "4kHz", "6kHz", "8kHz"};
        EditText[] leftFields = new EditText[8];
        EditText[] rightFields = new EditText[8];
        for (int i = 0; i < frequencies.length; i++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = ui.dp(7);
            test.addView(row, rowParams);
            TextView frequency = ui.text(frequencies[i], 12, ui.subTextColor, false);
            frequency.setGravity(Gravity.END);
            row.addView(frequency, new LinearLayout.LayoutParams(ui.dp(62),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            leftFields[i] = hearingEdit(String.valueOf(hearing.leftEq[i]));
            rightFields[i] = hearingEdit(String.valueOf(hearing.rightEq[i]));
            LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            fieldParams.leftMargin = ui.dp(8);
            row.addView(leftFields[i], fieldParams);
            LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            rightParams.leftMargin = ui.dp(8);
            row.addView(rightFields[i], rightParams);
        }
        TextView apply = ui.actionButton("应用听力图并写回 AirPods", true, v -> {
            for (int i = 0; i < 8; i++) {
                hearingValue.leftEq[i] = parseFloat(leftFields[i].getText().toString(), hearingValue.leftEq[i]);
                hearingValue.rightEq[i] = parseFloat(rightFields[i].getText().toString(), hearingValue.rightEq[i]);
            }
            setHearingAidEnabled(true);
            controller.writeHearingAid(hearingValue);
            ui.toast("已写入听力图");
        });
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        applyParams.topMargin = ui.dp(12);
        test.addView(apply, applyParams);
        ui.detail(test, "AACP 2C 01 01 + 33 01 → 设备；ATT 12 2A 00 <104B>");
    }

    private void addAttAdjustmentSliders(LinearLayout parent, AirPodsAttData.Settings value, boolean hearing) {
        int amp = Math.round((value.netAmplification + 1f) * 50f);
        int balance = Math.round((value.balance + 1f) * 50f);
        int tone = Math.round((value.leftTone + 1f) * 50f);
        int ambient = Math.round(value.leftAmbientNoiseReduction * 100f);
        ui.detail(parent, ProtocolInfo.attWrite(hearing ? 0x2A : 0x18, "<104B> 放大/平衡/音色/环境降噪"));
        ui.slider(parent, "放大", "-100% 至 +100%", 0, 100, amp, v -> v + "%", v -> {
            float next = v / 50f - 1f;
            setAmpBalance(value, next, value.balance);
            writeAtt(value, hearing);
        });
        ui.slider(parent, "平衡", "左耳 ↔ 右耳", 0, 100, balance, v -> v + "%", v -> {
            float next = v / 50f - 1f;
            setAmpBalance(value, value.netAmplification, next);
            writeAtt(value, hearing);
        });
        ui.slider(parent, "音色", "更暗 ↔ 更亮", 0, 100, tone, v -> v + "%", v -> {
            value.leftTone = v / 50f - 1f;
            value.rightTone = value.leftTone;
            writeAtt(value, hearing);
        });
        ui.slider(parent, "环境噪声降低", "少 ↔ 多", 0, 100, ambient, v -> v + "%", v -> {
            value.leftAmbientNoiseReduction = v / 100f;
            value.rightAmbientNoiseReduction = value.leftAmbientNoiseReduction;
            writeAtt(value, hearing);
        });
        if (hearing) {
            int own = Math.round(value.ownVoiceAmplification * 100f);
            ui.slider(parent, "自己的声音", "助听模式的自己声音放大", 0, 100, own, v -> v + "%", v -> {
                value.ownVoiceAmplification = v / 100f;
                writeAtt(value, true);
            });
        }
    }

    private void addEqSliders(LinearLayout parent, AirPodsAttData.Settings value, boolean bothEars, String title) {
        addEqSliders(parent, value, bothEars, title, false, true);
    }

    private void addEqSliders(LinearLayout parent, AirPodsAttData.Settings value, boolean bothEars,
                              String title, boolean hearing, boolean leftEar) {
        if (title != null) {
            TextView label = ui.text(title, 13, ui.textColor, true);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = ui.dp(10);
            parent.addView(label, params);
        }
        String[] bands = {"250", "500", "1k", "2k", "3k", "4k", "6k", "8k"};
        for (int i = 0; i < 8; i++) {
            final int index = i;
            float current = (leftEar ? value.leftEq[index] : value.rightEq[index]);
            int shown = Math.round(Math.max(0f, Math.min(1f, current)) * 100f);
            ui.slider(parent, bands[i] + "Hz", null, 0, 100, shown, v -> v + "%", v -> {
                float next = v / 100f;
                value.leftEq[index] = next;
                if (bothEars || !leftEar) value.rightEq[index] = next;
                writeAtt(value, hearing);
            });
        }
    }

    private void setAmpBalance(AirPodsAttData.Settings value, float amp, float balance) {
        value.netAmplification = Math.max(-1f, Math.min(1f, amp));
        value.balance = Math.max(-1f, Math.min(1f, balance));
        value.leftAmplification = value.netAmplification + Math.min(0f, value.balance);
        value.rightAmplification = value.netAmplification + Math.max(0f, value.balance);
    }

    private void writeAtt(AirPodsAttData.Settings value, boolean hearing) {
        if (hearing) controller.writeHearingAid(value);
        else controller.writeTransparency(value);
    }

    private EditText hearingEdit(String text) {
        EditText edit = new EditText(context);
        edit.setText(text);
        edit.setTextSize(12);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        edit.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
        edit.setBackground(ui.rounded(ui.cardColor, 10));
        edit.setTextColor(ui.textColor);
        return edit;
    }

    private static float parseFloat(String text, float fallback) {
        try {
            return Float.parseFloat(text.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private boolean hearingAidEnabled() {
        int[] value = controller.control(AacpProtocol.CONTROL_HEARING_AID);
        return value != null && value.length > 1 && value[0] == 1 && value[1] == 1;
    }

    private void setHearingAidEnabled(boolean enabled) {
        controller.setControlState(AacpProtocol.CONTROL_HEARING_AID, 1, enabled ? 1 : 2);
        controller.sendControl(AacpProtocol.CONTROL_HEARING_AID, 1, enabled ? 1 : 2);
        controller.setControlState(AacpProtocol.CONTROL_HEARING_ASSIST, enabled ? 1 : 2);
        controller.sendControl(AacpProtocol.CONTROL_HEARING_ASSIST, enabled ? 1 : 2);
        if (enabled) main.postDelayed(controller::readAtt, 300);
    }

    private void buildDevice() {
        LinearLayout rename = ui.section(content, "设备名称");
        ui.cardTitle(rename, "重命名", "通过 AACP 0x1A 写入，同时更新本机别名");
        EditText name = new EditText(context);
        name.setText(controller.modelName());
        name.setTextSize(15);
        name.setSingleLine(true);
        name.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        name.setBackground(ui.rounded(ui.cardColor, 14));
        name.setTextColor(ui.textColor);
        rename.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView apply = ui.actionButton("保存名称", true, v -> {
            controller.sendRename(name.getText().toString());
            ui.toast("已发送重命名");
        });
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        applyParams.topMargin = ui.dp(10);
        rename.addView(apply, applyParams);
        ui.detail(rename, ProtocolInfo.rename());

        LinearLayout info = ui.section(content, "设备信息");
        ui.cardTitle(info, controller.modelName(), controller.manufacturer());
        ui.infoRow(info, "型号号", controller.modelNumber());
        ui.infoRow(info, "设备序列号", controller.serialNumber());
        ui.infoRow(info, "左耳序列号", controller.leftSerialNumber());
        ui.infoRow(info, "右耳序列号", controller.rightSerialNumber());
        ui.infoRow(info, "固件 1", controller.version1());
        ui.infoRow(info, "固件 2", controller.version2());
        ui.infoRow(info, "固件 3", controller.version3());
        ui.infoRow(info, "硬件版本", controller.hardwareRevision());
        ui.infoRow(info, "更新器标识", controller.updaterIdentifier());
        ui.infoRow(info, "AACP", controller.opened() ? "已连接" : "未连接");
        ui.infoRow(info, "ATT", controller.attConnected() ? "已连接" : "未连接");
        ui.detail(info, "读取路径 AACP 1D / ATT 0A 18 / 1B / 2A · 设备回传");

        LinearLayout actions = ui.section(content, "操作");
        TextView refresh = ui.actionButton("重新读取设备与听力信息", false, v -> {
            controller.readAtt();
            ui.toast("已请求刷新");
        });
        actions.addView(refresh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView disconnect = ui.actionButton("断开 AirPods 连接", false, v -> {
            controller.disconnectDevice();
            ui.toast("已请求断开");
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ui.dp(9);
        actions.addView(disconnect, params);
        ui.detail(actions, ProtocolInfo.attRead(0x18) + "；" + ProtocolInfo.attRead(0x1B) + "；" + ProtocolInfo.attRead(0x2A) + "；BluetoothDevice.disconnect() · 手机系统");
    }

    private void buildAbout() {
        LinearLayout about = ui.card();
        ui.cardTitle(about, "OPods", "ColorOS AirPods 增强模块");
        ui.infoRow(about, "当前版本", versionName() + "  (" + versionCode() + ")");
        ui.infoRow(about, "当前仓库", "github.com/arkylin/OPods");
        ui.detail(about, "模块没有 INTERNET 权限；GitHub 只调用系统浏览器打开");
        content.addView(about);

        LinearLayout github = ui.section(content, "GitHub");
        ui.cardTitle(github, "项目与作者", "如果这个项目帮到你，欢迎点 Star 或关注作者");
        TextView repo = ui.actionButton("查看 GitHub 仓库", true,
                v -> openUrl("https://github.com/arkylin/OPods"));
        github.addView(repo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ui.detail(github, "https://github.com/arkylin/OPods");
        TextView star = ui.actionButton("给项目点个 Star", true,
                v -> openUrl("https://github.com/arkylin/OPods"));
        LinearLayout.LayoutParams starParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        starParams.topMargin = ui.dp(9);
        github.addView(star, starParams);
        ui.detail(github, "Star 请求 · 系统浏览器打开");
        TextView follow = ui.actionButton("关注作者 arkylin", false,
                v -> openUrl("https://github.com/arkylin"));
        LinearLayout.LayoutParams followParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        followParams.topMargin = ui.dp(9);
        github.addView(follow, followParams);
        TextView release = ui.actionButton("打开最新 GitHub Release", false,
                v -> openUrl("https://github.com/arkylin/OPods/releases/latest"));
        LinearLayout.LayoutParams releaseParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        releaseParams.topMargin = ui.dp(9);
        github.addView(release, releaseParams);
        ui.detail(github, "https://github.com/arkylin/OPods/releases/latest");

        LinearLayout storage = ui.section(content, "数据位置说明");
        ui.cardTitle(storage, "设备 / 手机 / 协同", "每个控件下方都标注了协议和保存位置");
        ui.infoRow(storage, "设备", "AACP 或 ATT 写入 AirPods，设置保存在耳机");
        ui.infoRow(storage, "手机", "SharedPreferences、媒体键、连接策略");
        ui.infoRow(storage, "手机协同", "耳机事件 + 手机媒体键/蓝牙 Profile");
        ui.infoRow(storage, "官方页", "Intent 打开 OPPO 官方 Activity");
        ui.detail(storage, "AACP 公共头 04 00 04 00；控制 body 09 00 id v0 v1 v2 v3");

        LinearLayout sources = ui.section(content, "代码来源与许可");
        ui.cardTitle(sources, "上游项目", "本项目采用 GPL-3.0，保留上游协议研究的署名");
        paragraph(sources, "LibrePods v1.0.4：AACP 行为、能力表、ATT 通透/助听数据结构和功能范围参考；GPL-3.0。");
        paragraph(sources, "HyperPods-X：HyperOS 原生弹窗、官方设置注入与 AACP 思路参考；GPL-3.0。");
        paragraph(sources, "airpods-off-fix：ColorOS Off 帧和能力位行为参考；该提交未附明确许可文件，本项目只使用公开协议事实并独立实现。");
        paragraph(sources, "COUI：Slider 的轨道、滑块、关键点、按压动画和配色参数参考；Apache-2.0，本项目使用 Android SeekBar 与 Drawable 独立重实现。");
        paragraph(sources, "LibXposed API：编译期 LSPosed API，Apache-2.0。详细说明见 README 和 THIRD_PARTY.md。");

        LinearLayout disclaimer = ui.section(content, "免责声明");
        ui.cardTitle(disclaimer, "使用风险", null);
        paragraph(disclaimer, "本项目与 Apple、OPPO、一加及 ColorOS 官方没有隶属、授权或背书关系。");
        paragraph(disclaimer, "模块通过官方系统进程做互操作性增强，不保证所有机型、系统版本、固件和 AirPods 型号都可用；使用风险由使用者承担。");
        paragraph(disclaimer, "LSPosed、系统更新或 OPPO 组件混淆变化可能导致 Hook 失效；请保留可回退的系统/模块版本。");
        paragraph(disclaimer, "模块本身不联网、不收集数据、不上传日志；GitHub 链接只交给系统浏览器。");
        paragraph(disclaimer, "AirPods、Apple 名称和商标归 Apple Inc.；OPPO、ColorOS 归 OPPO；本项目仅用于兼容性研究与个人使用。");
    }

    private String versionName() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo("io.github.oppoairpods.popup", 0);
            return info.versionName;
        } catch (Throwable ignored) {
            return "3.4.31";
        }
    }

    private String versionCode() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo("io.github.oppoairpods.popup", 0);
            return String.valueOf(info.getLongVersionCode());
        } catch (Throwable ignored) {
            return "65";
        }
    }

    private void paragraph(LinearLayout parent, String value) {
        TextView text = ui.text(value, 11, ui.subTextColor, false);
        text.setPadding(0, ui.dp(4), 0, ui.dp(4));
        text.setLineSpacing(0f, 1.12f);
        parent.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable error) {
            ui.toast("无法打开浏览器");
        }
    }

    private void setListeningMode(int mode) {
        controller.setControlState(AacpProtocol.CONTROL_LISTENING_MODE, mode);
        controller.sendControl(AacpProtocol.CONTROL_LISTENING_MODE, mode);
        main.postDelayed(() -> {
            if (dialog.isShowing()) rebuild();
        }, 220);
    }

    private void setOnOff(int id, boolean checked) {
        int value = checked ? 1 : 2;
        controller.setControlState(id, value);
        controller.sendControl(id, value);
    }

    private boolean hasCapability(int capability) {
        AirPodsModels.Model model = controller.model();
        return model == null || model.has(capability);
    }

    private String lastGestureLabel() {
        int gesture = controller.lastHeadGesture();
        if (gesture == HeadGestureTracker.NOD) return "点头";
        if (gesture == HeadGestureTracker.SHAKE) return "摇头";
        return "尚未识别";
    }

    private void openSpatialAudio() {
        try {
            Intent intent = new Intent("com.oplus.mydevices.AIRPODS_SPATIAL_AUDIO");
            intent.setPackage("com.heytap.mydevices");
            intent.putExtra("mac_address", controller.address());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable error) {
            ui.toast("无法打开空间音频设置");
        }
    }
}

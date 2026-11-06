package io.github.oppoairpods.popup;

import android.app.Dialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Card dashboard shown from the official MyDevices AirPods settings page. */
final class AirPodsDashboard implements AirPodsController.Listener {
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
    private int builtRevision;
    private boolean builtOpened;
    private boolean builtAttConnected;

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
        controller.addListener(this);
        dialog.setOnDismissListener(d -> {
            controller.removeListener(this);
            main.removeCallbacksAndMessages(null);
            // The pose test is stopped by a delayed callback that was just removed; without
            // this the AirPods would keep streaming head-tracking packets.
            if (controller.headTrackingActive()) controller.setHeadTracking(false);
        });
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

    /** Keeps the header live and redraws the page once the device reports changed values. */
    @Override
    public void onAirPodsStateChanged() {
        if (!dialog.isShowing()) return;
        refreshHeader();
        if (ui.isDragging()) return;
        if (controller.controlsRevision() != builtRevision || controller.opened() != builtOpened
                || controller.attConnected() != builtAttConnected) {
            int scroll = scrollView.getScrollY();
            rebuild();
            scrollView.post(() -> scrollView.scrollTo(0, scroll));
        }
    }

    private void rebuild() {
        builtRevision = controller.controlsRevision();
        builtOpened = controller.opened();
        builtAttConnected = controller.attConnected();
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
        specs.add(new DashboardUi.ToggleSpec("开盖自动连接", "开盖时弹出官方弹窗，由官方流程连接",
                ProtocolInfo.localEvent("auto_connect", "官方 PTC 开盖广播 + 官方弹窗"),
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
        String saved = controller.equalizerPreset();
        boolean custom = "custom".equals(saved);
        ui.detail(eq, ProtocolInfo.eq(2, "<low> <mid> <high>") + "；" + ProtocolInfo.local("equalizer_preset"));
        ui.segment(eq, new String[]{"预设", "自定义"}, custom ? 1 : 0, index -> {
            if (index == 0) {
                controller.setEqualizerPreset("off");
                controller.sendEqualizer(1, 50, 50, 50);
            } else {
                controller.setEqualizerPreset("custom");
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
                        controller.setEqualizerPreset(keys[index]);
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
        buildConversationSection();
        buildAccommodationSection();
    }

    private void buildConversationSection() {
        LinearLayout ca = ui.section(content, "对话感知（手机端）");
        ui.cardTitle(ca, "说话时调低媒体", "AirPods 检测到你开口说话时由手机调低音量，停下后恢复；需要打开“对话感知”");
        List<DashboardUi.ToggleSpec> specs = new ArrayList<>();
        specs.add(new DashboardUi.ToggleSpec("调低媒体音量", "说话时降到下面设置的音量",
                ProtocolInfo.local("ca_lower_volume"), controller.conversationLowerVolume(), true,
                (button, checked) -> controller.setConversationLowerVolume(checked)));
        specs.add(new DashboardUi.ToggleSpec("同时暂停播放", "说话时暂停，结束后继续",
                ProtocolInfo.local("ca_pause_music"), controller.conversationPauseMusic(), true,
                (button, checked) -> controller.setConversationPauseMusic(checked)));
        specs.add(new DashboardUi.ToggleSpec("按当前音量比例", "关闭后改为不超过最大音量的百分比",
                ProtocolInfo.local("ca_relative_volume"), controller.conversationRelativeVolume(), true,
                (button, checked) -> controller.setConversationRelativeVolume(checked)));
        ui.toggleGrid(ca, specs);
        ui.slider(ca, "降低到", "相对当前音量或最大音量的百分比", 0, 100,
                controller.conversationVolumePercent(), v -> v + "%",
                controller::setConversationVolumePercent);
        ui.detail(ca, "AACP 4B 对话事件：01/02 开始说话，06/08/09 结束 · SP ca_* · 手机本地");
    }

    private void buildAccommodationSection() {
        LinearLayout acc = ui.section(content, "耳机调节 EQ（实验）");
        ui.cardTitle(acc, "Headphone Accommodation", "对应 LibrePods 的“Apply EQ to”：8 段增益写入耳机，作用于通话和/或媒体");
        ui.notice(acc, "实验", "耳机不会回报当前值，这里显示的是本机上次写入的值；是否生效取决于机型和固件。");
        float[] eq = controller.accommodationEq();
        boolean[] targets = {controller.accommodationPhone(), controller.accommodationMedia()};
        Runnable send = () -> {
            if (!controller.sendHeadphoneAccommodation(eq, targets[0], targets[1])) {
                ui.toast("控制通道未连接，已保存但未写入");
            }
        };
        List<DashboardUi.ToggleSpec> specs = new ArrayList<>();
        specs.add(new DashboardUi.ToggleSpec("应用到通话", null,
                ProtocolInfo.local("accommodation_phone"), targets[0], true, (button, checked) -> {
                    targets[0] = checked;
                    send.run();
                }));
        specs.add(new DashboardUi.ToggleSpec("应用到媒体", null,
                ProtocolInfo.local("accommodation_media"), targets[1], true, (button, checked) -> {
                    targets[1] = checked;
                    send.run();
                }));
        ui.toggleGrid(acc, specs);
        String[] bands = {"250", "500", "1k", "2k", "3k", "4k", "6k", "8k"};
        for (int i = 0; i < 8; i++) {
            final int band = i;
            ui.slider(acc, bands[i] + "Hz", null, 0, 100, Math.round(eq[i] * 100f), v -> v + "%", v -> {
                eq[band] = v / 100f;
                send.run();
            });
        }
        ui.detail(acc, "AACP 53 00 84 00 02 02 <通话 01|02> <媒体 01|02> + 4×8 float · 设备（只写）");
    }

    private void sendCustomEq(int[] values) {
        controller.setEqualizerPreset("custom");
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
        ui.cardTitle(head, "点头 / 摇头", "来电响铃时点头接听、摇头拒接；可以先用下面的测试确认识别效果");
        boolean headCapable = hasCapability(AirPodsModels.CAP_HEAD_GESTURES);
        boolean headSupported = headCapable && controller.headGesturesEnabled();
        List<DashboardUi.ToggleSpec> headSpecs = new ArrayList<>();
        headSpecs.add(new DashboardUi.ToggleSpec("启用头部手势", "允许 AirPods 上报头部姿态",
                ProtocolInfo.localEvent("head_gestures", "AACP 17 姿态包"),
                controller.headGesturesEnabled(), headCapable,
                (button, checked) -> {
                    controller.setHeadGesturesEnabled(checked);
                    main.post(this::rebuild);
                }));
        headSpecs.add(new DashboardUi.ToggleSpec("点头接听 / 摇头拒接", "响铃时识别；优先用系统接/挂电话接口，失败时改发耳机按键",
                ProtocolInfo.localEvent("head_gesture_calls", "Telecom / KEYCODE_HEADSETHOOK"),
                controller.headGestureCalls(), headSupported,
                (button, checked) -> controller.setHeadGestureCalls(checked)));
        headSpecs.add(new DashboardUi.ToggleSpec("使用备用头部追踪包", "收不到姿态包时再试（LibrePods 同名选项）",
                ProtocolInfo.local("alternate_head_tracking"),
                controller.alternateHeadTracking(), headSupported,
                (button, checked) -> controller.setAlternateHeadTracking(checked)));
        ui.toggleGrid(head, headSpecs);
        TextView headTest = ui.actionButton(controller.headTrackingActive() ? "停止姿态接收" : "测试姿态接收（10 秒）", false, v -> {
            if (!headSupported) return;
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
                    ui.toast("收到 " + received + " 个姿态包，最近识别：" + lastGestureLabel());
                    if (dialog.isShowing()) rebuild();
                }, 10000);
                rebuild();
            }
        });
        headTest.setAlpha(headSupported ? 1f : 0.45f);
        LinearLayout.LayoutParams testParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        testParams.topMargin = ui.dp(10);
        head.addView(headTest, testParams);
        ui.detail(head, "AACP 17 00 ... · 设备姿态包；手机做阈值识别，只在测试或来电响铃期间接收");
        ui.infoRow(head, "已接收姿态包", controller.headTrackingPackets() + " 个");
        ui.infoRow(head, "最近识别", lastGestureLabel());

        LinearLayout camera = ui.section(content, "相机遥控");
        ui.cardTitle(camera, "按耳机柄拍照", "LibrePods：相机在前台时按一下耳机柄即按快门");
        ui.notice(camera, "不可用", "LibrePods 用无障碍服务判断相机在前台，再用 root 执行 input keyevent 27 按快门。"
                + "本模块不运行 root 命令、不新增服务，系统也不允许官方进程向相机发送按键，所以做不到。");
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
        wearSpecs.add(new DashboardUi.ToggleSpec("取下时断开音频", "双耳取下 3 秒后断开媒体/通话音频，戴回时弹出官方弹窗恢复",
                ProtocolInfo.localEvent("disconnect_when_not_wearing", "A2DP/HFP 断开 + 官方弹窗"),
                controller.disconnectWhenNotWearing(), true,
                (button, checked) -> controller.setDisconnectWhenNotWearing(checked)));
        ui.toggleGrid(wear, wearSpecs);

        LinearLayout lid = ui.section(content, "开盖弹窗");
        ui.cardTitle(lid, "开盖自动连接", "打开充电盒时弹出 OPPO 官方连接弹窗，由官方流程完成连接");
        List<DashboardUi.ToggleSpec> lidSpecs = new ArrayList<>();
        lidSpecs.add(new DashboardUi.ToggleSpec("开盖自动连接", "收到自己 AirPods 的开盖广播时弹窗",
                ProtocolInfo.localEvent("auto_connect", "官方 PTC 开盖广播 + 官方弹窗"),
                controller.autoConnect(), true,
                (button, checked) -> controller.setAutoConnect(checked)));
        lidSpecs.add(new DashboardUi.ToggleSpec("未拿到密钥时也响应", "还没拿到识别密钥的耳机按未验证方式弹窗",
                ProtocolInfo.localEvent("lid_open_unverified_fallback", "未验证开盖"),
                controller.lidOpenFallback(), controller.autoConnect(),
                (button, checked) -> controller.setLidOpenFallback(checked)));
        ui.toggleGrid(lid, lidSpecs);
        ui.infoRow(lid, "识别密钥", controller.proximityKeyStatus());
        ui.featureRow(lid, "最近开盖广播", "记录", controller.lastAdvertSummary());
        ui.notice(lid, "受限", "ColorOS 官方扫描器只在亮屏时匹配 AirPods 开盖广播，息屏开盖不会弹窗；"
                + "官方主进程没被 LSPosed 注入时，开盖唤醒依赖 :cards 进程还在运行。");
        ui.notice(lid, "说明", "识别密钥（IRK）在 AACP 连接后自动向耳机请求。拿到之前无法区分附近别人的同型号 AirPods，"
                + "可能被别人开盖触发弹窗；不想这样可以关闭“未拿到密钥时也响应”。");
        ui.detail(lid, "Apple 0x4C 07 19 开盖广播（官方 PTC）· RPA 用 IRK 校验 · 电量用 ENC 解密");

        LinearLayout conn = ui.section(content, "自动连接");
        ui.cardTitle(conn, "设备侧配置", "连接与弹窗由 OPPO 官方流程负责；这里只写 AirPods 自己的 0x20 配置");
        List<DashboardUi.ToggleSpec> connSpecs = new ArrayList<>();
        connSpecs.add(new DashboardUi.ToggleSpec("AirPods 自动连接", "AACP 0x20 设备侧配置",
                ProtocolInfo.aacpSwitch(AacpProtocol.CONTROL_AUTOMATIC_CONNECTION),
                controller.controlOn(AacpProtocol.CONTROL_AUTOMATIC_CONNECTION), true,
                (button, checked) -> setOnOff(AacpProtocol.CONTROL_AUTOMATIC_CONNECTION, checked)));
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
        TextView popup = ui.actionButton("通过官方弹窗连接", false, v -> {
            BluetoothDevice device = controller.currentDevice();
            if (device == null) device = controller.findActiveAirPods();
            if (device == null) {
                ui.toast("没有已配对的 AirPods");
                return;
            }
            controller.launchOfficialPopupNow(device);
        });
        LinearLayout.LayoutParams popupParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        popupParams.topMargin = ui.dp(9);
        info.addView(popup, popupParams);
        ui.detail(info, "替代 LibrePods 的“重新连接上一台设备”：只打开官方弹窗，由官方流程连接");

        LinearLayout policy = ui.section(content, "LibrePods 连接策略");
        ui.cardTitle(policy, "自动接管与多设备", "LibrePods 的这些选项依赖它自己的后台扫描或改动蓝牙栈");
        ui.notice(policy, "不可用", "按耳机状态（未连接/空闲/播放/通话）或手机事件（来电/开始播放）自动连接："
                + "需要后台 BLE 扫描判断耳机状态并主动发起连接，违反“只走官方连接/弹窗流程、禁止自建扫描”的规则。"
                + "可用替代：开盖弹窗、通过官方弹窗连接。");
        ui.notice(policy, "不可用", "多设备连接（Multipoint）和“伪装为苹果设备”：需要修改蓝牙栈上报的 VendorID，"
                + "本模块不修改蓝牙栈；依赖它的功能（大声降低、助听、通透自定义）可能被耳机忽略。");
    }

    private void buildHealth() {
        boolean hearingAidSupported = hasCapability(AirPodsModels.CAP_HEARING_AID);
        boolean loudSupported = hasCapability(AirPodsModels.CAP_LOUD_SOUND);
        if (!hearingAidSupported && !loudSupported) {
            LinearLayout note = ui.section(content, "听力健康");
            ui.cardTitle(note, "当前机型未声明听力能力", "AirPods 5 不支持助听/大声降低；Pro 3 才开放完整选项。");
            ui.notice(note, "不可用", "需要 AirPods Pro 系列：助听器、听力测试结果、通透模式自定义、大声降低、EN 352 工作保护；"
                    + "心率监测只有 AirPods Pro 3。");
            return;
        }

        LinearLayout protection = ui.section(content, "听力保护");
        ui.cardTitle(protection, "环境噪声", "ATT 0x1B / PPE 0x37");
        List<DashboardUi.ToggleSpec> protectionSpecs = new ArrayList<>();
        protectionSpecs.add(new DashboardUi.ToggleSpec("大声降低", "透明与自适应模式下降低突发大声",
                ProtocolInfo.attWrite(0x1B, "01|00"),
                controller.loudSoundReduction(), loudSupported && controller.attConnected(),
                (button, checked) -> {
                    boolean sent = controller.writeLoudSoundReduction(checked);
                    ui.toast(!sent ? "ATT 未连接，未写入" : checked ? "已开启大声降低" : "已关闭大声降低");
                    if (!sent) main.post(this::rebuild);
                }));
        protectionSpecs.add(new DashboardUi.ToggleSpec("EN 352 工作保护", "限制最大媒体音量 82 dBA",
                ProtocolInfo.aacpSwitch(AacpProtocol.CONTROL_PPE_TOGGLE),
                controller.controlOn(AacpProtocol.CONTROL_PPE_TOGGLE), hearingAidSupported,
                (button, checked) -> setOnOff(AacpProtocol.CONTROL_PPE_TOGGLE, checked)));
        ui.toggleGrid(protection, protectionSpecs);

        // ATT values are only editable once they were read from this connection: writing a
        // made-up template could overwrite the transparency/hearing data stored on the AirPods.
        AirPodsAttData.Settings transparency = AirPodsAttData.parseTransparency(controller.transparencyData());
        boolean trWritable = controller.transparencyWritable() && transparency != null;
        if (transparency == null) transparency = AirPodsAttData.Settings.defaults();
        LinearLayout tr = ui.section(content, "通透模式自定义");
        ui.cardTitle(tr, "通透模式", trWritable ? "已读取通透参数，修改即写入 AirPods"
                : controller.attConnected() ? "正在读取通透参数，读取完成前不可修改"
                : "ATT 未连接，读取到 AirPods 当前参数前不可修改");
        final AirPodsAttData.Settings trValue = transparency;
        List<DashboardUi.ToggleSpec> trSpecs = new ArrayList<>();
        trSpecs.add(new DashboardUi.ToggleSpec("启用自定义通透", "写入 AirPods 通透参数",
                ProtocolInfo.attWrite(0x18, "<100B>"),
                trValue.transparencyEnabled, trWritable, (button, checked) -> {
                    trValue.transparencyEnabled = checked;
                    writeAtt(trValue, false);
                }));
        trSpecs.add(new DashboardUi.ToggleSpec("对话增强", "通透模式下增强前方语音",
                ProtocolInfo.attWrite(0x18, "<100B>"),
                trValue.leftConversationBoost || trValue.rightConversationBoost,
                trWritable, (button, checked) -> {
                    trValue.leftConversationBoost = checked;
                    trValue.rightConversationBoost = checked;
                    writeAtt(trValue, false);
                }));
        ui.toggleGrid(tr, trSpecs);
        addAttAdjustmentSliders(tr, trValue, false, trWritable);
        addEqSliders(tr, trValue, "双耳 EQ", false, true, true, trWritable);
        ui.detail(tr, ProtocolInfo.attWrite(0x18, "<100B> 双耳 8 段 EQ"));

        AirPodsAttData.Settings hearing = AirPodsAttData.parseHearingAid(controller.hearingAidData());
        boolean aidWritable = controller.hearingAidWritable() && hearing != null;
        if (hearing == null) hearing = AirPodsAttData.Settings.defaults();
        LinearLayout aid = ui.section(content, "助听器");
        ui.cardTitle(aid, "助听模式", aidWritable ? "已读取助听参数，修改即写入 AirPods"
                : controller.attConnected() ? "正在读取助听参数，读取完成前不可修改"
                : "ATT 未连接，读取到 AirPods 当前参数前不可修改");
        final AirPodsAttData.Settings hearingValue = hearing;
        boolean aidEnabled = hearingAidEnabled();
        List<DashboardUi.ToggleSpec> aidSpecs = new ArrayList<>();
        aidSpecs.add(new DashboardUi.ToggleSpec("启用助听", "0x2C + 0x33",
                "AACP 09 00 2C 01 01|02 + 09 00 33 01|02 · 设备",
                aidEnabled, hearingAidSupported, (button, checked) -> setHearingAidEnabled(checked)));
        aidSpecs.add(new DashboardUi.ToggleSpec("滑动调节增益", "透明模式无媒体时滑动调节放大",
                ProtocolInfo.aacpSwitch(AacpProtocol.CONTROL_HPS_GAIN_SWIPE),
                controller.controlOn(AacpProtocol.CONTROL_HPS_GAIN_SWIPE), hearingAidSupported,
                (button, checked) -> setOnOff(AacpProtocol.CONTROL_HPS_GAIN_SWIPE, checked)));
        ui.toggleGrid(aid, aidSpecs);
        addAttAdjustmentSliders(aid, hearingValue, true, aidWritable);
        ui.divider(aid);
        addEqSliders(aid, hearingValue, "左耳 8 段 EQ", true, true, false, aidWritable);
        ui.divider(aid);
        addEqSliders(aid, hearingValue, "右耳 8 段 EQ", true, false, true, aidWritable);
        ui.detail(aid, ProtocolInfo.attWrite(0x2A, "<104B> 左右耳 8 段 EQ"));

        LinearLayout test = ui.section(content, "听力测试");
        ui.cardTitle(test, "更新听力图", "输入各频点听力损失（dB HL，"
                + (int) AUDIOGRAM_MIN + "–" + (int) AUDIOGRAM_MAX + "）；应用后写回助听 EQ 数据");
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
            leftFields[i] = hearingEdit(formatEq(hearing.leftEq[i]));
            rightFields[i] = hearingEdit(formatEq(hearing.rightEq[i]));
            leftFields[i].setEnabled(aidWritable);
            rightFields[i].setEnabled(aidWritable);
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
            if (!controller.hearingAidWritable()) {
                ui.toast("尚未读取到助听参数，未写入");
                return;
            }
            for (int i = 0; i < 8; i++) {
                Float left = parseEq(leftFields[i].getText().toString());
                Float right = parseEq(rightFields[i].getText().toString());
                if (left == null || right == null) {
                    ui.toast(frequencies[i] + " 的数值需在 " + (int) AUDIOGRAM_MIN + "–"
                            + (int) AUDIOGRAM_MAX + " dB HL 之间");
                    return;
                }
                hearingValue.leftEq[i] = left;
                hearingValue.rightEq[i] = right;
            }
            if (controller.writeHearingAid(hearingValue)) {
                setHearingAidEnabled(true);
                ui.toast("已写入听力图");
            } else {
                ui.toast("写入失败：ATT 未连接或参数未读取");
            }
        });
        apply.setAlpha(aidWritable ? 1f : 0.45f);
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        applyParams.topMargin = ui.dp(12);
        test.addView(apply, applyParams);
        ui.detail(test, "AACP 2C 01 01 + 33 01 → 设备；ATT 12 2A 00 <104B>");

        LinearLayout hrm = ui.section(content, "心率监测（实验）");
        ui.cardTitle(hrm, "耳机心率传感器", "AACP 0x30，仅 AirPods Pro 3");
        List<DashboardUi.ToggleSpec> hrmSpecs = new ArrayList<>();
        hrmSpecs.add(new DashboardUi.ToggleSpec("开启心率传感器", null,
                ProtocolInfo.aacpSwitch(AacpProtocol.CONTROL_HRM_STATE),
                controller.controlOn(AacpProtocol.CONTROL_HRM_STATE), hasCapability(AirPodsModels.CAP_HRM),
                (button, checked) -> setOnOff(AacpProtocol.CONTROL_HRM_STATE, checked)));
        ui.toggleGrid(hrm, hrmSpecs);
        ui.notice(hrm, "实验", "LibrePods 也只做到开关传感器；手机端暂时读不到心率数值。");

        LinearLayout assist = ui.section(content, "媒体辅助");
        ui.cardTitle(assist, "按听力图调整音乐和通话", "LibrePods 的 Media Assist");
        ui.notice(assist, "不可用", "LibrePods 上游已把这部分界面注释掉，写入格式未确认，暂不提供写入。");
    }

    // AirPods report transparency EQ bands on a 0..100 scale (LibrePods uses the same range).
    private static final float EQ_MAX = 100f;
    // Audiogram entries are hearing levels in dB HL; audiometers cover roughly -10..120.
    private static final float AUDIOGRAM_MIN = -20f;
    private static final float AUDIOGRAM_MAX = 120f;

    private void addAttAdjustmentSliders(LinearLayout parent, AirPodsAttData.Settings value,
                                         boolean hearing, boolean enabled) {
        int amp = toPercent(value.netAmplification);
        int balance = toPercent(value.balance);
        int tone = toPercent(value.leftTone);
        int ambient = Math.round(clamp01(value.leftAmbientNoiseReduction) * 100f);
        ui.detail(parent, ProtocolInfo.attWrite(hearing ? 0x2A : 0x18, "<104B> 放大/平衡/音色/环境降噪"));
        ui.slider(parent, "放大", "-100% 至 +100%", 0, 100, amp, v -> signedPercent(v), enabled, v -> {
            value.setAmpBalance(fromPercent(v), value.balance);
            writeAtt(value, hearing);
        });
        ui.slider(parent, "平衡", "左耳 ↔ 右耳", 0, 100, balance, v -> balanceLabel(v), enabled, v -> {
            value.setAmpBalance(value.netAmplification, fromPercent(v));
            writeAtt(value, hearing);
        });
        ui.slider(parent, "音色", "更暗 ↔ 更亮", 0, 100, tone, v -> signedPercent(v), enabled, v -> {
            value.leftTone = fromPercent(v);
            value.rightTone = value.leftTone;
            writeAtt(value, hearing);
        });
        ui.slider(parent, "环境噪声降低", "少 ↔ 多", 0, 100, ambient, v -> v + "%", enabled, v -> {
            value.leftAmbientNoiseReduction = v / 100f;
            value.rightAmbientNoiseReduction = value.leftAmbientNoiseReduction;
            writeAtt(value, hearing);
        });
        if (hearing) {
            int own = Math.round(clamp01(value.ownVoiceAmplification) * 100f);
            ui.slider(parent, "自己的声音", "助听模式的自己声音放大", 0, 100, own, v -> v + "%", enabled, v -> {
                value.ownVoiceAmplification = v / 100f;
                writeAtt(value, true);
            });
        }
    }

    private void addEqSliders(LinearLayout parent, AirPodsAttData.Settings value, String title,
                              boolean hearing, boolean leftEar, boolean rightEar, boolean enabled) {
        TextView label = ui.text(title, 13, ui.textColor, true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ui.dp(10);
        parent.addView(label, params);
        String[] bands = {"250", "500", "1k", "2k", "3k", "4k", "6k", "8k"};
        for (int i = 0; i < 8; i++) {
            final int index = i;
            float current = leftEar ? value.leftEq[index] : value.rightEq[index];
            int shown = Math.round(Math.max(0f, Math.min(EQ_MAX, current)));
            ui.slider(parent, bands[i] + "Hz", null, 0, (int) EQ_MAX, shown, v -> String.valueOf(v), enabled, v -> {
                value.setEq(index, v, leftEar, rightEar);
                writeAtt(value, hearing);
            });
        }
    }

    private void writeAtt(AirPodsAttData.Settings value, boolean hearing) {
        boolean sent = hearing ? controller.writeHearingAid(value) : controller.writeTransparency(value);
        if (!sent) {
            ui.toast("写入失败：ATT 未连接或参数未读取");
            main.post(this::rebuild);
        }
    }

    private static int toPercent(float signed) {
        return Math.round((Math.max(-1f, Math.min(1f, signed)) + 1f) * 50f);
    }

    private static float fromPercent(int value) {
        return value / 50f - 1f;
    }

    private static String signedPercent(int value) {
        int signed = value * 2 - 100;
        return (signed > 0 ? "+" : "") + signed + "%";
    }

    private static String balanceLabel(int value) {
        int signed = value * 2 - 100;
        if (signed == 0) return "居中";
        return signed < 0 ? "偏左 " + (-signed) + "%" : "偏右 " + signed + "%";
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static String formatEq(float value) {
        return Math.abs(value - Math.round(value)) < 0.005f
                ? String.valueOf(Math.round(value)) : String.format(java.util.Locale.ROOT, "%.2f", value);
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

    /** Parses one audiogram field; null unless it is a finite hearing level in range. */
    private static Float parseEq(String text) {
        try {
            float value = Float.parseFloat(text.trim());
            return Float.isFinite(value) && value >= AUDIOGRAM_MIN && value <= AUDIOGRAM_MAX ? value : null;
        } catch (Throwable ignored) {
            return null;
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
        ui.cardTitle(rename, "重命名", "通过 AACP 0x1A 写入 AirPods");
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
            String value = name.getText().toString().trim();
            if (value.isEmpty()) {
                ui.toast("名称不能为空");
                return;
            }
            controller.sendRename(value);
            ui.toast(controller.opened() ? "已发送重命名" : "控制通道连接后发送重命名");
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
        buildKeysSection();
        buildRawControlsSection();
        buildAdvancedControlsSection();
        buildTroubleshootingSection();
        LinearLayout find = ui.section(content, "查找（Find My）");
        ui.cardTitle(find, "加入查找网络 / 充电盒播放声音", null);
        ui.notice(find, "不可用", "LibrePods 上游也还没有实现（需要进一步逆向，可能还要 root），这里先保留入口。");
    }

    private void buildKeysSection() {
        LinearLayout keys = ui.section(content, "识别密钥");
        ui.cardTitle(keys, "IRK / ENC", "AirPods 通过 AACP 0x31 下发；用于开盖时确认是自己的耳机、解密广播电量");
        ui.infoRow(keys, "当前耳机", controller.proximityKeyStatus());
        ui.featureRow(keys, "最近开盖广播", "记录", controller.lastAdvertSummary());
        ui.detail(keys, "AACP 30 00 05 00 请求 · 31 回复 · 只保存在 MyDevices 私有目录，日志不记录密钥内容");
    }

    private void buildRawControlsSection() {
        LinearLayout raw = ui.section(content, "全部控制项（原始值）");
        ui.cardTitle(raw, "AirPods 回报的控制值", "连接时耳机会回报全部控制项，这里按原样列出");
        Map<Integer, int[]> snapshot = controller.controlsSnapshot();
        if (snapshot.isEmpty()) {
            ui.infoRow(raw, "暂无", "控制通道连接后显示");
            return;
        }
        for (Map.Entry<Integer, int[]> entry : snapshot.entrySet()) {
            ui.infoRow(raw, controlName(entry.getKey()), hexValues(entry.getValue()));
        }
    }

    private void buildAdvancedControlsSection() {
        LinearLayout adv = ui.section(content, "高级控制（实验）");
        ui.cardTitle(adv, "LibrePods 已定义、未在其界面开放的控制项", "含义未完全确认，修改前请确认你知道它的作用");
        ui.notice(adv, "实验", "这些开关直接写 AACP 09 控制包，以耳机回报为准；“软件静音”可能让麦克风无声，"
                + "“语音唤醒”只对支持的固件有效。");
        int[] ids = {AacpProtocol.CONTROL_VOICE_TRIGGER, AacpProtocol.CONTROL_IN_CASE_TONE,
                AacpProtocol.CONTROL_SIRI_MULTITONE, AacpProtocol.CONTROL_SOFTWARE_MUTE,
                AacpProtocol.CONTROL_ALLOW_AUTO_CONNECT, AacpProtocol.CONTROL_SSL};
        String[] names = {"语音唤醒", "盒内提示音", "Siri 多段提示音", "软件静音", "允许自动连接（0x36）", "SSL（0x29）"};
        List<DashboardUi.ToggleSpec> specs = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            final int id = ids[i];
            specs.add(new DashboardUi.ToggleSpec(names[i], null, ProtocolInfo.aacpSwitch(id),
                    controller.controlOn(id), true, (button, checked) -> {
                        if (!controller.sendRawControl(id, checked ? 1 : 2)) ui.toast("控制通道未连接");
                    }));
        }
        ui.toggleGrid(adv, specs);
        ui.detail(adv, "只读项（按键发送模式 0x05、连接归属 0x06、单击/双击/长按模式 0x14–0x16、"
                + "表冠方向 0x1C、EN 352 上限 0x38）见上方原始值列表");
    }

    private void buildTroubleshootingSection() {
        LinearLayout trouble = ui.section(content, "故障排查");
        ui.cardTitle(trouble, "调试日志", "排查问题时可以把最近的日志分享出去");
        ui.featureRow(trouble, "日志文件", "本机", Logx.logPath());
        TextView share = ui.actionButton("分享最近日志", false, v -> shareLog());
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shareParams.topMargin = ui.dp(10);
        trouble.addView(share, shareParams);
        TextView clear = ui.actionButton("清空日志", false, v -> {
            Logx.clear();
            ui.toast("已清空日志");
        });
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clearParams.topMargin = ui.dp(9);
        trouble.addView(clear, clearParams);
        ui.detail(trouble, "日志只保存在本机；分享时由你选择目标应用，模块本身不联网");
    }

    private void shareLog() {
        String log = Logx.tail(400);
        if (log == null || log.isEmpty()) {
            ui.toast("暂无日志");
            return;
        }
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "OPods 日志");
            send.putExtra(Intent.EXTRA_TEXT, log);
            Intent chooser = Intent.createChooser(send, "分享 OPods 日志");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
        } catch (Throwable error) {
            ui.toast("无法分享日志");
        }
    }

    private static String controlName(int id) {
        String name;
        switch (id) {
            case 0x01: name = "麦克风"; break;
            case 0x05: name = "按键发送模式"; break;
            case 0x06: name = "连接归属"; break;
            case 0x0A: name = "自动人耳检测"; break;
            case 0x0D: name = "聆听模式"; break;
            case 0x12: name = "语音唤醒"; break;
            case 0x14: name = "单击模式"; break;
            case 0x15: name = "双击模式"; break;
            case 0x16: name = "长按模式"; break;
            case 0x17: name = "按压速度"; break;
            case 0x18: name = "长按时长"; break;
            case 0x1A: name = "长按循环模式"; break;
            case 0x1B: name = "单耳降噪"; break;
            case 0x1C: name = "表冠方向"; break;
            case 0x1E: name = "自动接听"; break;
            case 0x1F: name = "提示音音量"; break;
            case 0x20: name = "自动连接"; break;
            case 0x23: name = "音量滑动速度"; break;
            case 0x24: name = "通话控制"; break;
            case 0x25: name = "音量滑动"; break;
            case 0x26: name = "自适应音量"; break;
            case 0x27: name = "软件静音"; break;
            case 0x28: name = "对话感知"; break;
            case 0x29: name = "SSL"; break;
            case 0x2C: name = "助听器"; break;
            case 0x2E: name = "自适应强度"; break;
            case 0x2F: name = "滑动调节增益"; break;
            case 0x30: name = "心率"; break;
            case 0x31: name = "盒内提示音"; break;
            case 0x32: name = "Siri 多段提示音"; break;
            case 0x33: name = "助听辅助"; break;
            case 0x34: name = "允许关闭"; break;
            case 0x35: name = "睡眠检测"; break;
            case 0x36: name = "允许自动连接"; break;
            case 0x37: name = "EN 352"; break;
            case 0x38: name = "EN 352 上限"; break;
            case 0x39: name = "耳机柄配置"; break;
            case 0x3B: name = "优化充电上限"; break;
            case 0x4B: name = "对话事件（本地）"; break;
            case 0x63: name = "均衡器（本地）"; break;
            default: name = "未知"; break;
        }
        String hex = Integer.toHexString(id).toUpperCase(java.util.Locale.ROOT);
        return "0x" + (hex.length() == 1 ? "0" + hex : hex) + " " + name;
    }

    private static String hexValues(int[] values) {
        if (values == null || values.length == 0) return "-";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) text.append(' ');
            String part = Integer.toHexString(values[i] & 0xFF).toUpperCase(java.util.Locale.ROOT);
            text.append(part.length() == 1 ? "0" + part : part);
        }
        return text.toString();
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

        buildParitySection();

        LinearLayout storage = ui.section(content, "数据位置说明");
        ui.cardTitle(storage, "设备 / 手机 / 协同", "每个控件下方都标注了协议和保存位置");
        ui.infoRow(storage, "设备", "AACP 或 ATT 写入 AirPods，设置保存在耳机");
        ui.infoRow(storage, "手机", "SharedPreferences（按耳机分别保存）、媒体键");
        ui.infoRow(storage, "手机协同", "耳机事件 + 手机媒体键");
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

    private void buildParitySection() {
        LinearLayout parity = ui.section(content, "LibrePods 功能对照");
        ui.cardTitle(parity, "上游 LibrePods 的功能在这里的位置",
                "已支持 / 受限 / 实验 / 不可用；受限和不可用的原因也写在对应页面");
        String[][] rows = {
                {"聆听模式切换", "已支持", "常用页噪声控制、官方首屏横条"},
                {"入耳检测与自动播放/暂停", "已支持", "连接页“自动人耳检测”“入耳播放”"},
                {"电量显示", "已支持", "官方弹窗、耳机页、控制中心磁贴和面板头部"},
                {"重命名", "已支持", "设备页；ColorOS 可能仍显示旧名，需要重新配对"},
                {"大声降低", "已支持", "健康页；需要 Pro 机型，可能还需要苹果 VendorID"},
                {"头部手势（点头接听/摇头拒接）", "已支持", "操控页；包含姿态测试与备用追踪包"},
                {"对话感知", "已支持", "常用页开关 + 音频页手机端降音量/暂停"},
                {"自动连接到 AirPods", "已支持", "连接页 0x20 设备侧配置 + 开盖自动连接"},
                {"开盖弹窗", "受限", "连接页；只在亮屏时官方扫描器能匹配，息屏开盖不弹"},
                {"助听器 / 听力测试结果", "已支持", "健康页；需要 Pro 机型"},
                {"通透模式自定义", "已支持", "健康页；需要 Pro 机型"},
                {"多设备连接（Multipoint）", "不可用", "需要修改蓝牙栈 VendorID，本模块不改蓝牙栈"},
                {"按压速度 / 长按时长 / 单耳降噪 / 音量滑动 / 滑动速度", "已支持", "操控页、常用页"},
                {"长按循环模式 / 语音助手", "已支持", "操控页耳机柄动作"},
                {"通话控制 / 自动接听", "已支持", "操控页"},
                {"个性化音量", "已支持", "常用页“自适应音量”"},
                {"麦克风", "已支持", "连接页"},
                {"入睡时暂停", "已支持", "连接页“睡眠检测”"},
                {"允许“关闭”聆听模式", "已支持", "连接页“允许关闭”"},
                {"优化充电上限", "已支持", "连接页“优化充电”（0x3B）"},
                {"提示音音量", "已支持", "音频页"},
                {"自适应音频强度", "已支持", "常用页、音频页"},
                {"均衡器", "已支持", "音频页预设与自定义"},
                {"耳机调节 EQ（Apply EQ to）", "实验", "音频页；只写不读"},
                {"EN 352 工作保护", "已支持", "健康页；需要 Pro 机型"},
                {"心率监测", "实验", "健康页；需要 Pro 3，只能开关传感器"},
                {"媒体辅助（Media Assist）", "不可用", "上游也已注释掉，写入格式未确认"},
                {"相机遥控", "不可用", "上游依赖无障碍服务和 root 按键注入"},
                {"按耳机状态/来电/播放自动接管", "不可用", "需要自建后台扫描和主动连接，违反只走官方流程的规则"},
                {"取下时断开音频", "已支持", "连接页"},
                {"重新连接上一台设备", "已支持", "连接页“通过官方弹窗连接”"},
                {"伪装为苹果设备（VendorID）", "不可用", "需要修改蓝牙栈"},
                {"备用头部追踪包", "已支持", "操控页"},
                {"识别密钥（IRK/ENC）", "已支持", "设备页；用于开盖识别和广播电量"},
                {"全部控制项 / 高级控制", "实验", "设备页"},
                {"故障排查日志", "已支持", "设备页分享/清空日志"},
                {"设备信息", "已支持", "设备页"},
                {"小组件 / 弹窗动画 / 灵动岛 / 主题", "受限", "没有独立 App；由 ColorOS 官方弹窗和控制中心磁贴代替"},
                {"低电量通知", "已支持", "常用页、连接页"},
                {"查找（Find My）", "不可用", "上游也未实现"},
                {"头部追踪空间音频", "受限", "只能打开 ColorOS 官方空间音频页；上游也未实现"},
                {"高质量双向音频", "不可用", "上游实验项，需要系统层面的编码支持"},
        };
        for (String[] row : rows) ui.featureRow(parity, row[0], row[1], row[2]);
    }

    private String versionName() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo("io.github.oppoairpods.popup", 0);
            return info.versionName;
        } catch (Throwable ignored) {
            return "-";
        }
    }

    private String versionCode() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo("io.github.oppoairpods.popup", 0);
            return String.valueOf(info.getLongVersionCode());
        } catch (Throwable ignored) {
            return "-";
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

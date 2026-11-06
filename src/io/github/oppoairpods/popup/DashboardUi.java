package io.github.oppoairpods.popup;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Small view toolkit used by the in-process AirPods dashboard. */
final class DashboardUi {
    final Context context;
    final int accent = Color.rgb(52, 120, 246);
    final int cardColor;
    final int groupColor;
    final int textColor;
    final int subTextColor;
    final int dividerColor;
    final int dangerColor = Color.rgb(220, 68, 68);
    final float density;
    private boolean dragging;

    DashboardUi(Context context) {
        this.context = context;
        this.density = context.getResources().getDisplayMetrics().density;
        boolean dark = (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        cardColor = dark ? Color.rgb(28, 29, 32) : Color.WHITE;
        groupColor = dark ? Color.rgb(42, 43, 47) : Color.rgb(245, 246, 248);
        textColor = dark ? Color.rgb(245, 245, 247) : Color.rgb(20, 20, 24);
        subTextColor = dark ? Color.rgb(166, 168, 176) : Color.rgb(105, 108, 116);
        dividerColor = dark ? Color.rgb(58, 59, 64) : Color.rgb(226, 228, 232);
    }

    /** True while the user holds a slider; rebuilding the page then would cancel the drag. */
    boolean isDragging() {
        return dragging;
    }

    int dp(float value) {
        return Math.round(value * density);
    }

    GradientDrawable rounded(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLineSpacing(0f, 1.05f);
        return view;
    }

    LinearLayout card() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(rounded(groupColor, 22));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);
        return card;
    }

    LinearLayout section(LinearLayout parent, String title) {
        TextView header = text(title, 13, subTextColor, true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(4);
        params.topMargin = dp(12);
        params.bottomMargin = dp(8);
        parent.addView(header, params);
        LinearLayout card = card();
        parent.addView(card);
        return card;
    }

    void cardTitle(LinearLayout card, String title, String subtitle) {
        card.addView(text(title, 17, textColor, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 12, subTextColor, false);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(4);
            params.bottomMargin = dp(8);
            card.addView(sub, params);
        }
    }

    void divider(LinearLayout parent) {
        View line = new View(context);
        line.setBackgroundColor(dividerColor);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        params.topMargin = dp(12);
        params.bottomMargin = dp(12);
        parent.addView(line, params);
    }

    LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    Switch switchView(boolean checked) {
        Switch toggle = new Switch(context);
        toggle.setChecked(checked);
        toggle.setShowText(false);
        toggle.setButtonTintList(ColorStateList.valueOf(accent));
        toggle.setTrackTintList(ColorStateList.valueOf(
                checked ? Color.argb(110, 52, 120, 246) : Color.argb(100, 150, 153, 160)));
        toggle.setClickable(false);
        toggle.setFocusable(false);
        return toggle;
    }

    static final class ToggleSpec {
        final String title;
        final String summary;
        final String detail;
        final boolean checked;
        final boolean enabled;
        final CompoundButton.OnCheckedChangeListener listener;

        ToggleSpec(String title, String summary, boolean checked, boolean enabled,
                   CompoundButton.OnCheckedChangeListener listener) {
            this(title, summary, null, checked, enabled, listener);
        }

        ToggleSpec(String title, String summary, String detail, boolean checked, boolean enabled,
                   CompoundButton.OnCheckedChangeListener listener) {
            this.title = title;
            this.summary = summary;
            this.detail = detail;
            this.checked = checked;
            this.enabled = enabled;
            this.listener = listener;
        }
    }

    void detail(LinearLayout parent, String text) {
        if (text == null || text.isEmpty()) return;
        TextView value = text(text, 9.5f, accent, false);
        value.setTypeface(Typeface.MONOSPACE);
        value.setLineSpacing(0f, 1.0f);
        value.setTextIsSelectable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4);
        parent.addView(value, params);
    }

    void toggleGrid(LinearLayout parent, List<ToggleSpec> specs) {
        for (int i = 0; i < specs.size(); i += 2) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) rowParams.topMargin = dp(8);
            parent.addView(row, rowParams);
            addToggleTile(row, specs.get(i));
            if (i + 1 < specs.size()) {
                View spacer = new View(context);
                row.addView(spacer, new LinearLayout.LayoutParams(dp(8), 1));
                addToggleTile(row, specs.get(i + 1));
            } else {
                View spacer = new View(context);
                row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
    }

    private void addToggleTile(LinearLayout row, ToggleSpec spec) {
        LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(14), dp(13), dp(14), dp(13));
        tile.setBackground(rounded(cardColor, 18));
        tile.setEnabled(spec.enabled);
        tile.setAlpha(spec.enabled ? 1f : 0.45f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(tile, params);
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        tile.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = text(spec.title, 14, textColor, true);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch toggle = switchView(spec.checked);
        top.addView(toggle, new LinearLayout.LayoutParams(dp(44), dp(30)));
        if (spec.summary != null && !spec.summary.isEmpty()) {
            TextView summary = text(spec.summary, 11, subTextColor, false);
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            summaryParams.topMargin = dp(5);
            tile.addView(summary, summaryParams);
        }
        if (spec.detail != null && !spec.detail.isEmpty()) {
            TextView detail = text(spec.detail, 9, accent, false);
            detail.setTypeface(Typeface.MONOSPACE);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            detailParams.topMargin = dp(5);
            tile.addView(detail, detailParams);
        }
        tile.setOnClickListener(v -> {
            if (!spec.enabled) return;
            boolean next = !toggle.isChecked();
            toggle.setChecked(next);
            toggle.setTrackTintList(ColorStateList.valueOf(
                    next ? Color.argb(110, 52, 120, 246) : Color.argb(100, 150, 153, 160)));
            spec.listener.onCheckedChanged(toggle, next);
        });
    }

    void slider(LinearLayout parent, String title, String summary, int min, int max, int value,
                java.util.function.IntFunction<String> formatter, IntConsumer onDone) {
        slider(parent, title, summary, min, max, value, formatter, true, onDone);
    }

    void slider(LinearLayout parent, String title, String summary, int min, int max, int value,
                java.util.function.IntFunction<String> formatter, boolean enabled, IntConsumer onDone) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blockParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blockParams.topMargin = dp(8);
        parent.addView(block, blockParams);
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        block.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        head.addView(text(title, 14, textColor, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView valueText = text(formatter.apply(Math.max(min, Math.min(max, value))),
                13, subTextColor, false);
        head.addView(valueText);
        if (summary != null && !summary.isEmpty()) {
            TextView sub = text(summary, 11, subTextColor, false);
            LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subParams.topMargin = dp(3);
            block.addView(sub, subParams);
        }
        SeekBar bar = new SeekBar(context);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
        bar.setSplitTrack(false);
        bar.setThumbTintList(ColorStateList.valueOf(accent));
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(dividerColor));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        barParams.topMargin = dp(2);
        block.addView(bar, barParams);
        bar.setEnabled(enabled);
        block.setAlpha(enabled ? 1f : 0.45f);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                valueText.setText(formatter.apply(progress + min));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                dragging = false;
                onDone.accept(seekBar.getProgress() + min);
            }
        });
    }

    LinearLayout segment(LinearLayout parent, String[] labels, int selected, IntConsumer onChange) {
        LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setPadding(dp(3), dp(3), dp(3), dp(3));
        group.setBackground(rounded(cardColor, 16));
        LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        groupParams.topMargin = dp(8);
        parent.addView(group, groupParams);
        List<TextView> buttons = new ArrayList<>();
        final int[] current = {selected};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView button = text(labels[i], 12, subTextColor, true);
            button.setGravity(Gravity.CENTER);
            button.setPadding(dp(8), dp(9), dp(8), dp(9));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            group.addView(button, params);
            buttons.add(button);
            button.setOnClickListener(v -> {
                current[0] = index;
                paintSegment(buttons, current[0]);
                onChange.accept(index);
            });
        }
        paintSegment(buttons, selected);
        return group;
    }

    private void paintSegment(List<TextView> buttons, int selected) {
        for (int i = 0; i < buttons.size(); i++) {
            TextView button = buttons.get(i);
            button.setBackground(rounded(i == selected ? accent : Color.TRANSPARENT, 13));
            button.setTextColor(i == selected ? Color.WHITE : subTextColor);
        }
    }

    TextView actionButton(String label, boolean primary, View.OnClickListener click) {
        TextView button = text(label, 14, primary ? Color.WHITE : textColor, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setBackground(rounded(primary ? accent : cardColor, 18));
        button.setOnClickListener(click);
        return button;
    }

    void infoRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(text(label, 13, subTextColor, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView valueView = text(value, 13, textColor, false);
        valueView.setGravity(Gravity.END);
        row.addView(valueView);
    }

    /** Inline explanation of a limitation: a coloured status tag followed by the reason. */
    void notice(LinearLayout parent, String status, String message) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(rounded(cardColor, 12));
        TextView tag = text(status, 10, Color.WHITE, true);
        tag.setPadding(dp(6), dp(2), dp(6), dp(2));
        tag.setBackground(rounded(statusColor(status), 8));
        row.addView(tag, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView body = text(message, 11, subTextColor, false);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bodyParams.leftMargin = dp(8);
        row.addView(body, bodyParams);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        parent.addView(row, params);
    }

    /** One line of a comparison list: name, status tag, and where it lives or why it cannot. */
    void featureRow(LinearLayout parent, String feature, String status, String detail) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(feature, 13, textColor, true), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView tag = text(status, 10, Color.WHITE, true);
        tag.setPadding(dp(6), dp(2), dp(6), dp(2));
        tag.setBackground(rounded(statusColor(status), 8));
        top.addView(tag);
        row.addView(top);
        if (detail != null && !detail.isEmpty()) {
            TextView body = text(detail, 11, subTextColor, false);
            LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bodyParams.topMargin = dp(2);
            row.addView(body, bodyParams);
        }
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    int statusColor(String status) {
        if (status == null) return subTextColor;
        if (status.startsWith("不可用")) return dangerColor;
        if (status.startsWith("受限")) return Color.rgb(224, 138, 0);
        if (status.startsWith("实验")) return accent;
        if (status.startsWith("已支持")) return Color.rgb(46, 160, 67);
        return subTextColor;
    }

    void toast(String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}
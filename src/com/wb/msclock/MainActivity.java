package com.wb.msclock;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单 Activity 双界面：
 *  - 主界面（MAIN）：显示当前所选源、同步状态、开启/关闭悬浮钟、手动微调、进入「时间源」入口。
 *  - 时间源选择页（SOURCES）：网格卡片，选中某平台后返回主界面。
 * 返回键：在 SOURCES 时回到 MAIN（不会退出应用）；在 MAIN 时为常规退出。
 */
public class MainActivity extends Activity {

    private static final int BG = 0xFFF1F4F9;
    private static final int CARD = 0xFFFFFFFF;
    private static final int BORDER = 0xFFE2E8F2;
    private static final int TEXT = 0xFF1B2330;
    private static final int SUB = 0xFF6B7A90;
    private static final int BLUE = 0xFF2F6FED;
    private static final int SEL_BG = 0xFFEDF3FF;
    private static final int GREEN = 0xFF12A150;

    private static final String[] ALL_KEYS = {
            "auto", "device", "taobao", "tmall", "jd", "meituan", "eleme",
            "vip", "pdd", "douyin", "kuaishou", "xhs", "alipay", "rail", "unionpay",
            "ntp"
    };

    private static final int SCREEN_MAIN = 0;
    private static final int SCREEN_SOURCES = 1;
    private static final int SCREEN_SETTINGS = 2;
    private int currentScreen = SCREEN_MAIN;

    private LinearLayout mainView, sourcesView, settingsView;
    private TextView tvBack, tvTitle;
    private ScrollView scroll;
    private android.widget.SeekBar sizeSeek, alphaSeek;
    private TextView tvSizeVal, tvAlphaVal;

    // 主界面控件
    private TextView tvMainName, tvMainDomain, tvMainSync, tvMainTime, tvMainAdjust;
    private LinearLayout mainToggleCard;
    private android.widget.ProgressBar syncBar;   // 同步进度条
    private TextView tvFail;                      // 失败明细（部分源失败）
    private android.app.AlertDialog failDialog;   // 失败弹窗：后台重校成功时自动关闭
    private boolean lastOkState = true;           // 用于「正常→失败」跃变时弹窗
    private GradientDrawable bgToggleOn, bgToggleOff;
    private boolean overlayGranted;               // 缓存权限状态，避免每 tick 走 binder
    private boolean lastToggleOn, lastToggleGranted, toggleDrawn;
    private String lastMainTime = "", lastAdjust = "", lastSyncText = "";
    private int tickCount;
    private long lastAppearanceAt = 0;

    // 时间源页控件
    private LinearLayout grid;
    private final Map<String, View> cards = new LinkedHashMap<>();
    private final Map<String, TextView> timeLabels = new LinkedHashMap<>();
    private final Map<String, TextView> devLabels = new LinkedHashMap<>();
    private String lastSel = "";

    private final android.os.Handler handler = new android.os.Handler();

    /** 200ms 只刷时间文本；每 5 个 tick 再做一次完整状态刷新 */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            updateClockTexts();
            if (++tickCount % 5 == 0) updateValues();
            handler.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FloatingClockService.loadPrefs(this);
        float d = getResources().getDisplayMetrics().density;
        buildUi(d);
        askNotification();
        refreshOverlayGranted();
        FloatingClockService.requestSync();
        // 快校时先出结果，稍后再补一次全量，保证时间源页 14 源都有数据
        handler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            long now = System.currentTimeMillis();
            if (FloatingClockService.lastFullSyncAt == 0
                    || now - FloatingClockService.lastFullSyncAt > 60_000) {
                FloatingClockService.requestFullSync();
            }
        }, 6000);
        showMain();
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshOverlayGranted();
        if (FloatingClockService.lastResult == null) FloatingClockService.requestSync();
        updateValues();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (failDialog != null) {
            try {
                failDialog.dismiss();
            } catch (Throwable ignored) {
            }
            failDialog = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (currentScreen != SCREEN_MAIN) {
            showMain();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------ 界面
    private void buildUi(float d) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // ---- 顶栏
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding((int) (10 * d), (int) (18 * d), (int) (10 * d), (int) (10 * d));

        tvBack = new TextView(this);
        tvBack.setText("‹");
        tvBack.setTextColor(TEXT);
        tvBack.setTextSize(30);
        tvBack.setPadding((int) (10 * d), 0, (int) (18 * d), 0);
        tvBack.setVisibility(View.GONE);
        tvBack.setOnClickListener(v -> showMain());
        header.addView(tvBack);

        tvTitle = new TextView(this);
        tvTitle.setText("毫秒悬浮时钟");
        tvTitle.setTextColor(TEXT);
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(tvTitle);

        TextView help = new TextView(this);
        help.setText("ⓘ");
        help.setTextColor(SUB);
        help.setTextSize(17);
        help.setPadding(0, 0, (int) (12 * d), 0);
        help.setOnClickListener(v -> toast("悬浮窗长按 1 秒关闭\n拖动可移动位置"));
        header.addView(help);
        root.addView(header);

        // ---- 权限提示（仅文字提示；主按钮本身会引导授权）
        TextView perm = new TextView(this);
        perm.setId(100);
        perm.setText("⚠ 尚未授予悬浮窗权限，点击「开始悬浮时钟」即可授权");
        perm.setTextColor(0xFFB4530A);
        perm.setTextSize(12);
        perm.setPadding((int) (18 * d), (int) (6 * d), (int) (18 * d), 0);
        perm.setVisibility(View.GONE);
        root.addView(perm);

        // ---- 两个界面容器
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        mainView = new LinearLayout(this);
        mainView.setOrientation(LinearLayout.VERTICAL);
        mainView.setPadding((int) (18 * d), (int) (4 * d), (int) (18 * d), (int) (24 * d));
        buildMain(d);

        sourcesView = new LinearLayout(this);
        sourcesView.setOrientation(LinearLayout.VERTICAL);
        sourcesView.setPadding((int) (18 * d), 0, (int) (18 * d), (int) (24 * d));
        sourcesView.setVisibility(View.GONE);
        buildSources(d);

        settingsView = new LinearLayout(this);
        settingsView.setOrientation(LinearLayout.VERTICAL);
        settingsView.setPadding((int) (18 * d), 0, (int) (18 * d), (int) (24 * d));
        settingsView.setVisibility(View.GONE);
        buildSettings(d);

        // ScrollView 只能有一个直接子容器，三个界面都放进去
        LinearLayout scrollContent = new LinearLayout(this);
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        scrollContent.addView(mainView);
        scrollContent.addView(sourcesView);
        scrollContent.addView(settingsView);

        scroll.addView(scrollContent);
        root.addView(scroll);

        setContentView(root);
    }

    // ---------------- 主界面 ----------------
    private void buildMain(float d) {
        // 当前所选源卡片
        LinearLayout cur = new LinearLayout(this);
        cur.setOrientation(LinearLayout.VERTICAL);
        cur.setBackground(bg(CARD, 16 * d, 1));
        cur.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (14 * d));
        TextView curLabel = bodyText("当前时间源", 13);
        curLabel.setTextColor(SUB);
        cur.addView(curLabel);
        LinearLayout curRow = new LinearLayout(this);
        curRow.setOrientation(LinearLayout.HORIZONTAL);
        curRow.setGravity(Gravity.CENTER_VERTICAL);
        curRow.setPadding(0, (int) (6 * d), 0, 0);
        tvMainName = bodyText("综合", 20);
        tvMainName.setTextColor(TEXT);
        tvMainName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        curRow.addView(tvMainName);
        curRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        tvMainSync = bodyText("等待同步", 13);
        tvMainSync.setTextColor(SUB);
        curRow.addView(tvMainSync);
        cur.addView(curRow);
        tvMainDomain = bodyText("多源加权", 12);
        tvMainDomain.setTextColor(SUB);
        tvMainDomain.setPadding(0, (int) (4 * d), 0, 0);
        cur.addView(tvMainDomain);
        mainView.addView(cur);

        // 同步进度条（同步期间可见）
        syncBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sbp.setMargins(0, (int) (6 * d), 0, 0);
        syncBar.setLayoutParams(sbp);
        syncBar.setMax(100);
        syncBar.setVisibility(View.GONE);
        mainView.addView(syncBar);

        // 失败明细（有源失败时可见）
        tvFail = bodyText("", 11);
        tvFail.setTextColor(0xFFD2364B);
        tvFail.setVisibility(View.GONE);
        mainView.addView(tvFail);

        // 大号开启/关闭按钮
        mainToggleCard = new LinearLayout(this);
        mainToggleCard.setOrientation(LinearLayout.VERTICAL);
        mainToggleCard.setGravity(Gravity.CENTER);
        bgToggleOff = bg(BLUE, 16 * d, 0);
        bgToggleOn = bg(0xFFD2364B, 16 * d, 0);
        mainToggleCard.setBackground(bgToggleOff);
        mainToggleCard.setPadding((int) (16 * d), (int) (16 * d), (int) (16 * d), (int) (16 * d));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(0, (int) (12 * d), 0, 0);
        mainToggleCard.setLayoutParams(tlp);
        TextView tl = bodyText("显示时间 ›", 16);
        tl.setTextColor(Color.WHITE);
        mainToggleCard.addView(tl);
        tvMainTime = bodyText("00:00:00.0", 26);
        tvMainTime.setTextColor(Color.WHITE);
        tvMainTime.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        tvMainTime.setPadding(0, (int) (6 * d), 0, 0);
        mainToggleCard.addView(tvMainTime);
        mainToggleCard.setOnClickListener(v -> toggleFloating());
        mainView.addView(mainToggleCard);

        // 手动微调
        mainView.addView(sectionLabel("手动调整时间"));
        LinearLayout adjust = new LinearLayout(this);
        adjust.setOrientation(LinearLayout.HORIZONTAL);
        adjust.setGravity(Gravity.CENTER_VERTICAL);
        adjust.setBackground(bg(CARD, 14 * d, 1));
        adjust.setPadding((int) (16 * d), (int) (14 * d), (int) (12 * d), (int) (14 * d));
        adjust.addView(bodyText("手动微调", 15));
        adjust.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        tvMainAdjust = bodyText("+0ms", 13);
        tvMainAdjust.setTextColor(BLUE);
        adjust.addView(tvMainAdjust);
        adjust.addView(pillButton("−", d, v -> FloatingClockService.setManualMs(
                this, FloatingClockService.manualMs - 1)));
        adjust.addView(pillButton("+", d, v -> FloatingClockService.setManualMs(
                this, FloatingClockService.manualMs + 1)));
        mainView.addView(adjust);

        // 进入时间源
        mainView.addView(sectionLabel("设置"));
        View entry = makeEntry("时间源", "选择悬浮窗显示哪个平台的时间", d);
        entry.setOnClickListener(v -> showSources());
        mainView.addView(entry);
        View entry2 = makeEntry("悬浮窗设置", "调整悬浮窗的大小与透明度", d);
        entry2.setOnClickListener(v -> showSettings());
        mainView.addView(entry2);
    }

    private View makeEntry(String title, String sub, float d) {
        LinearLayout entry = new LinearLayout(this);
        entry.setOrientation(LinearLayout.HORIZONTAL);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setBackground(bg(CARD, 14 * d, 1));
        entry.setPadding((int) (16 * d), (int) (14 * d), (int) (12 * d), (int) (14 * d));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(bodyText(title, 15));
        TextView st = bodyText(sub, 11);
        st.setTextColor(SUB);
        col.addView(st);
        entry.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView more = bodyText("›", 22);
        more.setTextColor(BORDER);
        entry.addView(more);
        return entry;
    }

    // ---------------- 时间源页 ----------------
    private void buildSources(float d) {
        sourcesView.addView(sectionLabel("时间源"));
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        sourcesView.addView(grid);

        for (int i = 0; i < ALL_KEYS.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < 2; j++) {
                int idx = i + j;
                if (idx >= ALL_KEYS.length) {
                    View spacer = new View(this);
                    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 1, 1f);
                    sp.setMargins(0, 0, (int) (12 * d), (int) (12 * d));
                    row.addView(spacer, sp);
                    continue;
                }
                View card = makeCard(ALL_KEYS[idx], d);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                cp.setMargins(0, 0, (int) (12 * d), (int) (12 * d));
                row.addView(card, cp);
            }
            grid.addView(row);
        }
    }

    private View makeCard(String key, float d) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(bg(CARD, 14 * d, 1));
        card.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (12 * d));
        card.setTag(key);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = new TextView(this);
        icon.setText(cardName(key).substring(0, 1));
        icon.setTextColor(Color.WHITE);
        icon.setTextSize(12);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(bg(cardColor(key), 100 * d, 0));
        row.addView(icon, new LinearLayout.LayoutParams((int) (22 * d), (int) (22 * d)));

        TextView name = bodyText(cardName(key), 14);
        name.setTextColor(TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = (int) (8 * d);
        row.addView(name, nlp);

        TextView radio = new TextView(this);
        radio.setText("○");
        radio.setTextSize(17);
        radio.setTextColor(0xFFBFC9D8);
        radio.setTag("radio");
        row.addView(radio);
        card.addView(row);

        TextView t = bodyText("--:--:--.-", 15);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = (int) (10 * d);
        card.addView(t, tlp);

        TextView dev = bodyText("已校准: --", 11);
        dev.setTextColor(0xFFBFC9D8);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = (int) (4 * d);
        card.addView(dev, dlp);

        timeLabels.put(key, t);
        devLabels.put(key, dev);
        cards.put(key, card);
        card.setOnClickListener(v -> select(key));
        return card;
    }

    // ---------------- 悬浮窗设置页 ----------------
    private void buildSettings(float d) {
        FloatingClockService.loadPrefs(this);
        LinearLayout sizeCard = card(d);
        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        sizeRow.addView(bodyText("悬浮窗大小", 15));
        sizeRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        tvSizeVal = bodyText(Math.round(FloatingClockService.scale * 100) + "%", 13);
        tvSizeVal.setTextColor(BLUE);
        sizeRow.addView(tvSizeVal);
        sizeCard.addView(sizeRow);
        sizeSeek = new android.widget.SeekBar(this);
        sizeSeek.setMax(150);                       // 50% ~ 200%
        sizeSeek.setProgress((int) ((FloatingClockService.scale - 0.5f) * 100));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.setMargins(0, (int) (10 * d), 0, 0);
        sizeSeek.setLayoutParams(sp);
        sizeSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int v, boolean fromUser) {
                if (!fromUser) return;
                float s = 0.5f + v / 100f;
                tvSizeVal.setText(Math.round(s * 100) + "%");
                long now = System.currentTimeMillis();
                if (now - lastAppearanceAt < 60) return;   // 合并窗口级更新，避免每个像素都走 IPC
                lastAppearanceAt = now;
                FloatingClockService.previewAppearance(s, FloatingClockService.alpha);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                float s = 0.5f + sb.getProgress() / 100f;
                FloatingClockService.setAppearance(MainActivity.this, s, FloatingClockService.alpha);
            }
        });
        sizeCard.addView(sizeSeek);
        settingsView.addView(sizeCard);

        LinearLayout alphaCard = card(d);
        LinearLayout alphaRow = new LinearLayout(this);
        alphaRow.setOrientation(LinearLayout.HORIZONTAL);
        alphaRow.setGravity(Gravity.CENTER_VERTICAL);
        alphaRow.addView(bodyText("透明度", 15));
        alphaRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        tvAlphaVal = bodyText(Math.round(FloatingClockService.alpha * 100) + "%", 13);
        tvAlphaVal.setTextColor(BLUE);
        alphaRow.addView(tvAlphaVal);
        alphaCard.addView(alphaRow);
        alphaSeek = new android.widget.SeekBar(this);
        alphaSeek.setMax(100);                      // 30% ~ 100%
        alphaSeek.setProgress((int) ((FloatingClockService.alpha - 0.3f) / 0.7f * 100));
        alphaSeek.setLayoutParams(new LinearLayout.LayoutParams(sp));
        alphaSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int v, boolean fromUser) {
                if (!fromUser) return;
                float a = 0.3f + v / 100f * 0.7f;
                tvAlphaVal.setText(Math.round(a * 100) + "%");
                long now = System.currentTimeMillis();
                if (now - lastAppearanceAt < 60) return;
                lastAppearanceAt = now;
                FloatingClockService.previewAppearance(FloatingClockService.scale, a);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                float a = 0.3f + sb.getProgress() / 100f * 0.7f;
                FloatingClockService.setAppearance(MainActivity.this, FloatingClockService.scale, a);
            }
        });
        alphaCard.addView(alphaSeek);
        settingsView.addView(alphaCard);

        // 刷新率：60fps 更顺滑，30fps 更省电
        LinearLayout fpsCard = card(d);
        LinearLayout fpsRow = new LinearLayout(this);
        fpsRow.setOrientation(LinearLayout.HORIZONTAL);
        fpsRow.setGravity(Gravity.CENTER_VERTICAL);
        fpsRow.addView(bodyText("毫秒刷新率", 15));
        fpsRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        final TextView tvFpsVal = bodyText(FloatingClockService.fps + "fps", 13);
        tvFpsVal.setTextColor(BLUE);
        fpsRow.addView(tvFpsVal);
        fpsCard.addView(fpsRow);
        TextView fpsHint = bodyText("60fps 更顺滑 / 30fps 更省电 · 点击切换", 11);
        fpsHint.setTextColor(SUB);
        fpsHint.setPadding(0, (int) (6 * d), 0, 0);
        fpsCard.addView(fpsHint);
        fpsCard.setOnClickListener(v -> {
            int nf = FloatingClockService.fps == 60 ? 30 : 60;
            FloatingClockService.setFps(this, nf);
            tvFpsVal.setText(nf + "fps");
            toast(nf == 60 ? "已切换到 60fps" : "已切换到 30fps（更省电）");
        });
        settingsView.addView(fpsCard);

        // 恢复默认
        TextView reset = pillButton("恢复外观默认（100% / 100%）", d, v -> {
            FloatingClockService.setAppearance(this, 1f, 1f);
            sizeSeek.setProgress(50);
            alphaSeek.setProgress(100);
            tvSizeVal.setText("100%");
            tvAlphaVal.setText("100%");
            toast("已恢复默认");
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.setMargins(0, (int) (16 * d), 0, 0);
        reset.setLayoutParams(rp);
        settingsView.addView(reset);
    }

    /** 通用白卡容器 */
    private LinearLayout card(float d) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(bg(CARD, 14 * d, 1));
        c.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (14 * d));
        return c;
    }

    // ---------------- 界面切换 ----------------
    private void showMain() {
        currentScreen = SCREEN_MAIN;
        mainView.setVisibility(View.VISIBLE);
        sourcesView.setVisibility(View.GONE);
        settingsView.setVisibility(View.GONE);
        tvTitle.setText("毫秒悬浮时钟");
        tvBack.setVisibility(View.GONE);
        if (scroll != null) scroll.post(() -> scroll.scrollTo(0, 0)); // 多屏共用滚动容器，切页回顶
        updateValues();
    }

    private void showSources() {
        currentScreen = SCREEN_SOURCES;
        mainView.setVisibility(View.GONE);
        sourcesView.setVisibility(View.VISIBLE);
        settingsView.setVisibility(View.GONE);
        tvTitle.setText("时间源");
        tvBack.setVisibility(View.VISIBLE);
        if (scroll != null) scroll.post(() -> scroll.scrollTo(0, 0));
        // 打开时间源页说明用户要看全量数据：数据过期或还没跑过就补一次
        long now = System.currentTimeMillis();
        if (FloatingClockService.lastFullSyncAt == 0
                || now - FloatingClockService.lastFullSyncAt > 60_000) {
            FloatingClockService.requestFullSync();
        }
        updateValues();
    }

    private void showSettings() {
        currentScreen = SCREEN_SETTINGS;
        mainView.setVisibility(View.GONE);
        sourcesView.setVisibility(View.GONE);
        settingsView.setVisibility(View.VISIBLE);
        tvTitle.setText("悬浮窗设置");
        tvBack.setVisibility(View.VISIBLE);
        if (scroll != null) scroll.post(() -> scroll.scrollTo(0, 0));
        updateValues();
    }

    private void select(String key) {
        FloatingClockService.setSource(this, key);
        updateSelection();
        // 选中后自动回到主界面，主界面会显示新选择
        handler.postDelayed(this::showMain, 180);
    }

    private void updateSelection() {
        String sel = FloatingClockService.source;
        if (sel.equals(lastSel)) return;
        lastSel = sel;
        float d = getResources().getDisplayMetrics().density;
        for (Map.Entry<String, View> e : cards.entrySet()) {
            boolean on = e.getKey().equals(sel);
            View c = e.getValue();
            c.setBackground(bg(on ? SEL_BG : CARD, 14 * d, on ? BLUE : BORDER));
            View radio = c.findViewWithTag("radio");
            if (radio instanceof TextView) {
                ((TextView) radio).setText(on ? "●" : "○");
                ((TextView) radio).setTextColor(on ? BLUE : 0xFFBFC9D8);
            }
        }
    }

    /** 只刷时间文本：主界面大钟 + 时间源页各卡时间（200ms 一次） */
    private void updateClockTexts() {
        long devNow = System.currentTimeMillis();
        TimeSync.Result r = FloatingClockService.lastResult;
        boolean synced = (r != null && r.ok);

        long bt = devNow + (long) FloatingClockService.appliedOffsetMs;
        String mt = fmt(bt, 1);
        if (!mt.equals(lastMainTime)) {
            lastMainTime = mt;
            tvMainTime.setText(mt);
        }

        if (sourcesView.getVisibility() != View.VISIBLE) return;
        for (String key : ALL_KEYS) {
            TextView tv = timeLabels.get(key);
            if (tv == null) continue;
            if ("device".equals(key)) {
                setTextIfChanged(tv, fmt(devNow, 1));
            } else if ("auto".equals(key)) {
                double off = synced
                        ? TimeSync.offsetOf(r, "auto")
                        : FloatingClockService.appliedOffsetMs;
                setTextIfChanged(tv, fmt(devNow + (long) off, 1));
            } else {
                TimeSync.Probe p = TimeSync.probeOf(r, key);
                if (p != null && p.ok) {
                    setTextIfChanged(tv, fmt(devNow + (long) p.offset, 1));
                }
            }
        }
    }

    private void updateValues() {
        TimeSync.Result r = FloatingClockService.lastResult;
        String sel = FloatingClockService.source;
        boolean synced = (r != null && r.ok);

        updateClockTexts();

        // 时间源页：只在整秒刷新校准量与失败原因
        if (sourcesView.getVisibility() == View.VISIBLE) {
            for (String key : ALL_KEYS) {
                TextView tv = timeLabels.get(key);
                TextView dv = devLabels.get(key);
                if ("auto".equals(key) || "device".equals(key)) {
                    double off = synced ? TimeSync.offsetOf(r, key) : 0;
                    if (dv != null) {
                        if (synced) {
                            setTextIfChanged(dv, String.format("已校准: %+.3fs", off / 1000.0));
                            dv.setTextColor(BLUE);
                        } else {
                            setTextIfChanged(dv, "已校准: --");
                            dv.setTextColor(0xFFBFC9D8);
                        }
                    }
                    continue;
                }
                TimeSync.Probe p = TimeSync.probeOf(r, key);
                if (p != null && p.ok) {
                    if (dv != null) {
                        setTextIfChanged(dv,
                                String.format("已校准: %+.3fs ±%.0fms", p.offset / 1000.0, p.unc));
                        dv.setTextColor(BLUE);
                    }
                } else if (p != null && p.err != null) {
                    if (tv != null) setTextIfChanged(tv, "--:--:--.-");
                    if (dv != null) {
                        setTextIfChanged(dv, "✕ " + p.err);
                        dv.setTextColor(0xFFD2364B);
                    }
                } else {
                    if (tv != null) setTextIfChanged(tv, "--:--:--.-");
                    if (dv != null) {
                        setTextIfChanged(dv, "已校准: --");
                        dv.setTextColor(0xFFBFC9D8);
                    }
                }
            }
            updateSelection();
        }

        // 主界面
        setTextIfChanged(tvMainName, cardName(sel));
        setTextIfChanged(tvMainDomain, cardDomain(sel));
        String adj = (FloatingClockService.manualMs >= 0 ? "+" : "")
                + FloatingClockService.manualMs + "ms";
        if (!adj.equals(lastAdjust)) {
            lastAdjust = adj;
            tvMainAdjust.setText(adj);
        }

        boolean syncing = FloatingClockService.syncing;
        String st;
        int stColor;
        if (syncing) {
            // 同步进度：已完成源数 / 总源数
            int total = TimeSync.progTotal, done = TimeSync.progDone.get();
            syncBar.setVisibility(View.VISIBLE);
            syncBar.setProgress(total > 0 ? done * 100 / total : 0);
            st = "同步中 " + done + "/" + total + "…";
            stColor = SUB;
            tvFail.setVisibility(View.GONE);
        } else {
            syncBar.setVisibility(View.GONE);
            if (synced) {
                st = "✓ 已同步";
                stColor = GREEN;
            } else if (r != null) {
                st = "同步失败";
                stColor = 0xFFD2364B;
            } else if (FloatingClockService.usingCachedOffset) {
                st = "缓存校准 · 校时中";
                stColor = SUB;
            } else {
                st = "等待同步";
                stColor = SUB;
            }
        }
        if (!st.equals(lastSyncText)) {
            lastSyncText = st;
            tvMainSync.setText(st);
        }
        tvMainSync.setTextColor(stColor);

        // 部分源失败 → 红色明细行
        String ft = TimeSync.lastFailText;
        if (!syncing && ft != null && !ft.isEmpty()) {
            tvFail.setVisibility(View.VISIBLE);
            setTextIfChanged(tvFail, "未通过: " + ft);
        } else {
            tvFail.setVisibility(View.GONE);
        }

        // 正常→失败跃变时弹窗告知原因；后台重校成功则自动关闭
        boolean okNow = (r != null && r.ok);
        if (!syncing && !okNow && r != null && lastOkState) {
            lastOkState = false;
            String msg = r.text + (ft == null || ft.isEmpty() ? "" : "\n\n" + ft);
            try {
                if (!isFinishing() && !isDestroyed() && failDialog == null) {
                    android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                            .setTitle("时间同步失败")
                            .setMessage(msg)
                            .setPositiveButton("知道了", null)
                            .create();
                    dlg.setOnDismissListener(d -> {
                        if (failDialog == d) failDialog = null;
                    });
                    dlg.show();
                    failDialog = dlg;
                }
            } catch (Throwable ignored) {
            }
        } else if (okNow) {
            lastOkState = true;
            if (failDialog != null) {
                try {
                    failDialog.dismiss();
                } catch (Throwable ignored) {
                }
                failDialog = null;
            }
        }

        // 开启/关闭按钮文案与配色：状态变化时才替换 Drawable，避免每 tick 重建
        boolean on = FloatingClockService.isAlive();   // 以"服务+窗口真实存活"为准
        boolean granted = overlayGranted;
        if (!toggleDrawn || on != lastToggleOn || granted != lastToggleGranted) {
            toggleDrawn = true;
            lastToggleOn = on;
            lastToggleGranted = granted;
            TextView tl = (TextView) mainToggleCard.getChildAt(0);
            if (on) {
                tl.setText("关闭悬浮时钟");
                mainToggleCard.setBackground(bgToggleOn);
            } else if (granted) {
                tl.setText("开始悬浮时钟");
                mainToggleCard.setBackground(bgToggleOff);
            } else {
                tl.setText("开始悬浮时钟（需授权）");
                mainToggleCard.setBackground(bgToggleOff);
            }
        }

        updatePermissionBanner();
    }

    private static void setTextIfChanged(TextView tv, String s) {
        if (!android.text.TextUtils.equals(tv.getText(), s)) tv.setText(s);
    }

    private void refreshOverlayGranted() {
        overlayGranted = Settings.canDrawOverlays(this);
    }

    private void toggleFloating() {
        android.util.Log.d("MSClock", "toggleFloating alive=" + FloatingClockService.isAlive());
        if (FloatingClockService.isAlive()) {
            stopClock();
        } else {
            startClock();
        }
        handler.postDelayed(this::updateValues, 600);
    }

    /** 开启悬浮钟（可由按钮或外部 intent cmd=start 调用） */
    private void startClock() {
        refreshOverlayGranted();
        if (!overlayGranted) {
            toast("正在打开悬浮窗授权…");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            }
            updatePermissionBanner();
            return;
        }
        FloatingClockService.userClosed = false;   // 用户重新开启 → 允许服务常驻
        // 服务已存活时 onStartCommand 会直接复用，不再先 stop 再 start（避免生命周期竞态）
        Intent i = new Intent(this, FloatingClockService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            FloatingClockService.fgStartPending = true;
            startForegroundService(i);
        } else {
            startService(i);
        }
        toast("悬浮时钟已开启");
    }

    /** 关闭悬浮钟（可由按钮或外部 intent cmd=stop 调用） */
    private void stopClock() {
        stopService(new Intent(this, FloatingClockService.class));
        FloatingClockService.userClosed = true;   // 明确关闭意图：禁止系统粘性复活
        toast("已关闭悬浮时钟");
    }

    private void updatePermissionBanner() {
        View banner = findViewById(100);
        if (banner != null) banner.setVisibility(overlayGranted ? View.GONE : View.VISIBLE);
    }

    private void askNotification() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 工具
    private String cardName(String key) {
        if ("auto".equals(key)) return "综合";
        String n = TimeSync.nameOf(key);
        return n == null ? key : n;
    }

    private String cardDomain(String key) {
        if ("auto".equals(key)) return "多源加权";
        return TimeSync.domainOf(key);
    }

    private int cardColor(String key) {
        if ("auto".equals(key)) return BLUE;
        return TimeSync.colorOf(key);
    }

    /** 无 Calendar / String.format 的时间格式化，固定 8 或 10 字符 */
    private static String fmt(long ms, int decimals) {
        long sec = Math.floorDiv(ms, 1000L);
        int milli = (int) (ms - sec * 1000L);
        int offsetSec = java.util.TimeZone.getDefault().getOffset(ms) / 1000;
        int sod = (int) Math.floorMod(sec + offsetSec, 86400L);
        int hh = sod / 3600, mm = (sod % 3600) / 60, ss = sod % 60;
        char[] b = new char[decimals >= 1 ? 10 : 8];
        b[0] = (char) ('0' + hh / 10);
        b[1] = (char) ('0' + hh % 10);
        b[2] = ':';
        b[3] = (char) ('0' + mm / 10);
        b[4] = (char) ('0' + mm % 10);
        b[5] = ':';
        b[6] = (char) ('0' + ss / 10);
        b[7] = (char) ('0' + ss % 10);
        if (decimals >= 1) {
            b[8] = '.';
            b[9] = (char) ('0' + milli / 100);
        }
        return new String(b);
    }

    private TextView sectionLabel(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(TEXT);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (18 * getResources().getDisplayMetrics().density);
        lp.bottomMargin = (int) (10 * getResources().getDisplayMetrics().density);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView bodyText(String s, int size) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(size);
        tv.setTextColor(TEXT);
        return tv;
    }

    private TextView pillButton(String s, float d, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(18);
        tv.setTextColor(TEXT);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(bg(0xFFF2F5FA, 100 * d, 0));
        tv.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                (int) (34 * d), (int) (34 * d));
        lp.leftMargin = (int) (8 * d);
        tv.setLayoutParams(lp);
        return tv;
    }

    private GradientDrawable bg(int color, float radius, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (strokeColor != 0) {
            g.setStroke(Math.max(1, (int) (getResources().getDisplayMetrics().density)), strokeColor);
        }
        return g;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}

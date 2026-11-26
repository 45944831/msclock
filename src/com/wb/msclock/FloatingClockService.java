package com.wb.msclock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 悬浮窗服务：窗口里只有一个时间。
 * 走时用 elapsedRealtime 锚定服务器时间，系统时间被改动也不影响显示。
 * 帧刷新由 Choreographer 单循环驱动；校时按「快同步 / 全量」分层调度。
 */
public class FloatingClockService extends Service {

    private static final String ACTION_STOP = "com.wb.msclock.action.STOP";
    private static final int NOTI_ID = 1001;
    private static final String CHANNEL = "clock";
    private static final String PREFS = "settings";

    public static boolean running = false;
    public static volatile String lastStatus = "";

    /** 当前选中的时间源：auto / taobao / tmall / jd / meituan / eleme / ntp / device */
    public static volatile String source = "auto";
    /** 手动微调（毫秒） */
    public static volatile int manualMs = 0;
    /** 最近一次校时结果，供界面读取 */
    public static volatile TimeSync.Result lastResult = null;
    public static volatile long lastSyncAt = 0;
    public static volatile boolean syncing = false;
    /** 最近一次全量校时完成时间（墙钟，仅用于展示/兼容） */
    public static volatile long lastFullSyncAt = 0;
    /** 最近一次全量校时完成的单调钟时刻——刷新门槛必须用它，墙钟跳变会卡死刷新 */
    public static volatile long lastFullSyncElapsed = 0;
    /** 结果版本号：校时完成即 +1，界面据此立刻刷新，不必等 1s 轮询 */
    public static volatile long syncVersion = 0;

    /** 悬浮窗外观：大小缩放(0.5~2.0)、不透明度(0.3~1.0)、刷新率(30/60fps) */
    public static volatile float scale = 1.0f;
    public static volatile float alpha = 1.0f;
    public static volatile int fps = 60;
    /** 是否显示毫秒。关闭后进入秒级省电模式：不注册 Choreographer，每秒对齐唤醒一次 */
    public static volatile boolean showMillis = true;
    /** 用户主动关闭后置位，阻止系统粘性复活；由主界面重新开启时清零 */
    public static volatile boolean userClosed = false;
    /** 本次由 startForegroundService 拉起：即使无权限也必须先履行 startForeground 义务再退出 */
    public static volatile boolean fgStartPending = false;

    /** 当前生效偏移（含手动微调），主界面与悬浮窗共用，保证两处显示一致 */
    public static volatile double appliedOffsetMs = 0;
    /** 当前偏移来自本地缓存、尚未被本次进程的校时结果确认 */
    public static volatile boolean usingCachedOffset = false;

    /** 各源最近一次成功偏移，供选中源失败时回退 */
    private static final Map<String, Double> lastGoodOffsets = new ConcurrentHashMap<>();
    private static volatile double cachedOffsetMs = 0;
    private static volatile boolean cachedValid = false;
    private static volatile boolean prefsLoaded = false;
    private static volatile int posX = Integer.MIN_VALUE;
    private static volatile int posY = Integer.MIN_VALUE;

    private static volatile long failureBackoffUntil = 0;
    private static volatile int failStreak = 0;
    private static volatile boolean pendingFull = false;
    public static volatile boolean syncingFull = false;
    private static volatile int syncGen = 0;
    /** 本轮校时开始的单调钟时刻，用于看门狗：卡死时强制解锁，防止时间源页永远等不到结果 */
    private static volatile long syncStartElapsed = 0;
    private static final long SYNC_WATCHDOG_MS = 25_000L;
    private static Future<?> syncFuture;
    private static final ExecutorService syncExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "timesync-scan");
        t.setDaemon(true);
        return t;
    });

    /** 悬浮钟是否真正在运行（服务存活且窗口已挂载）——按钮文案/状态以此为准 */
    public static boolean isAlive() {
        FloatingClockService s = instance;
        return running && s != null && s.view != null;
    }

    private static FloatingClockService instance;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized void loadPrefs(Context c) {
        if (prefsLoaded) return;
        prefsLoaded = true;
        try {
            SharedPreferences sp = prefs(c);
            scale = clamp(sp.getFloat("scale", 1.0f), 0.5f, 2f);
            alpha = clamp(sp.getFloat("alpha", 1.0f), 0.3f, 1f);
            fps = sp.getInt("fps", 30) == 60 ? 60 : 30;   // 默认 30fps：60fps 持续重绘在中低端机上耗电明显
            showMillis = sp.getBoolean("showMillis", true); // 默认显示毫秒（产品定位），可切秒级省电
            String s = sp.getString("source", "auto");
            source = TimeSync.hasKey(s) ? s : "auto";
            manualMs = (int) clamp(sp.getInt("manual", 0), -5000, 5000);
            posX = sp.getInt("posX", Integer.MIN_VALUE);
            posY = sp.getInt("posY", Integer.MIN_VALUE);

            long savedWall = sp.getLong("syncWall", 0);
            long savedElapsed = sp.getLong("syncElapsed", 0);
            String savedSource = sp.getString("syncSource", "");
            if (savedWall > 0 && source.equals(savedSource)) {
                long wall = System.currentTimeMillis();
                long elapsed = SystemClock.elapsedRealtime();
                boolean rebooted = elapsed < savedElapsed;
                boolean clockChanged =
                        Math.abs((wall - elapsed) - (savedWall - savedElapsed)) > 2000;
                // 缓存年龄用单调钟算：墙钟 age 在小步改钟(如调快几分钟)时会失真，
                // 导致过期缓存被当成新鲜；elapsed 不受改钟影响。重启由 rebooted 单独拦截。
                long age = elapsed - savedElapsed;
                if (!rebooted && !clockChanged && age >= 0 && age < 24L * 3600_000L) {
                    cachedOffsetMs = sp.getLong("syncOffset", 0);
                    cachedValid = true;
                }
            }
            appliedOffsetMs = (cachedValid ? cachedOffsetMs : 0) + manualMs;
            // 缓存值来自上次进程，精度不可考：给较大的 unc，让首次实测以较高权重接管
            appliedUnc = cachedValid ? 80 : 0;
            usingCachedOffset = cachedValid;
        } catch (Throwable t) {
            logPersistFailure("loadPrefs", t);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 持久化失败不能静默：至少留下日志，否则设置/缓存丢失无从排查 */
    private static void logPersistFailure(String what, Throwable t) {
        android.util.Log.w("MSClock", "保存失败: " + what, t);
    }

    public static void saveAppearance(Context c) {
        try {
            prefs(c).edit()
                    .putFloat("scale", scale).putFloat("alpha", alpha).apply();
        } catch (Throwable t) {
            logPersistFailure("appearance", t);
        }
    }

    /** 更新外观：立即应用到悬浮窗并持久化 */
    public static void setAppearance(Context c, float s, float a) {
        scale = clamp(s, 0.5f, 2f);
        alpha = clamp(a, 0.3f, 1f);
        saveAppearance(c);
        applyAppearance();
    }

    /** 拖动滑杆时的实时预览：不落盘，松手后再持久化 */
    public static void previewAppearance(float s, float a) {
        scale = clamp(s, 0.5f, 2f);
        alpha = clamp(a, 0.3f, 1f);
        applyAppearance();
    }

    public static void setFps(Context c, int value) {
        fps = value == 30 ? 30 : 60;
        try {
            prefs(c).edit().putInt("fps", fps).apply();
        } catch (Throwable t) {
            logPersistFailure("fps", t);
        }
        FloatingClockService inst = instance;
        if (inst != null) inst.ui.post(inst::applyFps);
    }

    /** 切换毫秒显示：关闭后停掉 Choreographer，改为每秒对齐唤醒一次，省电显著 */
    public static void setShowMillis(Context c, boolean show) {
        showMillis = show;
        try {
            prefs(c).edit().putBoolean("showMillis", show).apply();
        } catch (Throwable t) {
            logPersistFailure("showMillis", t);
        }
        FloatingClockService inst = instance;
        if (inst != null) inst.ui.post(inst::applyRenderMode);
    }

    public static void applyAppearance() {
        final FloatingClockService inst = instance;
        if (inst == null) return;
        inst.ui.post(inst::applyAppearanceNow);
    }

    private void applyFps() {
        frameIntervalNs = 1_000_000_000L / Math.max(1, fps);
    }

    private void applyAppearanceNow() {
        if (view == null || lp == null) return;
        try {
            // 整框等比缩放（背景+字体），窗口尺寸随 requestLayout 自动重排；
            // 上限同时受屏幕宽高约束，避免放大后超出屏幕被裁切
            float s = Math.min(scale, maxScaleForScreen());
            view.setUiScale(s);
            lp.alpha = alpha;
            wm.updateViewLayout(view, lp);
            if (layoutClamp != null) view.removeOnLayoutChangeListener(layoutClamp);
            // 尺寸变化要等 layout 完成后再 clamp，否则拿到的还是旧宽高
            layoutClamp = new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b,
                                           int ol, int ot, int or, int ob) {
                    if (r - l == or - ol && b - t == ob - ot) return;
                    v.removeOnLayoutChangeListener(this);
                    layoutClamp = null;
                    clampAndUpdate();
                }
            };
            view.addOnLayoutChangeListener(layoutClamp);
            view.post(this::clampAndUpdate);
        } catch (Throwable ignored) {
        }
    }

    private void clampAndUpdate() {
        if (view == null || lp == null) return;
        if (clampPosition()) {
            try {
                wm.updateViewLayout(view, lp);
            } catch (Throwable ignored) {
            }
            savePosition();
        }
    }

    /** 切换时间源：立即用已知偏移生效，再在后台校准该源 */
    public static void setSource(Context c, String key) {
        source = TimeSync.hasKey(key) ? key : "auto";
        try {
            prefs(c).edit().putString("source", source).apply();
        } catch (Throwable t) {
            logPersistFailure("source", t);
        }
        FloatingClockService inst = instance;
        if (lastResult != null && lastResult.ok) {
            double off = resolveOffset(lastResult, source);
            saveSyncCache(c, off, source);
            cachedOffsetMs = off;
            cachedValid = true;
            // 服务未运行时也让主界面立刻反映新源的偏移
            appliedOffsetMs = off + manualMs;
            appliedUnc = 0;          // 换源后历史基准已失效，下次实测直接采纳
            usingCachedOffset = false;
            if (inst != null) inst.applyResult(lastResult, true);
        } else {
            // 没有可用结果时，旧缓存属于上一个源，不能沿用到新源
            cachedOffsetMs = 0;
            cachedValid = false;
            appliedOffsetMs = manualMs;
            appliedUnc = 0;
            usingCachedOffset = false;
            if (inst != null) inst.applyRestoredOffset();
        }

        int idx = TimeSync.indexOf(source);
        if (idx <= 0) return;                 // auto / device
        if (syncing) {
            // 正在跑的全量已覆盖所有源，不必再排队一轮
            if (!syncingFull) pendingFull = true;
            return;
        }
        if ("taobao".equals(source) || "tmall".equals(source) || "ntp".equals(source)) {
            startSync(false);
        } else {
            startSingle(source);
        }
    }

    /** 手动微调并立即重新应用偏移 */
    public static void setManualMs(Context c, int ms) {
        manualMs = (int) clamp(ms, -5000, 5000);
        try {
            prefs(c).edit().putInt("manual", manualMs).apply();
        } catch (Throwable t) {
            logPersistFailure("manual", t);
        }
        FloatingClockService inst = instance;
        double base = (lastResult != null && lastResult.ok)
                ? resolveOffset(lastResult, source)
                : (cachedValid ? cachedOffsetMs : 0);
        appliedOffsetMs = base + manualMs;
        appliedUnc = 0;              // 用户手动微调后不再融合，以下一次实测为准
        if (inst != null) {
            if (lastResult != null && lastResult.ok) inst.applyResult(lastResult, true);
            else inst.applyRestoredOffset();
        }
    }

    /** 触发一次快校时 */
    public static void requestSync() {
        startSync(false);
    }

    /** 触发一次全量校时 */
    public static void requestFullSync() {
        startSync(true);
    }

    private static long backoffMs(int streak) {
        if (streak <= 1) return 60_000;
        if (streak == 2) return 120_000;
        if (streak == 3) return 300_000;
        return 600_000;
    }

    /** 单源校准：选中具体平台时使用，避免整轮 177 次请求 */
    private static void startSingle(final String key) {
        final int gen = syncGen;
        syncExec.submit(() -> {
            TimeSync.Probe p = TimeSync.calibrateSingle(key);
            if (p == null || gen != syncGen) return;
            if (!p.ok) return;
            lastGoodOffsets.put(key, p.offset);
            TimeSync.Result r = lastResult;
            if (r != null) {
                TimeSync.mergeProbe(r, p);
                syncVersion++;
            }
            FloatingClockService inst = instance;
            if (inst != null && key.equals(source)) {
                inst.applyResult(lastResult, true);
            }
        });
    }

    /** 后台执行一次校时；full=false 为 3 源快校时 */
    private static synchronized void startSync(final boolean full) {
        long nowElapsed = SystemClock.elapsedRealtime();
        if (syncing) {
            // 看门狗：后台校时超过 25s 还没回来（DNS 卡死 / 线程池被打满）时强制解锁。
            // 否则打开时间源页触发的 requestFullSync() 只会挂成 pendingFull，
            // 而上一轮永远不会结束——表现就是"时间源页再也不刷新"。
            if (nowElapsed - syncStartElapsed < SYNC_WATCHDOG_MS) {
                if (full) pendingFull = true;
                return;
            }
            android.util.Log.w("MSClock", "校时看门狗触发，强制解锁 syncing");
            syncGen++;
            syncing = false;
            syncingFull = false;
            pendingFull = false;
            if (syncFuture != null) syncFuture.cancel(true);
        }
        if (!full && nowElapsed < failureBackoffUntil) return;
        // Doze 空闲态：网络被系统挂起，快校必然全失败，还会污染 failStreak 触发退避。
        // 全量保留（用户主动打开时间源页时值得试一次）。
        if (!full && isDeviceIdle(instance)) {
            android.util.Log.d("MSClock", "Doze 空闲态，跳过快校");
            return;
        }
        // 无网预检：不发任何请求，0ms 给出可操作结论（v1.3 会跑满全部源空转 6 秒）
        if (!isOnline(instance)) {
            android.util.Log.d("MSClock", "无活动网络，跳过校时");
            TimeSync.Result off = TimeSync.offlineResult(full);
            lastResult = off;
            lastStatus = off.text;
            lastSyncAt = System.currentTimeMillis();
            syncVersion++;              // 让界面立刻显示"未连接网络"
            return;
        }
        syncing = true;
        syncingFull = full;
        syncStartElapsed = nowElapsed;
        // 先给进度条一个正确的分母，避免扫描排队时显示 0/0
        TimeSync.progTotal = full ? TimeSync.KEYS.length - 1 : TimeSync.FAST_SOURCES;
        TimeSync.progDone.set(0);
        final int gen = ++syncGen;
        final long tSubmit = nowElapsed;
        syncFuture = syncExec.submit(() -> {
            TimeSync.Result r;
            try {
                r = full ? TimeSync.calibrate() : TimeSync.calibrateFast();
            } catch (Throwable t) {
                r = null;
            }
            android.util.Log.d("MSClock", "startSync" + (full ? "全量" : "快") + " 完成 "
                    + (SystemClock.elapsedRealtime() - tSubmit) + "ms");
            boolean current = (gen == syncGen);
            boolean again = false;
            // 状态清理放在同步块里并保证每轮都执行：任何异常/代次失效都不能把 syncing 卡在 true
            synchronized (FloatingClockService.class) {
                if (current) {
                    if (r != null) {
                        lastResult = r;
                        lastSyncAt = System.currentTimeMillis();
                        lastStatus = r.text;
                        if (full) {
                            lastFullSyncAt = lastSyncAt;
                            lastFullSyncElapsed = SystemClock.elapsedRealtime();
                        }
                        if (r.ok) {
                            failStreak = 0;
                            failureBackoffUntil = 0;
                        } else {
                            failStreak++;
                            failureBackoffUntil =
                                    SystemClock.elapsedRealtime() + backoffMs(failStreak);
                        }
                    }
                    syncing = false;
                    syncingFull = false;
                    syncStartElapsed = 0;
                    again = pendingFull;
                    pendingFull = false;
                }
            }
            if (!current) return;       // 已被看门狗取消/替代
            FloatingClockService inst = instance;
            if (inst != null && r != null) inst.applyResult(r, false);
            syncVersion++;              // 结果已就绪：界面立刻重取，不等 1s 轮询
            if (again) startSync(true);
        });
    }

    private WindowManager wm;
    private ClockView view;
    private WindowManager.LayoutParams lp;
    private View.OnLayoutChangeListener layoutClamp;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Choreographer choreographer;
    private long frameIntervalNs = 1_000_000_000L / 60;
    private long lastFrameNs;

    /**
     * 统一走时基准：悬浮窗与 MainActivity 共用同一实例，保证两处显示完全一致。
     * 纯 JVM 类 TimeBase 承载锚点与频偏，可被单测直接覆盖。
     */
    public static final TimeBase TIME_BASE = new TimeBase();
    /** 当前基准的不确定度（ms）；≤0 表示无历史，融合时直接采纳新值。静态以匹配 appliedOffsetMs */
    private static volatile double appliedUnc;

    /** drift 拟合样本环形缓冲（最多 8 条） */
    private static final int DRIFT_CAP = 8;
    private final double[] driftT = new double[DRIFT_CAP];
    private final double[] driftOff = new double[DRIFT_CAP];
    private final double[] driftUnc = new double[DRIFT_CAP];
    private int driftN;
    private int driftHead;
    private int dragDownX, dragDownY;
    private int startX, startY;
    private long downAt;
    private boolean moved;
    private Runnable longPress;
    private int frameTick;
    private boolean pinching;
    private int pinchStartDist;
    private float pinchStartScale;

    private ConnectivityManager.NetworkCallback netCallback;
    private BroadcastReceiver screenReceiver;
    private boolean screenOff;

    /** 秒级省电驱动：对齐到整秒边界唤醒一次，只画秒，不注册 vsync */
    private final Runnable secondTick = new Runnable() {
        @Override
        public void run() {
            if (!running || view == null || choreographer == null || screenOff || showMillis) return;
            if (!Settings.canDrawOverlays(FloatingClockService.this)) {
                ui.post(FloatingClockService.this::quit);
                return;
            }
            view.invalidate();
            scheduleSecondTick();
        }
    };

    /** 计算距离下一个整秒边界的毫秒数，保证秒跳变及时显示 */
    private void scheduleSecondTick() {
        ui.removeCallbacks(secondTick);
        if (!running || view == null || choreographer == null || screenOff || showMillis) return;
        long wall = currentWallMs();
        long delay = 1000L - Math.floorMod(wall, 1000L);
        if (delay < 30L) delay = 1000L + delay;   // 边界附近留给下一秒，避免同秒重复画
        ui.postDelayed(secondTick, delay);
    }

    /** 应用渲染模式：毫秒→Choreographer；秒级→移除帧回调并对齐整秒唤醒 */
    private void applyRenderMode() {
        if (view == null) return;
        view.setShowMillis(showMillis);
        if (choreographer != null) choreographer.removeFrameCallback(frameCallback);
        ui.removeCallbacks(secondTick);
        if (screenOff) return;
        if (showMillis) {
            lastFrameNs = 0;
            choreographer.postFrameCallback(frameCallback);
        } else {
            view.invalidate();
            scheduleSecondTick();
        }
    }

    /** 唯一帧驱动：按目标刷新率节流，屏幕熄灭时 vsync 停止，循环自然暂停 */
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running || view == null || choreographer == null) return;
            // 切到秒级省电模式后不再续帧，让 vsync 循环自然停止
            if (!showMillis) return;
            // 权限被撤销后窗口会残留在屏幕上但不可见，约每秒检查一次并主动退出
            if ((++frameTick & 63) == 0 && !Settings.canDrawOverlays(FloatingClockService.this)) {
                ui.post(FloatingClockService.this::quit);
                return;
            }
            // vsync 间隔存在微小抖动，留 2ms 容差，避免 60fps 隔帧跳过导致实际只剩 30fps
            long gap = frameIntervalNs - 2_000_000L;
            if (gap < 1) gap = 1;
            if (frameTimeNanos - lastFrameNs >= gap) {
                lastFrameNs = frameTimeNanos;
                view.invalidate();
            }
            choreographer.postFrameCallback(this);
        }
    };

    /** 快同步：每 60s 一次（失败按退避跳过） */
    private final Runnable resync = new Runnable() {
        @Override
        public void run() {
            startSync(false);
            ui.postDelayed(this, 60_000);
        }
    };

    /** 全量同步：每 10 分钟一次 */
    private final Runnable fullResync = new Runnable() {
        @Override
        public void run() {
            startSync(true);
            ui.postDelayed(this, 600_000);
        }
    };

    public FloatingClockService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        loadPrefs(this);
        TIME_BASE.reset();
        applyRestoredOffset();

        if (!Settings.canDrawOverlays(this)) {
            userClosed = true;           // 无权限：不要被系统反复拉起
            Toast.makeText(this, "缺少悬浮窗权限", Toast.LENGTH_SHORT).show();
            // 由 startForegroundService 拉起时必须先满足 5s 内 startForeground 的义务，否则进程被杀；
            // 随后立即撤掉通知，避免无权限场景残留常驻通知
            if (fgStartPending) {
                startForeground(NOTI_ID, buildNotification());
                stopForeground(true);
            }
            fgStartPending = false;
            stopSelf();
            return;
        }
        fgStartPending = false;
        startForeground(NOTI_ID, buildNotification());
        screenOff = !isScreenOn();

        android.util.Log.d("MSClock", "服务 onCreate, 权限=" + Settings.canDrawOverlays(this)
                + " userClosed=" + userClosed);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        view = new ClockView(this);
        view.setSource(this::currentWallMs);

        // minSdk 26(O) 起 TYPE_APPLICATION_OVERLAY 可用；TYPE_PHONE 分支永不可达，已移除
        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                // 服务添加的窗口默认走软件绘制，60fps 重绘会明显吃 CPU；显式开启硬件加速
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = dp(120);
        if (posX != Integer.MIN_VALUE && posY != Integer.MIN_VALUE) {
            lp.x = posX;
            lp.y = posY;
        }

        view.setOnTouchListener(this::onTouch);
        lp.alpha = alpha;
        try {
            wm.addView(view, lp);
        } catch (Throwable t) {
            // 悬浮窗挂载失败：不要留下"运行中"的假状态
            running = false;
            instance = null;
            view = null;
            Toast.makeText(this, "悬浮窗启动失败，请重试", Toast.LENGTH_SHORT).show();
            stopSelf();
            return;
        }
        running = true;
        instance = this;
        view.setUiScale(scale);
        view.setShowMillis(showMillis);
        view.setAccent(usingCachedOffset
                ? 0xFF8A93A6
                : TimeSync.colorOf("auto".equals(source) ? "ntp" : source));

        choreographer = Choreographer.getInstance();
        applyFps();
        applyRenderMode();   // 按 showMillis 决定毫秒帧循环还是秒级对齐唤醒

        view.post(new Runnable() {
            @Override
            public void run() {
                if (view == null) return;
                if (view.getWidth() == 0) {   // 等首次测量完成，否则默认位置会按 0 宽度算到屏幕外
                    view.post(this);
                    return;
                }
                if (posX == Integer.MIN_VALUE) {
                    int sw = getResources().getDisplayMetrics().widthPixels;
                    lp.x = Math.max(0, sw - view.getWidth() - dp(14));
                }
                applyAppearanceNow();         // 内部 updateViewLayout 后会 clamp 并落盘位置
            }
        });

        registerNetworkCallback();
        registerScreenReceiver();
        // 500ms 而不是 150ms：ConnectivityManager 的网络状态在开关切换后有短暂滞后，
        // 太早预检会把"刚断网"误判为有网，白跑一轮注定失败的请求
        ui.postDelayed(() -> startSync(false), 500);
        ui.postDelayed(() -> {
            // 用单调钟判断，避免系统时间被校时/手动修改后 60s 门槛永不满足
            if (lastFullSyncElapsed == 0
                    || SystemClock.elapsedRealtime() - lastFullSyncElapsed > 60_000) startSync(true);
        }, 6000);
        if (!screenOff) {
            ui.postDelayed(resync, 60_000);
            ui.postDelayed(fullResync, 600_000);
        }
    }

    /**
     * 是否真的有可用网络。必须同时满足 INTERNET 与 VALIDATED：
     * 只连上但需认证的 WiFi（酒店/机场门户）有 INTERNET 却没有 VALIDATED，
     * 此时校时同样会全部失败，提前判定比跑满 14 个源更好。
     * 任何异常都按"有网"放行，把判定交给实际请求，避免误伤。
     */
    private static boolean isOnline(Context ctx) {
        if (ctx == null) return true;      // 服务未运行时不阻断，交给实际请求判定
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network n = cm.getActiveNetwork();
            if (n == null) {
                android.util.Log.d("MSClock", "网络预检: 无活动网络");
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            boolean inet = caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            boolean valid = caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            android.util.Log.d("MSClock", "网络预检: inet=" + inet + " valid=" + valid);
            return inet && valid;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 是否处于 Doze 空闲态（网络被系统挂起，校时无意义） */
    private static boolean isDeviceIdle(Context ctx) {
        if (ctx == null) return false;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isDeviceIdleMode();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isScreenOn() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 熄屏暂停渲染与周期校时（时间不可见，60s 唤醒纯属浪费）；亮屏立即补一帧并补校 */
    private void registerScreenReceiver() {
        try {
            screenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String a = intent.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(a)) enterScreenOff();
                    else if (Intent.ACTION_SCREEN_ON.equals(a)) leaveScreenOff();
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            // Android 13+ 要求显式声明导出标志；本接收器只收系统广播，不对外开放
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, f);
            }
        } catch (Throwable ignored) {
        }
    }

    private void enterScreenOff() {
        screenOff = true;
        if (choreographer != null) choreographer.removeFrameCallback(frameCallback);
        ui.removeCallbacks(secondTick);
        ui.removeCallbacks(resync);
        ui.removeCallbacks(fullResync);
    }

    private void leaveScreenOff() {
        screenOff = false;
        if (!running || choreographer == null) return;
        lastFrameNs = 0;                 // 亮屏立即重绘，避免显示熄屏前的旧时间
        if (showMillis) {
            choreographer.postFrameCallback(frameCallback);
        } else {
            view.invalidate();
            scheduleSecondTick();
        }
        ui.removeCallbacks(resync);
        ui.removeCallbacks(fullResync);
        ui.postDelayed(resync, 60_000);
        ui.postDelayed(fullResync, 600_000);
        if (SystemClock.elapsedRealtime() >= failureBackoffUntil) startSync(false);
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    failureBackoffUntil = 0;
                    if (screenOff) return;   // 熄屏不唤醒校时，亮屏时统一补校
                    startSync(false);
                }

                @Override
                public void onLost(Network network) {
                    // 断网瞬间就告诉用户，而不是等下一轮校时跑完才显示"同步失败"
                    try {
                        if (isOnline(FloatingClockService.this)) return;   // 还有别的网络（如 WiFi 切数据），不必改状态
                        lastStatus = "未连接网络";
                        syncVersion++;
                        android.util.Log.d("MSClock", "网络已断开");
                    } catch (Throwable ignored) {
                    }
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Throwable ignored) {
        }
    }

    private boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragDownX = (int) e.getRawX();
                dragDownY = (int) e.getRawY();
                startX = lp.x;
                startY = lp.y;
                downAt = SystemClock.uptimeMillis();
                moved = false;
                pinching = false;
                longPress = this::quit;
                ui.postDelayed(longPress, 900);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                // 第二根手指落下：进入捏合缩放，取消长按关闭与拖动语义
                if (e.getPointerCount() >= 2) {
                    pinching = true;
                    moved = true;
                    cancelLongPress();
                    pinchStartDist = pinchDistance(e);
                    pinchStartScale = Math.min(scale, maxScaleForScreen());
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (pinching && e.getPointerCount() >= 2) {
                    int dist = pinchDistance(e);
                    if (pinchStartDist > dp(20)) {
                        float s = Math.max(0.5f, Math.min(2f,
                                pinchStartScale * dist / pinchStartDist));
                        FloatingClockService.previewAppearance(s, alpha);
                    }
                    return true;
                }
                int dx = (int) e.getRawX() - dragDownX;
                int dy = (int) e.getRawY() - dragDownY;
                if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                    moved = true;
                    cancelLongPress();
                }
                lp.x = startX + dx;
                lp.y = startY + dy;
                clampPosition();
                try {
                    wm.updateViewLayout(view, lp);
                } catch (Throwable ignored) {
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                if (pinching) {
                    // 捏合结束：持久化当前缩放（取屏幕上实际生效的值）
                    float s = view != null ? view.getUiScale() : scale;
                    FloatingClockService.setAppearance(this, s, alpha);
                    pinching = false;
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelLongPress();
                if (pinching) {
                    pinching = false;   // POINTER_UP 已持久化；抬起剩余手指不再触发拖动/点击
                    return true;
                }
                clampPosition();
                snapToEdge();           // 边缘磁吸：松手后靠近左右边缘时吸附
                try {
                    wm.updateViewLayout(view, lp);
                } catch (Throwable ignored) {
                }
                savePosition();
                if (e.getActionMasked() == MotionEvent.ACTION_UP
                        && !moved && SystemClock.uptimeMillis() - downAt < 300) {
                    openApp();
                }
                return true;
        }
        return false;
    }

    private void cancelLongPress() {
        if (longPress != null) {
            ui.removeCallbacks(longPress);
            longPress = null;
        }
    }

    /** 两指间距（像素） */
    private int pinchDistance(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (int) Math.sqrt(dx * dx + dy * dy);
    }

    /** 边缘磁吸：松手后若距左/右边缘在阈值内，则吸附到边缘；阈值随窗口宽度自适应 */
    private void snapToEdge() {
        if (view == null || lp == null) return;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int maxX = Math.max(0, sw - view.getWidth());
        int threshold = Math.max(dp(24), view.getWidth() / 3);
        if (lp.x < threshold) lp.x = 0;
        else if (lp.x > maxX - threshold) lp.x = maxX;
    }

    private void openApp() {
        try {
            Intent i = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private void quit() {
        userClosed = true;          // 明确关闭意图：禁止系统粘性重启
        Toast.makeText(this, "已关闭悬浮时钟", Toast.LENGTH_SHORT).show();
        stopSelf();
    }

    /** 屏幕内的活动区域（考虑状态栏内嵌与窗口自身尺寸），返回是否发生了位移 */
    private boolean clampPosition() {
        if (view == null || lp == null) return false;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int w = view.getWidth(), h = view.getHeight();
        int top = statusBarInset();
        int maxX = Math.max(0, sw - w);
        int maxY = Math.max(0, sh - h - top);
        int nx = Math.max(0, Math.min(lp.x, maxX));
        int ny = Math.max(0, Math.min(lp.y, maxY));
        if (nx == lp.x && ny == lp.y) return false;
        lp.x = nx;
        lp.y = ny;
        return true;
    }

    /** 按屏幕宽高限制最大缩放，避免 200% 时窗口超出 1080px 被裁切 */
    private float maxScaleForScreen() {
        if (view == null) return 2f;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        // 用基准内容尺寸计算，不能用 getWidth()/cur：连续拖动滑杆时布局还没跟上，
        // 旧宽度配合新缩放会把基准算小，导致 200% 突破屏幕上限
        float baseW = view.getBaseWidth();
        float baseH = view.getBaseHeight();
        if (baseW <= 0 || baseH <= 0) return 2f;
        float byW = (sw - dp(8)) / baseW;
        float byH = (sh - statusBarInset() - dp(8)) / baseH;
        return Math.max(0.5f, Math.min(2f, Math.min(byW, byH)));
    }

    private int statusBarInset() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private void savePosition() {
        if (lp == null) return;
        posX = lp.x;
        posY = lp.y;
        try {
            prefs(this).edit().putInt("posX", posX).putInt("posY", posY).apply();
        } catch (Throwable t) {
            logPersistFailure("position", t);
        }
    }

    private static void saveSyncCache(Context c, double off, String src) {
        try {
            prefs(c).edit()
                    .putLong("syncOffset", Math.round(off))
                    .putString("syncSource", src)
                    .putLong("syncWall", System.currentTimeMillis())
                    .putLong("syncElapsed", SystemClock.elapsedRealtime())
                    .apply();
        } catch (Throwable t) {
            logPersistFailure("syncCache", t);
        }
    }

    /** 启动时用上次偏移顶着走，避免网络校时完成前显示未校正的设备时间 */
    private void applyRestoredOffset() {
        double total = (cachedValid ? cachedOffsetMs : 0) + manualMs;
        TIME_BASE.anchor(System.currentTimeMillis() + total,
                SystemClock.elapsedRealtime(), TIME_BASE.getDriftPpm());
        appliedOffsetMs = total;
        usingCachedOffset = cachedValid;
        if (view != null) {
            view.setAccent(usingCachedOffset
                    ? 0xFF8A93A6
                    : TimeSync.colorOf("auto".equals(source) ? "ntp" : source));
            view.invalidate();
        }
    }

    /** 选中源失败时优先用该源上次成功偏移，其次才回退综合值 */
    private static double resolveOffset(TimeSync.Result r, String key) {
        if (key == null || "auto".equals(key) || "device".equals(key)) {
            return TimeSync.offsetOf(r, key);
        }
        TimeSync.Probe p = TimeSync.probeOf(r, key);
        if (p != null && p.ok) {
            lastGoodOffsets.put(key, p.offset);
            return p.offset;
        }
        Double cached = lastGoodOffsets.get(key);
        return cached != null ? cached : TimeSync.offsetOf(r, key);
    }

    /**
     * 当前墙钟毫秒。走时 = 锚点 + 单调钟增量 × (1 + 频偏)。
     * 频偏项让"熄屏数小时/弱网长时间无法校时"后的累积误差降低一个量级。
     */
    private long currentWallMs() {
        return (long) TIME_BASE.at(SystemClock.elapsedRealtime(), System.currentTimeMillis());
    }

    /** 供 MainActivity 取同一走时基准；服务未锚定时回退到 墙钟+偏移，保证界面始终有值 */
    public static long wallNow() {
        if (TIME_BASE.isAnchored()) {
            return (long) TIME_BASE.at(SystemClock.elapsedRealtime(), System.currentTimeMillis());
        }
        return System.currentTimeMillis() + (long) appliedOffsetMs;
    }

    /** 本次测量结果的不确定度：综合值取参与源的中位 unc 折半，单源取其自身 unc */
    private double resolveUnc(TimeSync.Result r, String key) {
        if ("device".equals(key)) return 0;
        if ("auto".equals(key)) {
            double[] us = new double[r.list.size()];
            int n = 0;
            for (TimeSync.Probe p : r.list) if (p.ok) us[n++] = p.unc;
            if (n == 0) return 60;
            // 多源合成后不确定度按 1/√n 收缩，这里取中位数的 0.6 倍作为保守估计
            return Math.max(5.0, TimeMath.median(us, n) * 0.6);
        }
        TimeSync.Probe p = TimeSync.probeOf(r, key);
        return (p != null && p.ok) ? Math.max(p.unc, 5.0) : 60;
    }

    /** 记录一次测量样本用于频偏拟合；不可信样本（unc 过大）不入库 */
    private void recordDriftSample(double offset, double unc) {
        if (unc <= 0 || unc > 500) return;
        double t = SystemClock.elapsedRealtime() / 1000.0;
        driftT[driftHead] = t;
        driftOff[driftHead] = offset;
        driftUnc[driftHead] = unc;
        driftHead = (driftHead + 1) % DRIFT_CAP;
        if (driftN < DRIFT_CAP) driftN++;
    }

    /**
     * 重新拟合频偏。保护条件缺一不可，否则 drift 归零：
     * 样本 ≥3、时间跨度 ≥120s、|ppm| ≤100、残差 RMS ≤15ms。
     * 这些门槛的意义是——宁可不补偿，也不能用一个被噪声拟合出来的假斜率把走时带偏。
     */
    private void updateDrift() {
        if (driftN < 3) return;
        double[] ts = new double[driftN];
        double[] os = new double[driftN];
        double[] us = new double[driftN];
        int start = (driftHead - driftN + DRIFT_CAP) % DRIFT_CAP;
        for (int i = 0; i < driftN; i++) {
            int idx = (start + i) % DRIFT_CAP;
            ts[i] = driftT[idx];
            os[i] = driftOff[idx];
            us[i] = driftUnc[idx];
        }
        if (ts[driftN - 1] - ts[0] < 120) return;
        double[] fit = TimeMath.fitDrift(ts, os, us, driftN);
        if (fit == null) return;
        if (Math.abs(fit[0]) > 100 || fit[1] > 15) {
            TIME_BASE.setDriftPpm(0);      // 噪声主导或测量跳变，放弃补偿
            android.util.Log.d("MSClock", String.format(
                    "drift 弃用: %.1fppm 残差%.1fms (样本%d)", fit[0], fit[1], driftN));
            return;
        }
        double old = TIME_BASE.getDriftPpm();
        double next = old * 0.7 + fit[0] * 0.3;   // 平滑更新，避免抖动
        TIME_BASE.setDriftPpm(next);
        android.util.Log.d("MSClock", String.format(
                "drift 更新: %.1fppm 残差%.1fms (样本%d)", next, fit[1], driftN));
        // 频偏改变会让走时斜率突变：dt 越大跳得越狠（10 分钟 × 5ppm ≈ 3ms）。
        // 变化超过 2ppm 时用旧斜率算出当前时刻并重锚，保证显示连续。
        if (Math.abs(next - old) > 2.0) {
            final double od = old;
            ui.post(() -> {
                long e = SystemClock.elapsedRealtime();
                double now = TIME_BASE.atWithDrift(e, od);
                if (!Double.isNaN(now)) {
                    TIME_BASE.anchor(now, e, TIME_BASE.getDriftPpm());
                }
            });
        }
    }

    /** 把校时结果落到悬浮窗的走时锚点上 */
    private void applyResult(final TimeSync.Result r, final boolean force) {
        if (r == null || !r.ok) return;
        final double off = resolveOffset(r, source);
        final double unc = resolveUnc(r, source);
        // 置信度加权融合：抑制测量噪声导致的毫秒位跳变（RFC 5905 clock discipline 简化版）
        final double total = TimeMath.fuseOffset(appliedOffsetMs, appliedUnc, off + manualMs, unc);
        if (Math.abs(total - (off + manualMs)) > 0.5) {
            android.util.Log.d("MSClock", String.format(
                    "融合: 实测%.1f → 基准%.1f (unc %.0f/%.0f)", off + manualMs, total, unc, appliedUnc));
        }
        // 频偏拟合用融合前的原始测量值，避免融合把真实斜率抹平
        recordDriftSample(off, unc);
        updateDrift();
        final int accent = TimeSync.colorOf("auto".equals(source) ? "ntp" : source);
        boolean exact = "auto".equals(source) || "device".equals(source);
        if (!exact) {
            TimeSync.Probe p = TimeSync.probeOf(r, source);
            exact = p != null && p.ok;
        }
        if (exact) {
            saveSyncCache(this, off, source);
            cachedOffsetMs = off;
            cachedValid = true;
        }
        ui.post(() -> {
            // 缓存 → 实测、切换源、调微调时强制重锚。
            // 常规刷新：融合后差异已很小，超过 3ms 才重锚，兼顾"不跳"与"跟得上"
            if (!force && !usingCachedOffset && Math.abs(total - appliedOffsetMs) < 3) {
                return;
            }
            TIME_BASE.anchor(System.currentTimeMillis() + total,
                    SystemClock.elapsedRealtime(), TIME_BASE.getDriftPpm());
            appliedOffsetMs = total;
            appliedUnc = unc;
            usingCachedOffset = false;
            if (view != null) {
                view.setAccent(accent);
                view.invalidate();
            }
            // 偏移重锚后对齐节奏会变，秒级模式下重排整秒唤醒
            if (!showMillis && !screenOff) scheduleSecondTick();
        });
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "悬浮时钟", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, FloatingClockService.class).setAction(ACTION_STOP),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("毫秒悬浮时钟运行中")
                .setContentText("单击悬浮窗打开应用，长按关闭")
                .setSmallIcon(R.drawable.ic_notif)
                .setContentIntent(pi)
                .addAction(R.drawable.ic_notif, "关闭", stop)
                .setShowWhen(false)
                .setOngoing(true)
                .build();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            userClosed = true;
            stopSelf();
            return START_NOT_STICKY;
        }
        // 用户主动关闭后（长按悬浮窗）不再被系统粘性复活，否则悬浮窗会自己又出现
        if (userClosed) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        android.util.Log.d("MSClock", "服务 onDestroy");
        running = false;
        instance = null;
        syncGen++;
        syncing = false;
        syncingFull = false;
        pendingFull = false;
        syncStartElapsed = 0;
        if (syncFuture != null) syncFuture.cancel(true);
        if (choreographer != null) choreographer.removeFrameCallback(frameCallback);
        if (netCallback != null) {
            try {
                ConnectivityManager cm =
                        (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                cm.unregisterNetworkCallback(netCallback);
            } catch (Throwable ignored) {
            }
            netCallback = null;
        }
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Throwable ignored) {
            }
            screenReceiver = null;
        }
        ui.removeCallbacksAndMessages(null);
        if (view != null) {
            try {
                if (wm != null) wm.removeView(view);
            } catch (Throwable ignored) {
            }
            view = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

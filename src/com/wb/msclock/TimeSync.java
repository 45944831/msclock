package com.wb.msclock;

import android.os.Build;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多源校时。
 *
 * 淘宝/天猫：mtop.common.getTimestamp 直接返回毫秒时间戳，
 *            offset = serverMs - 本地中点，取 RTT 最小的一次。
 * 京东/美团/饿了么：HTTP Date 头只有秒级精度，单次采样把 offset 约束在
 *            宽度 1000ms+rtt 的区间内；按 1/15 秒相位栅格错开采样 15 次求交集，
 *            区间收敛到几十毫秒后取中点（收敛即提前结束）。
 * 阿里云 NTP：四时间戳公式 + 服务器链（阿里云→腾讯→cn.ntp），作独立基准，
 *            用于剔除离群源与加权。
 *
 * 测时全部用单调钟 SystemClock.elapsedRealtime()，采样完成后统一取一次
 * (墙钟 − 单调钟) gap 换算回 epoch 域：系统 NTP 校时或用户改时间造成的
 * 中途跳钟会被换算抵消，不再污染该轮结果——这正是「系统时间不可信」定位下
 * 测量层必须具备的性质。
 */
public class TimeSync {

    public static final String UA_PC =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    public static final String UA_MB =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1";

    /** 单个源的测量结果 */
    public static class Probe {
        public String name;
        public String color;
        public double offset;   // server - local，单位 ms
        public double unc;      // 半宽，单位 ms
        public double rtt;
        public boolean ok;
        public String err;      // 失败原因（ok=false 时有值）
        /** 可选源：失败时不计入总数、不参与失败判定（GNSS 室内无信号属正常） */
        public boolean optional;
    }

    /** 同步进度（供界面显示）：已完成源数 / 总源数 */
    public static final java.util.concurrent.atomic.AtomicInteger progDone =
            new java.util.concurrent.atomic.AtomicInteger();
    public static volatile int progTotal = 0;
    /** 最近一次校时的失败明细，如 "哔哩哔哩(连接超时) 云闪付(网络错误)"；全部成功时为空串 */
    public static volatile String lastFailText = "";

    /** 失败类型：供 UI 给出可操作的提示，而不是一句笼统的"请检查网络" */
    public static final int FAIL_NONE = 0;
    public static final int FAIL_NO_NETWORK = 1;   // 无活动网络 / 未通过 VALIDATED
    public static final int FAIL_TIMEOUT = 2;      // 有网但全部超时
    public static final int FAIL_DNS = 3;          // 多数域名解析失败
    public static final int FAIL_SSL = 4;          // 多数 SSL 握手失败
    public static final int FAIL_OTHER = 5;

    public static class Result {
        public List<Probe> list = new ArrayList<>();
        public double offset;   // 综合 offset，ms
        public boolean ok;
        /** 是否有非可选（网络）源成功。可选系统源(sysnet/gnss)用本地缓存时间，
         *  断网时也可能 ok=true，但那不代表"网络校时成功"，UI 展示与失败弹窗须用此字段。 */
        public boolean networkOk;
        public boolean full;    // true=全量，false=快校时 3 源
        public int total = 3;   // 本轮参与统计的源数（单源合并后会增长）
        public int okCount;
        public int failKind = FAIL_NONE;
        public String text = "未校时";
    }

    /** 扫描代次：旧扫描的迟到结果不再更新进度，避免跨轮次串扰 */
    private static final java.util.concurrent.atomic.AtomicInteger scanSeq =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 校时探测共用线程池：有界并发，核心线程空闲回收，避免每次同步新建 14 个线程 */
    private static final ThreadPoolExecutor POOL;
    static {
        // 8 线程：14 个源两批跑完；4 线程时单个慢源会把整轮拖到超时
        POOL = new ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "timesync");
                    t.setDaemon(true);
                    return t;
                });
        POOL.allowCoreThreadTimeOut(true);
    }

    // ------------------------------------------------------------ 各源定义
    // 单一数据源在 Sources.ALL；六数组由它派生，新增源只需改 Sources 一处。
    public static final String[] KEYS = Sources.KEYS;
    public static final String[] NAMES = Sources.NAMES;
    public static final String[] COLORS = Sources.COLORS;
    private static final String[] KINDS = Sources.KINDS;
    private static final String[] URLS = Sources.URLS;
    public static final String[] DOMAINS = Sources.DOMAINS;

    /** 可选源：失败不计入总数、不触发失败判定（室内无 GNSS 信号属正常） */
    private static boolean isOptionalKind(int i) {
        return Sources.ALL[i].optional();
    }

    /** 源下标；-1 表示未知 key */
    public static int indexOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return -1;
    }

    /** key 是否合法（含 auto） */
    public static boolean hasKey(String key) {
        return "auto".equals(key) || indexOf(key) >= 0;
    }

    public static String nameOf(String key) {
        int i = indexOf(key);
        return i < 0 ? null : NAMES[i];
    }

    public static String domainOf(String key) {
        int i = indexOf(key);
        return i < 0 ? "" : DOMAINS[i];
    }

    public static int colorOf(String key) {
        int i = indexOf(key);
        return i < 0 ? 0xFF3DDC84 : parseColor(COLORS[i]);
    }

    /** 取指定源的 offset（ms）。auto→综合值；device→本机时钟(0)；指定平台→该平台偏移；未知/失败→综合值 */
    public static synchronized double offsetOf(Result r, String key) {
        if (r == null || !r.ok) return 0;
        if (key == null || "auto".equals(key)) return r.offset;
        if ("device".equals(key)) return 0;          // 设备时间：不做任何校正
        String name = nameOf(key);
        if (name == null) return r.offset;
        for (Probe p : r.list) {
            if (p.ok && name.equals(p.name)) return p.offset;
        }
        return r.offset;
    }

    /** 按源 key 取探测结果（含失败原因 err）；auto/device/未知源返回 null */
    public static synchronized Probe probeOf(Result r, String key) {
        if (r == null) return null;
        String name = nameOf(key);
        if (name == null) return null;
        for (Probe p : r.list) {
            if (p != null && name.equals(p.name)) return p;
        }
        return null;
    }

    private static int parseColor(String hex) {
        return (int) Long.parseLong(hex.substring(1), 16) | 0xFF000000;
    }

    private static final Pattern T_PATTERN = Pattern.compile("\"t\"\\s*:\\s*\"?(\\d{10,16})\"?");

    // ------------------------------------------------------------ 主入口
    /** 快校时使用的源数 */
    public static final int FAST_SOURCES = 3;

    /**
     * 无网络时直接构造失败结果：不发任何请求、不占用线程池、0ms 返回。
     * 调用方（Service）已用 ConnectivityManager 做过 VALIDATED 预检。
     */
    public static Result offlineResult(boolean full) {
        Result r = new Result();
        r.full = full;
        r.ok = false;
        r.failKind = FAIL_NO_NETWORK;
        r.text = "未连接网络";
        r.total = full ? KEYS.length - 1 : FAST_SOURCES;
        // 不写 lastFailText：否则主界面会显示"未通过: 未连接网络"，与状态行重复且措辞别扭
        lastFailText = "";
        return r;
    }

    /** 全量校时：全部网络源 + 可选系统源 */
    public static Result calibrate() {
        return calibrate(true);
    }

    /** 快校时：NTP + 淘宝 + 天猫，用于启动和 60s 周期刷新 */
    public static Result calibrateFast() {
        return calibrate(false);
    }

    private static Result calibrate(boolean full) {
        // 调度/计时同样走单调钟：校时中途若系统 NTP 跳钟或用户改时间，
        // 墙钟 deadline 会被拉长或缩短，正常源会被误判「响应超时」。
        long tStart = SystemClock.elapsedRealtime();
        Result r = new Result();
        r.full = full;
        final int[] idx;
        r.total = full ? KEYS.length - 1 : FAST_SOURCES;
        if (full) {
            idx = new int[KEYS.length - 1];
            for (int i = 1; i < KEYS.length; i++) idx[i - 1] = i;
        } else {
            // 快校不再固定用淘宝/天猫/NTP：按历史健康度动态选，避免某个接口变更后
            // 快校持续失败而其他 12 个源其实是好的
            idx = pickFastSources(FAST_SOURCES);
        }
        progTotal = idx.length;
        progDone.set(0);
        final int sid = scanSeq.incrementAndGet();
        final Probe[] slots = new Probe[idx.length];
        List<Future<Probe>> futures = new ArrayList<>(idx.length);
        for (int k = 0; k < idx.length; k++) {
            final int i = idx[k];
            final int slot = k;
            futures.add(POOL.submit(() -> {
                long t0 = SystemClock.elapsedRealtime();
                Probe p = probeOne(i, !full);
                android.util.Log.d("MSClock", "源耗时 " + p.name + " " + (SystemClock.elapsedRealtime() - t0) + "ms "
                        + (p.ok ? "ok" : (p.err == null ? "fail" : p.err)));
                if (sid == scanSeq.get()) {
                    slots[slot] = p;
                    progDone.incrementAndGet();
                }
                return p;
            }));
        }
        long deadline = SystemClock.elapsedRealtime() + (full ? 6000 : 2200);
        for (int k = 0; k < futures.size(); k++) {
            Future<Probe> f = futures.get(k);
            try {
                long wait = Math.max(1, deadline - SystemClock.elapsedRealtime());
                slots[k] = f.get(wait, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                f.cancel(true);
                if (slots[k] == null) slots[k] = failed(idx[k], "响应超时");
            }
        }
        for (int k = 0; k < slots.length; k++) {
            if (slots[k] == null) slots[k] = failed(idx[k], "响应超时");
            r.list.add(slots[k]);
        }
        finalizeResult(r, full);
        android.util.Log.d("MSClock", "校时总耗时 " + (SystemClock.elapsedRealtime() - tStart) + "ms ("
                + (full ? "全量" : "快") + ")");
        return r;
    }

    // ------------------------------------------------------------ 源健康度
    /**
     * 每个源的健康画像：指数移动平均的成功率与不确定度，外加失败退避。
     * 进程内记忆即可——重启后退回默认先验，最坏情况等价于 v1.3 的固定选源。
     */
    private static final class Health {
        double successRate = 0.5;   // EMA，0..1
        double uncEma = 60.0;       // EMA of unc (ms)
        int failStreak;
        long backoffUntil;          // elapsedRealtime；0 = 无退避
    }

    private static final Map<String, Health> HEALTH =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 默认快校源给一点先验，保证首次运行仍按淘宝/天猫/NTP 走 */
    private static Health healthOf(String name) {
        return HEALTH.computeIfAbsent(name, k -> {
            Health h = new Health();
            if ("淘宝".equals(k) || "天猫".equals(k) || "阿里云NTP".equals(k)) {
                h.successRate = 0.7;
            }
            return h;
        });
    }

    /** 记录一次探测结果，更新成功率/精度画像与退避状态 */
    private static void recordHealth(Probe p) {
        if (p == null || p.name == null) return;
        Health h = healthOf(p.name);
        synchronized (h) {
            h.successRate = h.successRate * 0.8 + (p.ok ? 1.0 : 0.0) * 0.2;
            if (p.ok) {
                h.uncEma = h.uncEma * 0.8 + p.unc * 0.2;
                h.failStreak = 0;
                h.backoffUntil = 0;
            } else if (!p.optional) {
                h.failStreak++;
                if (h.failStreak >= 3) {
                    // 30s → 60s → 120s → 240s → 480s，上限 10min
                    long ms = Math.min(600_000L, 30_000L << Math.min(h.failStreak - 3, 4));
                    h.backoffUntil = SystemClock.elapsedRealtime() + ms;
                }
            }
        }
    }

    /** 健康度评分：成功率越高、历史精度越好，分数越高 */
    private static double healthScore(int i) {
        Health h = healthOf(NAMES[i]);
        synchronized (h) {
            return h.successRate * (100.0 / (h.uncEma + 20.0));
        }
    }

    /**
     * 快校选源：排除可选源（GNSS/系统时间）与退避中的源，按健康度取前 n。
     * 候选不足时用默认三源补齐，保证任何情况下都有 n 个源。
     */
    private static int[] pickFastSources(int n) {
        long now = SystemClock.elapsedRealtime();
        List<Integer> cand = new ArrayList<>();
        for (int i = 1; i < KEYS.length; i++) {
            if ("none".equals(KINDS[i])) continue;
            if (isOptionalKind(i)) continue;              // 可选源不进快校
            Health h = HEALTH.get(NAMES[i]);
            if (h != null && h.backoffUntil > now) continue;   // 退避中，跳过
            cand.add(i);
        }
        // 分数四舍五入到两位再比，避免浮点误差打乱同分时的稳定顺序
        cand.sort((a, b) -> {
            int c = Double.compare(
                    Math.round(healthScore(b) * 100), Math.round(healthScore(a) * 100));
            return c != 0 ? c : Integer.compare(a, b);
        });
        int[] out = new int[n];
        int cnt = 0;
        for (int i = 0; i < cand.size() && cnt < n; i++) out[cnt++] = cand.get(i);
        // 补齐：退避导致候选不足时，仍用默认三源（宁可试一次也不要空转）
        String[] fallback = {"taobao", "tmall", "ntp"};
        for (int f = 0; f < fallback.length && cnt < n; f++) {
            int i = indexOf(fallback[f]);
            if (i < 0) continue;
            boolean dup = false;
            for (int k = 0; k < cnt; k++) if (out[k] == i) { dup = true; break; }
            if (!dup) out[cnt++] = i;
        }
        if (cnt < n) {
            int[] shrink = new int[cnt];
            System.arraycopy(out, 0, shrink, 0, cnt);
            return shrink;
        }
        return out;
    }

    /** 单源探测：选中某个时间源时使用，避免整轮全量扫描 */
    public static Probe calibrateSingle(String key) {
        final int i = indexOf(key);
        if (i < 0) return null;
        long t0 = SystemClock.elapsedRealtime();
        Future<Probe> f = POOL.submit(() -> probeOne(i, false));
        Probe p;
        try {
            p = f.get(12, TimeUnit.SECONDS);
        } catch (Exception e) {
            f.cancel(true);
            p = failed(i, "响应超时");
        }
        logSingle(p);
        android.util.Log.d("MSClock", "单源总耗时 " + (SystemClock.elapsedRealtime() - t0) + "ms");
        return p;
    }

    private static void logSingle(Probe p) {
        if (p == null) return;
        String s = p.ok ? String.format("%+.0f±%.0fms", p.offset, p.unc)
                : (p.err == null ? "失败" : p.err);
        android.util.Log.d("MSClock", "校时单源: " + p.name + " " + s);
    }

    /** 把单源结果合并进最近一次结果，并重算综合值与文案 */
    public static synchronized void mergeProbe(Result r, Probe p) {
        if (r == null || p == null) return;
        for (int i = 0; i < r.list.size(); i++) {
            Probe old = r.list.get(i);
            if (old != null && p.name != null && p.name.equals(old.name)) {
                r.list.set(i, p);
                finalizeResult(r, r.full);
                return;
            }
        }
        r.list.add(p);
        r.total = Math.max(r.total, r.list.size());
        finalizeResult(r, r.full);
    }

    private static void finalizeResult(Result r, boolean full) {
        // 缓存 Date 头已在 probeDate 内用「跨秒采样的 Date 是否变化」前置检测；
        // 这里不再以 NTP 为唯一基准做 ±2s 硬剔除——那会让一个离群 NTP 误杀全部好源。
        // 仅对与全局中位数偏差超过 2s 的源放大不确定度（降权），保留兜底但不破坏合成。
        int okCnt = 0;
        double[] offs = new double[r.list.size()];
        for (Probe p : r.list) if (p.ok) offs[okCnt++] = p.offset;
        if (okCnt >= 3) {
            double[] sorted = java.util.Arrays.copyOf(offs, okCnt);
            java.util.Arrays.sort(sorted);
            double median = sorted[okCnt / 2];
            for (Probe p : r.list) {
                if (!p.ok) continue;
                double dev = Math.abs(p.offset - median);
                if (p.optional && dev > 200) {
                    // 系统级时间源可能与真实时间脱节（Android 每约 18h 才同步一次网络时间，
                    // 实测见过 -434ms），偏差过大时直接剔除，绝不能让它污染合成结果。
                    p.ok = false;
                    p.err = "与综合值偏差过大";
                    android.util.Log.d("MSClock", "可选源剔除(偏差" + (long) dev + "ms): " + p.name);
                    continue;
                }
                if (dev > 2000) {
                    p.unc = Math.max(p.unc * 8.0, 1000.0);
                    android.util.Log.d("MSClock", "离群源降权(中位数基准): " + p.name);
                }
            }
        }
        int okCount = 0, optFail = 0;
        int networkOkCount = 0;
        int nTimeout = 0, nDns = 0, nSsl = 0;
        StringBuilder dbg = new StringBuilder();
        StringBuilder fail = new StringBuilder();
        for (Probe p : r.list) {
            if (p.ok) {
                okCount++;
                if (!p.optional) networkOkCount++;
                dbg.append(p.name).append(String.format(" %+.0f±%.0fms; ", p.offset, p.unc));
            } else {
                String e = p.err == null ? "失败" : p.err;
                dbg.append(p.name).append("(").append(e).append("); ");
                if (p.optional) {
                    // GNSS 室内无信号、系统时间未同步都属正常：不计入总数，也不进失败明细，
                    // 否则用户会看到"未通过: GNSS授时"这种无从处理的提示
                    optFail++;
                } else {
                    fail.append(p.name).append("(").append(e).append(") ");
                    if (e.contains("超时")) {
                        nTimeout++;
                    } else if (e.contains("解析")) {
                        nDns++;
                    } else if (e.contains("SSL")) {
                        nSsl++;
                    }
                }
            }
        }
        if (optFail > 0) r.total = Math.max(1, r.total - optFail);
        r.okCount = okCount;
        r.networkOk = networkOkCount > 0;   // 等价于 TimeMath.hasNetworkSuccess(ok[], optional[])
        r.ok = okCount > 0;
        // 全量总是刷新明细；快校成功时也刷新，避免上一轮全量失败的红字在恢复后残留
        if (full || r.ok) lastFailText = fail.toString().trim();
        android.util.Log.d("MSClock", "校时" + (full ? "" : "快") + ": " + dbg);
        if (!r.ok) {
            // 分级文案：让用户知道该开网络、查 DNS 还是等接口恢复，而不是一句笼统的"请检查网络"
            int judged = Math.max(1, r.total);
            if (nDns * 2 > judged) {
                r.failKind = FAIL_DNS;
                r.text = "域名解析失败 · 检查 DNS/VPN";
            } else if (nSsl * 2 > judged) {
                r.failKind = FAIL_SSL;
                r.text = "SSL 握手失败 · 检查代理/证书";
            } else if (nTimeout * 2 > judged) {
                r.failKind = FAIL_TIMEOUT;
                r.text = "全部源超时 · 网络可能被限制";
            } else {
                r.failKind = FAIL_OTHER;
                r.text = "同步失败，请检查网络";
            }
            return;
        }
        r.offset = combine(r.list);
        Probe ntp = null;
        for (Probe p : r.list) if (p.ok && "阿里云NTP".equals(p.name)) ntp = p;
        int total = r.total > 0 ? r.total : (full ? KEYS.length - 1 : FAST_SOURCES);
        // NTP 失败时用综合值兜底，避免快校只显示"已同步 2/3 源"却没有偏差
        double ref = ntp != null ? ntp.offset : r.offset;
        if (r.networkOk) {
            r.text = String.format("已同步 %d/%d 源 · 本机%s %.0fms",
                    okCount, total, ref >= 0 ? "慢" : "快", Math.abs(ref));
        } else {
            // 仅靠系统缓存时间(sysnet/gnss)成功：时间可用但未真正联网校时，
            // 必须明确告知用户，不能显示"已同步 X 源"造成误导。
            r.text = String.format("网络不可用 · 仅用系统缓存时间（本机%s %.0fms）",
                    ref >= 0 ? "慢" : "快", Math.abs(ref));
            r.failKind = FAIL_NO_NETWORK;
        }
    }

    private static Probe failed(int i, String err) {
        Probe p = new Probe();
        p.name = NAMES[i];
        p.color = COLORS[i];
        p.ok = false;
        // 与 probeOneRaw 保持一致：可选源(GNSS/sysnet)失败不进失败明细、不计入总数
        p.optional = isOptionalKind(i);
        p.err = err;
        return p;
    }

    /** 探测并顺带更新健康度画像（calibrate / calibrateSingle 两条路径都覆盖） */
    private static Probe probeOne(int i, boolean fast) {
        Probe p = probeOneRaw(i, fast);
        recordHealth(p);
        return p;
    }

    private static Probe probeOneRaw(int i, boolean fast) {
        Probe p = new Probe();
        p.name = NAMES[i];
        p.color = COLORS[i];
        p.ok = false;
        p.optional = isOptionalKind(i);
        if ("none".equals(KINDS[i])) {
            p.err = "本地时钟";
            return p;
        }
        try {
            double[] m;
            String[] why = new String[1];
            switch (KINDS[i]) {
                case "ms":
                    m = probeMs(URLS[i], fast ? 2 : 4);
                    break;
                case "ntp":
                    // 快校给 UDP 两次机会（阿里云失败换腾讯接力）：UDP 123 常被运营商拦截，
                    // 只试一次会让快校在拦截网络下彻底失去 NTP 基准
                    m = probeNtp(URLS[i], fast ? 2 : 3, fast ? 900 : 1500);
                    break;
                case "gnss":
                case "sysnet":
                    // 系统级可信时间：不发网络请求，取不到就静默跳过
                    m = probeSystemClock(KINDS[i]);
                    break;
                default:
                    m = probeDate(URLS[i], 15, why);
            }
            if (m == null) {
                p.err = why[0] != null ? why[0] : "无有效响应";
                return p;
            }
            p.offset = m[0];
            p.unc = m[1];
            p.rtt = m[2];
            p.ok = true;
            return p;
        } catch (Throwable t) {
            p.err = errText(t);
            return p;
        }
    }

    /** 把异常翻译成用户可读的失败原因 */
    private static String errText(Throwable t) {
        if (t instanceof java.net.SocketTimeoutException) return "连接超时";
        if (t instanceof java.net.UnknownHostException) return "域名解析失败";
        if (t instanceof javax.net.ssl.SSLException) return "SSL握手失败";
        if (t instanceof java.net.ConnectException) return "连接失败";
        if (t instanceof java.io.IOException) return "网络错误";
        String s = String.valueOf(t.getMessage() != null ? t.getMessage() : t.toString());
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    /**
     * 毫秒时间戳接口。计时用单调钟，结束时一次 gap 换算：
     * 测量中途系统跳钟不影响本源结果。成功且流已排空的连接交还
     * keep-alive 池（后续采样免去 DNS/TCP/TLS 握手，RTT 显著变小），
     * 非 200 / 异常的连接断开弃用，避免污染连接池。
     */
    private static double[] probeMs(String urlTemplate, int samples) {
        double bestOff = 0, bestUnc = 0, bestRtt = Double.MAX_VALUE;
        boolean got = false;
        for (int i = 0; i < samples; i++) {
            HttpURLConnection c = null;
            boolean poisoned = true;
            try {
                String url = String.format(urlTemplate, System.currentTimeMillis());
                long e0 = SystemClock.elapsedRealtime();
                c = open(url, UA_MB);
                int code = c.getResponseCode();
                if (code != 200) continue;
                String body = readAll(c);   // 读完并关流 → 底层连接可复用
                poisoned = false;
                long e1 = SystemClock.elapsedRealtime();
                Matcher m = T_PATTERN.matcher(body);
                if (!m.find()) continue;
                double server = Double.parseDouble(m.group(1));
                double rtt = e1 - e0;
                double off = server - (e0 + e1) / 2.0 - wallGap();
                if (rtt < bestRtt) {
                    bestRtt = rtt;
                    bestOff = off;
                    bestUnc = rtt / 2.0;
                    got = true;
                }
                Thread.sleep(30);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable ignored) {
            } finally {
                if (c != null && poisoned) {
                    try { c.disconnect(); } catch (Throwable ignored) { }
                }
            }
        }
        return got ? new double[]{bestOff, bestUnc, bestRtt} : null;
    }

    /**
     * HTTP Date 头区间交集法（why[0] 回传最后一个采样失败的易读原因）。
     * 计时全用单调钟，区间先在 (server epoch − 单调钟) 域求交集，结束时统一减一次
     * gap 换算回墙钟域——采样中途系统跳钟不污染本源结果。
     * HEAD 采样成功且响应已排空时连接交还 keep-alive 池，后续采样免去
     * DNS/TCP/TLS 握手，rtt 变小直接收窄区间（宽度 = 1000 + rtt）。
     */
    private static double[] probeDate(String url, int samples, String[] why) {
        double[] lo = new double[samples];
        double[] hi = new double[samples];
        double minRtt = Double.MAX_VALUE;
        int n = 0;
        double step = 1000.0 / samples;
        long started = SystemClock.elapsedRealtime();
        // 整体预算：保证至少 8 个样本铺满 1 秒相位，慢站点也不拖住整轮
        final long budget = 3200;
        boolean noDateOnHead = false;
        // 交集随采样滚动更新：收敛到 30ms 内即提前结束（远好于秒级量化极限，省时省电）
        double low = -Double.MAX_VALUE, high = Double.MAX_VALUE;
        long firstDate = -1;
        boolean allSame = true;
        for (int i = 0; i < samples; i++) {
            long elapsed = SystemClock.elapsedRealtime() - started;
            if ((n >= 8 && elapsed > budget) || elapsed > 5000) break;
            HttpURLConnection c = null;
            boolean reused = false;   // HEAD 成功且已排空 → 连接回池，下一采样复用
            try {
                long e0 = SystemClock.elapsedRealtime();
                c = open(url, UA_PC, 1500, 1800);
                long dateMs = -1;
                long e1;
                if (!noDateOnHead) {
                    try {
                        c.setRequestMethod("HEAD");      // 只要响应头，省流量
                    } catch (Throwable ignored) {
                    }
                    c.getResponseCode();                 // 等到响应头到达
                    dateMs = c.getHeaderFieldDate("Date", -1);
                    e1 = SystemClock.elapsedRealtime();
                    if (dateMs <= 0) noDateOnHead = true;
                } else {
                    e1 = e0;
                }
                if (noDateOnHead) {
                    // 部分站点（如哔哩哔哩）对 HEAD 不回 Date，改用 GET：
                    // 拿到响应头即计时断开，不下载正文（整页正文可达数百 KB，会把 RTT 和区间宽度撑大）
                    try { c.disconnect(); } catch (Throwable ignored) { }
                    c = null;
                    e0 = SystemClock.elapsedRealtime();
                    c = open(url, UA_PC, 1500, 1800);
                    c.getResponseCode();
                    dateMs = c.getHeaderFieldDate("Date", -1);
                    e1 = SystemClock.elapsedRealtime();
                } else {
                    readAll(c);          // 排空 HEAD 响应（空体），连接交还 keep-alive 池
                    reused = true;
                }
                if (dateMs <= 0) continue;
                double rtt = e1 - e0;
                if (firstDate < 0) firstDate = dateMs;
                if (dateMs != firstDate) allSame = false;
                // Date 只有秒级精度：真值落在 [D, D+1000) 内，且响应头生成时刻在 [e0, e1] 之间
                lo[n] = dateMs - e1;
                hi[n] = dateMs + 1000 - e0;
                minRtt = Math.min(minRtt, rtt);
                n++;
                low = Math.max(low, lo[n - 1]);
                high = Math.min(high, hi[n - 1]);
                // 交集已收敛且已见到跨秒的不同 Date（排除缓存假收敛）才提前结束
                if (n >= 6 && !allSame && high - low < 30) break;
                // 对齐到下一个相位栅格，让采样点铺满一秒
                long next = (long) (Math.floor(e1 / step) + 1) * (long) step;
                long sleep = Math.max(12, Math.min(220, next - SystemClock.elapsedRealtime()));
                Thread.sleep(sleep);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                if (why != null && why[0] == null) why[0] = errText(t);
            } finally {
                if (c != null && !reused) {
                    try { c.disconnect(); } catch (Throwable ignored) { }
                }
            }
        }
        if (n < 3) {
            if (why != null && why[0] == null) why[0] = "响应中无Date头";
            return null;
        }
        if (allSame) {
            // 相位栅格跨秒采样必然出现不同 Date；全部相同说明命中了同一份缓存响应。
            // 判定只依赖「采样是否真的跨过秒边界」(span>1.5s) 而非样本数——慢站点在
            // 5s 预算内可能只采到 3~7 个样本，若用 n>=8 作门槛会绕过检测、
            // 带着窄交集假收敛把缓存时间当成高精度结果。
            // span 不足 1.5s 时无法区分「真在同一秒内」与缓存，放大 unc 到秒级最坏情况。
            long span = SystemClock.elapsedRealtime() - started;
            if (span > 1500) {
                if (why != null) why[0] = "疑似缓存时间";
                return null;
            }
            double mid = (low + high) / 2.0 - wallGap();
            return new double[]{mid, Math.max((high - low) / 2.0, 500.0), minRtt};
        }
        // 区间求交（含交集为空时的最大共识回退），逻辑在 TimeMath，可被单测覆盖
        double[] inter = TimeMath.intersect(lo, hi, n);
        double gap = wallGap();   // 采样结束后统一换算一次；中途跳钟在此抵消
        return new double[]{inter[0] - gap, inter[1], minRtt};
    }

    /** NTP 服务器链：首选阿里云，其后腾讯、cn.ntp.org.cn；并行发起后按中位 offset 合成 */
    private static final String[] NTP_HOSTS = {"ntp.aliyun.com", "ntp.tencent.com", "cn.ntp.org.cn"};

    /** NTP 应答质量门限（借鉴 TrueTime 的 rootDelay / rootDispersion / serverResponse 校验） */
    private static final double NTP_ROOT_DELAY_MAX = 1000.0;      // ms
    private static final double NTP_ROOT_DISPERSION_MAX = 1000.0; // ms
    private static final double NTP_SERVER_RESPONSE_MAX = 2000.0; // ms（t3−t2）

    /** NTP 子请求专用小线程池，避免占用校时主池导致饥饿；非 daemon 任务由 future 超时控制 */
    private static final java.util.concurrent.ExecutorService NTP_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "timesync-ntp");
                t.setDaemon(true);
                return t;
            });

    /**
     * DNS 解析单独隔离：InetAddress.getByName 没有超时参数，弱网下可能阻塞几十秒，
     * 占住 NTP_POOL 后 Future.cancel 也救不回来，整轮校时就会卡住（时间源页不刷新）。
     * 这里给解析加硬超时，并把成功结果缓存起来供后续轮次复用。
     */
    /** DNS 缓存条目：带单调钟时间戳，TTL 到期后重解析，避免网络切换/CDN 变更后永久指向旧 IP */
    private static final class DnsEntry {
        final InetAddress addr;
        final long atMillis;
        DnsEntry(InetAddress a, long t) { addr = a; atMillis = t; }
    }
    private static final long DNS_TTL_MS = 10 * 60 * 1000L;   // 10 分钟
    private static final Map<String, DnsEntry> NTP_DNS_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 有界池：最多 3 个解析任务（NTP 服务器就是 3 台），防止弱网下无限堆积解析线程 */
    private static final java.util.concurrent.ExecutorService DNS_POOL =
            new ThreadPoolExecutor(0, 3, 30, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "timesync-dns");
                        t.setDaemon(true);
                        return t;
                    });

    private static InetAddress resolveNtp(String host, int timeoutMs) throws Exception {
        DnsEntry cached = NTP_DNS_CACHE.get(host);
        long now = SystemClock.elapsedRealtime();
        // 缓存有效：直接复用。过期/不存在：重解析（失败时仍回退旧地址，保证可用性）
        if (cached != null && now - cached.atMillis < DNS_TTL_MS) return cached.addr;
        java.util.concurrent.Future<InetAddress> f =
                DNS_POOL.submit(() -> InetAddress.getByName(host));
        try {
            int dnsTimeout = Math.max(200, Math.min(timeoutMs, 1200));
            InetAddress addr = f.get(dnsTimeout, TimeUnit.MILLISECONDS);
            if (addr != null) NTP_DNS_CACHE.put(host, new DnsEntry(addr, SystemClock.elapsedRealtime()));
            return addr;
        } catch (Exception e) {
            f.cancel(true);
            if (cached != null) return cached.addr;
            throw e;
        }
    }

    /**
     * NTP：并行向多台服务器发一次请求，取各源 offset 的中位数（TrueTime/MuTime 思路），
     * 消除单点服务器偏差；单源仍用四时间戳公式 θ=(t2+t3)/2−(t1+t4)/2。
     * 校验 mode/LI/stratum/rootDelay/rootDispersion/serverResponse，拒掉 KoD、未同步与高抖动应答。
     * 计时全用单调钟，所有源结束后统一减一次 gap 换算回 epoch，中途跳钟不污染结果。
     */
    private static double[] probeNtp(String host, int tries, int timeoutMs) {
        int hostCount = Math.max(1, Math.min(tries, NTP_HOSTS.length));
        java.util.List<String> hosts = new ArrayList<>(hostCount);
        hosts.add(host);
        for (int i = 1; i < hostCount; i++) {
            String h = NTP_HOSTS[i % NTP_HOSTS.length];
            if (!h.equals(host)) hosts.add(h);
        }
        java.util.List<java.util.concurrent.Future<double[]>> futures = new ArrayList<>(hosts.size());
        for (String h : hosts) {
            final String hh = h;
            futures.add(NTP_POOL.submit(() -> ntpOnce(hh, timeoutMs)));
        }
        List<double[]> results = new ArrayList<>();
        // 所有源并行、共享同一总预算：按剩余预算等待，避免第一台超时吃掉后面成功源的收集机会
        long deadline = SystemClock.elapsedRealtime() + timeoutMs + 300;
        for (java.util.concurrent.Future<double[]> f : futures) {
            try {
                long remain = deadline - SystemClock.elapsedRealtime();
                if (remain <= 0) { f.cancel(true); continue; }
                double[] m = f.get(remain, TimeUnit.MILLISECONDS);
                if (m != null) results.add(m);
            } catch (Exception e) {
                f.cancel(true);
            }
        }
        if (results.isEmpty()) return null;
        // 每台源先按 offset 排序取中位；n 为偶数时取中间两值平均，与 TrueTime 一致
        results.sort((a, b) -> Double.compare(a[0], b[0]));
        double offMono;
        double repRtt;
        int sz = results.size();
        if (sz % 2 == 1) {
            offMono = results.get(sz / 2)[0];
            repRtt = results.get(sz / 2)[1];
        } else {
            double[] a = results.get(sz / 2 - 1), b = results.get(sz / 2);
            offMono = (a[0] + b[0]) / 2.0;
            repRtt = Math.min(a[1], b[1]);
        }
        return new double[]{offMono - wallGap(), repRtt / 2.0, repRtt};
    }

    /** 单次 NTP 请求，返回 [serverEpoch−单调钟 中值, RTT]；质量不合格返回 null */
    private static double[] ntpOnce(String host, int timeoutMs) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setSoTimeout(timeoutMs);
            byte[] buf = new byte[48];
            buf[0] = 0x1b;
            InetAddress addr = resolveNtp(host, timeoutMs);
            long e1 = SystemClock.elapsedRealtime();
            s.send(new DatagramPacket(buf, buf.length, addr, 123));
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            s.receive(pkt);
            long e4 = SystemClock.elapsedRealtime();
            int b0 = buf[0] & 0xFF;
            int mode = b0 & 0x07;
            int li = (b0 >> 6) & 0x03;
            int stratum = buf[1] & 0xFF;
            if (mode != 4 || li == 3 || stratum == 0 || stratum > 15) return null;
            double rootDelay = readNtpFixed(buf, 4);
            double rootDispersion = readNtpFixed(buf, 8);
            if (rootDelay > NTP_ROOT_DELAY_MAX || rootDispersion > NTP_ROOT_DISPERSION_MAX) return null;
            double t2 = readNtpTs(buf, 32);   // 服务器收到请求的时刻
            double t3 = readNtpTs(buf, 40);   // 服务器发出应答的时刻
            if (t2 <= 0 || t3 <= 0 || t3 < t2 || t3 - t2 > NTP_SERVER_RESPONSE_MAX) return null;
            double rtt = e4 - e1;
            // 单调域先相减，gap 在 probeNtp 收集齐后统一减一次
            double offMono = (t2 + t3) / 2.0 - (e1 + e4) / 2.0;
            return new double[]{offMono, rtt};
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (s != null) s.close();
        }
    }

    /** 读取 NTP 32 位定点秒（16.16），换算成毫秒（复用可单测的 TimeMath） */
    private static double readNtpFixed(byte[] buf, int off) {
        return TimeMath.ntpFixedToMs(buf, off);
    }

    /** 读取 NTP 64 位时间戳（1900 起秒 + 2^-32 分数），换算成 Unix epoch 毫秒 */
    private static double readNtpTs(byte[] buf, int off) {
        return TimeMath.ntpTsToMs(buf, off);
    }

    /**
     * 以 NTP 为基准剔除离群源（NTP 缺席时用中位数做参考，防单个离群源
     * 把好源全部踢出），再按精度加权。
     */
    private static double combine(List<Probe> list) {
        List<Probe> valid = new ArrayList<>();
        for (Probe p : list) if (p.ok) valid.add(p);
        if (valid.isEmpty()) return 0;
        double[] offs = new double[valid.size()];
        double[] uncs = new double[valid.size()];
        double ref = 0;
        Probe ntp = null;
        for (int i = 0; i < valid.size(); i++) {
            Probe p = valid.get(i);
            offs[i] = p.offset;
            uncs[i] = p.unc;
            if ("阿里云NTP".equals(p.name)) ntp = p;
        }
        if (ntp != null) {
            ref = ntp.offset;
        } else {
            // NTP 缺席用中位数，避免单个离群源成为参考（逻辑在 TimeMath，可被单测覆盖）
            ref = TimeMath.median(offs, offs.length);
        }
        return TimeMath.weightedCombine(offs, uncs, ref, 350.0);
    }

    // ------------------------------------------------------------ 工具

    /**
     * 墙钟 − 单调钟 的当前换算差。探测内部全部用单调钟计时，采样完成后取一次
     * gap 换算回 epoch 域：测量中途系统 NTP 校时 / 用户改时间造成的跳钟会被
     * 换算精确抵消（锚定端同样基于跳钟后的墙钟，两处误差相互抵消）。
     * 前后各取一次墙钟，取数间隙恰被跳钟击中时以后一次为准。
     */
    /**
     * 系统级可信时间源。Android 维护着两条独立于本应用的时间通道：
     * <ul>
     *   <li>{@code currentGnssTimeClock()}（API 29+）GNSS 卫星授时，室外可达微秒级，<b>无需网络</b></li>
     *   <li>{@code currentNetworkTimeClock()}（API 33+）系统 NTP/NITZ 结果</li>
     * </ul>
     * 两者在无信号/未同步时会抛异常（GNSS 在室内基本必然抛），这里一律返回 null
     * 由调用方按"可选源跳过"处理，绝不能算作失败——否则室内会永久显示 15/17 源。
     */
    private static double[] probeSystemClock(String kind) {
        boolean gnss = "gnss".equals(kind);
        if (gnss) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;          // API 29
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null;   // API 33
        }
        long e0 = SystemClock.elapsedRealtime();
        long t;
        try {
            java.time.Clock c = gnss
                    ? SystemClock.currentGnssTimeClock()
                    : SystemClock.currentNetworkTimeClock();
            t = c.millis();
        } catch (Throwable th) {
            return null;   // 无 GNSS 信号 / 系统时间尚未同步
        }
        long e1 = SystemClock.elapsedRealtime();
        if (t <= 0) return null;
        double off = t - (e0 + e1) / 2.0 - wallGap();
        // GNSS 授时精度高于网络源，但不给到 5ms 那么激进——避免单源主导合成。
        // 系统网络时间受 18h 级同步间隔限制（实测曾偏 -434ms），必须给大 unc 压低权重。
        double unc = gnss ? 10.0 : 150.0;
        return new double[]{off, unc, e1 - e0};
    }

    private static double wallGap() {
        // 墙钟与单调钟成对采样：正常时取两对中点消除残余偏差；
        // 若采样间隙发生跳钟（w2−w1>5ms），只用跳变后的一对，避免用跳变前的 e 污染 gap
        long w1 = System.currentTimeMillis();
        long e1 = SystemClock.elapsedRealtime();
        long w2 = System.currentTimeMillis();
        long e2 = SystemClock.elapsedRealtime();
        if (Math.abs(w2 - w1) > 5) return w2 - e2;
        return (w1 + w2) / 2.0 - (e1 + e2) / 2.0;
    }

    private static HttpURLConnection open(String url, String ua) throws Exception {
        return open(url, ua, 3000, 3000);
    }

    private static HttpURLConnection open(String url, String ua, int connectMs, int readMs)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Connection", "keep-alive");
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    /** 响应体读取上限：异常端点可能在 3s 内推送超大响应占满内存，256KB 足够时间戳接口 */
    private static final int MAX_BODY_BYTES = 256 * 1024;

    private static String readAll(HttpURLConnection c) {
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[2048];
            int len, total = 0;
            while ((len = in.read(b)) > 0) {
                bo.write(b, 0, len);
                total += len;
                if (total >= MAX_BODY_BYTES) break;
            }
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            try {
                InputStream in = c.getErrorStream();
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            return "";
        }
    }
}

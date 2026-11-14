package com.wb.msclock;

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
    }

    /** 同步进度（供界面显示）：已完成源数 / 总源数 */
    public static final java.util.concurrent.atomic.AtomicInteger progDone =
            new java.util.concurrent.atomic.AtomicInteger();
    public static volatile int progTotal = 0;
    /** 最近一次校时的失败明细，如 "哔哩哔哩(连接超时) 云闪付(网络错误)"；全部成功时为空串 */
    public static volatile String lastFailText = "";

    public static class Result {
        public List<Probe> list = new ArrayList<>();
        public double offset;   // 综合 offset，ms
        public boolean ok;
        public boolean full;    // true=全量 14 源，false=快校时 3 源
        public int total = 3;   // 本轮参与统计的源数（单源合并后会增长）
        public int okCount;
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
    // 注意：KEYS/NAMES/COLORS/KINDS/URLS/DOMAINS 六个数组必须按下标严格对齐
    //（device 占 0 位，KINDS[0]="none"、URLS[0]=null，不联网）
    public static final String[] KEYS = {
            "device", "taobao", "tmall", "jd", "meituan", "eleme",
            "vip", "pdd", "bili", "kuaishou", "xhs", "alipay", "rail", "unionpay",
            "ntp"
    };
    public static final String[] NAMES = {
            "设备时间", "淘宝", "天猫", "京东", "美团", "饿了么",
            "唯品会", "拼多多", "哔哩哔哩", "快手", "小红书", "支付宝", "12306", "云闪付",
            "阿里云NTP"
    };
    public static final String[] COLORS = {
            "#8A93A6", "#FF6A00", "#FF0036", "#E8443A", "#FFC300", "#1E90FF",
            "#F10180", "#E02E24", "#00A1D6", "#FF8800", "#FF2442", "#1677FF", "#1A5CC8", "#005BAC",
            "#3DDC84"
    };
    private static final String[] KINDS = {
            "none", "ms", "ms", "date", "date", "date",
            "date", "date", "date", "date", "date", "date", "date", "date",
            "ntp"
    };
    private static final String[] URLS = {
            null,
            "https://h5api.m.taobao.com/h5/mtop.common.getTimestamp/1.0/?jsv=2.5.1&appKey=12574478&t=%d&sign=1",
            "https://h5api.m.tmall.com/h5/mtop.common.getTimestamp/1.0/?jsv=2.5.1&appKey=12574478&t=%d&sign=1",
            "https://api.m.jd.com/api?appid=publicUseApi",
            "https://www.meituan.com/",
            "https://www.ele.me/",
            "https://www.vip.com/",
            "https://mobile.yangkeduo.com/",
            "https://www.bilibili.com/",
            "https://www.kuaishou.com/",
            "https://www.xiaohongshu.com/",
            "https://my.alipay.com/",
            "https://www.12306.cn/index/",
            "https://www.unionpayintl.com/",
            "ntp.aliyun.com"
    };
    /** 各源展示用的域名 */
    public static final String[] DOMAINS = {
            "设备系统时钟", "h5api.m.taobao.com", "h5api.m.tmall.com",
            "api.m.jd.com", "www.meituan.com", "www.ele.me",
            "www.vip.com", "mobile.yangkeduo.com", "www.bilibili.com",
            "www.kuaishou.com", "www.xiaohongshu.com",             "my.alipay.com", "www.12306.cn", "www.unionpayintl.com", "ntp.aliyun.com"
    };

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

    /** 全量校时：14 个网络源 */
    public static Result calibrate() {
        return calibrate(true);
    }

    /** 快校时：NTP + 淘宝 + 天猫，用于启动和 60s 周期刷新 */
    public static Result calibrateFast() {
        return calibrate(false);
    }

    private static Result calibrate(boolean full) {
        long tStart = System.currentTimeMillis();
        Result r = new Result();
        r.full = full;
        final int[] idx;
        r.total = full ? KEYS.length - 1 : FAST_SOURCES;
        if (full) {
            idx = new int[KEYS.length - 1];
            for (int i = 1; i < KEYS.length; i++) idx[i - 1] = i;
        } else {
            idx = new int[]{indexOf("taobao"), indexOf("tmall"), indexOf("ntp")};
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
                long t0 = System.currentTimeMillis();
                Probe p = probeOne(i, !full);
                android.util.Log.d("MSClock", "源耗时 " + p.name + " " + (System.currentTimeMillis() - t0) + "ms "
                        + (p.ok ? "ok" : (p.err == null ? "fail" : p.err)));
                if (sid == scanSeq.get()) {
                    slots[slot] = p;
                    progDone.incrementAndGet();
                }
                return p;
            }));
        }
        long deadline = System.currentTimeMillis() + (full ? 6000 : 2200);
        for (int k = 0; k < futures.size(); k++) {
            Future<Probe> f = futures.get(k);
            try {
                long wait = Math.max(1, deadline - System.currentTimeMillis());
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
        android.util.Log.d("MSClock", "校时总耗时 " + (System.currentTimeMillis() - tStart) + "ms ("
                + (full ? "全量" : "快") + ")");
        return r;
    }

    /** 单源探测：选中某个时间源时使用，避免整轮全量扫描 */
    public static Probe calibrateSingle(String key) {
        final int i = indexOf(key);
        if (i < 0) return null;
        long t0 = System.currentTimeMillis();
        Future<Probe> f = POOL.submit(() -> probeOne(i, false));
        Probe p;
        try {
            p = f.get(12, TimeUnit.SECONDS);
        } catch (Exception e) {
            f.cancel(true);
            p = failed(i, "响应超时");
        }
        logSingle(p);
        android.util.Log.d("MSClock", "单源总耗时 " + (System.currentTimeMillis() - t0) + "ms");
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
        // 防 CDN 缓存 Date 头：与 NTP 基准偏差超过 2 秒的源判为无效
        Probe ntpBase = null;
        for (Probe p : r.list) if (p.ok && "阿里云NTP".equals(p.name)) ntpBase = p;
        if (ntpBase != null) {
            for (Probe p : r.list) {
                if (p.ok && p != ntpBase && Math.abs(p.offset - ntpBase.offset) > 2000) {
                    p.ok = false;
                    p.err = "疑似缓存时间";
                    android.util.Log.d("MSClock", "剔除疑似缓存Date源: " + p.name);
                }
            }
        }
        int okCount = 0;
        StringBuilder dbg = new StringBuilder();
        StringBuilder fail = new StringBuilder();
        for (Probe p : r.list) {
            if (p.ok) {
                okCount++;
                dbg.append(p.name).append(String.format(" %+.0f±%.0fms; ", p.offset, p.unc));
            } else {
                dbg.append(p.name).append("(").append(p.err == null ? "失败" : p.err).append("); ");
                fail.append(p.name).append("(").append(p.err == null ? "失败" : p.err).append(") ");
            }
        }
        r.okCount = okCount;
        r.ok = okCount > 0;
        // 全量总是刷新明细；快校成功时也刷新，避免上一轮全量失败的红字在恢复后残留
        if (full || r.ok) lastFailText = fail.toString().trim();
        android.util.Log.d("MSClock", "校时" + (full ? "" : "快") + ": " + dbg);
        if (!r.ok) {
            r.text = "同步失败，请检查网络";
            return;
        }
        r.offset = combine(r.list);
        Probe ntp = null;
        for (Probe p : r.list) if (p.ok && "阿里云NTP".equals(p.name)) ntp = p;
        int total = r.total > 0 ? r.total : (full ? KEYS.length - 1 : FAST_SOURCES);
        // NTP 失败时用综合值兜底，避免快校只显示"已同步 2/3 源"却没有偏差
        double ref = ntp != null ? ntp.offset : r.offset;
        r.text = String.format("已同步 %d/%d 源 · 本机%s %.0fms",
                okCount, total, ref >= 0 ? "慢" : "快", Math.abs(ref));
    }

    private static Probe failed(int i, String err) {
        Probe p = new Probe();
        p.name = NAMES[i];
        p.color = COLORS[i];
        p.ok = false;
        p.err = err;
        return p;
    }

    private static Probe probeOne(int i, boolean fast) {
        Probe p = new Probe();
        p.name = NAMES[i];
        p.color = COLORS[i];
        p.ok = false;
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
        if (allSame && n >= 8) {
            // 相位栅格跨秒采样必然出现不同 Date；全部相同说明命中了同一份缓存响应。
            // 整体耗时已跨过 1.5s（必然跨过秒边界）时可以断定是缓存，交集虽窄也是假约束，
            // 直接判失败；耗时不足 1.5s 时无法区分「真在同一秒内」与缓存，
            // 只把不确定度放大到秒级最坏情况，避免缓存时间混进加权。
            long span = SystemClock.elapsedRealtime() - started;
            if (span > 1500) {
                if (why != null) why[0] = "疑似缓存时间";
                return null;
            }
            double mid = (low + high) / 2.0 - wallGap();
            return new double[]{mid, Math.max((high - low) / 2.0, 500.0), minRtt};
        }
        if (high <= low) {
            // 交集为空：取覆盖样本最多的窗口（最大共识）
            double bestC = low, bestCnt = -1;
            for (int i = 0; i < n; i++) {
                double c = lo[i];
                int cnt = 0;
                for (int j = 0; j < n; j++) if (lo[j] <= c && c <= hi[j]) cnt++;
                if (cnt > bestCnt) {
                    bestCnt = cnt;
                    bestC = c;
                }
            }
            double l2 = -Double.MAX_VALUE, h2 = Double.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (lo[j] <= bestC && bestC <= hi[j]) {
                    l2 = Math.max(l2, lo[j]);
                    h2 = Math.min(h2, hi[j]);
                }
            }
            if (h2 <= l2) {
                l2 = bestC - 50;
                h2 = bestC + 50;
            }
            low = l2;
            high = h2;
        }
        double gap = wallGap();   // 采样结束后统一换算一次；中途跳钟在此抵消
        return new double[]{(low + high) / 2.0 - gap, (high - low) / 2.0, minRtt};
    }

    /** NTP 服务器链：首选阿里云，UDP 123 被运营商拦截或单点故障时按序接力 */
    private static final String[] NTP_HOSTS = {"ntp.aliyun.com", "ntp.tencent.com", "cn.ntp.org.cn"};

    /**
     * NTP。四时间戳公式 θ=(t2+t3)/2−(t1+t4)/2，比旧版只用 t3 少了
     * 服务器处理时延一半的系统性偏差；校验 mode/LI/stratum 拒掉 KoD 拒答
     * 与未同步应答。计时用单调钟 + 结束一次 gap 换算，中途跳钟不污染结果。
     * tries<=2（快校）时首次成功即停；全量跑满取 min-RTT。
     */
    private static double[] probeNtp(String host, int tries, int timeoutMs) {
        double bestOff = 0, bestUnc = 0, bestRtt = Double.MAX_VALUE;
        boolean got = false;
        boolean stopOnFirst = tries <= 2;
        for (int i = 0; i < tries; i++) {
            DatagramSocket s = null;
            try {
                // 第 1 次用配置的首选源，后续按链接力
                String h = i == 0 ? host : NTP_HOSTS[i % NTP_HOSTS.length];
                s = new DatagramSocket();
                s.setSoTimeout(timeoutMs);
                byte[] buf = new byte[48];
                buf[0] = 0x1b;
                InetAddress addr = InetAddress.getByName(h);
                long e1 = SystemClock.elapsedRealtime();
                s.send(new DatagramPacket(buf, buf.length, addr, 123));
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                s.receive(pkt);
                long e4 = SystemClock.elapsedRealtime();
                int b0 = buf[0] & 0xFF;
                int mode = b0 & 0x07;
                int li = (b0 >> 6) & 0x03;
                int stratum = buf[1] & 0xFF;
                if (mode != 4 || li == 3 || stratum == 0 || stratum > 15) continue;
                double t2 = readNtpTs(buf, 32);   // 服务器收到请求的时刻
                double t3 = readNtpTs(buf, 40);   // 服务器发出应答的时刻
                if (t2 <= 0 || t3 <= 0) continue;
                double rtt = e4 - e1;
                double off = (t2 + t3) / 2.0 - (e1 + e4) / 2.0 - wallGap();
                if (rtt < bestRtt) {
                    bestRtt = rtt;
                    bestOff = off;
                    bestUnc = rtt / 2.0;
                    got = true;
                }
                if (got && stopOnFirst) break;
            } catch (Throwable ignored) {
            } finally {
                if (s != null) s.close();
            }
        }
        return got ? new double[]{bestOff, bestUnc, bestRtt} : null;
    }

    /** 读取 NTP 64 位时间戳（1900 起秒 + 2^-32 分数），换算成 Unix epoch 毫秒 */
    private static double readNtpTs(byte[] buf, int off) {
        long sec = ((buf[off] & 0xFFL) << 24) | ((buf[off + 1] & 0xFFL) << 16)
                | ((buf[off + 2] & 0xFFL) << 8) | (buf[off + 3] & 0xFFL);
        long frac = ((buf[off + 4] & 0xFFL) << 24) | ((buf[off + 5] & 0xFFL) << 16)
                | ((buf[off + 6] & 0xFFL) << 8) | (buf[off + 7] & 0xFFL);
        return (sec - 2208988800L) * 1000.0 + frac / 4294967296.0 * 1000.0;
    }

    /**
     * 以 NTP 为基准剔除离群源（NTP 缺席时用中位数做参考，防单个离群源
     * 把好源全部踢出），再按精度加权。
     */
    private static double combine(List<Probe> list) {
        List<Probe> valid = new ArrayList<>();
        for (Probe p : list) if (p.ok) valid.add(p);
        if (valid.isEmpty()) return 0;
        double ref;
        Probe ntp = null;
        for (Probe p : valid) if ("阿里云NTP".equals(p.name)) ntp = p;
        if (ntp != null) {
            ref = ntp.offset;
        } else {
            double[] offs = new double[valid.size()];
            for (int i = 0; i < valid.size(); i++) offs[i] = valid.get(i).offset;
            java.util.Arrays.sort(offs);
            ref = offs[offs.length / 2];
        }
        List<Probe> pool = new ArrayList<>();
        for (Probe p : valid) if (Math.abs(p.offset - ref) < 350) pool.add(p);
        if (pool.isEmpty()) pool = valid;
        double wsum = 0, sum = 0;
        for (Probe p : pool) {
            double u = Math.max(p.unc, 8.0);
            double w = 1.0 / (u * u);
            wsum += w;
            sum += p.offset * w;
        }
        return wsum > 0 ? sum / wsum : ref;
    }

    // ------------------------------------------------------------ 工具

    /**
     * 墙钟 − 单调钟 的当前换算差。探测内部全部用单调钟计时，采样完成后取一次
     * gap 换算回 epoch 域：测量中途系统 NTP 校时 / 用户改时间造成的跳钟会被
     * 换算精确抵消（锚定端同样基于跳钟后的墙钟，两处误差相互抵消）。
     * 前后各取一次墙钟，取数间隙恰被跳钟击中时以后一次为准。
     */
    private static double wallGap() {
        long w1 = System.currentTimeMillis();
        long e = SystemClock.elapsedRealtime();
        long w2 = System.currentTimeMillis();
        return (Math.abs(w2 - w1) <= 5 ? (w1 + w2) / 2.0 : w2) - e;
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

    private static String readAll(HttpURLConnection c) {
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[2048];
            int len;
            while ((len = in.read(b)) > 0) bo.write(b, 0, len);
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

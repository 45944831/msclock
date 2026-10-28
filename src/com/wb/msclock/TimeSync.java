package com.wb.msclock;

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
 *            offset = serverMs - (t0+t1)/2，取 RTT 最小的一次。
 * 京东/美团/饿了么：HTTP Date 头只有秒级精度，单次采样把 offset 约束在
 *            宽度 1000ms+rtt 的区间内；按 1/15 秒相位栅格错开采样 15 次求交集，
 *            区间收敛到几十毫秒后取中点。
 * 阿里云 NTP：作独立基准，用于剔除离群源与加权。
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
    /** 最近一次校时的失败明细，如 "抖音(连接超时) 云闪付(网络错误)"；全部成功时为空串 */
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
        POOL = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
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
            "vip", "pdd", "douyin", "kuaishou", "xhs", "alipay", "rail", "unionpay",
            "ntp"
    };
    public static final String[] NAMES = {
            "设备时间", "淘宝", "天猫", "京东", "美团", "饿了么",
            "唯品会", "拼多多", "抖音", "快手", "小红书", "支付宝", "12306", "云闪付",
            "阿里云NTP"
    };
    public static final String[] COLORS = {
            "#8A93A6", "#FF6A00", "#FF0036", "#E8443A", "#FFC300", "#1E90FF",
            "#F10180", "#E02E24", "#161823", "#FF8800", "#FF2442", "#1677FF", "#1A5CC8", "#005BAC",
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
            "https://www.douyin.com/",
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
            "www.vip.com", "mobile.yangkeduo.com", "www.douyin.com",
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
                Probe p = probeOne(i, !full);
                if (sid == scanSeq.get()) {
                    slots[slot] = p;
                    progDone.incrementAndGet();
                }
                return p;
            }));
        }
        long deadline = System.currentTimeMillis() + (full ? 12000 : 3500);
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
        return r;
    }

    /** 单源探测：选中某个时间源时使用，避免整轮全量扫描 */
    public static Probe calibrateSingle(String key) {
        final int i = indexOf(key);
        if (i < 0) return null;
        Future<Probe> f = POOL.submit(() -> probeOne(i, false));
        Probe p;
        try {
            p = f.get(12, TimeUnit.SECONDS);
        } catch (Exception e) {
            f.cancel(true);
            p = failed(i, "响应超时");
        }
        logSingle(p);
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
        if (ntp != null) {
            r.text = String.format("已同步 %d/%d 源 · 本机%s %.0fms",
                    okCount, total, ntp.offset >= 0 ? "慢" : "快", Math.abs(ntp.offset));
        } else {
            r.text = String.format("已同步 %d/%d 源", okCount, total);
        }
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
                    m = probeNtp(URLS[i], fast ? 2 : 4, fast ? 1200 : 2500);
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

    /** 毫秒时间戳接口 */
    private static double[] probeMs(String urlTemplate, int samples) {
        double bestOff = 0, bestUnc = 0, bestRtt = Double.MAX_VALUE;
        boolean got = false;
        for (int i = 0; i < samples; i++) {
            try {
                String url = String.format(urlTemplate, System.currentTimeMillis());
                long t0 = System.currentTimeMillis();
                HttpURLConnection c = open(url, UA_MB);
                int code = c.getResponseCode();
                String body = readAll(c);
                long t1 = System.currentTimeMillis();
                c.disconnect();
                if (code != 200) continue;
                Matcher m = T_PATTERN.matcher(body);
                if (!m.find()) continue;
                double server = Double.parseDouble(m.group(1));
                double rtt = t1 - t0;
                double off = server - (t0 + t1) / 2.0;
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
            }
        }
        return got ? new double[]{bestOff, bestUnc, bestRtt} : null;
    }

    /** HTTP Date 头区间交集法（why[0] 回传最后一个采样失败的易读原因） */
    private static double[] probeDate(String url, int samples, String[] why) {
        double[] lo = new double[samples];
        double[] hi = new double[samples];
        double minRtt = Double.MAX_VALUE;
        int n = 0;
        double step = 1000.0 / samples;
        for (int i = 0; i < samples; i++) {
            HttpURLConnection c = null;
            try {
                long t0 = System.currentTimeMillis();
                c = open(url, UA_PC);
                try {
                    c.setRequestMethod("HEAD");      // 只要响应头，省流量
                } catch (Throwable ignored) {
                }
                c.getResponseCode();                 // 等到响应头到达
                long dateMs = c.getHeaderFieldDate("Date", -1);
                if (dateMs <= 0) {
                    // 少数站点对 HEAD 不回 Date，本次采样退回 GET
                    try { c.disconnect(); } catch (Throwable ignored) { }
                    t0 = System.currentTimeMillis();
                    c = open(url, UA_PC);
                    c.getResponseCode();
                    dateMs = c.getHeaderFieldDate("Date", -1);
                    readAll(c);
                }
                long t1 = System.currentTimeMillis();
                c.disconnect();
                c = null;
                if (dateMs <= 0) continue;
                double rtt = t1 - t0;
                lo[n] = dateMs - t1;
                hi[n] = dateMs + 1000 - t1 + rtt;
                minRtt = Math.min(minRtt, rtt);
                n++;
                // 对齐到下一个相位栅格，让采样点铺满一秒
                long next = (long) (Math.floor(t1 / step) + 1) * (long) step;
                long sleep = Math.max(12, Math.min(220, next - System.currentTimeMillis()));
                Thread.sleep(sleep);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                if (why != null && why[0] == null) why[0] = errText(t);
            } finally {
                if (c != null) {
                    try { c.disconnect(); } catch (Throwable ignored) { }
                }
            }
        }
        if (n < 3) {
            if (why != null && why[0] == null) why[0] = "响应中无Date头";
            return null;
        }
        double low = -Double.MAX_VALUE, high = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            low = Math.max(low, lo[i]);
            high = Math.min(high, hi[i]);
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
        return new double[]{(low + high) / 2.0, (high - low) / 2.0, minRtt};
    }

    /** NTP */
    private static double[] probeNtp(String host, int tries, int timeoutMs) {
        double bestOff = 0, bestUnc = 0, bestRtt = Double.MAX_VALUE;
        boolean got = false;
        for (int i = 0; i < tries; i++) {
            DatagramSocket s = null;
            try {
                s = new DatagramSocket();
                s.setSoTimeout(timeoutMs);
                byte[] buf = new byte[48];
                buf[0] = 0x1b;
                InetAddress addr = InetAddress.getByName(host);
                long t1 = System.currentTimeMillis();
                s.send(new DatagramPacket(buf, buf.length, addr, 123));
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                s.receive(pkt);
                long t4 = System.currentTimeMillis();
                long sec = ((buf[40] & 0xFFL) << 24) | ((buf[41] & 0xFFL) << 16)
                        | ((buf[42] & 0xFFL) << 8) | (buf[43] & 0xFFL);
                long frac = ((buf[44] & 0xFFL) << 24) | ((buf[45] & 0xFFL) << 16)
                        | ((buf[46] & 0xFFL) << 8) | (buf[47] & 0xFFL);
                double server = (sec - 2208988800L) * 1000.0 + frac / 4294967296.0 * 1000.0;
                double rtt = t4 - t1;
                double off = server - (t1 + t4) / 2.0;
                if (rtt < bestRtt) {
                    bestRtt = rtt;
                    bestOff = off;
                    bestUnc = rtt / 2.0;
                    got = true;
                }
            } catch (Throwable ignored) {
            } finally {
                if (s != null) s.close();
            }
        }
        return got ? new double[]{bestOff, bestUnc, bestRtt} : null;
    }

    /** 以 NTP 为基准剔除离群源，再按精度加权 */
    private static double combine(List<Probe> list) {
        List<Probe> valid = new ArrayList<>();
        for (Probe p : list) if (p.ok) valid.add(p);
        Probe base = null;
        for (Probe p : valid) if ("阿里云NTP".equals(p.name)) base = p;
        if (base == null) {
            base = valid.get(0);
            for (Probe p : valid) if (p.unc < base.unc) base = p;
        }
        List<Probe> pool = new ArrayList<>();
        for (Probe p : valid) if (Math.abs(p.offset - base.offset) < 350) pool.add(p);
        if (pool.isEmpty()) pool = valid;
        double wsum = 0, sum = 0;
        for (Probe p : pool) {
            double u = Math.max(p.unc, 8.0);
            double w = 1.0 / (u * u);
            wsum += w;
            sum += p.offset * w;
        }
        return wsum > 0 ? sum / wsum : base.offset;
    }

    // ------------------------------------------------------------ 工具
    private static HttpURLConnection open(String url, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
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

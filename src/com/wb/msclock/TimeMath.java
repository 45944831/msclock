package com.wb.msclock;

import java.util.Arrays;

/**
 * 校时纯算法集合：不依赖任何 Android API，可在 JVM 上直接单测。
 * TimeSync 复用这些方法，保证测试覆盖的就是线上实际逻辑。
 */
public final class TimeMath {
    private TimeMath() { }

    /** 中位数（偶数长度取中间两值平均）。入参会被就地排序。 */
    public static double median(double[] values, int len) {
        if (len <= 0) return 0;
        double[] a = Arrays.copyOf(values, len);
        Arrays.sort(a);
        if ((len & 1) == 1) return a[len / 2];
        return (a[len / 2 - 1] + a[len / 2]) / 2.0;
    }

    /**
     * 按 1/unc² 加权合成。先以 ref 为基准剔除偏差 >= gate 的源（去重后若为空则回退全部），
     * unc 下限 8ms 防止零不确定度导致权重爆炸。
     *
     * @param offsets 各源偏移
     * @param uncs    各源半宽不确定度
     * @param ref     离群参考值
     * @param gate    离群门限（ms）
     * @return 合成偏移
     */
    public static double weightedCombine(double[] offsets, double[] uncs, double ref, double gate) {
        int n = Math.min(offsets.length, uncs.length);
        if (n == 0) return ref;
        boolean[] keep = new boolean[n];
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            keep[i] = Math.abs(offsets[i] - ref) < gate;
            if (keep[i]) cnt++;
        }
        if (cnt == 0) Arrays.fill(keep, true);   // 全被踢掉则回退，允许极端情况下仍有结果
        double wsum = 0, sum = 0;
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            double u = Math.max(uncs[i], 8.0);
            double w = 1.0 / (u * u);
            wsum += w;
            sum += offsets[i] * w;
        }
        return wsum > 0 ? sum / wsum : ref;
    }

    /**
     * 区间交集。lo[i]..hi[i] 为第 i 个源把 offset 约束到的闭区间。
     * 交集为空时取覆盖样本最多的窗口（最大共识）；仍为空则收窄到 ±50ms。
     * 返回 [中值, 半宽]。
     */
    public static double[] intersect(double[] lo, double[] hi, int n) {
        if (n <= 0) return new double[]{0, 0};
        double low = -Double.MAX_VALUE, high = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            low = Math.max(low, lo[i]);
            high = Math.min(high, hi[i]);
        }
        if (high > low) return new double[]{(low + high) / 2.0, (high - low) / 2.0};

        // 交集为空：以每个 lo 为候选点，数被多少区间覆盖，取最大共识
        double bestC = lo[0], bestCnt = -1;
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
        return new double[]{(l2 + h2) / 2.0, (h2 - l2) / 2.0};
    }

    /** NTP 64 位时间戳（1900 起秒 + 2^-32 分数）→ Unix epoch 毫秒 */
    public static double ntpTsToMs(byte[] buf, int off) {
        long sec = ((buf[off] & 0xFFL) << 24) | ((buf[off + 1] & 0xFFL) << 16)
                | ((buf[off + 2] & 0xFFL) << 8) | (buf[off + 3] & 0xFFL);
        long frac = ((buf[off + 4] & 0xFFL) << 24) | ((buf[off + 5] & 0xFFL) << 16)
                | ((buf[off + 6] & 0xFFL) << 8) | (buf[off + 7] & 0xFFL);
        return (sec - 2208988800L) * 1000.0 + frac / 4294967296.0 * 1000.0;
    }

    /** NTP 32 位定点秒（16.16）→ 毫秒，用于 rootDelay / rootDispersion */
    public static double ntpFixedToMs(byte[] buf, int off) {
        long v = ((buf[off] & 0xFFL) << 24) | ((buf[off + 1] & 0xFFL) << 16)
                | ((buf[off + 2] & 0xFFL) << 8) | (buf[off + 3] & 0xFFL);
        return v * 1000.0 / 65536.0;
    }

    /**
     * 无 Calendar / String.format 的本地时间格式化，固定 8 或 10 字符。
     * from MainActivity.fmt —— 抽到此处以便 JVM 单测覆盖时区/跨日/毫秒进位。
     */
    public static String formatClock(long ms, boolean withTenths, int tzOffsetSeconds) {
        long sec = Math.floorDiv(ms, 1000L);
        int milli = (int) (ms - sec * 1000L);
        int sod = (int) Math.floorMod(sec + tzOffsetSeconds, 86400L);
        int hh = sod / 3600, mm = (sod % 3600) / 60, ss = sod % 60;
        char[] b = new char[withTenths ? 10 : 8];
        b[0] = (char) ('0' + hh / 10);
        b[1] = (char) ('0' + hh % 10);
        b[2] = ':';
        b[3] = (char) ('0' + mm / 10);
        b[4] = (char) ('0' + mm % 10);
        b[5] = ':';
        b[6] = (char) ('0' + ss / 10);
        b[7] = (char) ('0' + ss % 10);
        if (withTenths) {
            b[8] = '.';
            b[9] = (char) ('0' + milli / 100);
        }
        return new String(b);
    }
}

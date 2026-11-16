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
     * 校时结果融合：抑制测量噪声导致的显示跳变（RFC 5905 clock discipline 的简化版）。
     *
     * <p>规则：
     * <ul>
     *   <li>没有历史值（prevUnc ≤ 0）→ 直接采纳新值</li>
     *   <li>|新−旧| &gt; 3σ（σ 为两者合成不确定度）→ 判为真实跳变（刚联网/系统时间被改），直接采纳</li>
     *   <li>否则按 1/unc² 加权，新值权重限制在 [0.4, 0.9]，既压噪声又不过度滞后</li>
     * </ul>
     *
     * @param prevOffset 当前基准偏移（ms）
     * @param prevUnc    当前基准不确定度（ms，≤0 表示无历史）
     * @param newOffset  本次测得偏移（ms）
     * @param newUnc     本次不确定度（ms）
     * @return 融合后的偏移（ms）
     */
    public static double fuseOffset(double prevOffset, double prevUnc,
                                    double newOffset, double newUnc) {
        if (prevUnc <= 0 || Double.isNaN(prevUnc) || Double.isInfinite(prevUnc)) return newOffset;
        double pu = Math.max(prevUnc, 1.0);
        double nu = Math.max(newUnc, 1.0);
        double sigma = Math.sqrt(pu * pu + nu * nu);
        if (Math.abs(newOffset - prevOffset) > 3.0 * sigma) return newOffset;   // 真实跳变
        double wp = 1.0 / (pu * pu);
        double wn = 1.0 / (nu * nu);
        double wNew = wn / (wp + wn);
        // 下限 0.5：保证两轮内能跟上真实漂移（实测源质量差时单轮差异可达 60ms+，
        // 权重再低就会出现"显示值比真实值滞后几十毫秒且迟迟不收敛"）
        if (wNew < 0.5) wNew = 0.5;
        if (wNew > 0.9) wNew = 0.9;      // 上限：单次高置信也不完全丢弃历史
        return prevOffset + wNew * (newOffset - prevOffset);
    }

    /**
     * 加权最小二乘拟合 {@code offset = a + b·t}，其中 b 是频率偏差。
     * 用于走时补偿：{@code wall = base + dt·(1 + b)}。
     *
     * @param tSec   各样本的单调钟时间（秒）
     * @param offset 各样本的偏移（ms）
     * @param unc    各样本不确定度（ms），用于加权
     * @param n      样本数
     * @return {@code [driftPpm, residualRmsMs]}；样本不足或退化返回 {@code null}
     */
    public static double[] fitDrift(double[] tSec, double[] offset, double[] unc, int n) {
        if (n < 3) return null;
        double wsum = 0, tsum = 0, osum = 0;
        for (int i = 0; i < n; i++) {
            double u = Math.max(unc[i], 1.0);
            double w = 1.0 / (u * u);
            wsum += w;
            tsum += tSec[i] * w;
            osum += offset[i] * w;
        }
        if (wsum <= 0) return null;
        double tMean = tsum / wsum;
        double oMean = osum / wsum;
        double num = 0, den = 0;
        for (int i = 0; i < n; i++) {
            double u = Math.max(unc[i], 1.0);
            double w = 1.0 / (u * u);
            double dt = tSec[i] - tMean;
            num += w * dt * (offset[i] - oMean);
            den += w * dt * dt;
        }
        if (den <= 1e-9) return null;
        double b = num / den;              // ms 每秒
        double ppm = b * 1000.0;           // 1 ppm = 1e-3 ms/s
        double ss = 0, ws = 0;
        for (int i = 0; i < n; i++) {
            double u = Math.max(unc[i], 1.0);
            double w = 1.0 / (u * u);
            double pred = oMean + b * (tSec[i] - tMean);
            double r = offset[i] - pred;
            ss += w * r * r;
            ws += w;
        }
        return new double[]{ppm, Math.sqrt(ss / Math.max(ws, 1e-9))};
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

import com.wb.msclock.TimeMath;

/**
 * 校时纯算法单测（无 JUnit 依赖，纯 JDK 运行）。
 * 运行：见 tests/jvm/run_tests.sh
 */
public class TimeMathTest {
    private static int passed = 0, failed = 0;

    private static void check(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("  PASS " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }

    private static void near(String name, double got, double want, double tol) {
        check(name + " (got=" + String.format("%.2f", got) + ", want=" + String.format("%.2f", want) + ")",
                Math.abs(got - want) <= tol);
    }

    public static void main(String[] args) {
        System.out.println("[TimeMath] 中位数");
        near("奇数长度", TimeMath.median(new double[]{3, 1, 2}, 3), 2, 1e-9);
        near("偶数长度取中间平均", TimeMath.median(new double[]{1, 2, 3, 4}, 4), 2.5, 1e-9);
        near("单元素", TimeMath.median(new double[]{7}, 1), 7, 1e-9);

        System.out.println("[TimeMath] 加权合成");
        // 三个源 offset=100/101/102，unc 相同，参考 100，gate 350 → 全保留，均值 101
        near("等权均值", TimeMath.weightedCombine(
                new double[]{100, 101, 102}, new double[]{10, 10, 10}, 100, 350), 101, 1e-6);
        // 离群源 5000 被 gate=350 剔除，剩 100/101 → 100.5
        near("离群源剔除", TimeMath.weightedCombine(
                new double[]{100, 101, 5000}, new double[]{10, 10, 10}, 100, 350), 100.5, 1e-6);
        // unc 越小权重越高：offset=100(unc=8) 与 200(unc=100) → 应明显偏向 100
        // 注意 unc 有 8ms 下限（防止零不确定度导致权重爆炸）
        double w = TimeMath.weightedCombine(
                new double[]{100, 200}, new double[]{8, 100}, 100, 1000);
        check("精度加权偏向低unc (" + String.format("%.2f", w) + ") ", w > 100 && w < 105);
        // 全部超门限 → 回退全部，不返回 ref 导致信息丢失
        double fallback = TimeMath.weightedCombine(
                new double[]{5000, 6000}, new double[]{10, 10}, 100, 350);
        check("全踢掉时回退", Math.abs(fallback - 5500) < 1e-6);

        System.out.println("[TimeMath] 区间交集");
        // 三段区间 [0,100] [10,90] [20,80] → 交集 [20,80]，中点50 半宽30
        double[] a = TimeMath.intersect(
                new double[]{0, 10, 20}, new double[]{100, 90, 80}, 3);
        near("交集中点", a[0], 50, 1e-9);
        near("交集半宽", a[1], 30, 1e-9);
        // 空交集：最大共识回退，不应崩溃，半宽有限
        double[] b = TimeMath.intersect(
                new double[]{0, 100}, new double[]{50, 150}, 2);
        check("空交集回退有解", Double.isFinite(b[0]) && Double.isFinite(b[1]) && b[1] > 0);

        System.out.println("[TimeMath] NTP 时间戳");
        // 2020-01-01 00:00:00 UTC = Unix 1577836800000ms，NTP 秒 = +2208988800
        long ntpSec = 1577836800L + 2208988800L;
        byte[] ts = new byte[8];
        ts[0] = (byte) (ntpSec >> 24); ts[1] = (byte) (ntpSec >> 16);
        ts[2] = (byte) (ntpSec >> 8);  ts[3] = (byte) ntpSec;
        ts[4] = (byte) 0x80; ts[5] = 0; ts[6] = 0; ts[7] = 0;  // 分数 0.5
        near("NTP 64位时间戳", TimeMath.ntpTsToMs(ts, 0), 1577836800500.0, 1e-3);
        // 定点数 1.5 秒 = 0x00018000
        byte[] fx = {(byte) 0x00, (byte) 0x01, (byte) 0x80, (byte) 0x00};
        near("NTP 32位定点秒", TimeMath.ntpFixedToMs(fx, 0), 1500.0, 1e-9);

        System.out.println("[TimeMath] 时间格式化");
        // UTC 01:02:03.400 → 1970-01-01T01:02:03.400Z
        long t = (1 * 3600 + 2 * 60 + 3) * 1000L + 400;
        check("时分秒.十分位 UTC", "01:02:03.4".equals(TimeMath.formatClock(t, true, 0)));
        check("仅时分秒 UTC", "01:02:03".equals(TimeMath.formatClock(t, false, 0)));
        // 东八区 +8h → 09:02:03.4
        check("东八区偏移", "09:02:03.4".equals(TimeMath.formatClock(t, true, 8 * 3600)));
        // 跨日：86400000ms 为次日 00:00:00，前后各取一点验证回绕
        check("跨日前一秒", "23:59:59.9".equals(TimeMath.formatClock(86400000L - 100, true, 0)));
        check("跨日回绕", "00:00:00.9".equals(TimeMath.formatClock(86400000L + 900, true, 0)));
        // 负时间（1970 前）floorMod 仍应落在合法范围
        check("负时间不越界", TimeMath.formatClock(-1, false, 0).matches("\\d{2}:\\d{2}:\\d{2}"));

        System.out.println("[TimeMath] 校时结果融合（抗抖动）");
        // 无历史 → 直接采纳
        near("融合:首次直接采纳", TimeMath.fuseOffset(0, 0, -58.0, 25.0), -58.0, 1e-9);
        // 小差异噪声、两源 unc 相同 → 等权 0.5，融合值取中点
        double f1 = TimeMath.fuseOffset(-60.0, 25.0, -52.0, 25.0);
        check("融合:小差异取中间", f1 > -60.0 && f1 < -52.0);
        near("融合:等unc取中点", f1, -56.0, 1e-6);
        // 新值置信度低（unc 大）→ 权重被抬到下限 0.5，仍会跟随但不被旧值锁死
        double fLow = TimeMath.fuseOffset(-60.0, 20.0, -50.0, 200.0);
        near("融合:低置信权重下限0.5", fLow, -60.0 + 0.5 * 10.0, 1e-6);
        // 超 3σ 真实跳变 → 直接采纳新值
        // σ = sqrt(25²+25²) ≈ 35.36，3σ ≈ 106；跳 500ms 必须直采
        near("融合:大跳变直采", TimeMath.fuseOffset(-60.0, 25.0, 440.0, 25.0), 440.0, 1e-9);
        // 新值极高置信（unc=5）→ 权重被限到 0.9
        double f2 = TimeMath.fuseOffset(-60.0, 100.0, -50.0, 5.0);
        near("融合:高置信权重上限0.9", f2, -60.0 + 0.9 * 10.0, 1e-6);
        // 旧 unc 极大（低置信）→ 新值仍受 0.9 上限约束，不会完全丢弃历史
        check("融合:权重不超过0.9", Math.abs(f2 - (-50.0)) > 1e-6);

        System.out.println("[TimeMath] 频率偏差拟合（drift）");
        check("drift:样本不足返回null",
                TimeMath.fitDrift(new double[]{0, 60}, new double[]{0, 1.5}, new double[]{5, 5}, 2) == null);
        // 完美线性 25ppm：每秒偏移增加 0.025ms
        double[] t3 = {0, 60, 120};
        double[] o3 = {0, 1.5, 3.0};
        double[] u3 = {5, 5, 5};
        double[] d3 = TimeMath.fitDrift(t3, o3, u3, 3);
        check("drift:三样本有解", d3 != null);
        near("drift:25ppm 拟合准确", d3[0], 25.0, 0.5);
        near("drift:无噪声残差近零", d3[1], 0.0, 1e-6);
        // 零漂移
        double[] d0 = TimeMath.fitDrift(new double[]{0, 60, 120},
                new double[]{-60, -60, -60}, new double[]{5, 5, 5}, 3);
        near("drift:零漂移", d0[0], 0.0, 1e-6);
        // 含 ±3ms 噪声，斜率仍应接近真值
        double[] dN = TimeMath.fitDrift(new double[]{0, 60, 120, 180},
                new double[]{0, 4.5, 3.0, 7.5}, new double[]{5, 5, 5, 5}, 4);
        check("drift:含噪声仍有解", dN != null && Math.abs(dN[0] - 25.0) < 12.0);
        check("drift:残差为正", dN[1] > 0);
        // 加权：unc 大的离群点影响应更小
        double[] dW = TimeMath.fitDrift(new double[]{0, 60, 120},
                new double[]{0, 50.0, 3.0}, new double[]{5, 500, 5}, 3);
        check("drift:离群点被降权", Math.abs(dW[0] - 25.0) < 3.0);

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}

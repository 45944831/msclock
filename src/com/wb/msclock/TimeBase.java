package com.wb.msclock;

/**
 * 统一走时基准（纯 JVM，不依赖 Android API，可单测）。
 *
 * <p>锚定一次「墙钟 ↔ 单调钟」对应关系，之后只凭单调钟推进：
 * <pre>
 *     wall = anchorWall + (elapsed − anchorElapsed) × (1 + driftPpm × 1e-6)
 * </pre>
 * 悬浮窗与 MainActivity 共用同一个实例，确保两处显示完全一致——
 * 此前 Main 用 System.currentTimeMillis()+offset（无频偏），悬浮窗用带频偏的锚点，
 * 长时间运行后两者会分叉数百毫秒。
 *
 * <p>所有方法 synchronized：锚点由校时线程/UI 线程写入，显示由 Choreographer 读取。
 */
public final class TimeBase {

    private double anchorWallMs;
    private long anchorElapsedMs;
    private double driftPpm;
    private boolean anchored;

    /** 重置为未锚定状态（服务关闭时调用） */
    public synchronized void reset() {
        anchored = false;
        anchorWallMs = 0;
        anchorElapsedMs = 0;
        driftPpm = 0;
    }

    /** 用一对同时采样的墙钟/单调钟重新锚定 */
    public synchronized void anchor(double wallNowMs, long elapsedNowMs, double ppm) {
        anchorWallMs = wallNowMs;
        anchorElapsedMs = elapsedNowMs;
        driftPpm = ppm;
        anchored = true;
    }

    /** 当前校正后墙钟毫秒；未锚定时返回 wallNowMs 原样（调用方传系统墙钟） */
    public synchronized double at(long elapsedNowMs, double wallNowMs) {
        if (!anchored) return wallNowMs;
        return anchorWallMs + (elapsedNowMs - anchorElapsedMs) * (1.0 + driftPpm * 1e-6);
    }

    /** 用指定频偏计算校正后墙钟——供频偏改变时按旧斜率重锚、保证显示连续 */
    public synchronized double atWithDrift(long elapsedNowMs, double ppm) {
        if (!anchored) return Double.NaN;
        return anchorWallMs + (elapsedNowMs - anchorElapsedMs) * (1.0 + ppm * 1e-6);
    }

    public synchronized double getDriftPpm() {
        return driftPpm;
    }

    public synchronized void setDriftPpm(double ppm) {
        driftPpm = ppm;
    }

    public synchronized boolean isAnchored() {
        return anchored;
    }

    public synchronized double getAnchorWallMs() {
        return anchorWallMs;
    }

    public synchronized long getAnchorElapsedMs() {
        return anchorElapsedMs;
    }
}

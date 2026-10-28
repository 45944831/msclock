package com.wb.msclock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.util.TimeZone;

/**
 * 只显示时间的悬浮控件：时:分:秒 . 毫秒
 * 用 Canvas 自绘，毫秒位每帧刷新，字体等宽不抖动。
 * 帧驱动由悬浮窗服务统一调度，这里不自行失效，避免双重刷新。
 */
public class ClockView extends View {

    public interface Now {
        double ms();
    }

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTime = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pMs = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float padH, padV, gap, radius;
    private float wTime, wMs, baseline;
    private float uiScale = 1f;   // 悬浮框整体缩放：背景+字体等比

    // 绘制全程零分配：时分秒与毫秒都写入复用字符缓冲
    private final char[] hms = {'0', '0', ':', '0', '0', ':', '0', '0'};
    private final char[] ms = {'.', '0', '0', '0'};
    private long lastSec = Long.MIN_VALUE;

    private Now source;

    public ClockView(Context c) {
        this(c, null);
    }

    public ClockView(Context c, AttributeSet a) {
        super(c, a);
        pBg.setColor(0xF2111419);
        pBg.setStyle(Paint.Style.FILL);

        Typeface mono = Typeface.create("monospace", Typeface.BOLD);
        pTime.setColor(0xFFF2F5FA);
        pTime.setTypeface(mono);
        pTime.setSubpixelText(false);
        pMs.setColor(0xFF3DDC84);
        pMs.setTypeface(mono);
        pMs.setSubpixelText(false);
        applyScale();
    }

    /** 设置悬浮框整体缩放（0.5~2.0），背景与文字等比变化，窗口尺寸随之重排 */
    public void setUiScale(float s) {
        uiScale = Math.max(0.5f, Math.min(2f, s));
        applyScale();
        requestLayout();
        invalidate();
    }

    public float getUiScale() {
        return uiScale;
    }

    /** 100% 缩放下的内容宽高：不依赖当前布局结果，供按屏幕计算最大缩放 */
    public float getBaseWidth() {
        return (padH * 2 + wTime + gap + wMs) / uiScale;
    }

    public float getBaseHeight() {
        Paint.FontMetrics fm = pTime.getFontMetrics();
        return (padV * 2 + (fm.descent - fm.ascent)) / uiScale;
    }

    private void applyScale() {
        Context c = getContext();
        float d = c.getResources().getDisplayMetrics().density;
        float sd = c.getResources().getDisplayMetrics().scaledDensity;
        pTime.setTextSize(29f * sd * uiScale);
        pMs.setTextSize(18f * sd * uiScale);
        padH = 14f * d * uiScale;
        padV = 7f * d * uiScale;
        gap = 1.5f * d * uiScale;
        radius = 12f * d * uiScale;
        wTime = pTime.measureText("00:00:00");
        wMs = pMs.measureText(".000");
        Paint.FontMetrics fm = pTime.getFontMetrics();
        baseline = -fm.ascent;
    }

    public void setSource(Now n) {
        source = n;
        lastSec = Long.MIN_VALUE;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = (int) Math.ceil(padH * 2 + wTime + gap + wMs);
        Paint.FontMetrics fm = pTime.getFontMetrics();
        int h = (int) Math.ceil(padV * 2 + (fm.descent - fm.ascent));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = source != null
                ? (long) Math.floor(source.ms())
                : System.currentTimeMillis();
        long sec = Math.floorDiv(now, 1000L);
        if (sec != lastSec) {
            lastSec = sec;
            fillHms(sec);
        }
        int milli = (int) (now - sec * 1000L);   // floorDiv 保证落在 0..999
        ms[1] = (char) ('0' + milli / 100);
        ms[2] = (char) ('0' + (milli / 10) % 10);
        ms[3] = (char) ('0' + milli % 10);

        int w = getWidth(), h = getHeight();
        rect.set(0, 0, w, h);
        c.drawRoundRect(rect, radius, radius, pBg);

        float x = padH;
        float y = padV + baseline;
        c.drawText(hms, 0, 8, x, y, pTime);
        c.drawText(ms, 0, 4, x + wTime + gap, y, pMs);
    }

    /** 把本地时:分:秒写入复用缓冲，每秒仅执行一次 */
    private void fillHms(long epochSec) {
        long local = epochSec + TimeZone.getDefault().getOffset(epochSec * 1000L) / 1000L;
        int s = (int) Math.floorMod(local, 86400L);
        int hh = s / 3600, mm = (s % 3600) / 60, ss = s % 60;
        hms[0] = (char) ('0' + hh / 10);
        hms[1] = (char) ('0' + hh % 10);
        hms[3] = (char) ('0' + mm / 10);
        hms[4] = (char) ('0' + mm % 10);
        hms[6] = (char) ('0' + ss / 10);
        hms[7] = (char) ('0' + ss % 10);
    }

    public void setAccent(int color) {
        pMs.setColor(color);
        invalidate();
    }
}

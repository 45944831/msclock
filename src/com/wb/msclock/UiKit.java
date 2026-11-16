package com.wb.msclock;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 通用 UI 构建工具（从 MainActivity 抽出）。
 * 无自身状态，所有方法接收 Context 与显式参数，便于复用与主界面瘦身。
 */
public final class UiKit {
    private UiKit() { }

    /** 通用白卡容器 */
    public static LinearLayout card(Context c, int cardColor, float d) {
        LinearLayout view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackground(bg(c, cardColor, 14 * d, 1));
        view.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (14 * d));
        return view;
    }

    public static TextView sectionLabel(Context c, int textColor, String s, float d) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextColor(textColor);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (18 * d);
        lp.bottomMargin = (int) (10 * d);
        tv.setLayoutParams(lp);
        return tv;
    }

    public static TextView bodyText(Context c, int textColor, String s, int size) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(size);
        tv.setTextColor(textColor);
        return tv;
    }

    public static TextView pillButton(Context c, int textColor, String s, float d,
                                      View.OnClickListener l) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(18);
        tv.setTextColor(textColor);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(bg(c, 0xFFF2F5FA, 100 * d, 0));
        tv.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                (int) (34 * d), (int) (34 * d));
        lp.leftMargin = (int) (8 * d);
        tv.setLayoutParams(lp);
        return tv;
    }

    public static GradientDrawable bg(Context c, int color, float radius, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (strokeColor != 0) {
            g.setStroke(Math.max(1, (int) (c.getResources().getDisplayMetrics().density)),
                    strokeColor);
        }
        return g;
    }

    public static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }
}

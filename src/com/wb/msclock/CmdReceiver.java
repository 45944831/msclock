package com.wb.msclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/**
 * 外部启停入口，仅用于 adb / 自动化。
 * 清单中声明 android:permission="android.permission.DUMP"，普通应用发送会被系统拦截，
 * 只有 shell(adb)/root 这类持有 DUMP 的调用方能生效，避免任意应用启停悬浮钟。
 */
public class CmdReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.wb.msclock.action.CMD";

    @Override
    public void onReceive(Context c, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        String cmd = intent.getStringExtra("cmd");
        android.util.Log.d("MSClock", "CmdReceiver cmd=" + cmd);
        if ("start".equals(cmd)) {
            if (!Settings.canDrawOverlays(c)) return;
            FloatingClockService.userClosed = false;
            Intent i = new Intent(c, FloatingClockService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                FloatingClockService.fgStartPending = true;
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } else if ("stop".equals(cmd)) {
            FloatingClockService.userClosed = true;
            c.stopService(new Intent(c, FloatingClockService.class));
        }
    }
}

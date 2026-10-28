# -*- coding: utf-8 -*-
"""异常/兼容：权限撤销与授权流、锁屏、后台、深色、字体、旋转、通知权限"""
import sys, os, time, re, subprocess, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import deep_test as D
import test_ui as U
from pngcolor import load_png

PKG = U.PKG
R = []
sh = U.sh


def rec(name, ok, note="", warn=False):
    st = "WARN" if warn else ("PASS" if ok else "FAIL")
    R.append((name, st, note))
    print(("[WARN] " if warn else ("[PASS] " if ok else "[FAIL] ")) + name + "  " + note, flush=True)


TOGGLE = (1037, 936)   # ColorOS 悬浮窗权限页右上开关


def can_overlay():
    return "allow" in sh(["shell", "appops", "get", PKG, "SYSTEM_ALERT_WINDOW"])


def set_perm(want):
    """ColorOS 禁止 shell 直接 appops set，改走设置页开关"""
    if can_overlay() == want:
        return
    sh(["shell", "am", "start", "-a", "android.settings.MANAGE_APP_OVERLAY_PERMISSION",
        "-d", "package:" + PKG]); time.sleep(2)
    sh(["shell", "input", "tap", str(TOGGLE[0]), str(TOGGLE[1])]); time.sleep(2.2)
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(1.5)


def wake():
    sh(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]); time.sleep(0.8)
    state = sh(["shell", "dumpsys", "power"])
    if "mWakefulness=Awake" not in state:
        sh(["shell", "input", "keyevent", "KEYCODE_POWER"]); time.sleep(1)
    sh(["shell", "input", "swipe", "540", "2000", "540", "800", "300"]); time.sleep(1)


def cpu_pct():
    out = sh(["shell", "dumpsys", "cpuinfo"])
    for l in out.splitlines():
        if PKG in l:
            m = re.search(r"([\d.]+)%", l)
            if m:
                return m.group(1)
    return None


def start_overlay():
    bt = U.toggle_top()
    if bt > 0:
        U.main_toggle(bt)
    time.sleep(3)
    return D.service_running() and D.overlay_frame() is not None


def main():
    print("=== 异常/兼容测试 %s ===" % time.strftime("%H:%M:%S"))
    set_perm(True)
    sh(["shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS"])
    sh(["shell", "am", "force-stop", PKG]); time.sleep(1.2)
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2.5)

    # 1) 运行中撤销悬浮窗权限
    ok = start_overlay()
    rec("E01 前置：悬浮窗已开启", ok, str(D.overlay_frame()))
    set_perm(False); time.sleep(3)
    svc = D.service_running(); f = D.overlay_frame(); perm = can_overlay()
    pic = U.shot("_q5_revoke.png")
    red = U.red_top(pic)
    rec("E02 撤销权限后窗口移除", f is None,
        "svc=%s frame=%s perm=%s 按钮顶(红)=%s" % (svc, f, perm, red))
    state_bad = svc and f is None and red > 0
    rec("E03 撤销权限后状态无残留假运行", not state_bad,
        "服务存活=%s 但按钮红色(显示关闭)=%s" % (svc, red > 0), warn=state_bad)
    set_perm(True); time.sleep(1)
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "stop"]); time.sleep(1.2)
    ok2 = start_overlay()
    rec("E04 恢复权限后可重新开启", ok2, str(D.overlay_frame()))
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "stop"]); time.sleep(1.2)

    # 2) 无权限时点开启 → 引导授权页
    set_perm(False); time.sleep(0.5)
    sh(["shell", "am", "force-stop", PKG]); time.sleep(1)
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2.5)
    bt = U.toggle_top()
    if bt > 0:
        U.main_toggle(bt); time.sleep(2.5)
    top = D.top_activity()
    rec("E05 无权限点击开启引导授权页", PKG not in top and bool(D.pid()), top[:80])
    U.back(); time.sleep(1.5)
    set_perm(True); time.sleep(0.5)
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(1)
    ok3 = start_overlay()
    rec("E06 授权后再次点击可开启", ok3, "")

    # 3) 锁屏/亮屏
    pid = D.pid()
    before = D.overlay_frame()
    sh(["shell", "input", "keyevent", "KEYCODE_POWER"]); time.sleep(5)
    cpu_off = cpu_pct()
    sh(["shell", "input", "keyevent", "KEYCODE_POWER"]); time.sleep(1)
    wake()
    time.sleep(1.5)
    after = D.overlay_frame()
    pic = U.shot("_q5_wake.png")
    alive = D.service_running() and bool(D.pid())
    rec("E07 锁屏/亮屏后悬浮钟存活", alive and after is not None,
        "before=%s after=%s" % (before, after))
    rec("E08 锁屏期间 CPU 采样", True, "屏幕关闭时进程CPU=%s" % cpu_off, warn=False)

    # 4) 退到桌面后台悬浮窗仍显示
    sh(["shell", "input", "keyevent", "KEYCODE_HOME"]); time.sleep(2)
    f = D.overlay_frame()
    blk = D._overlay_block(sh(["shell", "dumpsys", "window", "windows"]))
    vis = bool(blk) and "isVisible=true" in blk
    U.shot("_q5_home.png")
    # 悬浮窗可能停在屏幕任意位置（位置已持久化），不能固定扫描 y=300..800
    rec("E09 桌面上悬浮窗仍显示", f is not None and vis,
        "frame=%s isVisible=%s" % (f, vis))
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(1.5)

    # 5) 深色模式
    sh(["shell", "cmd", "uimode", "night", "yes"]); time.sleep(1.5)
    sh(["shell", "am", "force-stop", PKG]); time.sleep(1)
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2.5)
    bt = U.toggle_top()
    rec("E10 深色模式界面正常", bt > 0 and bool(D.pid()), "按钮顶=%d" % bt)
    sh(["shell", "cmd", "uimode", "night", "no"]); time.sleep(1.5)

    # 6) 字体放大 1.3
    sh(["shell", "settings", "put", "system", "font_scale", "1.3"]); time.sleep(1.5)
    sh(["shell", "am", "force-stop", PKG]); time.sleep(1)
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(3)
    bt = U.toggle_top()
    s = U.shot("_q5_font.png")
    rows = [x for x in D.white_bands(s) if x[1] - x[0] > 80]
    rec("E11 字体放大1.3倍界面可用", bt > 0 and len(rows) >= 3,
        "按钮顶=%d 卡片数=%d" % (bt, len(rows)))
    sh(["shell", "settings", "put", "system", "font_scale", "1.0"]); time.sleep(1.5)
    sh(["shell", "am", "force-stop", PKG]); time.sleep(1)
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2.5)

    # 7) 屏幕旋转（Activity 锁定竖屏；悬浮窗跟随显示旋转）
    ok = start_overlay()
    r0 = U.rect()
    sh(["shell", "settings", "put", "system", "accelerometer_rotation", "0"])
    sh(["shell", "settings", "put", "system", "user_rotation", "1"]); time.sleep(3)
    disp = sh(["shell", "dumpsys", "display"])
    rot = re.search(r"mCurrentOrientation=(\d+)", disp)
    top = D.top_activity()
    r1 = U.rect()
    rec("E12 旋转屏幕后悬浮钟仍存活", ok and D.service_running() and r1 is not None,
        "rot=%s top=%s frame=%s->%s" % (rot.group(1) if rot else "?", top[:40], r0, r1))
    sh(["shell", "settings", "put", "system", "user_rotation", "0"])
    sh(["shell", "settings", "put", "system", "accelerometer_rotation", "1"]); time.sleep(2)

    # 8) 通知权限撤销
    noti0 = sh(["shell", "dumpsys", "notification", "--noredact"]).count(PKG)
    sh(["shell", "pm", "revoke", PKG, "android.permission.POST_NOTIFICATIONS"]); time.sleep(2)
    svc = D.service_running(); f = D.overlay_frame()
    noti1 = sh(["shell", "dumpsys", "notification", "--noredact"]).count(PKG)
    rec("E13 撤销通知权限后悬浮钟仍运行", svc and f is not None,
        "svc=%s frame=%s noti引用=%d->%d" % (svc, f, noti0, noti1))
    sh(["shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS"]); time.sleep(1)

    # 收尾
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "stop"]); time.sleep(1.2)
    sh(["shell", "settings", "put", "system", "user_rotation", "0"])
    sh(["shell", "settings", "put", "system", "accelerometer_rotation", "1"])
    sh(["shell", "settings", "put", "system", "font_scale", "1.0"])
    sh(["shell", "cmd", "uimode", "night", "no"])
    set_perm(True)
    crash = sh(["shell", "logcat", "-d", "-b", "crash"]).count(PKG)
    rec("E14 异常专项无崩溃", crash == 0, "crash=%d" % crash)
    with open(os.path.join(D.DIR, "_q5_edge_results.json"), "w", encoding="utf-8") as f:
        json.dump(R, f, ensure_ascii=False, indent=1)
    n = sum(1 for _, s, _ in R if s == "PASS"); fl = sum(1 for _, s, _ in R if s == "FAIL")
    print("=== 总计 %d: PASS %d / FAIL %d / WARN %d ===" % (len(R), n, fl, len(R) - n - fl))


if __name__ == "__main__":
    main()

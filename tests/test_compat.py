# -*- coding: utf-8 -*-
"""兼容性补测：桌面/他应用覆盖、锁屏亮屏、最近任务清除、通知撤销、强停"""
import sys, os, time, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import deep_test as D
import test_ui as U

PKG = U.PKG
R = []
sh = U.sh
fails = 0


def rec(name, ok, note="", warn=False):
    global fails
    if not ok and not warn:
        fails += 1
    st = "WARN" if warn else ("PASS" if ok else "FAIL")
    R.append((name, st, note))
    print(("[WARN] " if warn else ("[PASS] " if ok else "[FAIL] ")) + name + "  " + note, flush=True)


def overlay_visible():
    out = sh(["shell", "dumpsys", "window", "windows"])
    b = D._overlay_block(out)
    return bool(b) and "isVisible=true" in b


def dark_box_on_screen(tag):
    p = U.shot(tag)
    try:
        return D.clock_dark_box(p)
    except Exception:
        return None


def noti_count():
    return sh(["shell", "dumpsys", "notification", "--noredact"]).count(PKG)


def main():
    print("=== 兼容性补测 %s ===" % time.strftime("%H:%M:%S"))
    sh(["shell", "logcat", "-b", "crash", "-c"])
    sh(["shell", "am", "force-stop", "com.weaknet.app"])
    if "allow" not in sh(["shell", "appops", "get", PKG, "SYSTEM_ALERT_WINDOW"]):
        print("前置权限非 allow，跳过")
        return
    ok = D.ensure_overlay()
    before = D.overlay_frame()
    rec("C00 前置悬浮钟运行", ok and before is not None, "frame=%s" % (before,))
    if not ok:
        return
    # 位置会持久化，先把悬浮钟拖回上方，深色框检测区域(box_y=300..800)才覆盖得到
    U.drag_overlay(300, 500); time.sleep(0.8)

    # 1) 回桌面
    sh(["shell", "input", "keyevent", "KEYCODE_HOME"]); time.sleep(2.5)
    vis = overlay_visible()
    db = dark_box_on_screen("_q5_home2.png")
    rec("C01 桌面背景下悬浮钟可见", vis and db is not None, "visible=%s dark=%s" % (vis, db))

    # 2) 其他应用之上
    sh(["shell", "am", "start", "-a", "android.settings.SETTINGS"]); time.sleep(2.5)
    top = D.top_activity()
    vis = overlay_visible()
    db = dark_box_on_screen("_q5_other_app.png")
    rec("C02 系统设置页之上悬浮钟可见", "settings" in top.lower() and vis and db is not None,
        "top=%s visible=%s dark=%s" % (top[:50], vis, db))

    # 3) 锁屏→亮屏解锁
    sh(["shell", "input", "keyevent", "KEYCODE_SLEEP"]); time.sleep(8)
    off_svc = D.service_running()
    off_pid = D.pid()
    sh(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]); time.sleep(1.2)
    sh(["shell", "wm", "dismiss-keyguard"]); time.sleep(1.5)
    sh(["shell", "input", "swipe", "540", "1800", "540", "600", "200"]); time.sleep(1.5)
    after = D.overlay_frame()
    vis = overlay_visible()
    alive = D.service_running() and bool(D.pid())
    rec("C03 锁屏8s后亮屏解锁悬浮钟存活", off_svc and alive and vis,
        "熄屏svc=%s pid=%s 亮屏svc=%s visible=%s frame=%s->%s" % (off_svc, off_pid, alive, vis, before, after))
    rec("C03b 亮屏后窗口位置未漂移", after == before, "%s -> %s" % (before, after))

    # 4) 最近任务清除
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2)
    sh(["shell", "logcat", "-c"])
    sh(["shell", "input", "keyevent", "KEYCODE_APP_SWITCH"]); time.sleep(1.5)
    sh(["shell", "input", "swipe", "540", "1600", "540", "400", "250"]); time.sleep(3)
    svc = D.service_running()
    vis = overlay_visible()
    killed = "Force stopping " + PKG in sh(["shell", "logcat", "-d"])
    rec("C04 最近任务划掉后悬浮钟存活", svc and vis,
        "svc=%s visible=%s 系统日志force-stop=%s（ColorOS 上划清除即强停，START_STICKY 无效）"
        % (svc, vis, killed), warn=True)
    if not (svc and vis):
        D.ensure_overlay(); time.sleep(1)

    # 5) 通知存在 + 撤销通知权限
    n0 = noti_count()
    sh(["shell", "pm", "revoke", PKG, "android.permission.POST_NOTIFICATIONS"]); time.sleep(2)
    vis = overlay_visible()
    svc = D.service_running()
    n1 = noti_count()
    rec("C05 撤销通知权限后悬浮钟仍显示", svc and vis, "noti=%d->%d svc=%s visible=%s" % (n0, n1, svc, vis))
    rec("C05b 前台通知确实存在", n0 > 0, "noti引用=%d" % n0)
    sh(["shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS"]); time.sleep(1)

    # 6) 强停进程（系统级）→ 服务与窗口应一并消失
    sh(["shell", "am", "force-stop", PKG]); time.sleep(2)
    svc = D.service_running()
    vis = overlay_visible()
    rec("C06 force-stop 后服务与窗口清理干净", not svc and not vis, "svc=%s visible=%s" % (svc, vis))

    # 7) 冷启动恢复
    ok = D.ensure_overlay()
    rec("C07 冷启动后再次开启正常", ok, "frame=%s" % (D.overlay_frame(),))

    sh(["shell", "logcat", "-d", "-b", "crash", "-o", "file", os.path.join(D.DIR, "_q5_crash.txt")])
    crash = sh(["shell", "logcat", "-d", "-b", "crash"]).count(PKG)
    rec("C08 兼容性专项无崩溃", crash == 0, "crash=%d" % crash)

    D.stop_overlay()
    with open(os.path.join(D.DIR, "_q5_compat_results.json"), "w", encoding="utf-8") as f:
        json.dump(R, f, ensure_ascii=False, indent=1)
    n = sum(1 for _, s, _ in R if s == "PASS"); fl = sum(1 for _, s, _ in R if s == "FAIL")
    print("=== 总计 %d: PASS %d / FAIL %d / WARN %d ===" % (len(R), n, fl, len(R) - n - fl))


if __name__ == "__main__":
    main()

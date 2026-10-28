# -*- coding: utf-8 -*-
"""权限专项：无权限引导授权、运行中撤销、恢复、状态一致性"""
import sys, os, time, re, subprocess, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import deep_test as D
import test_ui as U

PKG = U.PKG
R = []
sh = U.sh
TOGGLE = (1037, 936)


def rec(name, ok, note="", warn=False):
    st = "WARN" if warn else ("PASS" if ok else "FAIL")
    R.append((name, st, note))
    print(("[WARN] " if warn else ("[PASS] " if ok else "[FAIL] ")) + name + "  " + note, flush=True)


def op():
    o = sh(["shell", "appops", "get", PKG, "SYSTEM_ALERT_WINDOW"])
    return "allow" if "allow" in o else ("deny" if "deny" in o else "?")


def open_perm():
    sh(["shell", "am", "force-stop", "com.weaknet.app"])
    sh(["shell", "am", "start", "-a", "android.settings.MANAGE_APP_OVERLAY_PERMISSION",
        "-d", "package:" + PKG])
    time.sleep(2)
    top = D.top_activity()
    return "settings" in top.lower()


def toggle_perm():
    open_perm()
    sh(["shell", "input", "tap", str(TOGGLE[0]), str(TOGGLE[1])])
    time.sleep(2)
    return op()


def overlay_visible():
    """窗口记录可能仍在，但授权被撤销后系统置 mPolicyVisibility=false/isVisible=false"""
    out = sh(["shell", "dumpsys", "window", "windows"])
    b = D._overlay_block(out)
    return bool(b) and "isVisible=true" in b


def go_app(tries=4):
    """冷启动 ColorOS 上偶发被 launcher 抢焦点，带重试直到主界面按钮可见"""
    for _ in range(tries):
        sh(["shell", "am", "start", "-W", "-n", PKG + "/.MainActivity"])
        time.sleep(1.5)
        if PKG in D.top_activity() and U.toggle_top() > 0:
            time.sleep(0.8)
            return True
        sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1)
    return False


def main():
    print("=== 权限专项 %s ===" % time.strftime("%H:%M:%S"))
    sh(["shell", "logcat", "-b", "crash", "-c"])
    sh(["shell", "am", "force-stop", PKG]); time.sleep(1)
    # 确保初始为 deny（上一步已通过设置页关闭）
    if op() != "deny":
        toggle_perm()
    rec("P00 前置权限已关闭", op() == "deny", "appops=%s" % op())

    # 1) 无权限点开启 → 引导到系统授权页，且服务不启动
    sh(["shell", "am", "force-stop", PKG]); time.sleep(1)
    go_app(); time.sleep(1.5)
    bt = U.toggle_top()
    U.main_toggle(bt); time.sleep(2.5)
    top = D.top_activity()
    rec("P01 无权限点开启引导授权页", PKG not in top and "settings" in top.lower(), top[:80])
    rec("P02 无权限时不会误启动服务", not D.service_running(), "svc=%s" % D.service_running())
    sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1.2)

    # 2) 授权后再次点击可开启
    new_op = toggle_perm()
    rec("P03 设置页重新授权", new_op == "allow", "appops=%s" % new_op)
    sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1)
    go_app(); time.sleep(1.2)
    bt = U.toggle_top()
    U.main_toggle(bt); time.sleep(3)
    f = D.overlay_frame()
    rec("P04 授权后点击开启成功", D.service_running() and overlay_visible(), "frame=%s" % (f,))

    # 3) 运行中撤销权限
    new_op = toggle_perm()          # 悬浮窗在顶部 y467-611，不会挡住 y936 开关
    time.sleep(2.5)
    svc = D.service_running()
    vis = overlay_visible()
    rec("P05 运行中撤销权限后窗口不可见", new_op == "deny" and not vis,
        "appops=%s visible=%s" % (new_op, vis))
    rec("P05b 撤销后服务/前台通知应自停或提示", not svc,
        "svc=%s（窗口已被系统隐藏，服务仍在跑=假运行风险）" % svc, warn=svc)
    sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1.5)
    go_app(); time.sleep(1.2)
    bt = U.toggle_top()
    red = U.red_top(U.shot("_q6_revoked_app.png"))
    rec("P06 撤销后主界面有状态提示", red <= 0,
        "服务=%s 主按钮红=%s(顶=%s)，无授权提示" % (svc, red > 0, bt), warn=(svc and red > 0))
    # 4) 恢复权限并重启悬浮钟
    new_op = toggle_perm()
    sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1)
    sh(["shell", "am", "force-stop", PKG]); time.sleep(1)   # 清掉旧服务，验证全新启动
    go_app(); time.sleep(1)
    bt = U.toggle_top(); U.main_toggle(bt); time.sleep(3)
    rec("P07 恢复权限后可正常开启", new_op == "allow" and D.service_running() and overlay_visible(),
        "appops=%s svc=%s visible=%s" % (new_op, D.service_running(), overlay_visible()))
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "stop"]); time.sleep(1.2)
    crash = sh(["shell", "logcat", "-d", "-b", "crash"]).count(PKG)
    rec("P08 权限专项无崩溃", crash == 0, "crash=%d" % crash)
    # 保证最终权限为 allow
    if op() != "allow":
        toggle_perm()
    with open(os.path.join(D.DIR, "_q6_perm_results.json"), "w", encoding="utf-8") as f:
        json.dump(R, f, ensure_ascii=False, indent=1)
    n = sum(1 for _, s, _ in R if s == "PASS"); fl = sum(1 for _, s, _ in R if s == "FAIL")
    print("=== 总计 %d: PASS %d / FAIL %d / WARN %d ===" % (len(R), n, fl, len(R) - n - fl))


if __name__ == "__main__":
    main()

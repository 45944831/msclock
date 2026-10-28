# -*- coding: utf-8 -*-
"""外观专项：reset / 50-200% / alpha / 持久化 / 遮挡缺陷（可靠导航版）"""
import sys, os, time, re, subprocess, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import deep_test as D
import test_ui as U
sh = U.sh

PKG = U.PKG
R = []


def rec(name, ok, note="", warn=False):
    st = "WARN" if warn else ("PASS" if ok else "FAIL")
    R.append((name, st, note))
    print(("[WARN] " if warn else ("[PASS] " if ok else "[FAIL] ")) + name + "  " + note, flush=True)


def on_settings(bt):
    """进入设置页并确认渲染（滑杆应在 y≈471/700）"""
    for _ in range(3):
        U.goto_settings(bt)
        s = U.shot("_q3_set.png")
        seeks = D.find_seeks(s)
        if len(seeks) == 2 and abs(seeks[0] - 471) < 30 and abs(seeks[1] - 700) < 30:
            return seeks
        U.back()
        bt = U.toggle_top()
    return D.find_seeks(s)


def back_main():
    U.back()
    return U.toggle_top()


def main():
    print("=== 外观专项 %s ===" % time.strftime("%H:%M:%S"))
    # 清空状态并重启
    U.sh(["shell", "am", "force-stop", PKG]); time.sleep(1.2)
    U.sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2.5)
    bt = U.toggle_top()

    # 1) 恢复默认并验证
    seeks = on_settings(bt)
    rec("A00 设置页渲染", len(seeks) == 2, str(seeks))
    D.tap(540, U.Y_RESET); time.sleep(1.3)
    U.back()
    bt = U.toggle_top()
    U.main_toggle(bt); time.sleep(3)
    f = D.overlay_frame(); a = D.window_alpha()
    rec("A01 恢复默认并启动(100%/100%)", f is not None and abs(f[0] - 634) < 25 and a and a > 0.98,
        "frame=%s alpha=%s" % (f, a))

    # 2) 把悬浮窗移到底部，避免遮挡设置页
    U.drag_overlay(300, 2050)
    bt = U.toggle_top()
    seeks = on_settings(bt)
    rec("A02 移开悬浮窗后可进入设置页", len(seeks) == 2 and abs(seeks[0] - 471) < 30, str(seeks))

    # 3) 大小 50/100/200/60
    res = {}
    for ratio, tag in [(0.0, "50"), (50 / 150, "100"), (1.0, "200"), (10 / 150, "60")]:
        U.set_slider(U.Y_SIZE, ratio)
        f = D.overlay_frame(); res[tag] = f[0] if f else -1
    rec("A03 大小 50/100/200/60% 生效",
        abs(res["50"] - 317) < 25 and abs(res["100"] - 634) < 25
        and abs(res["200"] - 1056) < 70 and res["200"] <= 1080 and abs(res["60"] - 380) < 25, str(res))

    # 4) 透明度
    U.set_slider(U.Y_ALPHA, 1.0); a100 = D.window_alpha()
    U.set_slider(U.Y_ALPHA, 0.0); a30 = D.window_alpha()
    U.set_slider(U.Y_ALPHA, (0.35 - 0.3) / 0.7); a35 = D.window_alpha()
    rec("A04 透明度 100/30/35% 生效",
        a100 and a100 > 0.98 and a30 and abs(a30 - 0.30) < 0.02 and a35 and abs(a35 - 0.349) < 0.02,
        "100=%s 30=%s 35=%s" % (a100, a30, a35))

    # 5) 恢复默认
    D.tap(540, U.Y_RESET); time.sleep(1.5)
    f = D.overlay_frame(); a = D.window_alpha()
    rec("A05 恢复默认按钮(100/100)", f is not None and abs(f[0] - 634) < 25 and a and a > 0.98,
        "frame=%s alpha=%s" % (f, a))

    # 6) 持久化 60/35
    U.set_slider(U.Y_SIZE, 10 / 150); U.set_slider(U.Y_ALPHA, (0.35 - 0.3) / 0.7)
    f0 = D.overlay_frame(); a0 = D.window_alpha()
    U.sh(["shell", "am", "force-stop", PKG]); time.sleep(1.5)
    U.sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2.5)
    bt = U.toggle_top(); U.main_toggle(bt); time.sleep(3)
    f1 = D.overlay_frame(); a1 = D.window_alpha()
    rec("A06 外观持久化(重启后仍为60%/35%)",
        f1 is not None and abs(f1[0] - 380) < 25 and a1 and abs(a1 - 0.349) < 0.03,
        "before=%s/%s after=%s/%s" % (f0, a0, f1, a1))

    # 7) 位置持久化：拖到自定义位置后重启服务仍在原处，且不会跑出屏幕
    U.sh(["shell", "am", "force-stop", PKG]); time.sleep(1.2)
    U.sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2.5)
    bt = U.toggle_top(); on_settings(bt)
    D.tap(540, U.Y_RESET); time.sleep(1.2); U.back()
    bt = U.toggle_top(); U.main_toggle(bt); time.sleep(3)
    U.drag_overlay(300, 1900); time.sleep(0.8)
    r0 = U.rect()
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "stop"]); time.sleep(1.5)
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "start"]); time.sleep(3)
    r1 = U.rect()
    same = r0 and r1 and abs(r0[0] - r1[0]) < 6 and abs(r0[1] - r1[1]) < 6
    onscreen = r1 and r1[0] >= 0 and r1[1] >= 0 and r1[2] <= 1080 and r1[3] <= 2400
    rec("A07 拖动位置持久化且重启后仍在屏幕内", bool(same and onscreen),
        "重启前=%s 重启后=%s" % (r0, r1))

    # 收尾：100% + 停止 + 主界面
    D.tap(540, U.Y_RESET); time.sleep(1.2); U.back()
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "stop"]); time.sleep(1.5)
    crash = U.sh(["shell", "logcat", "-d", "-b", "crash"]).count(PKG)
    rec("A08 外观专项无崩溃", crash == 0, "crash=%d" % crash)
    with open(os.path.join(D.DIR, "_q3_appearance_results.json"), "w", encoding="utf-8") as f:
        json.dump(R, f, ensure_ascii=False, indent=1)
    n = sum(1 for _, s, _ in R if s == "PASS"); fl = sum(1 for _, s, _ in R if s == "FAIL")
    print("=== 总计 %d: PASS %d / FAIL %d / WARN %d ===" % (len(R), n, fl, len(R) - n - fl))


if __name__ == "__main__":
    main()

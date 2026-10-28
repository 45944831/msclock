# -*- coding: utf-8 -*-
"""功能 + 悬浮窗测试集（修正版：动态锚点、外观测试先移开悬浮窗）"""
import sys, os, time, re, subprocess, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import deep_test as D
from pngcolor import load_png

PKG = "com.wb.msclock"
R = []
Y_SIZE, Y_ALPHA, Y_RESET = 471, 700, 1066
X0, TW = D.TRACK_X0, D.TRACK_W


def rec(name, ok, note="", warn=False):
    st = "WARN" if warn else ("PASS" if ok else "FAIL")
    R.append((name, st, note))
    print(("[WARN] " if warn else ("[PASS] " if ok else "[FAIL] ")) + name + "  " + note, flush=True)


def sh(a, t=40):
    return subprocess.run(["adb"] + a, capture_output=True, text=True, timeout=t).stdout


def shot(n):
    p = os.path.join(D.DIR, n)
    with open(p, "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=25).stdout)
    return p


def red_top(path):
    w, h, nch, buf = load_png(path)
    for y in range(250, 1200):
        c = 0
        for x in range(80, 1000, 4):
            i = y * w * nch + x * nch
            r, g, b = buf[i], buf[i + 1], buf[i + 2]
            if r > 160 and g < 120 and b < 150 and r > g + 70:
                c += 1
        if c > 150:
            return y
    return -1


def toggle_top():
    """主界面大按钮顶部（蓝=未开，红=已开）"""
    p = shot("_q2_toggle.png")
    bt = D.blue_top(p)
    return bt if bt > 0 else red_top(p)


def main_toggle(bt):
    D.tap(540, bt + 150)


def ensure_app():
    """同机并行跑其他测试时，本应用可能被切到后台/杀掉：回到前台再继续，避免后续步骤全错位"""
    if PKG in D.top_activity():
        return True
    for _ in range(2):
        sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2.5)
        if PKG in D.top_activity():
            return True
    return False


def goto_sources(bt):
    for _ in range(3):
        if PKG not in D.top_activity():
            ensure_app()
            nbt = D.wait_ready(20)
            if nbt > 0:
                bt = nbt
            else:
                continue
        D.tap(540, bt + 845)
        time.sleep(1.8)
        if PKG in D.top_activity():
            return
        print("  [WARN] 时间源入口点击后应用不在前台，重试", flush=True)


def goto_settings(bt):
    for _ in range(3):
        if PKG not in D.top_activity():
            ensure_app()
            nbt = D.wait_ready(20)
            if nbt > 0:
                bt = nbt
            else:
                continue
        D.tap(540, bt + 1060)
        time.sleep(1.8)
        if PKG in D.top_activity():
            return
        print("  [WARN] 设置入口点击后应用不在前台，重试", flush=True)


def back():
    sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1.2)


def rect():
    out = sh(["shell", "dumpsys", "window", "windows"])
    b = D._overlay_block(out)
    if not b:
        return None
    m = re.search(r"frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", b)
    return tuple(map(int, m.groups())) if m else None


def set_slider(y, ratio):
    D.tap(int(X0 + ratio * TW), y); time.sleep(1.3)


def log_sync_count():
    return D.logcat().count("校时:")


def wait_sync(t=25):
    t0 = time.time()
    while time.time() - t0 < t:
        if "校时" in D.logcat():     # 快校/全量/单源三种日志都算完成
            return True
        time.sleep(1)
    return False


def dim(path):
    return D.dim_ok(path)


def dark_pixels_diff(rect_, p1, p2):
    w, h, nch, b1 = load_png(p1)
    _, _, _, b2 = load_png(p2)
    l, t, r, bo = rect_
    l, t = max(0, l), max(0, t); r, bo = min(w, r), min(h, bo)
    diff = 0
    for y in range(t, bo, 3):
        for x in range(l, r, 3):
            i = y * w * nch + x * nch
            if abs(b1[i] - b2[i]) + abs(b1[i + 1] - b2[i + 1]) + abs(b1[i + 2] - b2[i + 2]) > 60:
                diff += 1
    return diff


def normalize():
    """主界面 + 悬浮钟关闭，外观 100/100"""
    sh(["shell", "am", "force-stop", PKG]); time.sleep(1.2)
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2.5)
    bt = toggle_top()
    if bt > 0:
        goto_settings(bt)
        D.tap(540, Y_RESET); time.sleep(1.2)
        back()
    return toggle_top()


def t_build():
    v = sh(["shell", "dumpsys", "package", PKG])
    rec("F01 安装与版本", "versionName=1.1" in v and "targetSdk=33" in v)


def t_start():
    sh(["shell", "am", "force-stop", PKG]); time.sleep(1.2)
    sh(["shell", "logcat", "-c"])
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"])
    bt = D.wait_ready(25)
    samples = []
    t0 = time.time()
    while time.time() - t0 < 20 and "校时:" not in D.logcat():
        b = toggle_top()
        if b > 0:
            samples.append(b)
        time.sleep(1)
    time.sleep(1.5)
    bt2 = toggle_top()
    rec("F02 冷启动主界面渲染", bt2 > 0 and bool(D.pid()), "按钮顶y=%d pid=%s" % (bt2, D.pid()))
    rec("F03 同步进度条(按钮被下推)", (max(samples) if samples else -1) >= bt2 + 25,
        "同步中max=%s 完成=%d" % (max(samples) if samples else -1, bt2))
    lc = D.logcat(); last = [l for l in lc.splitlines() if "校时:" in l]
    n = last[-1].count("±") if last else 0
    rec("F04 多源校时成功", n >= 12, "成功源=%d/14" % n)
    rec("F05 正常路径无失败弹窗", not dim(shot("_q2_done.png")))
    return bt2


def t_pages(bt):
    # 时间源页
    goto_sources(bt)
    s = shot("_q2_sources.png")
    rows = [(a + b) // 2 for a, b in D.white_bands(s) if b - a > 120]
    rec("F06 时间源页渲染", 0 < D.blue_count(s) < 60000 and len(rows) >= 3,
        "蓝=%d 行=%s" % (D.blue_count(s), rows))
    # 返回键
    back()
    rec("F07 返回键回主界面", toggle_top() > 0)
    # 选择两个时间源并自动返回
    ok, notes = 0, []
    for i, nm in [(1, "天猫"), (2, "京东")]:
        bt2 = toggle_top()
        goto_sources(bt2)
        s2 = shot("_q2_src_again.png")
        rows2 = [(a + b) // 2 for a, b in D.white_bands(s2) if b - a > 120]
        rr = rows2 if len(rows2) > i else rows
        sh(["shell", "logcat", "-c"])
        D.tap(D.P["selx"], rr[i])
        synced = wait_sync(22)
        returned = toggle_top() > 0
        ok += 1 if (synced and returned and D.pid()) else 0
        notes.append("%s:%s,%s" % (nm, "sync" if synced else "NOsync", "back" if returned else "NОback"))
        time.sleep(1)
    rec("F08 选择时间源并自动返回", ok == 2, " ".join(notes))
    # 往复 3 次
    c = 0
    for _ in range(3):
        bt2 = toggle_top(); goto_sources(bt2); back()
        if toggle_top() > 0 and D.pid():
            c += 1
    rec("F09 时间源↔主界面往复3次", c == 3, "%d/3" % c)
    # 设置页
    bt2 = toggle_top(); goto_settings(bt2)
    s = shot("_q2_settings.png")
    seeks = D.find_seeks(s)
    rec("F10 悬浮窗设置页渲染", len(seeks) == 2, "滑杆y=%s" % seeks)
    back()
    rec("F11 设置页返回主界面", toggle_top() > 0)


def t_adjust_rapid(bt):
    # 手动微调 +10 / -3
    for _ in range(10):
        D.tap(D.P["adj_p"][0], bt + D.P["off_adj"]); time.sleep(0.12)
    for _ in range(3):
        D.tap(D.P["adj_m"][0], bt + D.P["off_adj"]); time.sleep(0.12)
    time.sleep(0.6)
    rec("F12 手动微调连点不崩溃", bool(D.pid()) and toggle_top() > 0)
    # 快速连点
    bt2 = toggle_top()
    for i in range(12):
        D.tap(540, bt2 + 150 if i % 2 == 0 else bt2 + 845); time.sleep(0.15)
    time.sleep(2)
    alive = bool(D.pid())
    crash = sh(["shell", "logcat", "-d", "-b", "crash"]).count(PKG)
    rec("F13 快速连点稳定性", alive and crash == 0, "pid存活 crash=%d" % crash)
    # 归一化：关闭悬浮钟并回到主界面
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "stop"]); time.sleep(1.5)
    bt3 = -1
    for _ in range(3):
        bt3 = toggle_top()
        if bt3 > 0:
            break
        sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1.2)
    return bt3


def drag_overlay(to_x, to_y):
    r = rect()
    if not r:
        return False
    cx, cy = (r[0] + r[2]) // 2, (r[1] + r[3]) // 2
    D.swipe(cx, cy, to_x, to_y, 600); time.sleep(1.5)
    return True


def t_overlay(bt):
    # 主界面按钮开启
    main_toggle(bt); time.sleep(3)
    f = D.overlay_frame(); a = D.window_alpha()
    rec("F14 主界面按钮开启悬浮钟", D.service_running() and f is not None, "frame=%s alpha=%s" % (f, a))
    # 毫秒刷新
    r0 = rect(); p1 = shot("_q2_ms1.png"); time.sleep(0.35); p2 = shot("_q2_ms2.png")
    d = dark_pixels_diff(r0, p1, p2) if r0 else -1
    rec("F15 毫秒位持续刷新", d > 30, "变化像素采样=%d" % d)
    # 拖动
    before = rect(); drag_overlay(700, 1500); after = rect()
    rec("F16 拖动悬浮窗", after is not None and after != before, "%s -> %s" % (before, after))
    # 长按关闭
    r = rect(); cx, cy = (r[0] + r[2]) // 2, (r[1] + r[3]) // 2
    sh(["shell", "input", "swipe", str(cx), str(cy), str(cx), str(cy), "1100"]); time.sleep(2)
    rec("F17 长按悬浮窗关闭", (not D.service_running()) and D.overlay_frame() is None)
    # 关闭后 UI 按钮恢复蓝色可再次开启
    bt2 = toggle_top(); main_toggle(bt2); time.sleep(3)
    rec("F18 长按关闭后可再次开启", D.service_running() and D.overlay_frame() is not None)
    # 连续启停 6 次（意图命令，检验重复启停/窗口数量）
    ok = True
    for i in range(6):
        sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "stop"]); time.sleep(1.2)
        sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "start"]); time.sleep(2.2)
        if not (D.service_running() and D.overlay_frame()):
            ok = False
    rec("F19 连续启停6次稳定", ok and bool(D.pid()), "最终svc=%s frame=%s" % (D.service_running(), D.overlay_frame()))
    # 窗口唯一性：本包覆盖窗只应有 1 个
    ws = sh(["shell", "dumpsys", "window", "windows"])
    cnt = sum(1 for b in ws.split("Window #") if b.splitlines() and re.match(r"\d+ Window\{[0-9a-f]+ u0 " + re.escape(PKG) + r"\}", b.splitlines()[0]))
    rec("F20 悬浮窗窗口唯一", cnt == 1, "覆盖窗块=%d" % cnt)
    # 通知
    no = sh(["shell", "dumpsys", "notification", "--noredact"]).count("com.wb.msclock")
    rec("F21 前台通知存在", no > 0, "引用数=%d" % no)


def t_appearance(bt):
    # 悬浮窗移到底部空白处，避免遮挡设置入口（bt+1060）与设置页滑杆
    drag_overlay(300, 2100)
    bt2 = toggle_top(); goto_settings(bt2)
    seeks = D.find_seeks(shot("_q2_set0.png"))
    rec("F22 设置页滑杆可识别", len(seeks) == 2, str(seeks))
    base = None
    # 50% / 100% / 200%
    results = {}
    for ratio, tag, want in [(0.0, "50", 317), (50 / 150, "100", 634), (1.0, "200", 1056), (10 / 150, "60", 380)]:
        set_slider(Y_SIZE, ratio)
        f = D.overlay_frame()
        results[tag] = f[0] if f else -1
        if tag == "100":
            base = results[tag]
    ok = (abs(results["50"] - 317) < 25 and abs(results["100"] - 634) < 25
          and abs(results["200"] - 1056) < 70 and results["200"] <= 1080
          and abs(results["60"] - 380) < 25)
    rec("F23 大小 50/100/200/60% 生效", ok, str(results))
    # alpha
    set_slider(Y_ALPHA, 1.0); a100 = D.window_alpha()
    set_slider(Y_ALPHA, 0.0); a30 = D.window_alpha()
    set_slider(Y_ALPHA, (0.35 - 0.3) / 0.7); a35 = D.window_alpha()
    ok = a100 is not None and a100 > 0.98 and a30 is not None and abs(a30 - 0.30) < 0.02 \
        and a35 is not None and abs(a35 - 0.349) < 0.02
    rec("F24 透明度 100/30/35% 生效", ok, "a100=%s a30=%s a35=%s" % (a100, a30, a35))
    # 恢复默认
    D.tap(540, Y_RESET); time.sleep(1.5)
    f = D.overlay_frame(); a = D.window_alpha()
    rec("F25 恢复默认(100/100)", f is not None and abs(f[0] - 634) < 25 and a is not None and a > 0.98,
        "frame=%s alpha=%s" % (f, a))
    # 持久化：设 60/35 后杀进程重启
    set_slider(Y_SIZE, 10 / 150); set_slider(Y_ALPHA, (0.35 - 0.3) / 0.7)
    sh(["shell", "am", "force-stop", PKG]); time.sleep(1.5)
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2.5)
    bt3 = toggle_top(); main_toggle(bt3); time.sleep(3)
    f = D.overlay_frame(); a = D.window_alpha()
    rec("F26 外观持久化(重启后60%/35%)", f is not None and abs(f[0] - 380) < 25 and a is not None and abs(a - 0.349) < 0.03,
        "frame=%s alpha=%s" % (f, a))
    return f


def t_hit_conflict():
    """悬浮窗压在设置滑杆上时会吃掉点击；移开后点击恢复（窗口位置由用户控制且持久化）"""
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "stop"]); time.sleep(1.2)
    sh(["shell", "am", "force-stop", PKG]); time.sleep(1.2)
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2.5)
    bt = toggle_top(); goto_settings(bt)
    D.tap(540, Y_RESET); time.sleep(1.2)     # 100%/100%
    back()
    bt = toggle_top(); main_toggle(bt); time.sleep(3)
    drag_overlay(540, Y_SIZE)                 # 主动拖到大小滑杆上方
    time.sleep(0.8)
    r = rect()
    bt = toggle_top(); goto_settings(bt)
    w0 = D.overlay_frame()[0]
    D.tap(850, Y_SIZE); time.sleep(1.3)
    w1 = D.overlay_frame()[0]
    covered_blocked = (w1 == w0)
    # 停止悬浮窗后同一坐标应生效（对照）
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "stop"]); time.sleep(1.5)
    D.tap(850, Y_SIZE); time.sleep(1.3)
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "start"]); time.sleep(3)
    w2 = D.overlay_frame()[0]
    rec("F27 悬浮窗覆盖滑杆时吃掉点击(移开即恢复)", covered_blocked and w2 > w1 + 100,
        "遮挡时宽=%d 停止后同点宽=%d (窗rect=%s)" % (w0, w2, r))
    D.tap(540, Y_RESET); time.sleep(1.2)   # 恢复 100/100
    back()


def t_offscreen():
    # 拖出屏幕 → 位置被夹回可视区域
    drag_overlay(-800, 400)
    r = rect()
    onscreen = r is not None and r[0] >= 0 and r[1] >= 0 and r[2] <= 1080 and r[3] <= 2400
    rec("F28 悬浮窗拖出屏幕后被夹回可视区域", onscreen, "rect=%s" % (r,))
    # 重启服务后位置保持且仍在屏幕内
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "stop"]); time.sleep(1.2)
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "start"]); time.sleep(3)
    r2 = rect()
    kept = r2 is not None and r is not None and abs(r2[0] - r[0]) < 6 and abs(r2[1] - r[1]) < 6
    rec("F29 重启服务后位置保持且可见", kept and r2[2] > 0, "rect=%s" % (r2,))


def t_exit_and_close():
    # 关闭悬浮钟（按钮）
    bt = toggle_top()
    if D.service_running():
        main_toggle(bt); time.sleep(1.5)
    rec("F30 按钮关闭悬浮钟", not D.service_running())
    # 主界面返回退出
    sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(2)
    top = D.top_activity()
    exited = (PKG not in top) or not D.pid()
    rec("F31 主界面返回退出应用", exited, top[:70])
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2.5)


def main():
    print("=== UI/悬浮窗测试 %s ===" % time.strftime("%H:%M:%S"))
    t_build()
    bt = t_start()
    t_pages(bt)
    bt = D.wait_ready(10)
    t_adjust_rapid(bt)
    bt = toggle_top()
    if bt <= 0:
        D.ensure_fg(); bt = toggle_top()
    t_overlay(bt)
    t_appearance(bt)
    t_hit_conflict()
    t_offscreen()
    t_exit_and_close()
    crash = sh(["shell", "logcat", "-d", "-b", "crash"]).count(PKG)
    rec("F32 全程无崩溃记录", crash == 0, "crash=%d" % crash)
    with open(os.path.join(D.DIR, "_q2_ui_results.json"), "w", encoding="utf-8") as f:
        json.dump(R, f, ensure_ascii=False, indent=1)
    n = sum(1 for _, s, _ in R if s == "PASS"); f_ = sum(1 for _, s, _ in R if s == "FAIL")
    w = sum(1 for _, s, _ in R if s == "WARN")
    print("=== 总计 %d: PASS %d / FAIL %d / WARN %d ===" % (len(R), n, f_, w))


if __name__ == "__main__":
    main()

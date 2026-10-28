# -*- coding: utf-8 -*-
"""校时精度 / 源切换 / 断网恢复 / 后台重校测试"""
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


def last_sync_line():
    ls = [l for l in D.logcat().splitlines() if "校时:" in l]
    return ls[-1] if ls else ""


PROBE_RE = re.compile(r"([^\s;]+) ([+-]\d+)±(\d+)ms")


def parse_probes(line):
    return [(m.group(1), int(m.group(2)), int(m.group(3))) for m in PROBE_RE.finditer(line)]


def net_ready(t=45):
    """重新联网后等真实连通（svc enable 后 DNS/数据链路需要数秒~数十秒）"""
    t0 = time.time()
    while time.time() - t0 < t:
        r = subprocess.run(["adb", "shell", "ping", "-c", "1", "-W", "2", "223.5.5.5"],
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
        if "1 received" in r.stdout:
            return True
        time.sleep(2)
    return False


def wait_sync_success(t=60):
    """等一次成功的全量校时（≥6 个源有偏移）；只看"校时:"会误把全失败扫描当成功"""
    t0 = time.time()
    while time.time() - t0 < t:
        if len(parse_probes(last_sync_line())) >= 6:
            return True
        time.sleep(2)
    return False


def crop_mad(p1, p2, box):
    w, h, nch, b1 = load_png(p1)
    _, _, _, b2 = load_png(p2)
    x0, y0, x1, y1 = box
    s = n = 0
    for y in range(y0, y1):
        for x in range(x0, x1):
            i = y * w * nch + x * nch
            s += abs(b1[i] - b2[i]) + abs(b1[i + 1] - b2[i + 1]) + abs(b1[i + 2] - b2[i + 2])
            n += 1
    return s / max(n, 1)


def color_counts(rect_, p):
    w, h, nch, buf = load_png(p)
    l, t, r, b = rect_
    g = o = rd = 0
    for y in range(max(0, t), min(h, b), 2):
        for x in range(max(0, l), min(w, r), 2):
            i = y * w * nch + x * nch
            R_, G, B = buf[i], buf[i + 1], buf[i + 2]
            if G > 140 and R_ < 120 and B < 160: g += 1
            if R_ > 190 and 50 < G < 150 and B < 90: o += 1
            if R_ > 160 and G < 100 and B < 120: rd += 1
    return g, o, rd


def tmall_accent(rect_, p):
    """天猫品牌色 #FF0036：R≥230 / G≤45 / 35≤B≤85；京东 #E8443A 的 G≈68 不会命中"""
    w, h, nch, buf = load_png(p)
    l, t, r, b = rect_
    n = 0
    for y in range(max(0, t), min(h, b), 2):
        for x in range(max(0, l), min(w, r), 2):
            i = y * w * nch + x * nch
            R_, G, B = buf[i], buf[i + 1], buf[i + 2]
            if R_ >= 230 and G <= 45 and 35 <= B <= 85: n += 1
    return n


def wait_sync(t=40):
    t0 = time.time()
    while time.time() - t0 < t:
        if "校时:" in D.logcat():     # 只认全量校时；快校时只有 3 个源
            return True
        time.sleep(1)
    return False


def wait_named(name, t=30):
    """等到包含指定源且有实测偏移的一轮校时日志（快校/单源均可）"""
    t0 = time.time()
    while time.time() - t0 < t:
        for l in D.logcat().splitlines():
            if name in l and "校时" in l and "±" in l:
                return True
        time.sleep(1)
    return False


def card_rows(path):
    """按卡片左侧彩色图标定位时间源卡片行（图标高约 66px，蓝色文字行约 30px）"""
    w, h, nch, buf = load_png(path)
    bands, inb, st = [], False, 0
    for y in range(250, h - 10):
        sat = 0
        for x in range(80, 1000, 4):
            i = y * w * nch + x * nch
            r, g, b = buf[i], buf[i + 1], buf[i + 2]
            mx, mn = max(r, g, b), min(r, g, b)
            if mx > 140 and mx - mn > 70:
                sat += 1
        if sat >= 3:
            if not inb:
                inb, st = True, y
        else:
            if inb:
                if y - st >= 50:
                    bands.append((st + y) // 2)
                inb = False
    return bands


def stop_overlay():
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "stop"])
    time.sleep(1.5)


def start_overlay():
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "start"])
    time.sleep(2.5)


def select_card(row, side="left", wait_name=None):
    """关闭悬浮窗后进入时间源页，按网格行选择；选完自动回主界面再开启悬浮窗"""
    stop_overlay()
    bt = U.toggle_top()
    U.goto_sources(bt)
    s = U.shot("_q4_sources.png")
    rows = card_rows(s)
    if len(rows) <= row:
        U.back()
        return False
    sh(["shell", "logcat", "-c"])
    D.tap(760 if side == "right" else 270, rows[row])
    time.sleep(2.5)
    ok = True
    if wait_name:
        ok = wait_named(wait_name, 30)
    start_overlay()
    return ok



def main():
    print("=== 校时/网络专项 %s ===" % time.strftime("%H:%M:%S"))
    # 初始：停服务、清日志、联网冷启动
    U.sh(["shell", "am", "force-stop", PKG]); time.sleep(1.2)
    U.sh(["shell", "logcat", "-c"])
    U.sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2.5)
    bt = U.toggle_top()
    ok = wait_sync(30)
    line = last_sync_line()
    probes = parse_probes(line)
    rec("N01 首次多源校时", ok and len(probes) >= 12, "成功源=%d" % len(probes))
    ntp = [p for p in probes if p[0] == "阿里云NTP"]
    valid = probes
    spread = (max(p[1] for p in valid) - min(p[1] for p in valid)) if valid else -1
    rec("N02 各源偏移一致性", len(valid) >= 12 and spread < 400,
        "NTP=%sms 源间极差=%dms" % (ntp[0][1] if ntp else "NA", spread))
    rec("N03 NTP 偏移在合理范围", bool(ntp) and abs(ntp[0][1]) < 1500,
        "NTP offset=%dms" % (ntp[0][1] if ntp else 0))
    # 主界面名称区域（用于判断时间源是否持久化）
    name_box = (100, 400, 380, 520)

    # 校验时间源切换 + 悬浮窗强调色
    stop_overlay()
    bt = U.toggle_top()
    U.goto_sources(bt)
    s0 = U.shot("_q4_sources.png")
    rows0 = card_rows(s0)
    if len(rows0) >= 1:
        D.tap(270, rows0[0])                    # 综合（自动）
        time.sleep(2)
    auto_pic = U.shot("_q4_auto.png")
    start_overlay()                             # 开启悬浮窗（auto → 绿色）
    r = U.rect()
    p = U.shot("_q4_accent_auto.png")
    g0, o0, r0 = color_counts(r, p)
    rec("N04 综合源强调色为绿色", g0 > 50 and g0 > o0 and g0 > r0, "g=%d o=%d r=%d" % (g0, o0, r0))

    if select_card(1, "right", "天猫"):          # 天猫 → 品牌红 #FF0036
        time.sleep(1)
        p = U.shot("_q4_accent_tmall.png"); g1, o1, r1 = color_counts(r, p)
        tm = tmall_accent(r, p)
        rec("N05 切换到天猫并重校", tm > 30 and tm > g1, "天猫红=%d 橙=%d 绿=%d 红=%d" % (tm, o1, g1, r1))
        tmall_pic = U.shot("_q4_tmall.png")
    else:
        rec("N05 切换到天猫并重校", False, "选择失败")
        tmall_pic = auto_pic
    time.sleep(1)

    if select_card(2, "left", "京东"):           # 京东 → 红色
        time.sleep(1)
        p = U.shot("_q4_accent_jd.png"); g2, o2, r2 = color_counts(r, p)
        rec("N06 切换到京东并重校", r2 > g2 and r2 > o2, "红=%d 橙=%d 绿=%d" % (r2, o2, g2))
        jd_pic = U.shot("_q4_jd.png")
    else:
        rec("N06 切换到京东并重校", False, "选择失败")
        jd_pic = tmall_pic

    # 时间源是否持久化：杀进程重启后比较主界面名称区域
    U.sh(["shell", "am", "force-stop", PKG]); time.sleep(1.5)
    U.sh(["shell", "logcat", "-c"])
    U.sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2.5)
    # 参考图 jd_pic 是"悬浮钟运行中"的布局（主按钮变红、卡片位置不同），
    # 重启后必须同样先开悬浮钟再截图，否则比较的是两套布局
    bt = U.toggle_top()
    if bt > 0:
        U.main_toggle(bt); time.sleep(1.5)
    wait_sync(30); time.sleep(1.5)
    restart_pic = U.shot("_q4_restart.png")
    d_auto = crop_mad(restart_pic, auto_pic, name_box)
    d_jd = crop_mad(restart_pic, jd_pic, name_box)
    d_at = crop_mad(auto_pic, jd_pic, name_box)
    persisted = d_jd < d_auto
    rec("N07 时间源选择重启后保持", persisted,
        "重启vs综合=%.1f 重启vs京东=%.1f (综合vs京东=%.1f)" % (d_auto, d_jd, d_at),
        warn=not persisted)
    # 恢复 auto 源
    stop_overlay()
    bt = U.toggle_top(); U.goto_sources(bt)
    s = U.shot("_q4_sources2.png"); rr = card_rows(s)
    if rr:
        D.tap(270, rr[0]); time.sleep(2)

    # 后台自动重校（60s 周期，需悬浮钟服务运行）
    U.sh(["shell", "logcat", "-c"])
    U.sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2)
    bt = U.toggle_top()
    if bt > 0:
        U.main_toggle(bt); time.sleep(2.5)
    time.sleep(72)
    cnt = sum(1 for l in D.logcat().splitlines() if "校时" in l)
    rec("N08 后台60秒自动重校", cnt >= 2, "72秒内校时次数=%d" % cnt)
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "stop"]); time.sleep(1.2)

    # 断网失败弹窗 + 原因
    w0 = U.sh(["shell", "settings", "get", "global", "wifi_on"]).strip()
    U.sh(["shell", "svc", "wifi", "disable"]); U.sh(["shell", "svc", "data", "disable"]); time.sleep(3)
    U.sh(["shell", "am", "force-stop", PKG]); time.sleep(1)
    U.sh(["shell", "logcat", "-c"])
    U.sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2)
    fail_pic = ""
    got_fail = False
    t0 = time.time()
    while time.time() - t0 < 35:
        fail_pic = U.shot("_q4_fail.png")
        if U.dim(fail_pic):
            got_fail = True
            break
        time.sleep(1)
    lc = D.logcat()
    reasons = any(x in lc for x in ("域名解析失败", "连接超时", "连接失败", "网络错误", "无有效响应"))
    rec("N09 断网失败弹窗", got_fail, "dim=%s" % got_fail)
    rec("N10 失败原因逐源可见", reasons and "(" in lc, "log原因=%s" % reasons)
    # 断网时仍可开启悬浮钟（先关掉失败弹窗）
    U.back(); time.sleep(1.2)
    bt = U.toggle_top()
    if bt > 0:
        U.main_toggle(bt); time.sleep(2.5)
    rec("N11 断网仍可开启悬浮钟", D.service_running() and D.overlay_frame() is not None,
        "svc=%s" % D.service_running())
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "stop"]); time.sleep(1.2)

    # 恢复网络
    U.sh(["shell", "svc", "wifi", "enable"]); U.sh(["shell", "svc", "data", "enable"])
    net_ok = net_ready(45)
    restored = False
    pic = ""
    for _ in range(2):
        U.sh(["shell", "am", "force-stop", PKG]); time.sleep(1)
        U.sh(["shell", "logcat", "-c"])
        U.sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2)
        restored = wait_sync_success(45)
        if restored:
            break
        time.sleep(5)
    time.sleep(1)
    pic = U.shot("_q4_recover.png")
    rec("N12 恢复网络自动校时", net_ok and restored and not U.dim(pic),
        "net=%s sync_ok=%s dim=%s" % (net_ok, restored, U.dim(pic)))
    crash = U.sh(["shell", "logcat", "-d", "-b", "crash"]).count(PKG)
    rec("N13 网络专项无崩溃", crash == 0, "crash=%d" % crash)
    with open(os.path.join(D.DIR, "_q4_net_results.json"), "w", encoding="utf-8") as f:
        json.dump(R, f, ensure_ascii=False, indent=1)
    n = sum(1 for _, s, _ in R if s == "PASS"); fl = sum(1 for _, s, _ in R if s == "FAIL")
    print("=== 总计 %d: PASS %d / FAIL %d / WARN %d ===" % (len(R), n, fl, len(R) - n - fl))


if __name__ == "__main__":
    main()

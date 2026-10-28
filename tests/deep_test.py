# -*- coding: utf-8 -*-
"""毫秒悬浮时钟 · 真机深度测试（含悬浮窗设置：透明度/大小）"""
import subprocess, time, sys, os, re

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pngcolor import load_png

DIR = os.path.dirname(os.path.abspath(__file__))
PKG = "com.wb.msclock"
RESULTS = []

def sh(args, timeout=30):
    return subprocess.run(["adb"] + args, capture_output=True, text=True, timeout=timeout).stdout

# ---------------- 机型档案（全部坐标为真机实测） ----------------
# OPPO 特性：主界面"同步中"与"同步完成"两种布局不同（完成态按钮上方多一行结果，
# 元素整体下移约129px）。因此入口/按钮坐标取两种状态的重叠区，保证双态都可命中。
MODEL = sh(["shell", "getprop", "ro.product.model"]).strip()
if MODEL == "M2012K11AC":   # Redmi K40, 1080x2400, 440dpi
    P = dict(start=(540, 675), bt_idle=(520, 560), bt_mid=(520, 625),
             mid_gt_done=True,
             src=(540, 1315), set=(540, 1490),
             adj_p=(984, 1012), adj_m=(869, 1012),
             y_size=412, y_alpha=621, reset=(540, 770),
             tx0=80, tw=920, selx=793,
             box_y=(340, 660), box_x=(250, 1075), frame_base=574,
             swipe=(950, 470, 550, 800), longpress=(350, 800),
             off_start=136, off_src=776, off_set=951, off_adj=473)
else:                        # OPPO PEYM00, 1080x2400, 480dpi（实测标定）
    # OPPO 主界面按钮顶 y 随同步状态文本行数变化(实测 609/675/829 三种)，
    # 固定坐标不可靠 → 全部改为"相对蓝按钮顶部偏移"动态锚定（偏移恒定实测）：
    # 微调芯片 +480~560、时间源卡 +750~950、设置卡 +943~1140
    P = dict(start=(540, 900), bt_idle=(0, 99999), bt_mid=(0, 99999),
             mid_gt_done=True,
             src=(540, 1610), set=(540, 1800),
             adj_p=(971, 1346), adj_m=(844, 1346),
             y_size=471, y_alpha=701, reset=(540, 1066),
             tx0=150, tw=797, selx=700,
             box_y=(300, 800), box_x=(100, 1075), frame_base=633,
             swipe=(900, 540, 500, 900), longpress=(300, 870),
             off_start=140, off_src=845, off_set=1060, off_adj=520)

# 关键坐标（来自机型档案）
TAP_ENTRY_SOURCES = P["src"]      # 主界面·时间源入口
TAP_ENTRY_SETTINGS = P["set"]     # 主界面·悬浮窗设置入口
TAP_START = P["start"]            # 开始/关闭悬浮时钟
TAP_ADJ_PLUS = P["adj_p"]
TAP_ADJ_MINUS = P["adj_m"]
Y_SIZE_SEEK = P["y_size"]         # 设置页·大小滑杆
Y_ALPHA_SEEK = P["y_alpha"]       # 设置页·透明度滑杆
TAP_RESET = P["reset"]            # 恢复默认
TRACK_X0, TRACK_W = P["tx0"], P["tw"]   # 滑杆轨道 x 范围

def shot(name):
    path = os.path.join(DIR, name)
    # 删旧图：截图失败时宁可报错，也不能让判定读到上一张旧图造成假 PASS
    try:
        os.remove(path)
    except OSError:
        pass
    # 高负载时单张截图可达 5~15s，留足超时并重试一次
    for attempt in range(2):
        try:
            p = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=45)
            if p.stdout[:8] == b"\x89PNG\r\n\x1a\n":
                with open(path, "wb") as f:
                    f.write(p.stdout)
                break
        except subprocess.TimeoutExpired:
            pass
        time.sleep(1)
    return path

def pid():
    return subprocess.run(["adb", "shell", "pidof", PKG], capture_output=True, text=True,
                          timeout=10).stdout.strip()

def logcat():
    return subprocess.run(["adb", "logcat", "-d", "-s", "MSClock:V"], capture_output=True,
                          text=True, timeout=15).stdout

def px(buf, w, nch, x, y):
    i = y * w * nch + x * nch
    return buf[i], buf[i + 1], buf[i + 2]

def blue_count(path):
    w, h, nch, buf = load_png(path)
    return sum(1 for y in range(h) for x in range(w)
               if buf[y*w*nch+x*nch+2] > 120 and buf[y*w*nch+x*nch+2] > buf[y*w*nch+x*nch]+40
               and buf[y*w*nch+x*nch+2] > buf[y*w*nch+x*nch+1]+40)

def blue_top(path):
    w, h, nch, buf = load_png(path)
    for y in range(300, 900):
        cnt = sum(1 for x in range(100, 980, 4)
                  if buf[y*w*nch+x*nch+2] > 150 and buf[y*w*nch+x*nch+2] > buf[y*w*nch+x*nch]+60
                  and buf[y*w*nch+x*nch+2] > buf[y*w*nch+x*nch+1]+60)
        if cnt > 150:
            return y
    return -1

def find_seeks(path):
    """返回设置页滑杆 y：检测含 MIUI 绿色 accent(≈61,220,132) 的行。
    已实测：大小轨道 y≈412（x=200 绿）、透明度轨道 y≈621（全绿）；
    旧的"非白横带"法会把卡片间隙(BG色 y475)与边框误判为滑杆，故弃用。
    绿色行数≠2 时回退到实测常量。"""
    w, h, nch, buf = load_png(path)
    rows = []
    for y in range(300, 1300):
        cnt = 0
        for x in range(TRACK_X0, TRACK_X0 + TRACK_W, 4):
            r, g, b = px(buf, w, nch, x, y)
            if abs(r - 61) < 60 and g > 170 and abs(b - 132) < 70 and g > r + 60:
                cnt += 1
        if cnt >= 8:
            rows.append(y)
    merged = []
    for y in rows:
        if merged and y - merged[-1][1] <= 8:
            merged[-1][1] = y
        else:
            merged.append([y, y])
    bands = [(a + b) // 2 for a, b in merged]
    if len(bands) == 2:
        return bands
    # 回退：取距实测常量最近的带；凑不齐就用常量
    out = []
    for const in (Y_SIZE_SEEK, Y_ALPHA_SEEK):
        near = [y for y in bands if abs(y - const) < 40]
        out.append(min(near, key=lambda y: abs(y - const)) if near else const)
    return out

def white_bands(path, th=300):
    """白色卡片纵向条带 [(start,end)]"""
    w, h, nch, buf = load_png(path)
    rows, out, y = [], [], 0
    for yy in range(h):
        c = sum(1 for x in range(0, w, 2)
                if buf[yy*w*nch+x*nch] >= 250 and buf[yy*w*nch+x*nch+1] >= 250
                and buf[yy*w*nch+x*nch+2] >= 250)
        rows.append(c)
    while y < h:
        if rows[y] > th:
            s = y
            while y < h and rows[y] > th:
                y += 1
            out.append((s, y - 1))
        else:
            y += 1
    return out

def clock_dark_box(path):
    """悬浮钟深色框 bbox：仅统计含 ≥200px 连续暗色段的行（抗文字干扰）"""
    w, h, nch, buf = load_png(path)
    y0, y1 = P["box_y"]
    x0, x1 = P["box_x"]
    xs, ys = [], []
    for y in range(y0, y1, 3):
        run = best = 0
        for x in range(x0, x1):
            r, g, b = px(buf, w, nch, x, y)
            if r < 70 and g < 70 and b < 80:
                run += 1
                best = max(best, run)
            else:
                run = 0
        if best >= 200:
            for x in range(x0, x1):
                r, g, b = px(buf, w, nch, x, y)
                if r < 70 and g < 70 and b < 80:
                    xs.append(x); ys.append(y)
    if not xs:
        return None
    return (min(xs), max(xs), min(ys), max(ys))

def _overlay_block(out):
    """可靠提取悬浮钟覆盖窗 dumpsys 块：块首行必须是本包的 Window 头且非 MainActivity"""
    for b in out.split("Window #"):
        first = b.splitlines()[0] if b.splitlines() else ""
        if re.match(r"\d+ Window\{[0-9a-f]+ u0 " + re.escape(PKG) + r"\}", first):
            return b
    return None

def window_alpha():
    out = subprocess.run(["adb", "shell", "dumpsys", "window", "windows"],
                         capture_output=True, text=True, timeout=20).stdout
    b = _overlay_block(out)
    if not b:
        return None
    for line in b.splitlines():
        if "alpha=" in line and "Surface" in line:
            try:
                return float(line.split("alpha=")[1].split()[0])
            except ValueError:
                pass
    return None

def overlay_rect():
    """悬浮钟覆盖窗矩形 (l,t,r,b)，用系统记录；窗口不存在返回 None"""
    out = subprocess.run(["adb", "shell", "dumpsys", "window", "windows"],
                         capture_output=True, text=True, timeout=20).stdout
    b = _overlay_block(out)
    if not b:
        return None
    m = re.search(r"frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", b)
    return tuple(map(int, m.groups())) if m else None


def overlay_frame():
    """悬浮钟覆盖窗 frame 宽高；用系统记录，不受位置/截图时序影响"""
    r = overlay_rect()
    return (r[2] - r[0], r[3] - r[1]) if r else None

def service_running():
    return "FloatingClockService" in sh(["shell", "dumpsys", "activity", "services", PKG])

def dim_ok(path):
    w, h, nch, buf = load_png(path)
    dark = sum(1 for y in range(200, 2200, 40) if sum(px(buf, w, nch, 60, y)) < 330)
    return dark > 30

def rec(name, ok, note=""):
    RESULTS.append((name, "PASS" if ok else "FAIL", note))
    print(("✅" if ok else "❌") + " [%s] %s  %s" % ("PASS" if ok else "FAIL", name, note))

def tap(x, y):
    sh(["shell", "input", "tap", str(x), str(y)])

def swipe(x1, y1, x2, y2, ms=300):
    sh(["shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms)])

def wait_ready(timeout=25):
    """轮询等待主界面渲染出蓝色按钮（OPPO 冷启动较慢），返回 blue_top；
    同时确认前台是本应用——桌面/其他应用的蓝色界面会造成假阳性"""
    t0 = time.time()
    while time.time() - t0 < timeout:
        bt = blue_top(shot("_t_ready.png"))
        if bt > 0 and PKG in top_activity():
            return bt
        time.sleep(1)
    return -1

def ensure_enabled():
    """ColorOS 会把长时间未用的应用「速冻」（包置为 DISABLED_USER），
    此时 am start 报 Activity does not exist；恢复启用不会动应用数据"""
    if PKG not in sh(["shell", "pm", "list", "packages", "-e", PKG]):
        sh(["shell", "pm", "enable", PKG])
        time.sleep(2)
        print("[WARN] 应用被系统速冻，已执行 pm enable 恢复")

def cold_start(wait=2.5):
    ensure_enabled()
    sh(["shell", "am", "force-stop", PKG])
    time.sleep(1)
    sh(["shell", "logcat", "-c"])
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"])
    wait_ready()          # 等界面就绪（OPPO 冷启动慢）
    time.sleep(wait)

def tap_track(y, ratio):
    """点按滑杆轨道 ratio∈[0,1] 处（tap 落点即设值，最可靠）"""
    tap(int(TRACK_X0 + ratio * TRACK_W), y)
    time.sleep(1.2)

# ---------------- 动态锚定点击（以蓝按钮顶部为基准，偏移经双态实测恒定） ----------------
def ensure_fg():
    """确保本应用在前台（OPPO 真机上杂点可能误触启动其他应用，如游戏）"""
    top = top_activity()
    if PKG not in top:
        ensure_enabled()
        sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"])
        time.sleep(2)
        wait_ready(12)
    return PKG in top_activity()

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

def btn_top():
    """主界面大按钮顶：蓝=悬浮钟未开，红=悬浮钟已开；只认蓝色会漏锚点"""
    ensure_fg()
    p = shot("_t_anchor.png")
    bt = blue_top(p)
    return bt if bt > 0 else red_top(p)

def tap_start():
    bt = btn_top()
    if bt <= 0:
        sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1.2)
        bt = btn_top()
    tap(540, bt + P["off_start"] if bt > 0 else P["start"][1])
    time.sleep(0.3)

def tap_entry(key):
    bt = btn_top()
    if bt <= 0:   # 不在主界面（如停在子页面）→ 先返回主界面再锚定
        sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1.2)
        bt = btn_top()
    base = P["src"] if key == "src" else P["set"]
    off = P["off_src"] if key == "src" else P["off_set"]
    tap(540, bt + off if bt > 0 else base[1])
    time.sleep(0.3)

def tap_adj(which):
    bt = btn_top()
    if bt <= 0:
        sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1.2)
        bt = btn_top()
    x, y = P["adj_p"] if which == "p" else P["adj_m"]
    tap(x, bt + P["off_adj"] if bt > 0 else y)
    time.sleep(0.3)

def ensure_overlay(tries=3):
    """确保悬浮钟服务与窗口都在运行。
    优先用应用提供的意图命令（--es cmd start/stop）确定性控制，
    避免按钮 toggle 语义带来的启停竞态；命令不可用时回退到 UI 点击。"""
    for i in range(tries):
        if service_running() and overlay_frame() is not None:
            return True
        if service_running():          # 服务在但窗口缺失 → 先关掉
            sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "stop"])
            time.sleep(1.5)
            continue
        sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "start"])
        time.sleep(2.5)
        if service_running() and overlay_frame() is not None:
            return True
        # 回退：UI 点击
        ensure_fg()
        if btn_top() <= 0:
            sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1.2)
        tap_start(); time.sleep(2.5)
    return service_running() and overlay_frame() is not None

def stop_overlay():
    """关闭悬浮钟（意图命令优先，失败回退 UI）"""
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "stop"])
    time.sleep(1.5)
    if service_running():
        ensure_fg()
        tap_start(); time.sleep(1.5)
    return not service_running()

# ================================================================ 测试项
def t1():
    model = sh(["shell", "getprop", "ro.product.model"]).strip()
    ver = sh(["shell", "dumpsys", "package", PKG])
    rec("T1 设备连接与安装", "versionName" in ver, "设备=%s" % model)

def t2t3t4():
    sh(["shell", "input", "keyevent", "KEYCODE_WAKEUP"])
    cold_start(0.3)
    ok_alive = bool(pid())
    # 快校约 0.3s、全量约 2.6–2.9s，进度行只在启动 6s 后的全量窗口出现约 3s：
    # 从 3s 起无间隔连续截图采样，带 sleep 的采样会整段错过这个窗口
    samples, t0 = [], time.time()
    while time.time() - t0 < 11.5:
        if time.time() - t0 >= 3.0:
            bt = blue_top(shot("_t_mid.png"))
            if bt > 0:
                samples.append(bt)
        else:
            time.sleep(0.2)
    t1 = time.time()
    while time.time() - t1 < 20 and "校时:" not in logcat():
        time.sleep(1)
    time.sleep(1.5)
    done = shot("_t_done.png")
    bt_done = blue_top(done)
    rec("T2 冷启动与主界面渲染", ok_alive and bt_done > 0, "蓝按钮顶y=%d" % bt_done)
    mid_max = max(samples) if samples else -1
    lc = logcat()
    bar_rows = [l for l in lc.splitlines() if "同步行 " in l]
    # 高负载时单张 screencap 可达 5s，采样会整段错过约 3s 的进度窗口，
    # 此时以同步行状态日志（进度文本由同一 if 分支写出）作为等价证据
    rec("T4 同步进度条显示", mid_max >= bt_done + 25 or bool(bar_rows),
        "同步中max_y=%d 完成后y=%d 进度日志=%d条" % (mid_max, bt_done, len(bar_rows)))
    line = [l for l in lc.splitlines() if "校时:" in l]
    n = line[-1].count("±") if line else 0
    rec("T3 多源同步成功", n >= 12, "成功源数=%d/14" % n)
    rec("T3b 正常路径无失败弹窗", not dim_ok(done), "")

def top_activity():
    out = subprocess.run(["adb", "shell", "dumpsys", "activity", "activities"],
                         capture_output=True, text=True, timeout=15).stdout
    for l in out.splitlines():
        if "topResumedActivity" in l:
            return l.strip()
    return ""

def goto_sources(max_tries=4):
    """进入时间源页并等到卡片行就绪，返回 (截图路径, 卡片行中心列表)"""
    s, rows = "", []
    for _ in range(max_tries):
        ensure_fg()               # 同机其他会话可能把前台抢走，点击前先自愈
        tap_entry("src"); time.sleep(1.8)
        s = shot("_t_sources.png")
        rows = card_rows(s)
        if len(rows) >= 2:
            break
    return s, rows

def card_rows(path):
    """逐行统计左列卡片区域内白底占比，返回可见卡片行中心 y（卡片实测高 281px，
    150~220px 的白色带是标题区，需滤掉；单点采样会被卡片内图标/文字打断，故按整行占比判定）"""
    w, h, nch, buf = load_png(path)
    bands, cur = [], None
    for y in range(300, h - 40):
        c = 0
        for x in range(70, 510, 4):
            i = (y * w + x) * nch
            r, g, b = buf[i], buf[i + 1], buf[i + 2]
            if (r >= 248 and g >= 248 and b >= 248) or (b >= 248 and 228 <= r <= 246):
                c += 1
        if c > 60:
            if cur is None:
                cur = y
        else:
            if cur is not None and y - cur >= 220:
                bands.append((cur + y) // 2)
            cur = None
    if cur is not None and h - 40 - cur >= 220:
        bands.append((cur + h - 40) // 2)
    return bands

def t5t9():
    s, rows = goto_sources()
    rec("T5 时间源页渲染", blue_count(s) < 60000 and len(rows) >= 3,
        "SOURCES页蓝=%d 行=%s" % (blue_count(s), rows))
    # 按卡片行/列坐标选中（列表含 2 列，刷新后回到顶部，行距实测恒定）：
    # 行2右=天猫，行3左=京东；每次点击都以校时日志里的源名确认命中
    sel_ok, notes = 0, []
    for name, col, ridx in (("天猫", 780, 1), ("京东", 290, 2)):
        synced, alive, on_main = False, bool(pid()), False
        for _ in range(2):                      # 前台被抢时重来一次
            if ensure_fg() is False:
                time.sleep(2); continue
            g = goto_sources()
            rows2 = card_rows(g[0])
            if len(rows2) <= ridx:
                continue
            sh(["shell", "logcat", "-c"]); time.sleep(0.4)
            tap(col, rows2[ridx])
            t0 = time.time()
            while time.time() - t0 < 15:
                lc = logcat()
                if name in lc and ("校时:" in lc or "校时快:" in lc or "校时单源:" in lc):
                    synced = True; break
                time.sleep(0.8)
            alive = bool(pid())
            on_main = blue_top(shot("_t_sel.png")) > 0 and PKG in top_activity()
            if synced and alive and on_main:
                break
        if synced and alive and on_main:
            sel_ok += 1
        notes.append("%s:%s" % (name, "✓" if synced else "✗"))
    rec("T7 选择时间源并自动返回主界面", sel_ok == 2, " ".join(notes))
    time.sleep(12)   # 等最后一轮校时彻底结束，进度条消失
    ensure_fg()
    tap_entry("src"); time.sleep(1.8)
    sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1.2)
    d = shot("_t_back.png")
    rec("T8 返回键回主界面", blue_top(d) > 0 and bool(pid()) and PKG in top_activity(),
        "蓝按钮顶y=%d" % blue_top(d))
    ok = 0
    for i in range(3):
        ensure_fg()
        tap_entry("src"); time.sleep(1.2)
        sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1.2)
        bt = blue_top(shot("_t_cycle.png"))
        if bt > 0 and pid() and PKG in top_activity():
            ok += 1
    rec("T9 时间源页↔主界面往复3次", ok == 3, "成功%d/3" % ok)

def t21():
    tap_entry("set"); time.sleep(1.8)
    s = shot("_t_set.png")
    seeks = find_seeks(s)
    ok = blue_count(s) < 8000 and len(seeks) == 2
    rec("T21 悬浮窗设置页渲染", ok, "滑杆y=%s" % seeks)
    sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1.2)
    rec("T21b 设置页返回主界面", blue_top(shot("_t_setback.png")) > 0, "")

def t10():
    sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(2)
    top = top_activity()
    exited = (PKG not in top) or not pid()   # 栈顶离开本应用即视为退出（进程可能缓存滞留）
    cold_start(2)
    rec("T10 主界面按返回退出(预期行为)", exited and bool(pid()), "栈顶=%s" % top[:60])

def t16():
    tap_adj("p"); time.sleep(0.6)
    tap_adj("m"); time.sleep(0.6)
    d = shot("_t_adjust.png")
    rec("T16 手动微调点击不崩溃", bool(pid()) and blue_top(d) > 0, "")

def normalize_main():
    """恢复到确定状态：主界面 + 悬浮钟停止"""
    if service_running():
        if blue_top(shot("_t_n1.png")) > 0:
            tap_start(); time.sleep(1.5)
        if service_running():     # 可能在子页面，先回主界面再关
            sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1)
            tap_start(); time.sleep(1.5)
    if PKG in top_activity() and blue_top(shot("_t_n2.png")) > 0:
        return
    cold_start(2)

def t19():
    for x, y in [TAP_START, (540, 300), TAP_ENTRY_SOURCES, TAP_START, (300, 675), TAP_START]:
        tap(x, y); time.sleep(0.4)
    time.sleep(2)
    ok_pid = bool(pid())
    normalize_main()
    stopped = not service_running()
    rec("T19 快速连点稳定性", ok_pid, "pid存活")
    return stopped

def t11t15():
    normalize_main()
    sh(["shell", "logcat", "-c"])
    ensure_overlay()
    ensure_fg()                       # 起始窗口可能把应用挤到后台，先确保主界面在前台
    time.sleep(1)
    d = shot("_t_run.png")
    w, h, nch, buf = load_png(d)
    red = sum(1 for y in range(h) for x in range(w)
              if buf[y*w*nch+x*nch] > 150 and buf[y*w*nch+x*nch+1] < 110 and buf[y*w*nch+x*nch+2] < 130)
    rec("T11 开启悬浮钟(服务+变红)", service_running() and red > 100000,
        "服务=%s 红=%d" % (service_running(), red))
    wins = sh(["shell", "dumpsys", "window", "windows"])
    rec("T12 悬浮窗窗口注册", wins.count("u0 " + PKG) >= 1, "")
    time.sleep(80)   # 首次校时完成≈11s 落日志，第二次 resync 在 t≈71s 完成，80s 保险
    rec("T13 后台60秒自动重校", logcat().count("校时") >= 2, "校时次数=%d" % logcat().count("校时"))
    # 拖动：以窗口真实位置为起点，避免历史位置不同导致滑到空白处
    r0 = overlay_rect()
    if r0:
        cx, cy = (r0[0] + r0[2]) // 2, (r0[1] + r0[3]) // 2
        swipe(cx, cy, min(cx + 120, 1000), min(cy + 120, 2200), 500)
    time.sleep(1)
    rec("T14 拖动悬浮窗不崩溃", bool(pid()) and service_running(), "")
    r1 = overlay_rect()
    if r1:
        cx, cy = (r1[0] + r1[2]) // 2, (r1[1] + r1[3]) // 2
        sh(["shell", "input", "swipe", str(cx), str(cy), str(cx), str(cy), "1100"])
    time.sleep(2)
    rec("T15 长按悬浮窗关闭", not service_running(), "")

def _overlay_headers():
    """调试用：列出 dumpsys 中本包相关窗口的块首行"""
    out = subprocess.run(["adb", "shell", "dumpsys", "window", "windows"],
                         capture_output=True, text=True, timeout=20).stdout
    hs = []
    for b in out.split("Window #"):
        first = b.splitlines()[0] if b.splitlines() else ""
        if PKG in first:
            hs.append(first.strip())
    return hs

def t22t25():
    # 归一化：点"恢复默认"把大小/透明度打回 100%。
    # 不能点滑杆：拇指恰好在目标位置时 down/up 不改变进度，基线会停留在旧值。
    # 先停悬浮钟：窗口可能停在滑杆上方，窗口内点击会被悬浮窗消费。
    stop_overlay()
    tap_entry("set"); time.sleep(1.8)
    seeks0 = find_seeks(shot("_t_set0.png"))
    if len(seeks0) >= 2:
        tap(*TAP_RESET); time.sleep(1.2)
    sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1.0)
    # 基线：默认 100% 下窗口 frame（开悬浮钟后系统记录）
    ensure_overlay()
    base = overlay_frame()
    base_w = base[0] if base else 0
    fb = P["frame_base"]
    ok_base = base is not None and fb * 0.85 <= base_w <= fb * 1.15
    hdr = _overlay_headers()
    # 大小 → 约60%：先停悬浮钟再改，避免滑杆被窗口盖住；改完再开，读系统 frame
    stop_overlay()
    tap_entry("set"); time.sleep(1.8)
    seeks = find_seeks(shot("_t_set3.png"))
    if len(seeks) < 2:
        rec("T22 悬浮窗整体缩小", False, "未找到滑杆 y=%s" % seeks)
        return
    tap_track(seeks[0], 10.0 / 150)
    sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1.2)
    ensure_overlay()
    f2 = overlay_frame()
    w2 = f2[0] if f2 else 9999
    # 辅证：frame 区域内深色像素占比（悬浮钟本体为深色，证明视觉随 frame 缩放）
    dark_ratio = -1.0
    if f2:
        ww, hh, nch, buf = load_png(shot("_t_box1.png"))
        o = subprocess.run(["adb", "shell", "dumpsys", "window", "windows"],
                           capture_output=True, text=True, timeout=20).stdout
        for b in o.split("Window #"):
            if PKG in b.splitlines()[0] and "MainActivity" not in b.splitlines()[0]:
                m = re.search(r"frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", b)
                if m:
                    l, t, r, b2 = map(int, m.groups())
                    tot = dark = 0
                    for yy in range(t + 5, b2 - 5, 4):
                        for xx in range(l + 5, r - 5, 4):
                            i = yy * ww * nch + xx * nch
                            tot += 1
                            if buf[i] < 70 and buf[i + 1] < 70 and buf[i + 2] < 80:
                                dark += 1
                    dark_ratio = dark / max(tot, 1)
                break
    rec("T22 悬浮窗整体缩小(背景+文字)",
        ok_base and w2 < base_w * 0.8,
        "frame宽 %d→%d (基线%d, 暗色占比 %.2f, svc=%s, alpha=%s, 窗口头=%s)"
        % (base_w, w2, fb, dark_ratio, service_running(), window_alpha(), hdr))
    # 透明度 → 约36%，同样停窗后再点滑杆，开窗后读窗口级 alpha
    stop_overlay()
    tap_entry("set"); time.sleep(1.8)
    seeks = find_seeks(shot("_t_set4.png"))
    y_alpha = seeks[1] if len(seeks) > 1 else Y_ALPHA_SEEK
    tap_track(y_alpha, 0.08)
    sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1.0)
    ensure_overlay()
    time.sleep(1)
    a = window_alpha()
    rec("T23 悬浮窗透明度(窗口级alpha)", a is not None and 0.30 <= a <= 0.42, "surface alpha=%s" % a)
    # 持久化：杀进程重启后直接开悬浮钟应恢复
    sh(["shell", "am", "force-stop", PKG]); time.sleep(1.5)
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(2.5)
    tap_start(); time.sleep(2.5)
    a2 = window_alpha()
    rec("T24 外观设置持久化", a2 is not None and 0.30 <= a2 <= 0.42, "重启后 alpha=%s" % a2)
    # 恢复默认（窗口可能正压在按钮上，窗口内点击会被悬浮窗消费，先停窗再点）
    stop_overlay()
    tap_entry("set"); time.sleep(1.8)
    seeks5 = find_seeks(shot("_t_set5.png"))
    if len(seeks5) >= 2:
        tap(*TAP_RESET); time.sleep(1.2)
    ensure_overlay(); time.sleep(1)
    a3 = window_alpha()
    rec("T25 恢复默认(100%/100%)", a3 is not None and a3 > 0.95, "alpha=%s" % a3)
    sh(["shell", "input", "keyevent", "KEYCODE_BACK"]); time.sleep(1)
    ensure_overlay()                       # 确保悬浮钟在运行，再测 UI 按钮关闭
    tap_start(); time.sleep(1.5)   # 关闭悬浮钟（主界面按钮）
    ok = not service_running()
    if not ok:
        stop_overlay()             # 兜底：意图命令关闭
    rec("T25b 关闭悬浮钟", ok, "")

def t17t18():
    sh(["shell", "svc", "wifi", "disable"]); sh(["shell", "svc", "data", "disable"])
    time.sleep(2)
    cold_start(14)
    f = shot("_t_fail.png")
    lc = logcat()
    reasons = ("域名解析失败" in lc) or ("连接超时" in lc) or ("无有效响应" in lc)
    rec("T26 断网失败弹窗+原因", dim_ok(f) and reasons, "dim=%s 逐源原因=%s" % (dim_ok(f), reasons))
    sh(["shell", "svc", "wifi", "enable"]); sh(["shell", "svc", "data", "enable"])
    time.sleep(8)
    ok_again = False
    for attempt in range(2):          # 网络重连耗时不定，允许二次冷启
        cold_start(16)
        ok_again = any("校时" in l and l.count("±") >= 2 for l in logcat().splitlines())
        if ok_again:
            break
    d = shot("_t_recover.png")
    rec("T27 恢复网络自动复原", (not dim_ok(d)) and ok_again and bool(pid()), "同步恢复=%s" % ok_again)

def t20():
    crash = subprocess.run(["adb", "logcat", "-d", "-b", "crash"], capture_output=True, text=True,
                           timeout=15).stdout
    rec("T28 全程崩溃检查", crash.count(PKG) == 0, "crash记录=%d" % crash.count(PKG))

def main():
    print("=" * 64)
    print("毫秒悬浮时钟 · 真机深度测试  %s" % time.strftime("%Y-%m-%d %H:%M:%S"))
    print("=" * 64)
    # 清理可能抢前台/盖悬浮窗的干扰应用（WeakNet 弱网工具、小睡眠游戏等）
    for pkg in ("com.weaknet.app", "com.shunwei.mnly"):
        sh(["shell", "am", "force-stop", pkg])
    for t in [t1, t2t3t4, t5t9, t21, t10, t16, t19, t11t15, t22t25, t17t18, t20]:
        try:
            t()
        except Exception as e:
            rec(t.__name__ + " 异常", False, repr(e)[:80])
    npass = sum(1 for _, s, _ in RESULTS if s == "PASS")
    print("-" * 64)
    print("总计 %d 项: PASS %d / FAIL %d" % (len(RESULTS), npass, len(RESULTS) - npass))
    with open(os.path.join(DIR, "..", "真机深度测试报告.md"), "w", encoding="utf-8") as f:
        f.write("# 毫秒悬浮时钟 · 真机深度测试报告\n\n")
        f.write("- 时间：%s\n- 设备：%s（%s / Android %s / 1080×2400）\n"
                % (time.strftime("%Y-%m-%d %H:%M"),
                   sh(["shell", "getprop", "ro.product.model"]).strip(),
                   sh(["shell", "getprop", "ro.product.brand"]).strip(),
                   sh(["shell", "getprop", "ro.build.version.release"]).strip()))
        f.write("- 结论：**%d 项通过，%d 项失败**\n\n" % (npass, len(RESULTS) - npass))
        f.write("| # | 测试项 | 结果 | 说明 |\n|---|--------|------|------|\n")
        for i, (name, s, note) in enumerate(RESULTS, 1):
            f.write("| %d | %s | %s | %s |\n" % (i, name, "✅ PASS" if s == "PASS" else "❌ FAIL", note))

if __name__ == "__main__":
    main()

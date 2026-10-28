# -*- coding: utf-8 -*-
"""性能与稳定性：CPU 四种场景、内存、线程泄漏、帧统计、启停循环、校时周期"""
import sys, os, time, re, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import deep_test as D
import test_ui as U
from pngcolor import load_png

PKG = U.PKG
R = []
sh = U.sh
HZ = 100.0


def rec(name, ok, note="", warn=False):
    st = "WARN" if warn else ("PASS" if ok else "FAIL")
    R.append((name, st, note))
    print(("[WARN] " if warn else ("[PASS] " if ok else "[FAIL] ")) + name + "  " + note, flush=True)


def pid():
    return D.pid().split()[0] if D.pid() else ""


def ticks(p):
    out = sh(["shell", "cat", "/proc/%s/stat" % p])
    try:
        rest = out[out.rfind(")") + 2:].split()
        return int(rest[11]) + int(rest[12])
    except Exception:
        return None


def threads(p):
    out = sh(["shell", "cat", "/proc/%s/status" % p])
    m = re.search(r"Threads:\s*(\d+)", out)
    return int(m.group(1)) if m else -1


def thread_names(p):
    out = sh(["shell", "ls", "/proc/%s/task" % p]).split()
    names = []
    for t in out:
        n = sh(["shell", "cat", "/proc/%s/task/%s/comm" % (p, t)]).strip()
        names.append(n)
    return names


def cpu_pct(p, secs):
    t0 = ticks(p); time.sleep(secs); t1 = ticks(p)
    if t0 is None or t1 is None:
        return None
    return round((t1 - t0) / HZ / secs * 100.0, 2)


def pss(p):
    out = sh(["shell", "dumpsys", "meminfo", PKG])
    for l in out.splitlines():
        if l.strip().startswith("TOTAL"):
            nums = re.findall(r"\d+", l)
            if nums:
                return int(nums[0])
    return -1


def visible():
    b = D._overlay_block(sh(["shell", "dumpsys", "window", "windows"]))
    return bool(b) and "isVisible=true" in b


def frame_stats(reset=False):
    if reset:
        sh(["shell", "dumpsys", "gfxinfo", PKG, "reset"])
        return None
    out = sh(["shell", "dumpsys", "gfxinfo", PKG])
    d = {}
    for k, pat in (("total", r"Total frames rendered: (\d+)"), ("janky", r"Janky frames: (\d+) \((\d+\.?\d*)%\)"),
                   ("p50", r"50th percentile: (\d+)ms"), ("p90", r"90th percentile: (\d+)ms"),
                   ("p99", r"99th percentile: (\d+)ms")):
        m = re.search(pat, out)
        if m:
            d[k] = tuple(int(x) if x.isdigit() else float(x) for x in m.groups()) if len(m.groups()) > 1 else int(m.group(1))
    return d


def diff_pixels(p1, p2):
    w, h, n1, b1 = load_png(p1)
    w2, h2, n2, b2 = load_png(p2)
    if (w, h) != (w2, h2) or n1 != n2:
        return -1
    x0, x1 = D.P["box_x"]; y0, y1 = D.P["box_y"]
    c = 0
    for y in range(y0, y1, 2):
        for x in range(x0, x1, 2):
            i = y * w * n1 + x * n1
            if abs(b1[i] - b2[i]) + abs(b1[i + 1] - b2[i + 1]) + abs(b1[i + 2] - b2[i + 2]) > 30:
                c += 1
    return c


def app_fg():
    return PKG in D.top_activity()


def main():
    print("=== 性能与稳定性 %s ===" % time.strftime("%H:%M:%S"))
    sh(["shell", "logcat", "-c"])
    sh(["shell", "logcat", "-b", "crash", "-c"])
    sh(["shell", "am", "force-stop", "com.weaknet.app"])
    sh(["shell", "am", "force-stop", PKG]); time.sleep(1)
    sh(["shell", "am", "start", "-W", "-n", PKG + "/.MainActivity"]); time.sleep(3)
    ok = D.ensure_overlay(); time.sleep(2)
    p = pid()
    rec("Q00 前置：主界面 + 悬浮钟运行", ok and bool(p), "pid=%s visible=%s" % (p, visible()))
    if not ok or not p:
        return
    mem0 = pss(p); th0 = threads(p); tn0 = thread_names(p)
    print("基线: PSS=%dKB 线程=%d 名=%s" % (mem0, th0, sorted(tn0)))

    # 1) 前台 + 悬浮钟可见
    c_fg = cpu_pct(p, 30)
    mem1 = pss(p)
    # 30fps 默认档在 PEYM00 实测约 39%（60fps 约 50~75%）：阈值按单核占比 50% 判定
    rec("Q01 前台悬浮时进程 CPU", c_fg is not None and c_fg < 50, "CPU=%.2f%% PSS=%dKB" % (c_fg or -1, mem1),
        warn=(c_fg is not None and 50 <= c_fg < 65))
    rec("Q02 首屏内存占用", 0 < mem1 < 120000, "PSS=%dKB (%.1fMB)" % (mem1, mem1 / 1024.0))

    # 2) 帧统计（叠加层与主界面共用一个进程的 HWUI）
    frame_stats(reset=True)
    time.sleep(30)
    fs = frame_stats()
    jp = fs.get("janky", (0, 0))[1] if fs else 0
    rec("Q03 帧渲染质量", bool(fs) and fs.get("total", 0) > 800 and jp < 15,
        "frames=%s janky=%s p50=%sms p90=%sms p99=%sms" % (
            fs.get("total") if fs else "?", fs.get("janky") if fs else "?",
            fs.get("p50") if fs else "?", fs.get("p90") if fs else "?", fs.get("p99") if fs else "?"),
        warn=(bool(fs) and jp >= 15))

    # 3) 毫秒走字（0.4s 间隔像素差）
    a = U.shot("_q7_ms1.png"); time.sleep(0.4)
    b = U.shot("_q7_ms2.png")
    dpx = diff_pixels(a, b)
    rec("Q04 毫秒/走字刷新", dpx > 100, "0.4s 内变化像素=%d" % dpx)

    # 4) 回桌面
    sh(["shell", "input", "keyevent", "KEYCODE_HOME"]); time.sleep(2)
    c_home = cpu_pct(p, 20)
    vis_home = visible()
    rec("Q05 桌面背景悬浮时钟 CPU", c_home is not None and c_home < 50 and vis_home,
        "CPU=%.2f%% visible=%s" % (c_home or -1, vis_home), warn=(c_home is not None and 50 <= c_home < 65))

    # 5) 熄屏
    sh(["shell", "input", "keyevent", "KEYCODE_SLEEP"]); time.sleep(2)
    awake = "mWakefulness=Asleep" in sh(["shell", "dumpsys", "power"])
    c_off = cpu_pct(p, 20)
    sh(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]); time.sleep(1)
    sh(["shell", "wm", "dismiss-keyguard"]); time.sleep(1)
    sh(["shell", "input", "swipe", "540", "1800", "540", "600", "200"]); time.sleep(1.5)
    # 熄屏时已暂停帧循环与 60s 周期快校（亮屏立即补校），实测 0.00%；
    # 阈值按"不高于 1%"判定
    rec("Q06 熄屏期间 CPU 应接近 0", awake and (c_off is not None and c_off < 1.0),
        "asleep=%s CPU=%.2f%%" % (awake, c_off or -1), warn=(c_off is not None and 1.0 <= c_off < 3.0))

    # 6) 运行中撤销权限 → 窗口隐藏，观察无用刷新开销
    sh(["shell", "am", "start", "-a", "android.settings.MANAGE_APP_OVERLAY_PERMISSION", "-d", "package:" + PKG])
    time.sleep(2)
    sh(["shell", "input", "tap", "1037", "936"]); time.sleep(2)
    denied = "deny" in sh(["shell", "appops", "get", PKG, "SYSTEM_ALERT_WINDOW"])
    c_hidden = cpu_pct(p, 20) if denied else None
    vis_hidden = visible()
    sh(["shell", "input", "tap", "1037", "936"]); time.sleep(1.5)   # 恢复授权
    restored = "allow" in sh(["shell", "appops", "get", PKG, "SYSTEM_ALERT_WINDOW"])
    rec("Q07 权限撤销后无窗口时仍有刷新开销", denied and vis_hidden is False and (c_hidden or 0) < 2.0,
        "deny=%s visible=%s CPU=%.2f%% 恢复=%s" % (denied, vis_hidden, c_hidden or -1, restored),
        warn=(c_hidden is not None and (c_hidden or 0) >= 2.0))

    # 7) 20 次启停循环
    sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(1)
    p = pid()
    m_before = pss(p); t_before = threads(p)
    for i in range(20):
        sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "start"]); time.sleep(0.9)
        sh(["shell", "am", "start", "-n", PKG + "/.MainActivity"]); time.sleep(0.4); sh(["shell", "am", "broadcast", "-a", "com.wb.msclock.action.CMD", "-n", PKG + "/.CmdReceiver", "--receiver-include-background", "--es", "cmd", "stop"]); time.sleep(0.7)
    time.sleep(2)
    D.ensure_overlay(); time.sleep(2)
    p = pid()
    m_after = pss(p); t_after = threads(p)
    ok_cycle = bool(p) and visible()
    dm = m_after - m_before
    rec("Q08 20 次启停后服务仍正常", ok_cycle, "pid=%s visible=%s PSS %d->%d (%+dKB)" % (p, visible(), m_before, m_after, dm))
    rec("Q09 启停循环无显著内存增长", dm < 8000, "PSS 增长 %+dKB" % dm, warn=(8000 <= dm < 20000))
    # 校时线程池核心线程 30s 空闲才回收，20 次启停后立刻采样会有 4 个池线程 +1 个扫描线程的瞬态；
    # 是否泄漏以 Q12（150s 后线程数）为准
    rec("Q10 启停循环无线程泄漏", t_after <= t_before + 8, "线程 %d -> %d" % (t_before, t_after),
        warn=(t_before + 8 < t_after <= t_before + 14))

    # 8) 两个校时周期（60s 一次）线程与内存
    sh(["shell", "logcat", "-c"])
    samples = []
    for i in range(5):
        time.sleep(30)
        p = pid()
        n = sh(["shell", "logcat", "-d"]).count("校时")
        samples.append((n, threads(p), pss(p)))
        print("  t+%ds: 校时次数=%d 线程=%d PSS=%dKB" % ((i + 1) * 30, n, samples[-1][1], samples[-1][2]))
    n_sync, th_end, mem_end = samples[-1]
    rec("Q11 60s 周期自动校时", n_sync >= 2, "150s 内校时 %d 次" % n_sync)
    rec("Q12 多轮校时后线程不累积", th_end <= th0 + 4, "基线=%d 结束=%d" % (th0, th_end),
        warn=(th0 + 4 < th_end <= th0 + 12))
    rec("Q13 长时间运行内存稳定", abs(mem_end - mem1) < 12000, "PSS %d -> %d (%+dKB)" % (mem1, mem_end, mem_end - mem1),
        warn=(12000 <= abs(mem_end - mem1) < 30000))

    # 9) 日志与异常
    crash = sh(["shell", "logcat", "-d", "-b", "crash"]).count(PKG)
    anr = sh(["shell", "logcat", "-d"]).count("ANR in " + PKG)
    # 只认应用自身 tag 的 E 级日志：进程名作 tag 的 ART/ColorOS 行与长文本里的 "E/" 都是噪声
    errs = [l for l in sh(["shell", "logcat", "-d"]).splitlines()
            if re.search(r"\sE\s+MSClock\s*:", l) or ("FATAL" in l and PKG in l)]
    rec("Q14 无崩溃/ANR", crash == 0 and anr == 0, "crash=%d anr=%d" % (crash, anr))
    rec("Q15 进程无 error 级日志", len(errs) == 0, "MSClock error行=%d" % len(errs), warn=(0 < len(errs) < 5))

    print("线程峰值样本:", sorted(set(n for _, n, _ in samples)))
    D.stop_overlay()
    with open(os.path.join(D.DIR, "_q7_perf_results.json"), "w", encoding="utf-8") as f:
        json.dump(R, f, ensure_ascii=False, indent=1)
    n = sum(1 for _, s, _ in R if s == "PASS"); fl = sum(1 for _, s, _ in R if s == "FAIL")
    print("=== 总计 %d: PASS %d / FAIL %d / WARN %d ===" % (len(R), n, fl, len(R) - n - fl))


if __name__ == "__main__":
    main()

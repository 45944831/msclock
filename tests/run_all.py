# -*- coding: utf-8 -*-
"""真机全套回归驱动：顺序执行全部测试集，流式写入 _full_run.log"""
import subprocess, sys, os, time

DIR = os.path.dirname(os.path.abspath(__file__))
SUITES = ["deep_test.py", "test_ui.py", "test_appearance.py", "test_net.py",
          "test_edge.py", "test_perm.py", "test_compat.py", "test_perf.py"]
env = dict(os.environ, PYTHONUTF8="1", PYTHONIOENCODING="utf-8")

log = open(os.path.join(DIR, "_full_run.log"), "w", encoding="utf-8", buffering=1)
for s in SUITES:
    for pkg in ("com.wb.msclock", "com.weaknet.app"):
        subprocess.run(["adb", "shell", "am", "force-stop", pkg], capture_output=True)
    log.write("\n===== %s  start %s =====\n" % (s, time.strftime("%H:%M:%S")))
    t0 = time.time()
    p = subprocess.Popen([sys.executable, os.path.join(DIR, s)], cwd=DIR,
                         stdout=log, stderr=subprocess.STDOUT, env=env)
    try:
        rc = p.wait(timeout=1500)
    except subprocess.TimeoutExpired:
        p.kill(); rc = "TIMEOUT"
    log.write("\n===== %s  end %s  rc=%s  %.0fs =====\n"
              % (s, time.strftime("%H:%M:%S"), rc, time.time() - t0))
log.write("\nALL DONE %s\n" % time.strftime("%H:%M:%S"))
log.close()

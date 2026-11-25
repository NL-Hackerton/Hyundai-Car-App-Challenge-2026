#!/usr/bin/env python3
"""Timed end-to-end rehearsal of the live demo path (pt/demo_plan.md), with screen recording.

route -> open (74%) -> ramp -> Stay ... -> Go appears -> tap Go -> navi -> stop -> reopen StayGo
-> drive (lock) -> park. Prints the elapsed time of every step and state change, saves
screenshots of the key states and the raw screenrecord segments.

    python3 apps/staygo/tools/demo_rehearse.py OUT_DIR [--no-record]

Env: SERIAL (default emulator-5560). Do not run Gradle builds at the same time: the emulator
hangs under full CPU load and its watchdog kills it.
"""
import os
import re
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
LIVE = os.path.join(HERE, "demo_live.sh")
SERIAL = os.environ.get("SERIAL", "emulator-5560")
ADB = ["adb", "-s", SERIAL]
OUT = sys.argv[1] if len(sys.argv) > 1 else "demo_run"
RECORD = "--no-record" not in sys.argv
os.makedirs(OUT, exist_ok=True)
T0 = time.time()
LOG = open(os.path.join(OUT, "timeline.txt"), "w")


def mark(msg):
    line = f"{time.time() - T0:7.1f}s  {time.strftime('%H:%M:%S')}  {msg}"
    print(line, flush=True)
    LOG.write(line + "\n"); LOG.flush()


def sh(*args, **kw):
    return subprocess.run(list(args), capture_output=True, text=True, **kw)


def live(cmd):
    r = sh(LIVE, cmd, env={**os.environ, "SERIAL": SERIAL})
    bad = [l for l in r.stdout.splitlines() if " PUT " in l and not l.endswith(" 200")]
    tail = r.stdout.strip().splitlines()[-1:] if cmd == "route" else []
    mark(f"demo_live.sh {cmd}" + (f"  !! {bad}" if bad else "") + (f"  -> {tail[0]}" if tail else ""))
    return r.returncode


def shot(name):
    with open(os.path.join(OUT, f"{name}.png"), "wb") as f:
        subprocess.run(ADB + ["exec-out", "screencap", "-p"], stdout=f)
    mark(f"screenshot {name}.png")


rec_stop = threading.Event()


def recorder():
    i = 0
    while not rec_stop.is_set():
        i += 1
        mark(f"record segment {i} start")
        p = subprocess.Popen(ADB + ["shell", "screenrecord", "--size", "1920x1080", "--bit-rate", "6000000",
                                    "--time-limit", "178", f"/sdcard/demo_{i}.mp4"])
        while p.poll() is None:
            if rec_stop.is_set():
                sh(*ADB, "shell", "pkill", "-INT", "screenrecord")
                p.wait()
            time.sleep(0.5)
        if p.returncode != 0 and not rec_stop.is_set():
            mark(f"RECORD FAILED (screenrecord exit {p.returncode}); device gone?")
            break
    time.sleep(2)
    for k in range(1, i + 1):
        sh(*ADB, "pull", f"/sdcard/demo_{k}.mp4", os.path.join(OUT, f"seg_{k}.mp4"))
        sh(*ADB, "shell", "rm", f"/sdcard/demo_{k}.mp4")
    mark(f"pulled {i} segments")


sh(*ADB, "shell", "settings", "put", "system", "show_touches", "1")
sh(*ADB, "logcat", "-c")
if live("route") != 0 and live("route") != 0:
    mark("ABORT: navi route not set"); sys.exit(1)
live("open")
time.sleep(4)
rec = None
if RECORD:
    rec = threading.Thread(target=recorder); rec.start()
time.sleep(4)
shot("01_open_74")

ramp = subprocess.Popen([LIVE, "ramp"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                        env={**os.environ, "SERIAL": SERIAL})
t_ramp = time.time()
mark("RAMP start (this is the 'LIVE -2:10' cue)")


def ramp_reader():
    done = set()
    for l in ramp.stdout:
        LOG.write("    ramp| " + l)
        m = re.search(r"SOC (\d+)%", l)
        if not m:
            continue
        soc = int(m.group(1))
        mark(f"+{time.time() - t_ramp:5.1f}s after ramp: SOC {soc}%")
        for target, name in ((78, "02_stay_78"), (83, "03_stay_83")):
            if soc >= target and name not in done:
                done.add(name); time.sleep(1.5); shot(name)


threading.Thread(target=ramp_reader, daemon=True).start()

logcat = subprocess.Popen(ADB + ["logcat", "-v", "time", "-s", "StayGo:I"], stdout=subprocess.PIPE, text=True)
last_key, go_at = None, None
for line in logcat.stdout:
    m = re.search(r"DECISION rule=(\S+) emphasis=(\S+) state=(\S+) soc=(\S+) A=(\S+) P=(\S+) Pmax=(\S+)", line)
    if not m:
        continue
    rule, emph, state, soc, a, p, pmax = m.groups()
    key = (rule, emph, state)
    if key != last_key:
        mark(f"+{time.time() - t_ramp:5.1f}s after ramp: rule={rule} emphasis={emph} state={state} soc={soc} A={a} P={p} Pmax={pmax}")
        last_key = key
    if emph == "GO" and go_at is None:
        go_at = time.time()
        mark(f"GO shown {go_at - t_ramp:.1f}s after ramp start (soc={soc}, A={a})")
        time.sleep(2)
        shot("04_go")
        break
    if time.time() - t_ramp > 420:
        mark("TIMEOUT: no GO within 7 min")
        break
logcat.terminate()

if go_at:
    # uiautomator dump fails on this image ("could not get idle state"); the Go button sits at a
    # fixed spot on the 2560x1440 head unit (right half of the bottom action row).
    pos = (2100, 1120)
    if pos:
        sh(*ADB, "shell", "input", "tap", str(pos[0]), str(pos[1]))
        mark("tapped Go")
        time.sleep(1)
        top = sh(*ADB, "shell", "dumpsys", "activity", "activities").stdout
        fg = re.search(r"topResumedActivity=.*? (\S+/\S+)", top)
        mark(f"foreground after Go: {fg.group(1) if fg else '?'}")
        time.sleep(6)
        shot("05_after_go_navi")
        # The navi cancels and rebuilds the route on Go and restarts guidance after its ~5 s "안내 시작"
        # countdown; returning to StayGo before that shows "목적지 없음" for a moment. Runbook: 15 s.
        time.sleep(8)
live("stop")
time.sleep(1)
sh(*ADB, "shell", "am", "start", "-n", "ai.staygo.app/ai.staygo.MainActivity")
mark("reopened StayGo")
time.sleep(4)
shot("06_back_in_app")
live("drive")
time.sleep(5)
shot("07_drive_lock")
live("park")
time.sleep(5)
shot("08_parked")
ramp.wait(timeout=10)
if rec:
    rec_stop.set(); rec.join()
mark("done")

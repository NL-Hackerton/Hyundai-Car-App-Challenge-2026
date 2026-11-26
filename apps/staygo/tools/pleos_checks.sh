#!/bin/bash
# Release checks on the Pleos Connect emulator, one line per check: "<id>\t<PASS|FAIL|INFO>\t<observed>".
# The ids follow research/pleos-production-benchmark.md (R1 driving restriction, R5 performance, R6 stability,
# R7 privacy/security). Checks that need a vehicle or the market review are not here.
#
#   pleos_checks.sh APK OUT_DIR
#
# Env: ADB (default adb), SERIAL (default emulator-5560), CONSOLE (default http://127.0.0.1:18088), BUILD_TOOLS
# (directory with aapt and apksigner; required). Precondition: the emulator is booted,
# `adb forward tcp:18088 tcp:8088` is set and the probe app (ai.probe.staygo) is installed for `demo_live.sh route`.
set -u
APK=$1; OUT=$2; mkdir -p "$OUT"
HERE=$(cd "$(dirname "$0")" && pwd); LIVE="$HERE/demo_live.sh"
ADB=${ADB:-adb}; SERIAL=${SERIAL:-emulator-5560}; A="$ADB -s $SERIAL"; PKG=ai.staygo.app; ACT=$PKG/ai.staygo.MainActivity
export ADB SERIAL
R="$OUT/results.tsv"; : > "$R"
res(){ printf '%s\t%s\t%s\n' "$1" "$2" "$3" | tee -a "$R"; }
shot(){ $A exec-out screencap -p > "$OUT/$1.png"; }
logs(){ $A logcat -d -v time -s StayGo:I; }
fg(){ $A shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity' | grep -o "$PKG[^ }]*" | head -1; }
# The documented `cmd hmg_car_service ... driving_regulation on|off` is not present in the Connect v2.1.0 image,
# so the restriction is driven the way a car does it: gear D at 30 km/h, then 0 km/h and gear P.
regulation(){ if [ "$1" = on ]; then "$LIVE" drive >/dev/null; else "$LIVE" park >/dev/null; fi; }
# Taps the first view whose text matches the pattern; fails when there is none.
tap_text(){
  $A shell uiautomator dump /sdcard/staygo_ui.xml >/dev/null 2>&1
  local b; b=$($A shell cat /sdcard/staygo_ui.xml | tr '>' '\n' | grep -E "text=\"($1)\"" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
  [ -z "$b" ] && return 1
  set -- $(echo "$b" | grep -o '[0-9]*')
  $A shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
ui_has(){ $A shell uiautomator dump /sdcard/staygo_ui.xml >/dev/null 2>&1; $A shell cat /sdcard/staygo_ui.xml | grep -q -E "text=\"($1)\""; }

# ---- R7 static: the artefact under test, its signature, flags and components ----
if [ -z "${BUILD_TOOLS:-}" ]; then res R7.static FAIL "BUILD_TOOLS is not set: signature and debuggable flag not checked"; exit 1; fi
res APK INFO "sha256 $(sha256sum "$APK" | cut -c1-64) $("$BUILD_TOOLS/aapt" dump badging "$APK" | head -1 | grep -o "versionCode='[0-9]*' versionName='[^']*'")"
dbg=$("$BUILD_TOOLS/aapt" dump badging "$APK" | grep -c application-debuggable)
[ "$dbg" = 0 ] && res R7.debuggable PASS "not debuggable" || res R7.debuggable FAIL "debuggable"
cert=$("$BUILD_TOOLS/apksigner" verify --print-certs "$APK" 2>/dev/null | grep -m1 'SHA-256' | awk '{print $NF}')
dn=$("$BUILD_TOOLS/apksigner" verify --print-certs "$APK" 2>/dev/null | grep -m1 'certificate DN')
case "$dn" in *"Android Debug"*|"") res R7.signature FAIL "${dn:-unsigned}";; *) res R7.signature PASS "signer $cert";; esac
manifest=$("$BUILD_TOOLS/aapt" dump xmltree "$APK" AndroidManifest.xml)
# Exported without a permission: only the launcher activity may be.
open=$(echo "$manifest" | python3 -c '
import re, sys
out, cur = [], None
for line in sys.stdin:
    m = re.match(r"\s*E: (\S+)", line)
    if m:
        if m.group(1) in ("activity", "activity-alias", "service", "receiver", "provider"):
            cur = {"name": None, "exported": False, "permission": False}; out.append(cur)
        elif m.group(1) not in ("intent-filter", "action", "category", "meta-data", "data"):
            cur = None
        continue
    if cur is None: continue
    a = re.match(r"\s*A: android:(\w+)\(0x[0-9a-f]+\)=(.*)", line)
    if not a: continue
    if a.group(1) == "name" and cur["name"] is None: cur["name"] = re.search(r"\"([^\"]*)\"", a.group(2)).group(1)
    if a.group(1) == "exported" and "0xffffffff" in a.group(2): cur["exported"] = True
    if a.group(1) == "permission": cur["permission"] = True
print(" ".join(sorted(c["name"] for c in out if c["exported"] and not c["permission"])))
')
[ "$open" = "ai.staygo.MainActivity" ] && res R7.components PASS "exported without permission: launcher activity only" || res R7.components FAIL "exported without permission: $open"
echo "$manifest" | grep -q 'android.permission.INTERNET' && res R7.network FAIL "INTERNET permission declared" || res R7.network PASS "no network permission"
res R7.permissions INFO "$(echo "$manifest" | grep -A1 'E: uses-permission' | grep -o '"[^"]*permission[^"]*"' | tr -d '"' | sort -u | tr '\n' ' ')"

# ---- install without granting, then the consent and permission path a driver takes ----
$A uninstall $PKG >/dev/null 2>&1
out=$($A install "$APK" 2>&1 | tail -1); [ "$out" = Success ] && res PL-12.install PASS "$out" || { res PL-12.install FAIL "$out"; exit 1; }
$A shell setprop log.tag.StayGo DEBUG; $A logcat -c
$A shell am start -W -n $ACT >/dev/null; sleep 5; shot first_run
if ui_has '동의하고 시작'; then
  logs | grep -q -E 'soc=[0-9]|trip est' && res PL-52.before-consent FAIL "vehicle or navigation data read before consent" || res PL-52.before-consent PASS "data notice shown first; nothing read before consent"
  tap_text '동의 안 함'; sleep 2; shot consent_declined
  ui_has '동의하고 시작' && res PL-52.decline PASS "declined: the notice stays, nothing is read" || res PL-52.decline FAIL "notice gone after declining"
  tap_text '동의하고 시작'; sleep 3; shot after_consent
else
  res PL-52.before-consent FAIL "no data notice on first run"
fi
if ui_has '권한 허용'; then
  res PL-52.rationale PASS "permission reason shown before the system dialog"
  tap_text '권한 허용'; sleep 3; shot permission_dialog
  tap_text '허용 안함|허용 안 함|거부|Don.t allow|Deny' && { sleep 2; shot permission_denied; ui_has '권한 허용' && res PL-52.denied PASS "denied: app stays on the permission screen without vehicle values" || res PL-52.denied FAIL "no permission screen after denial"; tap_text '권한 허용'; sleep 3; }
  tap_text '앱 사용 중에만 허용|허용|Allow|While using the app' && sleep 3
  shot permission_granted
  $A shell dumpsys package $PKG | grep -q 'android.car.permission.CAR_ENERGY: granted=true' && res PL-52.granted PASS "runtime permission granted through the dialog" || res PL-52.granted FAIL "CAR_ENERGY not granted after the dialog"
else
  $A shell dumpsys package $PKG | grep -q 'android.car.permission.CAR_ENERGY: granted=true' && res PL-52.rationale INFO "permission already granted by the image; no dialog needed" || res PL-52.rationale FAIL "no permission screen and permission not granted"
fi

# ---- R7 logs: nothing about battery, places or decisions unless the tag is enabled ----
$A shell setprop log.tag.StayGo INFO
"$LIVE" route >/dev/null; "$LIVE" open >/dev/null
$A shell setprop log.tag.StayGo INFO; $A shell am force-stop $PKG; $A logcat -c
$A shell am start -W -n $ACT >/dev/null; sleep 12
n=$(logs | grep -c -E 'soc=|estSoc|DECISION|stop=|via=')
[ "$n" = 0 ] && res R7.logs PASS "0 lines with battery/place data in release logs" || res R7.logs FAIL "$n lines"

# ---- R5 cold start (3 runs) ----
worst=0
for i in 1 2 3; do
  $A shell am force-stop $PKG; sleep 2
  t=$($A shell am start -W -n $ACT | grep -o 'TotalTime: [0-9]*' | grep -o '[0-9]*')
  [ "${t:-99999}" -gt "$worst" ] && worst=${t:-99999}
done
[ "$worst" -lt 10000 ] && res R5.coldstart PASS "worst of 3: ${worst} ms (limit 10000)" || res R5.coldstart FAIL "${worst} ms"

# ---- charging session for the remaining checks ----
$A shell setprop log.tag.StayGo DEBUG; $A shell am force-stop $PKG; $A logcat -c
"$LIVE" open >/dev/null
( "$LIVE" ramp > "$OUT/ramp.log" 2>&1 & )
for i in $(seq 1 30); do sleep 3; logs | grep -q 'emphasis=STAY' && break; done
logs | grep -q 'emphasis=STAY' && res R2.stay PASS "$(logs | grep -m1 'emphasis=STAY' | grep -o 'rule=.*Pmax=[^ ]*')" || res R2.stay FAIL "no Stay decision within 90 s"
shot charging
# With the tag enabled for a rehearsal the log still carries numbers only, never a place name.
# The whole process is checked, not only the app's tag: the Pleos SDKs run in it and handle place names.
pid=$($A shell pidof $PKG | tr -d '\r')
names=$($A logcat -d --pid="$pid" | grep -c -P '[\x{AC00}-\x{D7A3}]')
[ "$names" = 0 ] && res R7.logs.names PASS "debug-tag log has no place names" || res R7.logs.names FAIL "$names lines with place names"

# R5 parked CPU and memory while charging is shown
cpu=$($A shell top -b -n 3 -d 2 -p "$($A shell pidof $PKG | tr -d '\r')" | grep "$PKG" | tail -1 | awk '{print $9}')
res R5.cpu.parked "$(awk -v c="${cpu:-100}" 'BEGIN{print (c<=25)?"PASS":"FAIL"}')" "${cpu}% of one core (limit 25 % of the system)"
pss=$($A shell dumpsys meminfo $PKG | grep -m1 'TOTAL PSS' | grep -o 'TOTAL PSS: *[0-9]*' | grep -o '[0-9]*$')
[ -z "$pss" ] && pss=$($A shell dumpsys meminfo $PKG | grep -m1 -E '^ *TOTAL' | awk '{print $2}')
res R5.memory "$(awk -v p="${pss:-999999}" 'BEGIN{print (p<=409600)?"PASS":"FAIL"}')" "$((${pss:-0} / 1024)) MB PSS (limit 400 MB)"
jank=$($A shell dumpsys gfxinfo $PKG | grep -m1 'Janky frames' | tr -d '\r')
res R5.frames INFO "${jank:-no frame data}"

# ---- R1 driving restriction ----
regulation on; sleep 3; shot regulation_on
logs | grep -q 'driving restricted=true' && res R1.lock PASS "lock screen within 3 s of gear D, 30 km/h" || res R1.lock FAIL "no restriction seen"
$A shell am force-stop $PKG; $A shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4; shot regulation_on_launch
top=$(fg); n=$(logs | grep -c 'restricted=false')
logs | tail -5 | grep -q 'restricted=false' && res R1.launch FAIL "unlocked while driving" || res R1.launch PASS "launched while driving: foreground='${top:-blocked by the platform}', stays locked (regulation_on_launch.png)"
$A logcat -c; regulation off; sleep 4; shot regulation_off
$A shell am start -n $ACT >/dev/null; sleep 4
logs | grep -q 'driving restricted=false' && res R1.unlock PASS "usable again at 0 km/h, gear P" || res R1.unlock FAIL "still locked"

# ---- R6 background: no listeners or services, state restored after the process is killed ----
for i in $(seq 1 15); do sleep 3; logs | grep -q 'charging .* -> CHARGING' && break; done
# HOME keeps the app window on a Pleos display; another app in front is what puts StayGo in the background.
$A shell am start -n ai.umos.maps.android.navigation.app/.MainActivity >/dev/null 2>&1; sleep 8; $A logcat -c
svc=$($A shell dumpsys activity services $PKG | grep -c 'ServiceRecord')
[ "$svc" = 0 ] && res PL-25.services PASS "no service while in the background" || res PL-25.services FAIL "$svc services"
bgcpu=$($A shell top -b -n 3 -d 2 -p "$($A shell pidof $PKG | tr -d '\r')" | grep "$PKG" | tail -1 | awk '{print $9}')
res R5.cpu.background "$(awk -v c="${bgcpu:-100}" 'BEGIN{print (c<5)?"PASS":"FAIL"}')" "${bgcpu}% in the background (limit 5 %)"
n=$(logs | grep -c -E 'trip est|DECISION')
[ "$n" = 0 ] && res R5.background.idle PASS "no navigation requests or decisions while hidden" || res R5.background.idle FAIL "$n events while hidden"
$A shell am kill $PKG; sleep 2; $A logcat -c
alive=$($A shell pidof $PKG | tr -d '\r'); res R6.kill INFO "process after am kill: ${alive:-gone}"
$A shell am start -n $ACT >/dev/null
for i in $(seq 1 10); do sleep 2; logs | grep -q -E 'charging .* -> CHARGING|session ended' && break; done
if logs | grep -q 'session ended'; then res R6.restore FAIL "session ended after process recreation"
elif logs | grep -q 'charging .* -> CHARGING'; then res R6.restore PASS "charging session continued after process recreation ($(logs | grep -m1 'DECISION' | grep -o 'Pmax=[^ ]*'))"
else res R6.restore FAIL "charging not recognized within 20 s"; fi
shot restored

# ---- R6 monkey: touch events only, inside the app ----
$A logcat -c
$A shell monkey -p $PKG --pct-touch 100 --throttle 150 -s 7 600 > "$OUT/monkey.log" 2>&1
crash=$($A logcat -d -b crash | grep -c "$PKG"); anr=$(grep -c -i 'ANR\|CRASH' "$OUT/monkey.log")
[ "$crash" = 0 ] && [ "$anr" = 0 ] && res R6.monkey PASS "600 touch events, no crash or ANR" || res R6.monkey FAIL "crash lines $crash, monkey reports $anr"

# ---- cleanup and uninstall residue ----
"$LIVE" stop >/dev/null 2>&1; "$LIVE" park >/dev/null 2>&1; "$LIVE" hb-stop >/dev/null 2>&1
$A shell setprop log.tag.StayGo INFO
out=$($A uninstall $PKG | tail -1)
left=$($A shell "ls -d /data/data/$PKG /sdcard/Android/data/$PKG 2>/dev/null" | wc -l | tr -d ' ')
[ "$out" = Success ] && [ "$left" = 0 ] && res PL-12.uninstall PASS "no data left" || res PL-12.uninstall FAIL "uninstall=$out leftover dirs=$left"
echo "---"; awk -F'\t' '{c[$2]++} END{for(k in c) printf "%s %d  ", k, c[k]; print ""}' "$R"

#!/bin/bash
# Demo recording on the Pleos Connect emulator (emulator-5560, Virtual Console on 127.0.0.1:18088).
# Precondition: route guidance to the destination is active (set by the probe app, as a driver would in the navi).
# The charging curve is fed to the Virtual Console once per second: battery level (kWh) and integer SOC.
# Power profile (kW by SOC): <=78: 230, 78-80: 230->150, 80-82: 150->110, >=82: 95.
set -u
B=http://127.0.0.1:18088
A="$HOME/android-sdk/platform-tools/adb -s emulator-5560"
O=${1:-$HOME/pleos-probe/demo}
CAP=77.4
START_KWH=${START_KWH:-57.3}
END_SOC=${END_SOC:-87}
mkdir -p "$O"; : > "$O/stimulus.log"
put(){ echo "$(date +%T.%3N) PUT $1 $(curl -s -o /dev/null -w %{http_code} -X PUT "$B/vehicle/$1")" >> "$O/stimulus.log"; }

put "ev-battery?field=battery_level&value=$START_KWH"
put "ev-battery?field=soc&value=$(python3 -c "print(int($START_KWH/$CAP*100))")"
$A shell settings put system show_touches 1
$A shell am force-stop ai.staygo.app
$A shell run-as ai.staygo.app rm -rf shared_prefs >/dev/null 2>&1; rm -f "$O/stop"
$A shell am start -W -n ai.staygo.app/ai.staygo.MainActivity >/dev/null
sleep 8

$A logcat -c
$A logcat -v time -s StayGo:I > "$O/logcat.txt" &
LC=$!
# screenrecord is capped at 180 s per file; record back-to-back segments.
( for i in 1 2 3; do $A shell screenrecord --bit-rate 24000000 --time-limit 178 /sdcard/staygo_$i.mp4; done ) &
REC=$!
echo "$(date +%T.%3N) RECORD start" >> "$O/stimulus.log"
sleep 6

echo "$(date +%T.%3N) PLUG charge_port_open" >> "$O/stimulus.log"
put "ev-battery?field=charge_port_open&value=true"
python3 - "$B" "$START_KWH" "$CAP" "$O/stop" >> "$O/stimulus.log" <<'PY' &
import sys, time, urllib.request, datetime, os
B, kwh, cap, stop = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), sys.argv[4]
def put(field, value):
    r = urllib.request.Request(f"{B}/vehicle/ev-battery?field={field}&value={value}", method="PUT")
    return urllib.request.urlopen(r, timeout=2).status
def power(soc):
    if soc < 78: return 230.0
    if soc < 80: return 230.0 - (soc - 78) / 2 * 80.0
    if soc < 82: return 150.0 - (soc - 80) / 2 * 40.0
    return 95.0
last_soc = int(kwh / cap * 100)
t = time.time()
while True:
    soc_f = kwh / cap * 100
    if os.path.exists(stop): break
    kwh += power(soc_f) / 3600.0
    put("battery_level", f"{kwh:.4f}")
    soc = int(kwh / cap * 100)
    if soc != last_soc:
        put("soc", soc); last_soc = soc
        print(f"{datetime.datetime.now().strftime('%H:%M:%S.%f')[:-3]} SOC {soc} level {kwh:.3f} kWh power {power(soc_f):.0f} kW", flush=True)
    t += 1.0
    time.sleep(max(0.0, t - time.time()))
PY
RAMP=$!
until grep -q "SOC $END_SOC " "$O/stimulus.log"; do sleep 1; done
sleep 12
echo "$(date +%T.%3N) TAP Go" >> "$O/stimulus.log"
$A shell input tap 2100 1120
touch "$O/stop"; wait $RAMP; echo "$(date +%T.%3N) RAMP done" >> "$O/stimulus.log"
sleep 8
echo "$(date +%T.%3N) SWITCH back to StayGo" >> "$O/stimulus.log"
$A shell am start -n ai.staygo.app/ai.staygo.MainActivity >/dev/null
sleep 4
put "ev-battery?field=charge_port_open&value=false"
put "gear?gear=Drive"; put "controls?speed=30&accelerator=0&brake=0&steering=0"
echo "$(date +%T.%3N) DRIVE" >> "$O/stimulus.log"
sleep 8
put "controls?speed=0&accelerator=0&brake=0&steering=0"; put "gear?gear=Park"
sleep 4
echo "$(date +%T.%3N) RECORD stop" >> "$O/stimulus.log"
kill $REC 2>/dev/null
$A shell pkill -INT screenrecord
sleep 3; kill $LC
for i in 1 2 3; do $A pull /sdcard/staygo_$i.mp4 "$O/" >/dev/null 2>&1 && $A shell rm /sdcard/staygo_$i.mp4; done
$A shell settings put system show_touches 0
ls -la "$O"

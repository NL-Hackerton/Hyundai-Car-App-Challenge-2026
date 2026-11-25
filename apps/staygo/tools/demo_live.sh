#!/bin/bash
# Live PT demo helper for the Pleos Connect emulator (no screen recording, no automatic tap).
# Same charging curve as demo_charge.sh: <=78%: 230 kW, 78-80%: 230->150, 80-82%: 150->110, >=82%: 95 kW.
#
#   demo_live.sh route   # start route guidance to Busan City Hall via the probe app (if the navi has no route)
#   demo_live.sh open    # reset StayGo state, set battery to START_KWH, open StayGo
#   demo_live.sh ramp    # plug in and feed the charging curve once per second (Ctrl+C or `stop` to end)
#   demo_live.sh stop    # stop the ramp
#   demo_live.sh drive   # unplug, gear D, 30 km/h  (driving lock)
#   demo_live.sh park    # 0 km/h, gear P
#   demo_live.sh hb-stop # stop the Virtual Console heartbeat (started automatically by every write)
#
# Env: ADB (default: adb), SERIAL (default: emulator-5560), CONSOLE (default: http://127.0.0.1:18088),
#      START_KWH (default 57.3 = 74%), END_SOC (default 90).
# Precondition: `adb -s $SERIAL forward tcp:18088 tcp:8088` so the Virtual Console answers on $CONSOLE.
# The Virtual Console rejects writes with 503 unless a browser session pings it every second
# (the session expires ~3 s after the last ping), so this script keeps a background heartbeat alive.
set -u
ADB=${ADB:-adb}; SERIAL=${SERIAL:-emulator-5560}; B=${CONSOLE:-http://127.0.0.1:18088}
A="$ADB -s $SERIAL"; CAP=77.4; START_KWH=${START_KWH:-57.3}; END_SOC=${END_SOC:-90}
STOP=${TMPDIR:-/tmp}/staygo_demo_stop
DEST="부산광역시청 부산"
HB_PID=${TMPDIR:-/tmp}/staygo_demo_hb.pid
hb(){
  if [ -f "$HB_PID" ] && kill -0 "$(cat "$HB_PID")" 2>/dev/null; then return; fi
  ( while true; do curl -s -o /dev/null -m 2 -X POST "$B/browser/session?client=staygo-demo"; sleep 1; done ) \
    >/dev/null 2>&1 &
  echo $! > "$HB_PID"; sleep 2
}
put(){ echo "$(date +%T) PUT $1 $(curl -s -o /dev/null -w %{http_code} -X PUT "$B/vehicle/$1")"; }
case "${1:-}" in route|open|ramp|drive|park) hb;; esac

case "${1:-}" in
  route)
    $A shell am force-stop ai.probe.staygo   # a running probe would keep its old intent (old destination)
    $A logcat -c
    # Busan City Hall exactly as the navi's own search returns it (name, POI id, address, coordinates). With an
    # empty POI id the navi pops "경로 탐색 오류" over the route screen when StayGo's Go hands the route back.
    $A shell am start -W -n ai.probe.staygo/.MainActivity --ed destLat 35.18047777 --ed destLng 129.07445277 \
      --es destName "'$DEST'" --es destPoiId 3688dcq7hqkncm5nqhe4 --es destAddress "'연제구 연산동'" \
      --ez route true >/dev/null
    # Right after boot the navi may not be ready and silently ignores the request: wait for the destination.
    for _ in $(seq 1 30); do
      sleep 1
      if $A logcat -d -s StayOrGoProbe:I | grep -q "onDrivingInfo dest=name=$DEST dist=[1-9]"; then
        echo "route OK: $DEST"; exit 0; fi
    done
    echo "ROUTE NOT SET after 30 s - run 'route' again (or set the destination in the navi)"; exit 1;;
  open)
    put "ev-battery?field=charge_port_open&value=false"
    put "ev-battery?field=battery_level&value=$START_KWH"
    put "ev-battery?field=soc&value=$(python3 -c "print(int($START_KWH/$CAP*100))")"
    $A shell am force-stop ai.staygo.app
    $A shell run-as ai.staygo.app rm -rf shared_prefs >/dev/null 2>&1
    $A shell am start -W -n ai.staygo.app/ai.staygo.MainActivity >/dev/null
    echo "StayGo opened at $START_KWH kWh";;
  ramp)
    rm -f "$STOP"
    put "ev-battery?field=charge_port_open&value=true"
    python3 - "$B" "$START_KWH" "$CAP" "$STOP" "$END_SOC" <<'PY'
import sys, time, urllib.request, datetime, os
B, kwh, cap, stop, end = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), sys.argv[4], int(sys.argv[5])
def put(field, value):
    r = urllib.request.Request(f"{B}/vehicle/ev-battery?field={field}&value={value}", method="PUT")
    try:
        return urllib.request.urlopen(r, timeout=2).status
    except Exception as e:  # one dropped write must not end the live ramp
        print(f"{datetime.datetime.now():%H:%M:%S} WARN {field} write failed: {e}", flush=True)
        return 0
def power(soc):
    if soc < 78: return 230.0
    if soc < 80: return 230.0 - (soc - 78) / 2 * 80.0
    if soc < 82: return 150.0 - (soc - 80) / 2 * 40.0
    return 95.0
last = int(kwh / cap * 100); prev = time.time()
try:
    while not os.path.exists(stop):
        soc_f = kwh / cap * 100
        if soc_f >= end: break
        # Integrate over the real elapsed time and always wait ~1 s: if a write stalls (busy host), the next
        # write carries the missed energy in one step instead of a fast catch-up burst, which the app would
        # read as a huge power.
        now = time.time(); kwh += power(soc_f) * (now - prev) / 3600.0; prev = now
        put("battery_level", f"{kwh:.4f}")
        soc = int(kwh / cap * 100)
        if soc != last:
            put("soc", soc); last = soc
            print(f"{datetime.datetime.now():%H:%M:%S} SOC {soc}% level {kwh:.2f} kWh power {power(soc_f):.0f} kW", flush=True)
        time.sleep(max(0.2, 1.0 - (time.time() - now)))
except KeyboardInterrupt:
    pass
print("ramp stopped")
PY
    ;;
  stop) touch "$STOP"; echo "stop requested";;
  hb-stop) [ -f "$HB_PID" ] && kill "$(cat "$HB_PID")" 2>/dev/null; rm -f "$HB_PID"; echo "heartbeat stopped";;
  drive)
    put "ev-battery?field=charge_port_open&value=false"
    put "gear?gear=Drive"; put "controls?speed=30&accelerator=0&brake=0&steering=0";;
  park)
    put "controls?speed=0&accelerator=0&brake=0&steering=0"; put "gear?gear=Park";;
  *) sed -n 2,18p "$0"; exit 1;;
esac

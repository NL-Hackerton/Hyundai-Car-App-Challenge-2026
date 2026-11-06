#!/bin/bash
# usage: trip_exp.sh <battery_kwh> <soc> <lat> <lng> <tag>
B=http://127.0.0.1:18088; A="$HOME/android-sdk/platform-tools/adb -s emulator-5560"
curl -s -o /dev/null -w "lvl %{http_code}\n" -X PUT "$B/vehicle/ev-battery?field=battery_level&value=$1"
curl -s -o /dev/null -w "soc %{http_code}\n" -X PUT "$B/vehicle/ev-battery?field=soc&value=$2"
sleep 2
$A shell am force-stop ai.probe.staygo; $A logcat -c
$A shell am start -W -n ai.probe.staygo/.MainActivity --ed destLat $3 --ed destLng $4 >/dev/null
sleep 20
$A logcat -d -v time -s StayOrGoProbe:I > ~/pleos-probe/trip_$5.log
grep -iE "tripinfo|estSoc|EV_CHARGING|routePoint|point|level|SOC" ~/pleos-probe/trip_$5.log | grep -v "callback#" | head -20

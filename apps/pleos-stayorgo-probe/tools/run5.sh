#!/bin/bash
# Run 5: route guidance at each battery level whose plan inserts a different charging waypoint; capture onDrivingInfo.
B=http://127.0.0.1:18088; A="$HOME/android-sdk/platform-tools/adb -s emulator-5560"; O=~/pleos-probe/run5
mkdir -p $O; : > $O/stimulus.log
P(){ echo "$(date +%T.%3N) PUT $1=$2 $(curl -s -o /dev/null -w %{http_code} -X PUT "$B/vehicle/ev-battery?field=$1&value=$2")" >> $O/stimulus.log; }
$A logcat -c
$A logcat -v time -s StayOrGoProbe:I > $O/logcat.txt &
LC=$!
for l in 62 60 50 40; do
  echo "## guidance to Busan at $l kWh" >> $O/stimulus.log
  P battery_level $l; sleep 2
  $A shell am force-stop ai.probe.staygo
  $A shell am start -W -n ai.probe.staygo/.MainActivity --ed destLat 35.1796 --ed destLng 129.0756 --ez route true >/dev/null
  sleep 40
done
kill $LC
$A shell getprop ro.build.fingerprint > $O/build.txt
echo done >> $O/stimulus.log

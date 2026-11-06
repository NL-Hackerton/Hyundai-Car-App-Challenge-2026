#!/bin/bash
# Run 6: charging-waypoint threshold on three more destinations.
# For each destination: full battery trip -> route energy E; then trips at direct arrival SOC 9.0% and 11.0%.
B=http://127.0.0.1:18088; A="$HOME/android-sdk/platform-tools/adb -s emulator-5560"; O=~/pleos-probe/run6
mkdir -p $O; : > $O/stimulus.log
P(){ echo "$(date +%T.%3N) PUT $1=$2 $(curl -s -o /dev/null -w %{http_code} -X PUT "$B/vehicle/ev-battery?field=$1&value=$2")" >> $O/stimulus.log; }
$A logcat -c
$A logcat -v time -s StayOrGoProbe:I > $O/logcat.txt &
LC=$!
trip(){ # level lat lng
  P battery_level $1; sleep 2
  $A shell am force-stop ai.probe.staygo
  $A shell am start -W -n ai.probe.staygo/.MainActivity --ed destLat $2 --ed destLng $3 >/dev/null
  sleep 12
}
for d in "gwangju 35.1595 126.8526" "gangneung 37.7519 128.8761" "daegu 35.8714 128.6014"; do
  set -- $d
  echo "## $1 full" >> $O/stimulus.log
  trip 77.4 $2 $3
  E=$(grep "onTripInfo" $O/logcat.txt | tail -1 | sed -E "s/.*energy=([0-9.]+).*/\1/")
  for s in 9.0 11.0; do
    L=$(python3 -c "print(round($E/1000+77.4*$s/100,2))")
    echo "## $1 direct ${s}% level $L (E=$E Wh)" >> $O/stimulus.log
    trip $L $2 $3
  done
done
kill $LC
echo done >> $O/stimulus.log

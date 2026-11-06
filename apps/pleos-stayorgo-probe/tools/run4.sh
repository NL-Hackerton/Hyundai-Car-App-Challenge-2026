#!/bin/bash
# Run 4: one continuous log for every item cited in the proposal (table 6, table 10).
B=http://127.0.0.1:18088; A="$HOME/android-sdk/platform-tools/adb -s emulator-5560"; O=~/pleos-probe/run4
mkdir -p $O
P(){ echo "$(date +%T.%3N) PUT $1=$2 $(curl -s -o /dev/null -w %{http_code} -X PUT "$B/vehicle/ev-battery?field=$1&value=$2")" >> $O/stimulus.log; }
G(){ echo "$(date +%T.%3N) PUT $1 $(curl -s -o /dev/null -w %{http_code} -X PUT "$B/vehicle/$1")" >> $O/stimulus.log; }
start(){ $A shell am force-stop ai.probe.staygo; $A shell am start -W -n ai.probe.staygo/.MainActivity "$@" >/dev/null; }
BUSAN="--ed destLat 35.1796 --ed destLng 129.0756"
: > $O/stimulus.log
$A logcat -c
$A logcat -v time -s StayOrGoProbe:I > $O/logcat.txt &
LC=$!
echo "## A: 22.4 km trip, SOC subscription, power estimate" >> $O/stimulus.log
P battery_level 55.7; P soc 72; sleep 2
start; sleep 12
for s in 73 74 75 76 77 78 79 80 81 82 83; do P soc $s; sleep 2; done
python3 - <<PY >> $O/stimulus.log
import time,urllib.request
lvl=55.7
def put(v):
    r=urllib.request.Request("$B/vehicle/ev-battery?field=battery_level&value=%.4f"%v,method="PUT"); urllib.request.urlopen(r).read()
for kw,n in ((150,20),(95,20)):
    print(time.strftime("%H:%M:%S"),"ramp",kw,"kW for",n,"s from",round(lvl,4))
    for i in range(n):
        lvl+=kw/3600.0; put(lvl); time.sleep(1)
print(time.strftime("%H:%M:%S"),"ramp end",round(lvl,4))
PY
echo "## B: 22.4 km trip at 30.0 kWh" >> $O/stimulus.log
P battery_level 30.0; sleep 2; start; sleep 12
echo "## C: 375 km trip at 64.2 / 63 / 62 / 60 / 50 kWh" >> $O/stimulus.log
for l in 64.2 63 62 60 50; do P battery_level $l; sleep 2; start $BUSAN; sleep 12; done
echo "## D: trip to candidate (청도새마을휴게소) at 60 kWh" >> $O/stimulus.log
P battery_level 60; sleep 2; start --ed destLat 35.5967726 --ed destLng 128.7682314; sleep 12
echo "## E: route guidance at 23.2 kWh" >> $O/stimulus.log
P battery_level 23.2; sleep 2; start $BUSAN --ez route true; sleep 40
echo "## F: UX restrictions" >> $O/stimulus.log
G "gear?gear=Drive"; G "controls?speed=30&accelerator=0&brake=0&steering=0"; sleep 6
G "controls?speed=0&accelerator=0&brake=0&steering=0"; G "gear?gear=Park"; sleep 6
kill $LC
$A shell getprop ro.build.fingerprint > $O/build.txt
wc -l $O/logcat.txt $O/stimulus.log

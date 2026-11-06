#!/bin/bash
# Run 7: charge port + charge switch on, then read EV_CHARGE_STATE and the instantaneous rate with a saved log.
B=http://127.0.0.1:18088; A="$HOME/android-sdk/platform-tools/adb -s emulator-5560"; O=~/pleos-probe/run7
mkdir -p $O; : > $O/stimulus.log
P(){ echo "$(date +%T.%3N) PUT $1=$2 $(curl -s -o /dev/null -w %{http_code} -X PUT "$B/vehicle/ev-battery?field=$1&value=$2")" >> $O/stimulus.log; }
st(){ curl -s $B/vehicle/state | python3 -c "import sys,json;e=json.load(sys.stdin)[\"ev_battery\"];print(\"$(date +%T.%3N) STATE\",{k:e.get(k) for k in (\"state_of_charge\",\"current_energy_kwh\",\"charging_activated\",\"charging_door_opened\",\"ready_to_charge\")})" >> $O/stimulus.log; }
$A logcat -c
$A logcat -v time -s StayOrGoProbe:I > $O/logcat.txt &
LC=$!
P battery_level 50; st
P charge_port_open true; sleep 2
P charge_switch true; sleep 2; st
$A shell am force-stop ai.probe.staygo
$A shell am start -W -n ai.probe.staygo/.MainActivity >/dev/null; sleep 15
P charge_switch false; sleep 2
P charge_port_open false; sleep 3; st
kill $LC
echo done >> $O/stimulus.log

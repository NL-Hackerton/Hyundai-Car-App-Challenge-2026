#!/bin/bash
B=http://127.0.0.1:18088; A="$HOME/android-sdk/platform-tools/adb -s emulator-5560"
P(){ echo "PUT $(date +%s%3N) $1=$2 $(curl -s -o /dev/null -w %{http_code} -X PUT "$B/vehicle/ev-battery?field=$1&value=$2")"; }
st(){ curl -s $B/vehicle/state | python3 -c "import sys,json;e=json.load(sys.stdin)[\"ev_battery\"];print(\"STATE\",{k:e[k] for k in (\"state_of_charge\",\"current_energy_kwh\",\"charging_activated\",\"charging_door_opened\",\"ready_to_charge\",\"charge_limit_pct\")})"; }
P soc 72; P charge_percent_limit 100 2>/dev/null; P percent_limit 100
$A shell am force-stop ai.probe.staygo; $A logcat -c
$A shell am start -W -n ai.probe.staygo/.MainActivity >/dev/null; sleep 6
st
P charge_port_open true; sleep 3; st
P charge_switch true; sleep 3; st
for s in 73 74 75 76 77 78 79 80 81 82 83; do P soc $s; sleep 2; done
P battery_level 64.2; sleep 2
P charge_switch false; sleep 3; st
P charge_port_open false; sleep 3; st
$A logcat -d -v epoch -s StayOrGoProbe:I > ~/pleos-probe/run3.log

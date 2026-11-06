# Empirical run log (Asia/Seoul)

Date: 2026-11-06. Three runs: run 1 (box) failed to boot the emulator; run 2 (4060 Ti host) booted and produced runtime logs; run 3 drove vehicle signals through the Pleos Virtual Console.

# Run 1 — box

## Environment

- Host: Linux box, `/dev/kvm` present (KVM API 12, `KVM_CREATE_VM` succeeds after chmod).
- No `/dev/dri` (no host GPU). Emulator flags tried: `-gpu swiftshader_indirect -accel on -no-window`.
- Android cmdline-tools + emulator 37.2.13, platform android-34.
- Probe APK: `./gradlew :app:assembleDebug` **BUILD SUCCESSFUL** (`ai.probe.staygo`).

## Images obtained

| Image | Source | Status |
|---|---|---|
| Pleos Connect v2.1.0 x86_64 | `https://nexus-playground.pleos.ai/.../crp_car_emulator.zip` (~3.9 GB) via public sys-img XML | Downloaded + extracted under SDK `system-images/android-34/pleos_car_emulator/x86_64` (~19 GB unpacked) |
| AAOS Automotive Google APIs 34-ext9 x86_64 | `sdkmanager "system-images;android-34-ext9;android-automotive;x86_64"` | Installed; AVD `aaos34` created |

## Emulator boot result: **FAILED (guest never online)**

Attempted both AAOS substitute AVD and cold boot (`-no-snapshot -wipe-data`). Observations:

- QEMU process starts (`-enable-kvm` in argv), SwiftShader GLES init OK.
- `adb devices` stays empty or `offline` for >10 minutes; `sys.boot_completed` never becomes `1`.
- Host CPU of qemu falls to ~0.3% (guest idle/hung); `-show-kernel` prints **no** guest kernel lines.
- Log note: `FeatureControl ... KVM = off` even though acceleration status says KVM usable.
- Therefore **no APK install / no runtime logs** for SOC, AAOS properties, or NaviHelper on this box.

# Run 2 — 4060 Ti host (Pleos Connect v2.1.0 booted)

## Environment

- Host: Ubuntu 24.04, x86_64, 16 cores / 30 GB, user in `kvm` group, NVIDIA RTX 4060 Ti (`/dev/dri` present).
- Emulator 37.2.13; `emulator -accel-check` → KVM (version 12) usable.
- Image: same Pleos Connect v2.1.0 zip, extracted to `system-images/android-34/pleos_car_emulator/x86_64`.
  Fingerprint `Pleos/crp_avd/crp_avd_x86_64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys`, characteristics `nosdcard,automotive`.
- AVD written by hand (`config.ini`: `image.sysdir.1=system-images/android-34/pleos_car_emulator/x86_64/`, `tag.id=android-automotive`, 2560×1440 @160 dpi, 8 GB RAM, 4 cores).
- Launch: `emulator -avd pleos21 -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect -accel on -port 5560`.
- `sys.boot_completed=1` after ~20 s.

## Probe fixes required before the probe produced data

The first run of the original APK (commit `672e7de`) on this emulator showed:

| Lane | Original result | Cause | Fix |
|---|---|---|---|
| AAOS | `AAOS Car unavailable: InvocationTargetException: null` | `Car.createCar(Context)` returns an already-connected `Car` on API 34; the extra `connect()` throws | call `connect()` only when `isConnected()` is false; log `targetException` |
| AAOS | `NoSuchElementException` after the connect fix | `CarPropertyManager` has no `getProperty(Class, int)` overload | use `getProperty(int propId, int areaId)` with `areaId=0` |
| AAOS | (latent) | reflective `CarPropertyEventCallback` proxy returned `null` for `hashCode`/`equals` | answer `Object` methods in the proxy; resolve `registerCallback(callback, int, float)` exactly |
| NaviHelper | `Permission [pleos.car.permission.NAVI_ROUTE] is required` on every call | permission missing from manifest | declare `pleos.car.permission.NAVI_ROUTE` |
| Location | `ACCESS_FINE/COARSE_LOCATION => false` without a dialog | permissions requested at runtime but not declared | declare both in manifest |

## Permissions observed (`dumpsys package permission`)

| Permission | Protection | Granted to probe (`isSystemApp=false`) |
|---|---|---|
| `pleos.car.permission.CAR_ENERGY` | normal | yes |
| `pleos.car.permission.CAR_INFO` | normal | yes |
| `pleos.car.permission.NAVI_ROUTE` | normal | yes |
| `android.car.permission.CAR_ENERGY` | dangerous | yes (runtime grant; `adb install -g`) |
| `android.car.permission.CAR_INFO` | normal | yes |
| `android.car.permission.CAR_POWERTRAIN` / `CAR_SPEED` | — | not requested; `GEAR_SELECTION` / `PERF_VEHICLE_SPEED` reads throw `SecurityException` |

## Runtime results (fixed probe, vehicle parked, gear P, fully charged)

- **Pleos Vehicle SOC:** `Vehicle.initialize OK`; `checkEvBatteryStateOfChargeCapabilities` → `CapabilityConfig(accessType=READ)`; `getEvBatteryStateOfCharge => 100`; `registerEvBatteryStateOfCharge` delivers the current value on registration (two callbacks 5 ms apart), then nothing while SOC is constant over 90 s.
- **AAOS CarPropertyManager:** `EV_BATTERY_LEVEL=77400.0` (Wh), `INFO_EV_BATTERY_CAPACITY=77400.0`, `EV_CHARGE_STATE=2` (FULLY_CHARGED), `EV_CHARGE_TIME_REMAINING=0`, `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE=0.0`. `registerCallback(..., 1 Hz)` returns `true` for all four; `EV_BATTERY_LEVEL`, `EV_CHARGE_TIME_REMAINING` and `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` then fire about once per second even with unchanged values; `EV_CHARGE_STATE` fires once on registration.
- **NaviHelper (no route set):** `initialize OK`; `onRouteStateInfo routing=false mode=SAFE_GUIDING remainChg=0`; `onDestinationInfo name= remainDist=0`; `onCurrentLocationInfo lat=37.4127212 lng=127.094681`. After `requestTrip(37.5665, 126.9780)`: `onTripInfo distance=22442 eta=1797 estSoc=96 points=1` (~0.6 s later). `onDrivingInfo` was not delivered in 90 s.

# Run 3 — Pleos Virtual Console driving vehicle signals (same host, same day)

## Virtual Console access

The emulator image serves the Pleos Connect Virtual Console at guest port 8088 (`adb forward tcp:18088 tcp:8088`, then `http://127.0.0.1:18088/`). The page lists its controls at `GET /vehicle/catalog` and current values at `GET /vehicle/state`. Writes are `PUT <write_endpoint>?<params>&<field>=<value>`, e.g. `PUT /vehicle/ev-battery?field=soc&value=72`.

Writes return `503` until a browser session is alive: the page posts `POST /browser/session?client=<id>` every second. With that heartbeat running (`while true; do curl -s -X POST ".../browser/session?client=probe-cli"; sleep 1; done`), `/vehicle/state` reports `bridge_ready: true, emulator_active: true` and writes return `200`.

Energy controls exposed by the catalog: `EV_BATTERY_SOC` (vendor), `EV_BATTERY_LEVEL`, `INFO_EV_BATTERY_CAPACITY`, `EV_CHARGE_CURRENT_DRAW_LIMIT`, `EV_CHARGE_PERCENT_LIMIT`, `EV_CHARGE_SWITCH`, `EV_CHARGE_PORT_OPEN`, `RANGE_REMAINING`. There is **no** control for `EV_CHARGE_STATE`, `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` or `EV_CHARGE_TIME_REMAINING`. Gear and speed are writable via `/vehicle/gear?gear=Drive|Park` and `/vehicle/controls?speed=…&accelerator=…&brake=…&steering=…`.

Scripts used: `tools/charge_exp.sh` (SOC steps + charge port/switch; expects the `/browser/session` heartbeat to be running) and inline `curl` / Python loops on the host.

## Results

| Probe | Stimulus | Observation |
|---|---|---|
| Pleos SOC subscription | SOC 72→83 in 1 %p steps every 2 s | 11/11 changes delivered once each, callback interval 2,002–2,020 ms (matches stimulus cadence) |
| `EV_CHARGE_STATE` | charge port open + charge switch on (`charging_activated: true` in console state) | stays `2` (FULLY_CHARGED); the console switch does not drive this property |
| `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE`, `EV_CHARGE_TIME_REMAINING` | — (not exposed) | stay `0` |
| `EV_BATTERY_LEVEL` | `battery_level` raised every 1 s at 150 kW then 95 kW (55.7 → 57.06 kWh) | 1 Hz callbacks carry the new values; probe's 10 s ΔWh/Δt estimate reads ≈148 kW and ≈94 kW once the window fills |
| `requestTrip` → `onTripInfo.estimatedSocAtDest` (22.4 km) | SOC 72 / level 55.7 kWh → 68; SOC 72 / level 30.0 kWh → 35; SOC 40 / level 55.7 kWh → 68 | arrival SOC follows **battery energy (level/capacity)**, not the Pleos SOC value; ≈4 %p used over 22.4 km |
| `requestTrip` (375 km, Busan) | level 23.2 kWh | `distance=375429 eta=14615 estSoc=10 energy=54490`; route points: `EV_CHARGING` waypoint 「여주휴게소 (강릉) 전기차충전소」 (37.2392, 127.5672) at 15.8 % of route, then destination |
| `requestRoute` | same destination | needs `pleos.car.permission.NAVI_ROUTE_SEARCH` (normal); with it, guidance starts ~8 s later (`onRouteStateInfo mode=ROUTE_GUIDING remainChg=7740`) |
| `onDrivingInfo` | during guidance | destination `dist=375000 dur=14700 targetSoc=10`; waypoint #0 `name=여주휴게소 (강릉) 전기차충전소 dist=59000 dur=2820 charging=true maxPower=350 chargers=6 targetSoc=10 plan=null` |
| `CarUxRestrictionsManager` | gear Drive + 30 km/h, then 0 km/h + Park | parked: `requiresDO=false active=0`; Drive: `requiresDO=true active=80` within 1 s; Park: back to `false` |

## Probe changes for Run 3

- Destination from intent extras (`--ed destLat … --ed destLng …`), optional `--ez route true` to call `requestRoute`.
- Detailed `onDrivingInfo` / `onTripInfo` logging (waypoints, max charging power, charger count, route points).
- `CarUxRestrictionsManager` listener via reflection.
- Charging-power estimate from the rate of change of `EV_BATTERY_LEVEL` (10 s window).
- Manifest declares `pleos.car.permission.NAVI_ROUTE_SEARCH`.

# Run 4 — one continuous recorded run, charging-waypoint threshold (same host, same day)

Run 3 values were read from the live logcat buffer and some raw lines were later lost when the buffer was cleared. Run 4 repeats every cited item in a single script (`tools/run4.sh`) with logcat captured to a file from the start. Raw files: `logs/run4/logcat.txt` (tag `StayOrGoProbe`, emulator clock, PST), `logs/run4/stimulus.log` (Virtual Console writes, host clock, 17 h ahead), `logs/run4/build.txt` (`release_connect_v2.1.0:user/test-keys`). The guidance route started in Run 3 was still active at the start of Run 4 (`onRouteStateInfo mode=ROUTE_GUIDING remainChg=7740` on every launch).

| Step | Stimulus | Observation (emulator time) |
|---|---|---|
| A | level 55.7 kWh, SOC 72, default destination | `onTripInfo distance=26135 estSoc=67 energy=3394.9` (05:23:16.706) |
| A | SOC 73→83, 1 %p every 2 s | 11/11 callbacks, intervals 2,007–2,019 ms (05:23:28.675–05:23:48.778) |
| A | level +150 kW for 20 s, then +95 kW for 20 s (1 s writes) | 9.0–9.1 s ΔWh estimate 148.6–149.2 kW then 94.2–94.5 kW; transient 165.1/165.4 kW at 05:24:02–03; negative values when the level is set downwards |
| B | level 30.0 kWh | `estSoc=34` for the same 26.1 km destination (05:24:35.028) |
| C | Busan (375 km), level 64.2 / 63 / 62 / 60 / 50 kWh | 64.2: no waypoint, estSoc 12. 63: no waypoint, estSoc 10. 62: `EV_CHARGING` 덕산졸음쉼터(부산방향) at 93.9 % of route, estSoc 10. 60: 청도새마을휴게소(부산방향) at 82.2 %, estSoc 10. 50: 칠곡휴게소 (부산방향) E-pit at 63.3 %, estSoc 9. `energy` ≈ 54.6 kWh in all cases |
| D | level 60 kWh, destination = 청도새마을휴게소 coordinates | `distance=308479 estSoc=19 energy=45057.1` (05:26:09.018) |
| E | level 23.2 kWh, Busan with `route=true` | `SAFE_GUIDING` at 05:26:25.060, `ROUTE_GUIDING` at 05:26:33.299; `onDrivingInfo` waypoint 여주휴게소 (강릉) `dist=59000 charging=true maxPower=350 chargers=6` (05:26:33.992) |
| F | gear Drive + 30 km/h, then 0 km/h + Park | `requiresDO=true active=80` at 05:27:04.806, `false` at 05:27:10.833 |

Charging-waypoint threshold: with route energy ≈ 54.63 kWh and capacity 77.4 kWh, the direct arrival SOC is 10.8 % at 63 kWh (no waypoint) and 9.5 % at 62 kWh (waypoint inserted). The navigation inserts an `EV_CHARGING` stop only when the direct arrival SOC would fall below about 10 %, and places it farther along the route as the battery level rises. `onTripInfo.estimatedSocAtDest` then reports the planned arrival (≈10 %), so the direct arrival SOC has to be derived from `energyConsumption`.

Earlier single trips with `tools/trip_exp.sh` (`logs/trips/`, each log starts with a cleared buffer) gave the same pattern, including 40/45/55 kWh (선산(창원) 휴게소 at 53.1 %, 칠곡 E-pit at 63.3 %, 청도새마을 at 82.2 %).

# Run 5 — route guidance per charging waypoint (same host, same day)

`tools/run5.sh` starts guidance (`--ez route true`) to the 375 km destination at four battery levels and records `onDrivingInfo`, which only arrives while guidance is active. Raw files: `logs/run5/logcat.txt`, `logs/run5/stimulus.log`, `logs/run5/build.txt`.

| Level | Waypoint chosen by the navigation | `onDrivingInfo` waypoint (emulator time) |
|---|---|---|
| 62 kWh | 덕산졸음쉼터(부산방향) 전기차충전소, 93.9 % of route | `dist=352000 charging=true maxPower=100 chargers=4` (05:42:33.761) |
| 60 kWh | 청도새마을휴게소(부산방향) 전기차충전소, 82.2 % | `dist=308000 maxPower=300 chargers=6` (05:43:16.677) |
| 50 kWh | 칠곡휴게소 (부산방향) E-pit 전기차충전소, 63.3 % | `dist=237000 maxPower=260 chargers=6` (05:44:00.143) |
| 40 kWh | 선산(창원) 휴게소 전기차충전소, 53.1 % | `dist=199000 maxPower=100 chargers=4` (05:44:43.667) |

Each new `requestRoute` first reports `SAFE_GUIDING` with an empty waypoint, then `ROUTE_GUIDING` with the new waypoint about 8 s later. The navigation also picks 100 kW stations, so a charging stop it inserts is not necessarily faster than the current charger.

# Run 6 — charging-waypoint threshold on three more destinations (same host, same day)

`tools/run6.sh`: for each destination a full-battery trip gives the route energy E, then two trips at battery levels where the direct arrival SOC is 9.0 % and 11.0 % (E + 0.09 × 77.4 kWh, E + 0.11 × 77.4 kWh). Raw files: `logs/run6/`.

| Destination | Distance, energy | Direct 9 % | Direct 11 % |
|---|---|---|---|
| 광주 (35.1595, 126.8526) | 276.6 km, 40.37 kWh | level 47.34 kWh: `EV_CHARGING` 백양사(순천) 휴게소 at 88.9 %, estSoc 9 | level 48.88 kWh: no waypoint, estSoc 10 |
| 강릉 (37.7519, 128.8761) | 193.0 km, 28.07 kWh | level 35.03 kWh: `EV_CHARGING` 워터 평창휴게소 강릉방향 at 71.5 %, estSoc 9 (06:01:05.098) | level 36.58 kWh: no waypoint, estSoc 11 (06:01:20.200) |
| 대구 (35.8714, 128.6014) | 265.8 km, 38.62 kWh | level 45.59 kWh: `EV_CHARGING` 칠곡휴게소 (부산방향) E-pit at 89.4 %, estSoc 10 | level 47.14 kWh: no waypoint, estSoc 11 |

All four destinations from the same emulator start point (193–375 km) show the same boundary: a charging stop is inserted only when the direct arrival SOC falls below about 10 %.

# Run 7 — charge switch on, logged (same host, same day)

`tools/run7.sh`: battery level 50 kWh, Virtual Console `charge_port_open=true` and `charge_switch=true` (console state `charging_activated: true`), then a fresh app start. Raw files: `logs/run7/`.

| Property | Value with the console charging (emulator time 06:16:50) |
|---|---|
| `EV_CHARGE_STATE` | 2 |
| `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` | 0.0 |
| `EV_CHARGE_TIME_REMAINING` | 0 |

This repeats the Run 3 observation with a kept log: the console charge switch does not drive the AAOS charging properties. Across Runs 4–7, the 10 s ΔWh power estimate ranges from −16,995 kW to +16,757 kW at the moments the console sets the battery level in one step; these are artefacts of step changes, not charging power.

# Questions — status

1. **3rd-party `CAR_ENERGY` grant + SOC callback rate + AAOS TIME_REMAINING / INSTANTANEOUS_RATE / CHARGE_STATE**
   - Grant: answered. Pleos permissions are `normal`; `android.car.permission.CAR_ENERGY` is `dangerous` and grantable to a non-system app.
   - SOC subscription: event-driven, one callback per change, no loss in 11 steps.
   - `EV_BATTERY_LEVEL`: 1 Hz, usable as a charging-power fallback.
   - `EV_CHARGE_STATE` / `INSTANTANEOUS_CHARGE_RATE` / `TIME_REMAINING` while charging: **not measurable on this emulator** (no Virtual Console control; the charge switch does not drive `EV_CHARGE_STATE`). Needs a real vehicle or Devbox.

2. **NaviHelper `onDrivingInfo` / `onTripInfo` while parked (gear P) vs with route**
   - Parked, no guidance: `requestTrip` returns `onTripInfo` (distance, ETA, arrival SOC, route points incl. `EV_CHARGING` stops) without starting guidance.
   - With guidance (`requestRoute`): `onDrivingInfo` delivers charging waypoints with max power and charger count.

# How to reproduce

```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk   # -g grants the dangerous CAR_ENERGY and location permissions
adb shell am start -n ai.probe.staygo/.MainActivity --ed destLat 35.1796 --ed destLng 129.0756 --ez route true
adb logcat -s StayOrGoProbe:I
```

Virtual Console: forward guest port 8088, keep the `/browser/session` heartbeat alive, then drive values with `PUT /vehicle/ev-battery?field=<soc|battery_level|…>&value=<v>` and `PUT /vehicle/gear?gear=<Drive|Park>`.

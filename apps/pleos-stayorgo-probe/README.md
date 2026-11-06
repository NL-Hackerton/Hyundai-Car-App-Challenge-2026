> Imported from the team's probe repository for the Hyundai Pleos contest (Stay-or-Go). Contest planning docs live at the repository root and in `submission/stay-or-go/`.

# pleos-stayorgo-probe

Empirical probe for Hyundai Pleos Stay-or-Go unknowns (3rd-party energy permissions, SOC callback rate, AAOS charge properties, NaviHelper DrivingInfo/TripInfo while parked).

## What it tests

1. **Permissions (3rd-party app)**  
   Requests `pleos.car.permission.CAR_ENERGY` / `CAR_INFO` and `android.car.permission.CAR_ENERGY` / `CAR_INFO`, logs grant results for a non-system `applicationId`.

2. **Pleos Vehicle SOC**  
   `Vehicle.initialize` → `getEvBatteryStateOfCharge` (`Integer`) + `registerEvBatteryStateOfCharge` callback timestamps (`dtMs`) to estimate update rate.

3. **AAOS CarPropertyManager** (reflection)  
   Reads `EV_CHARGE_TIME_REMAINING`, `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE`, `EV_CHARGE_STATE`, `EV_BATTERY_LEVEL`, gear/speed. Registers ~1 Hz callbacks when available.

4. **NaviHelper**  
   `onDrivingInfo` / `onTripInfo` / route state while parked; optional `requestTrip` to a fixed Seoul coordinate.

## Requirements

- **Preferred:** Pleos Connect Emulator v2.1.0+ (Vehicle SDK does **not** work on a plain AVD).  
  SDK Update Site (Linux/Windows):  
  `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`  
  Package: `system-images;android-34;pleos_car_emulator;x86_64`
- **Fallback:** stock AAOS Automotive image (Google APIs). Pleos Vehicle/NaviHelper will fail; AAOS property permission behavior can still be observed.
- KVM recommended. No GPU → use `-gpu swiftshader_indirect`.

## Build

```bash
export ANDROID_HOME=...   # Android SDK
export JAVA_HOME=...      # JDK 17+
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n ai.probe.staygo/.MainActivity
adb logcat -s StayOrGoProbe:I
```

Optional: grant dangerous permissions without UI dialogs (emulator):

```bash
adb shell pm grant ai.probe.staygo android.car.permission.CAR_ENERGY
adb shell pm grant ai.probe.staygo android.car.permission.CAR_INFO
```

Simulate charging via Pleos Virtual Console or AAOS VHAL (property IDs for `EV_*` as in `VehiclePropertyIds`).

## Notes

- Not a contest submission app. Private research probe for Stay-or-Go API verification.
- Personal info / signing blanks intentionally unused.

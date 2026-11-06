# 스테이고 자체 채점 (2026-11-06, API 검증 반영)

내부 가정 배점 25×4. 공식 배점 비공개.

| 기준 | 점수 | 근거 |
|---|---:|---|
| 차량특화성 | 23 | SOC Integer 구독(CAR_ENERGY)+주차 UX+NaviHelper 재경로는 폰 단독 대체가 어렵다. DrivingInfo/TripInfo의 EV_CHARGING 포인트까지 확인했다. |
| 창의성 | 20 | 출발 전 루트플래너와 겹치지 않는 「세션 중 분기」. 아이디어 자체는 직관적이라 만점은 어렵다. |
| 기술 활용성 | 23 | Vehicle 2.3.2·NaviHelper/navi-data 시그니처로 SOC 단위·ChargerOperator 한계·경로 충전 포인트·AAOS-only 잔여시간/순간전력을 확정하고 폴백을 설계했다. |
| 사업성 | 20 | 친환경차법 급속 1시간·과태료, 서울 점거 사용료 입법예고, CPO 혼잡수수료를 인용해 B2B/OEM 경로를 강화했다. 계약·유입 실적은 없다. |
| **합계** | **86** | 검증으로 기술·사업 약점을 줄였으나 실차 데모·계약 증거는 여전히 없다. |

## 확인됨 (이번 검증)
1. `getEvBatteryStateOfCharge` → `Integer`, `pleos.car.permission.CAR_ENERGY`. Wh가 아닌 퍼센트 SOC로 취급(정수+PercentLimit 정합). Level/Capacity Float는 deprecated→AAOS Wh.
2. SOC 리스너 존재. 갱신 Hz·최소 주기는 SDK 미문서(unknown).
3. Pleos EvBattery 2.3.2에 `EV_CHARGE_TIME_REMAINING`·`EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` getter 없음. `EvChargeState` enum만 있고 getter 없음.
4. AAOS: LEVEL Wh, TIME_REMAINING 초, INSTANTANEOUS_RATE mW, CHARGE_STATE — 읽기 `PERMISSION_ENERGY`(dangerous). CAPACITY는 `PERMISSION_CAR_INFO`(normal).
5. `getChargerOperatorInfo` → `ChargerOperator(id, name)`만. 좌표·경로상 충전소 아님.
6. 경로 급속: `onDrivingInfo`/`onTripInfo` + `RoutePointPoiType.EV_CHARGING`(lat/lng). 전체 폴리라인 없음.
7. 환경부 `getChargerStatus`: period 1~10(기본 5), `stat`/`statUpdDt`, 개발계정 일 ~1000회 안내. 실시간 불일치·지연 보도 있음.
8. 급속 1시간 초과=충전방해·과태료(지자체 안내 흔히 10만). 80%는 법률 아님. 서울 점거 사용료는 조례안.

## 아직 unknown / 실차·정상 에뮬 확인 필요
1. SOC 콜백 실제 Hz·값 범위(0–100). 박스에서 Pleos/AAOS 이미지·프로브 APK까지는 준비했으나 게스트가 adb online 전 정지 → 미측정(RESULTS.md).
2. 3rd-party에 `android.car.permission.CAR_ENERGY` / `pleos.car.permission.CAR_ENERGY` 런타임 부여 여부.
3. DrivingInfo/TripInfo가 주차(P)·무경로 vs 경로 설정 시 도래하는지.
4. 환경부 API 운영 계정 한도·실제 지연 분포.
5. Bluelink가 향후 세션 중 Stay/Go를 내장하면 차별 축소.

점수 86 유지(서명·런타임 검증은 늘었으나 에뮬 실행 실패로 배점 상향 근거는 없음).

# 스테이고(StayGo)

공용 급속충전 중에 차량의 SOC·충전전력과 내비의 도착 예상 SOC를 결합해, 지금 충전기에서 더 채울지(Stay) 지금 출발할지(Go)를 권하는 Pleos Connect 차량용 앱이다.

## 동작

- 차량 신호: Pleos Vehicle SDK의 SOC 조회·구독, AAOS 차량 속성의 배터리 잔량(Wh)·충전 상태·순간 충전전력·배터리 용량.
- 충전 인식: 충전 상태가 충전 중이거나, 배터리 잔량이 30초 동안 계속 늘면 충전 중으로 본다. 충전 상태가 충전 완료로 남아 있어도 잔량이 계속 늘면 충전 중으로 본다. 충전 중으로 본 뒤 잔량이 멈추면 충전 종료로 본다. 기어·속도는 충전 인식에 쓰지 않는다.
- 충전전력: 순간 충전전력이 0이거나 없으면 배터리 잔량의 10초 변화량으로 계산하고, 30초 중앙값으로 순간 튐을 거른다. 화면에 「추정」을 표시한다.
- 내비: 내비에 설정된 목적지(`getDestinationInfo`)로 `requestTrip`을 보내 도착 예상 SOC와 경로 소비 전력량을 받는다. 내비가 충전 경유지를 넣으면 그 지점으로 다시 `requestTrip`을 보내 경유지 도착 SOC를 받는다. 경유지 최대 출력·충전기 수는 활성 경로의 `onDrivingInfo`에서 받는다.
- 판단: `decision/DecisionModel.kt`의 규칙 1~6(기획서 3항). 예비 SOC 15%p, 후반 판정 비율 0.5, 경유 정차 부가시간 5분이 기본값이다.
- Go: 목적지 경로(규칙 5에서 경유지가 더 빠르면 경유지 포함 경로)를 내비에 요청한다.
- 주행 중: `CarUxRestrictionsManager`가 주의 분산 최적화를 요구하면 조작 화면을 숨기고 요약 한 줄만 보인다.
- 기기에는 SOC 5%p 구간별 최대 충전전력만 저장하며 위치·시각·충전소는 담지 않는다. 외부 통신은 없다.

## 빌드

JDK 17과 Android SDK(compileSdk 34)가 필요하다. Pleos SDK는 `nexus-playground.pleos.ai` Maven 저장소에서 받는다.

```bash
./gradlew :app:testDebugUnitTest      # 판단 규칙·전력 추정 단위 테스트
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease        # keystore.properties가 있으면 서명
```

release 서명 정보는 저장소 밖 `keystore.properties`(storeFile, storePassword, keyAlias, keyPassword)에서 읽는다.

## 에뮬레이터 시연

Pleos Connect 에뮬레이터 v2.1.0에서 확인했다. `tools/demo_charge.sh`는 가상 콘솔(`adb forward tcp:18088 tcp:8088`)로 충전 곡선(SOC 78%까지 230 kW, 82% 이후 95 kW)을 1초마다 입력하고, 화면을 녹화하며, Go를 누른 뒤 D단 30 km/h로 주행 잠금을 확인한다. 시연 전에 내비에 목적지 경로 안내가 켜져 있어야 한다.

에뮬레이터의 충전 상태 값은 충전 스위치를 켜도 바뀌지 않고 순간 충전전력은 0이므로, 시연 화면의 충전 인식과 충전전력은 배터리 잔량 변화로 계산한 값이다.

## 라이선스

글꼴 Pretendard는 SIL Open Font License 1.1을 따른다(`licenses/Pretendard-OFL.txt`).

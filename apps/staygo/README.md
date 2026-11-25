# 스테이고(StayGo)

공용 급속충전 중에 차량의 SOC·충전전력과 내비의 도착 예상 SOC를 결합해, 지금 충전기에서 더 채울지(Stay) 지금 출발할지(Go)를 권하는 Pleos Connect 차량용 앱이다.

## 동작

- 차량 신호: Pleos Vehicle SDK의 SOC 조회·구독, AAOS 차량 속성의 배터리 잔량(Wh)·충전 상태·순간 충전전력·배터리 용량.
- 충전 인식: 충전 상태가 충전 중이거나, 정차 중 배터리 잔량이 30초 동안 계속 늘면 충전 중으로 본다. 충전 상태가 충전 완료로 남아 있어도 잔량이 계속 늘면 충전 중으로 본다. 충전 중으로 본 뒤 잔량이 멈추면 충전 종료로 본다. 주행 제한 상태에서는 잔량이 늘어도(회생제동) 충전으로 보지 않으며, 주행을 시작하면 충전 세션을 끝낸다. 정차 여부는 `CarUxRestrictionsManager`의 주행 제한으로 판단하고 기어·속도 속성은 읽지 않는다.
- 충전 상태 확인 불가: 충전 상태와 배터리 잔량을 모두 읽지 못하면 운전자가 충전 중임을 직접 확인해야 판단 화면을 연다.
- 충전전력: 순간 충전전력이 0이거나 없으면 배터리 잔량의 10초 변화량으로 계산하고, 30초 중앙값으로 순간 튐을 거른다. 화면에 「추정」을 표시한다.
- 내비: 내비에 설정된 목적지(`getDestinationInfo`)로 `requestTrip`을 보내 도착 예상 SOC와 경로 소비 전력량을 받는다. 내비가 충전 경유지를 넣으면 그 지점으로 다시 `requestTrip`을 보내 경유지 도착 SOC를 받는다. 경유지 최대 출력·충전기 수는 활성 경로의 `onDrivingInfo`에서 받는다. SOC가 바뀔 때와 30초마다 다시 요청하고, 응답이 없으면 5초 뒤 실패로 처리해 간격을 늘려 가며 다시 요청한다.
- 목표 SOC: 내비에 연결하지 못하거나 도착 예상 SOC 요청이 두 번 연속 실패하면 운전자가 목표 SOC를 정하고, 앱은 현재 SOC와 목표 SOC의 차이로 판단한다. 내비가 다시 응답하면 내비 값으로 돌아간다.
- 차량 연동 불가: 권한이 거절되거나 SOC 조회가 지원되지 않으면 차량 신호를 시작하지 않고 연동 불가를 안내한다. 차량 값은 표시하지 않는다.
- SOC 차이: Pleos SOC와 배터리 잔량 기준 SOC가 3%p 넘게 다르면 두 값을 함께 표시한다. 내비의 도착 예상 SOC는 잔량을 따른다.
- 판단: `decision/DecisionModel.kt`의 규칙 1~6(기획서 3항). 예비 SOC 15%p(화면에서 5%p 단위로 조정, 기기에 저장), 후반 판정 비율 0.5, 경유 정차 부가시간 5분이 기본값이다.
- Go: 목적지 경로(규칙 5에서 경유지가 더 빠르면 경유지 포함 경로)를 내비에 요청한다.
- 주행 중: `CarUxRestrictionsManager`가 주의 분산 최적화를 요구하면 조작 화면을 숨기고 요약 한 줄만 보인다.
- 기기에는 SOC 5%p 구간별 최대 충전전력과 예비 SOC 설정만 저장하며 위치·시각·충전소는 담지 않는다. 외부 통신은 없다.

## 구조

| 패키지 | 역할 |
|---|---|
| `core` | `StayGoEngine`: 차량·내비 이벤트를 받아 화면 상태(`StayGoUiState`)를 만든다. Android에 의존하지 않는 Kotlin 코드이며 JVM 테스트로 재생한다. `VehicleSource`·`NaviSource`는 입력 인터페이스다. |
| `decision`, `signal` | 판단 규칙 1~6, 배터리 잔량 기반 전력 추정 |
| `vehicle`, `navi` | Pleos Vehicle SDK·AAOS 차량 속성·NaviHelper 구현 |
| `StayGoViewModel` | 엔진과 입력의 수명 관리. 화면에 `state`·`notices`를 내보내고 조작을 받는다. |
| `MainActivity`, `ui`, `res` | 화면. 상태를 그리기만 한다. |
| `src/debug` | 시뮬레이터(디버그 빌드 전용) |

<p>표 1. 소스 구성.</p>

화면과 나머지 코드의 경계, 상태 필드, 시뮬레이터 시나리오는 [`docs/UI_CONTRACT.md`](docs/UI_CONTRACT.md)에 있다.

## 빌드

JDK 17과 Android SDK(compileSdk 34)가 필요하다. Pleos SDK는 `nexus-playground.pleos.ai` Maven 저장소에서 받는다.

```bash
./gradlew :app:testDebugUnitTest      # 판단 규칙·전력 추정·엔진 상태·시나리오 재생 테스트(44건)
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease        # keystore.properties가 있으면 서명
```

release 서명 정보는 저장소 밖 `keystore.properties`(storeFile, storePassword, keyAlias, keyPassword)에서 읽는다.

## 에뮬레이터 시연

Pleos Connect 에뮬레이터 v2.1.0에서 확인했다. `tools/demo_charge.sh`는 가상 콘솔(`adb forward tcp:18088 tcp:8088`)로 충전 곡선(SOC 78%까지 230 kW, 82% 이후 95 kW)을 1초마다 입력하고, 화면을 녹화하며, Go를 누른 뒤 D단 30 km/h로 주행 잠금을 확인한다. 시연 전에 내비에 목적지 경로 안내가 켜져 있어야 한다.

에뮬레이터의 충전 상태 값은 충전 스위치를 켜도 바뀌지 않고 순간 충전전력은 0이므로, 시연 화면의 충전 인식과 충전전력은 배터리 잔량 변화로 계산한 값이다.

버전 1.1.0(구조 변경과 차량 상태 화면 추가)은 JVM 테스트와 디버그 빌드 시뮬레이터(일반 Android 에뮬레이터)로 확인했다. Pleos Connect 에뮬레이터에서의 재확인은 아직 하지 않았다. 1.0.0의 시연 영상과 `apk/StayGo_release.apk`는 이전 버전이다.

## 라이선스

글꼴 Pretendard는 SIL Open Font License 1.1을 따른다(`licenses/Pretendard-OFL.txt`).

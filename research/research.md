# 작업 A 조사 결과

## 1. 결정 요약

**[가정·추천] 1차 기획서의 주제는 C ‘도착 후 짐·반려동물 동반 하차 준비 확인’으로 선택하는 것이 가장 타당하다.** 단, 반려동물·짐을 감지하는 안전 앱이 아니라, **사용자가 미리 지정한 준비 항목을 차량 도어 상태와 결합하여 확인하는 편의 앱**으로 정의해야 한다.

추천 순위는 **C → A → B**다.

- **C:** 도어 상태 조회·구독에 대한 공식 호출 흐름과 권한을 확인했다. 반려동물 양육 및 외출 준수사항에 관한 공식 조사도 확보했다. 다만 도어 개폐는 짐 반출·동물 하차의 증거가 아니므로 완료 판단은 사용자가 해야 한다.
- **A:** 차량특화성은 높지만, 핵심인 **실제 충전 상태·잔여 충전시간의 Pleos 대상 환경 지원과 접근 권한이 미확인**이다. 충전 스위치 상태를 실제 충전 상태로 해석해서는 안 된다. 이 조건을 확인하기 전에는 “남은 시간에 맞는 활동과 정확한 복귀 안내”를 확정 기능으로 약속하기 어렵다.
- **B:** 휴식 요청의 정리·전달은 가능하지만, 멀미 발생 규모와 제안 기능의 효과를 뒷받침하는 직접적인 공식 조사 근거가 부족하다. “저자극”은 설계 원칙이며 멀미 완화·예방 효과를 뜻하지 않는다.

**중요한 공통 결론:** 공개 문서가 있다는 사실과 제3자 앱이 해당 차량에서 API를 사용할 수 있다는 사실은 다르다. Pleos 공식 문서는 차종별 capability 확인, 권한 협의·승인, 일부 SDK의 이용 준비 상태를 별도로 명시한다.[1][4][5][10]

---

## 2. 조사 브리프와 확인 범위

### 결정 브리프

공개 문서로 확인할 수 있는 API와 문제 근거를 바탕으로, 세 후보 중 **1차 기획서에서 구현 가능성과 차별성을 가장 정직하게 설명할 수 있는 후보 하나**를 선택한다.

| 차원 | 이번 조사에서 적용한 기준 |
|---|---|
| 차량 전용 가치 | 단순 체크리스트·타이머와 달리 차량 상태가 행동 흐름을 바꾸는지 검토했다. |
| API 실현 가능성 | 클래스·호출명·권한·상태 의미·버전·이용 제한을 구분했다. |
| 사용자 문제 | 공식 통계·조사와 기능 수요의 직접성을 구분했다. |
| 선행 기능 | 공식 서비스 설명과 대조하여 동일 기능과 차별화 제안을 나눴다. |
| 안전·개인정보 | 감지·의료 효능·안전 보장을 가정하지 않았다. |
| 사업성 | 실제 확보하지 않은 이용자 수·제휴·매출을 가정으로 표시했다. |
| 열어 둠 | 가격, 제휴사, 출시 차종, 실차 배포 일정, 실제 권한 승인 결과는 정하지 않았다. |

### 관점별 조사 질문과 배움

1. **기본 사실:** 현재 Pleos 환경에서 무엇을 읽을 수 있는가?  
   → Vehicle SDK의 도메인·비동기 호출·capability 구조와 Door 호출 흐름을 확인했다. 배터리 개별 API 페이지는 제목·목록까지만 읽혀 반환형·단위·상태값은 검증하지 못했다.[2][4][6][7]

2. **주최·플랫폼 운영자:** 공개 API라면 곧바로 배포할 수 있는가?  
   → 차종별 지원과 앱 권한 승인 조건이 있으며, SDK 접근 안내에는 Vehicle State·NaviHelper와 위치·음성·차량 제어의 준비 상태가 다르게 기재되어 있다.[1][5][10]

3. **현장 이용자·보호자:** 실제 문제가 무엇이며 앱이 확인할 수 있는 범위는 어디까지인가?  
   → 반려동물 양육과 외출 준수사항에 대한 공식 조사는 확보했다. 짐 분실·차량 하차 누락·멀미의 국내 발생률로 확대할 수는 없다.[14]

4. **기존 서비스 제공자:** 이미 제공되는 기능과 무엇이 다른가?  
   → 충전 상태 조회, 차량 찾기, 모션큐, 후석 움직임 알림, 하차 접근 차량 경고는 기존 기능이다. 추천안은 이 기능들을 재구현하는 대신 **사용자 준비 항목의 확인 순서**에 초점을 둔다.[11][12][13]

---

## 3. 공식 API 계약표

### 3.1 버전 및 접근 조건

**[문헌] 공식 문서 사이에 기준 버전 차이가 있다. 이를 하나의 확정 배포 버전으로 합치면 안 된다.**

- 에뮬레이터 설치 안내: **Emulator 2.1.0**, Android API 34 이미지, 제품 앱 **minSDK 28 이상**, Gradle 8.0 이상이다.[3]
- 2026년 10월 19일 SDK 릴리스 노트: **Vehicle 2.3.1**, NaviHelper 3.0.5, TTS/STT 3.3.1, FusedLocation 1.1.2다.[8]
- 현재 Vehicle 소개·의존성 안내: **Vehicle 2.3.2**이며, 소개 문서의 Service 기준은 2.3.21이다.[2][9]
- 공식 Sample App 안내: **Vehicle 2.3.2 / Service 2.3.22**를 기준으로 한다. Sample App의 minSDK 23을 제품 앱 요구사항으로 가져오면 안 된다.[6]
- 같은 릴리스에서 **VehicleSpeedListener가 제거**되었고, 에뮬레이터의 **LLM SDK 지원도 종료**되었다. 과거 예제에 의존한 속도 구독·내장 LLM 설계는 피해야 한다.[8][17]

### 3.2 검증 수준을 구분한 계약

| 용도 | 실제 확인한 클래스·API·속성 | 권한·상태 계약 | 확인 수준 및 실패 경계 |
|---|---|---|---|
| Vehicle 연결 | `Vehicle`, `initialize()`, `release()`, `getDoor()`, `getBrake()` | capability/get/set 결과는 `onComplete`·`onFailed`로 비동기 전달된다. 비동기 완료 전에 `release()`하지 않는다.[2] | 공식 안내 본문 확인. 초기화 호출 성공만으로 Service 연결·기능 이용 가능을 확정하지 않는다.[6] |
| 기능 접근 수준 | `checkXxxCapabilities`, `CapabilityConfig.accessType`, `AreaBounds.minValue/maxValue` | `NONE`, `READ`, `WRITE`, `READ_WRITE`다. 읽기는 `READ` 또는 `READ_WRITE`일 때만 사용한다.[4] | 공식 예제 확인. 차종명만으로 지원을 추정하지 않는다. |
| C 도어 상태 | `Door`, `checkDoorPositionCapabilities`, `getCurrentDoorPosition`, `registerDoorPosition`, `unregisterDoorPosition` | `pleos.car.permission.CAR_DOORS`. 예제 영역은 `ROW_1_LEFT`, 예제값은 `Closed(0)`·`Open(1)`이다.[6] | **호출 흐름·권한·예제 상태값 확인.** 값 범위는 대상 영역 capability를 다시 확인해야 한다. 모든 문·트렁크 지원을 보장하지 않는다. |
| 도어 제어 | `setDoorPosition` | `CONTROL_CAR_DOORS`가 필요하다.[6] | **추천안에서 제외한다.** 요청 완료는 실제 상태 변경의 증거가 아니다. |
| A 배터리 충전 수준 | `EvBattery`, `checkEvBatteryStateOfChargeCapabilities`, `getEvBatteryStateOfCharge`, `registerEvBatteryStateOfCharge`, `unregisterEvBatteryStateOfCharge` | 도메인의 대표 조회 권한은 `pleos.car.permission.CAR_ENERGY`다. 개별 API annotation 확인이 필요하다.[5][7] | **클래스·메서드 목록 존재 확인에 한정한다.** 상세 본문이 비어 반환형·단위·범위·개별 권한을 검증하지 못했다. |
| A 충전 스위치 | `getEvChargeSwitchState`, `checkEvChargeSwitchCapabilities` | 개별 반환형·상태 의미는 미확인이다.[7] | **실제 충전 진행 여부로 사용하지 않는다.** AAOS에서도 충전 스위치는 사용자가 원하는 시작·중지 설정이고, 실제 충전 상태 속성과 구별된다.[16] |
| 실제 충전 상태·잔여시간의 표준 정의 | AAOS `VehiclePropertyIds.EV_CHARGE_STATE`, `EV_CHARGE_TIME_REMAINING` | 전자는 `Integer`·읽기·ONCHANGE, 후자는 `Integer`·초·읽기·CONTINUOUS이며 충전 중이 아니면 0이다. 읽기 권한은 `Car.PERMISSION_ENERGY`다.[16] | **Google 표준 계약만 검증했다. Pleos 지원·접근 승인은 미확인이다.** SDK에 없는 특수 속성 직접 구현은 기술지원 검토가 먼저다.[10] |
| 주행·정차 구분 | `Brake` 도메인은 주차 브레이크 정보를 제공한다. AAOS 목록에는 `GEAR_SELECTION`, `PARKING_BRAKE_ON`, `PERF_VEHICLE_SPEED`가 있다.[2][16] | 구체적인 Pleos getter·현재 유효한 속도 구독 계약·기어 상태값·개별 권한은 미확인이다. | **정차 판정을 검증했다고 표시할 수 없다.** 속도 0, 도어 열림, 내비 도착 각각을 주차 완료와 동일시하지 않는다. |
| C 목적지 정보 | `NaviHelper`, `initialize()`, `addListener()`, `NaviHelperEventListener`, `removeListener()`, `release()` | 3.0.5. 내비 탑재 차량에서 호환되며, 실제 이벤트는 Navigation 앱 구현·상태에 따라 달라진다.[10][15] | 진입점·수명주기 확인. 도착 이벤트 enum·권한·백그라운드 전달 계약은 미확인이다. 최소안에서는 제외한다. |
| 위치 | `FusedLocation`, `isSupported()`, `getLastKnownLocation()`, `registerFusedLocationCallback()` 등 | 1.1.2, `pleos.car.permission.FUSED_LOCATION`. 마지막 위치는 새 측정이 아니며 `null`일 수 있다. `LocationData`는 좌표·방향·신뢰도·시각 등을 포함한다.[18] | 일반 Android 위치 권한·백그라운드 정책을 그대로 적용할 수 없다. 추천 최소안에서 제외한다. |
| 음성 출력 | `ai.pleos.playground.tts.TextToSpeech`, `Mode.ON_DEVICE`, `initialize()`, `speak()`, `stop()`, `release()` | 3.3.1, `pleos.car.permission.TTS_SERVICE`. ON_DEVICE만 사용하면 네트워크·앱 등록은 필요 없다고 안내한다.[19][20] | SDK 접근 안내에는 TTS가 `Not Available Yet`로 기재되어 있어 이용 가능 여부는 별도 확인해야 한다.[5] 최소안에서 제외한다. |

<p>표 1. 공식 문서에서 확인한 API 계약과 미확인 경계</p>

### 해석상 주의

- **Pleos 권한과 Android `Car.PERMISSION_*`는 같은 문자열이 아니다.** 위 표의 AAOS 계약을 Pleos 권한으로 자동 치환하지 않는다.
- Sample App은 Door 조회·구독 권한이 일반 Android dangerous permission처럼 런타임 승인 창을 사용하지 않는다고 설명한다. 그렇더라도 앱 심사·사용 승인 조건이 사라지는 것은 아니다.[1][6]
- `register…` 호출이 예외 없이 반환한 사실과 실제 알림 수신은 다르다.[6]
- 이번 조사는 문서 조사다. SDK 설치, API 호출, 에뮬레이터·실차 실측은 수행하지 않았다.

---

## 4. 문제 근거와 선행 서비스

### 4.1 공식 조사 근거

| 조사 | 읽은 수치와 모집단 | 제안서에 사용할 수 있는 주장 | 사용할 수 없는 주장 |
|---|---|---|---|
| 농림축산식품부 「2025년 반려동물 양육현황조사」 | 3,000가구 방문 면접조사. 반려동물 양육가구 비율 **29.2%**, 양육가구 중 개 양육 비율 **80.5%**다. 2026년 2월 12일 발표 자료다.[14] | 반려동물 동반 외출 준비를 검토할 생활 집단이 존재한다. | 29.2%가 차량 동반 이동자·Pleos 이용자·앱 고객이라는 주장 |
| 농림축산식품부 「2025년 동물복지에 대한 국민의식조사」 | 5,000명 온라인 조사. 반려견 외출 시 목줄·인식표·배설물 수거 등 준수사항을 잘 지킨다는 긍정 응답은 **48.8%**다. 반려인은 86.9%, 비반려인은 39.9%다.[14] | 외출 준수사항을 확인하는 행동 지원의 정책적 관련성을 설명할 수 있다. | 실제 미준수율 51.2%, 차량 하차 실수율, 앱의 준수율 개선 효과 |
| 한국소비자원 「전기차 충전요금 표시 실태조사」 | 2025년 11월 30일 발간·26쪽이라는 서지정보와 12월 17일 보도자료 게시를 확인했다. 첨부 PDF는 바이너리로 반환되었고 문서뷰어 본문은 읽히지 않았다.[21] | 충전 정보 제공 문제가 공식 조사 대상이라는 사실만 사용할 수 있다. | 이번 조사에서 읽지 못한 상담 건수·불만 비율·충전 대기시간 수치 |

<p>표 2. 공식 조사 근거와 인용 가능한 범위</p>

**[문헌] 농식품부는 두 조사의 모집단과 조사 방법이 달라 조사 간 비교가 불가능하다고 명시한다.** 따라서 두 비율을 곱해 차량용 앱 시장 규모를 산출하지 않는다.[14]

**근거의 빈칸:** A의 ‘충전 중 남은 시간 활용 불편’, B의 ‘가족 멀미·휴식 요청 전달 불편’, C의 ‘차량 하차 시 준비 항목 누락’에 대한 직접적인 공식 발생률은 확보하지 못했다. 이를 기존 조사 수치로 대신하지 않는다.

### 4.2 공식 선행 기능 4건

| 선행 서비스·기능 | 공식적으로 제공하는 내용 | 후보와 겹치는 부분 | 차별화해야 할 부분 |
|---|---|---|---|
| Hyundai Bluelink | 충전 상태·주행 가능 거리, 차량 도어·트렁크 상태, 차량 찾기, 충전 예약 등을 제공한다. 호주 공식 설명이며 차종·사양별 차이가 있다.[11] | A의 충전 정보 조회, C의 도어 상태·주차 위치 조회 | A는 조회 화면이 아니라 활동 중단·복귀 판단, C는 상태 조회가 아니라 사용자 준비 항목 확인을 제공해야 한다. |
| Apple 차량 모션큐 | 동승자가 iPhone을 사용할 때 화면 가장자리 움직이는 점으로 차량 움직임을 표시한다. 운전 중 사용해서는 안 된다고 명시한다.[12] | B의 멀미 관련 사용자 가치 | B는 시각적 멀미 완화 기능을 복제하지 않고 휴식 요청의 정리·정차 후 안내로 구별해야 한다. |
| Hyundai Advanced Rear Occupant Alert | 주차·시동 종료 후 센서가 아이·동물의 움직임을 감지하면 시각·소리·Bluelink 알림을 제공할 수 있다.[13] | C의 동승자·반려동물 관련 하차 확인 | C는 센서 감지가 아니라 사용자 입력 항목 확인이다. 방치 감지·방치 예방 보장으로 표현하지 않는다. |
| Hyundai Safe Exit Assist | 주차 상태에서 뒤쪽 접근 차량을 경고하며, 조건에 따라 차일드록 해제에 개입한다.[13] | C의 하차 시점 | C는 주변 교통의 안전 판단이나 도어 제어를 하지 않는다. 외출 준비 확인과 하차 안전 판단을 분리한다. |

<p>표 3. 직접 관련 선행 서비스와 기능 차이</p>

[13]의 기존 2025년 주소는 현재 2027년형 Santa Fe 페이지로 이동했다. **현재 읽힌 설명을 근거로 사용했으며, 2025년형의 고정 사양을 확인했다고 주장하지 않는다.**

---

## 5. 후보 비교와 실패 경계

| 항목 | A 충전 중 짧은 활동·복귀 | B 가족·동승자 휴식 요청 | C 도착 후 동반 하차 준비 |
|---|---|---|---|
| 핵심 가치 [가정] | 차량이 제공하는 충전 잔여시간 안에서 끝낼 활동을 선택한다. | 동승자의 휴식 요구를 운전자에게 부담 적게 정리한다. | 미리 지정한 짐·반려동물 준비 항목을 하차 시점에 확인한다. |
| 차량 전용 차별점 [가정] | 실제 충전 상태·시간 변화에 따라 활동을 조정한다. | 차량 운행 상태에 따라 요청 접수와 표시 시점을 구분한다. | 도어 상태를 확인 맥락으로 사용하여 수동 체크리스트의 시점을 보완한다. |
| 최소 핵심 API | 실제 충전 상태·잔여시간·정차 판정이 필요하다. SOC는 보조 정보다. | 운행 상태·정차 판정이 필요하다. 의료·생체 정보는 필요하지 않다. | 도어 capability·조회·구독과 주차 상태에 따른 UI 제한이 필요하다. |
| 근거 강도 | 충전 정보 문제의 간접 근거만 확보했다. | 공식 선행 기능은 있으나 직접 문제 규모가 부족하다. | 공식 양육·외출 준수사항 조사와 도어 API 흐름을 확보했다. |
| 가장 큰 반증 | 기존 차량 충전 화면과 휴대전화 타이머만으로 충분할 수 있다. | 가족이 말로 요청하는 것보다 번거로울 수 있다. | 휴대전화 체크리스트보다 차량 화면 확인이 번거로울 수 있다. |
| 기술적 실패 경계 | 남은 시간 미지원·권한 거절·예측 변화·충전 중단 시 핵심 가치가 무너진다. | 운행 상태를 못 읽거나 요청이 반복되면 운전자 주의 분산을 늘릴 수 있다. | 후석·트렁크 영역 미지원, 전원 종료·앱 중단으로 적절한 확인 시점을 놓칠 수 있다. |
| 안전·개인정보 경계 | 활동 종료·복귀 시간과 충전 완료를 보장하지 않는다. 차 밖 사용자에게 차량 앱이 알림을 보낼 수 있다고 가정하지 않는다. | 멀미 감지·치료·예방을 주장하지 않는다. 증상·가족 프로필을 저장하지 않는다. | 도어 개폐로 탑승자 존재·짐 반출·동물 하차를 추론하지 않는다. 안전한 하차를 허가하지 않는다. |
| 추천 | 2순위, 핵심 속성 지원 확인 후 재평가한다. | 3순위, 사용자 문제 검증이 먼저다. | **1순위, 편의 기능으로 범위를 명확히 한다.** |

<p>표 4. 세 후보의 차별성과 실패 경계 비교</p>

### C의 기획 단계 최소 API 선택

**필수로 제안할 차량 API는 Door 읽기 기능 한 도메인으로 제한한다.**

- `Vehicle` 연결·해제
- `getDoor()`
- `checkDoorPositionCapabilities`
- `getCurrentDoorPosition`
- `registerDoorPosition`
- `unregisterDoorPosition`
- 권한: `pleos.car.permission.CAR_DOORS`

**별도 필수 전제:** 정차·주차 상태에 따른 상호작용 허용 조건을 플랫폼 공식 지침과 대상 환경에서 확인해야 한다. 도어 API만으로 주차 여부를 판정하지 않는다. 이 전제를 확인하기 전까지 자동 실행·주행 중 조작 가능을 확정하지 않는다.

**최소안에서 제외할 기능**

- 차량 문·창문·공조·시트 제어
- 위치·이동 기록·VIN 수집
- 상시 마이크·음성인식
- 스마트폰 푸시·원격 감시
- 자동 탑승자·반려동물·짐 감지
- 내비게이션 도착 이벤트에 대한 필수 의존

**[가정] 사용자 흐름:** 주차 후 사용자가 하차 준비 화면을 열면, 앱은 지원되는 도어 상태와 사용자가 설정한 준비 항목을 함께 보여준다. 도어가 열려도 항목을 자동 완료하지 않는다. 완료 여부는 사용자가 직접 확인한다.

이는 “자동 도착 감지 앱”으로 범위를 바꿔 설명해서는 안 된다. **목적지 도착 자동 인식은 별도 계약 검증이 필요한 선택 기능**이다.

---

## 6. 사업성과 검증 설계

다음 내용은 모두 **[가정]**이며, 제휴·사용자 조사·성과를 확보했다는 뜻이 아니다.

### 타깃·유입·반복 사용

| 후보 | 초기 타깃 | 유입 채널 제안 | 반복 사용 계기 |
|---|---|---|---|
| A | 공용 충전을 이용하며 충전 중 짧은 활동을 원하는 Pleos EV 이용자 | Pleos 앱 마켓, EV 이용자 커뮤니티 | 실제 충전 세션 |
| B | 가족·동승자와 이동하며 휴식 요청 조정에 불편을 느끼는 이용자 | Pleos 앱 마켓, 가족 이동 관련 커뮤니티 | 동승 이동 중 휴식 요구 |
| C | 반려동물 또는 준비물이 많은 가족 외출을 하는 Pleos 이용자 | Pleos 앱 마켓, 반려동물 외출·용품 채널 | 동물병원·공원·여행지 등 도착 후 하차 준비 |

채널은 제안일 뿐이며 주최·현대차·충전사업자·반려동물 사업자와의 제휴는 확인되지 않았다.

### 이용자 지표의 분모

- **MAU:** 한 달 동안 준비 확인 등 핵심 행동을 1회 이상 수행한 고유 이용자다. 차량 단위 측정이면 ‘월 활성 차량 수’로 별도 표기한다.
- **활성 전환율:** 지원 차종·권한 조건을 충족하고 앱을 설치한 이용자 중 핵심 행동을 완료한 이용자의 비율이다.
- **30일 재사용률:** 첫 핵심 행동 후 30일 관찰을 완료한 이용자 중 다시 핵심 행동을 수행한 이용자의 비율이다.
- **상황 발생 기준 재사용률:** 관찰 기간 중 적격 충전·동승 이동·반려동물 외출이 다시 있었던 이용자 중 재사용한 비율이다. 단순 비사용과 사용 기회 부재를 구별한다.

반려동물 양육가구 비율이나 전체 EV 보유량을 MAU로 제시하지 않는다. 현재는 지원 차종·실제 설치 기반·사용 빈도가 없으므로 MAU 예측값을 제시하지 않는 편이 타당하다.

### 선택을 뒤집을 검증

1. **API 검증:** 대상 에뮬레이터에서 capability·권한·영역별 상태 수신·앱 종료와 전원 전환을 확인한다.
2. **대안 비교:** C를 휴대전화 체크리스트 및 무도움 조건과 비교하여 준비 누락, 확인 소요시간, 조작 횟수, 불필요한 안내를 측정한다.
3. **가치 반증:** 차량 연동이 수동 체크리스트보다 도움이 없거나 확인 부담을 늘리면 차량 전용 가치 가설을 기각한다.
4. **재사용 검증:** 같은 외출 상황의 재발 여부와 재사용 여부를 함께 기록한다.
5. **안전 경계 검증:** 주행 상태·불명 상태에서 확인 화면을 조작할 수 없는지, 도어 개폐가 자동 완료로 이어지지 않는지 확인한다.

이번 조사에서는 이 검증을 실행하지 않았다.

---

## 7. 미확인 사항과 기획서 표현 제한

1. **Vehicle 버전 정합성:** 릴리스 노트 2.3.1과 현재 의존성 2.3.2의 차이를 대상 환경에서 확인해야 한다.
2. **정차 판정:** 현재 유효한 Pleos 속도·기어·주차 브레이크 API의 상세 계약을 확보하지 못했다. 제거된 `VehicleSpeedListener`를 사용한다고 적으면 안 된다.
3. **충전 정보:** SOC 개별 반환형·단위·충전 스위치 의미·실제 충전 상태·잔여시간 지원과 권한이 미확인이다.
4. **도어 영역:** `ROW_1_LEFT` 예제 외에 후석·트렁크의 실제 지원 영역을 확인하지 못했다.
5. **생명주기:** 시동 종료·화면 전환·백그라운드·앱 종료 후 이벤트 전달을 확인하지 못했다.
6. **SDK 접근 상태:** TTS·STT·FusedLocation의 기능 문서와 `Not Available Yet` 접근 안내를 함께 확인했다. 사용 가능하다고 단정하지 않는다.
7. **직접 수요 근거:** 세 후보의 정확한 상황별 불편 발생률·지불 의사·재사용률은 미확인이다.
8. **선행 수상작:** 공식 발표로 확인한 이전 수상작·심사평은 이번 근거에 포함하지 않았다. 독창성의 전면 보증도 하지 않는다.
9. **한국소비자원 첨부:** 공개 URL·서지·뷰어까지 접근했으나 본문 수치를 읽지 못했다. 기사 수치를 원 통계 확인 없이 옮기지 않았다.
10. **검증 범위:** 파일 편집·설치·회원가입·접수·빌드·테스트·실험·사용자 조사를 수행하지 않았다.

---

## 8. `sources.json`으로 저장 가능한 JSON 장부

아래 번호는 본문 인용 번호와 대응한다. `null`은 발행일을 확인하지 못했거나 고정 발행일이 없는 경우다. 동일 URL은 중복 항목으로 만들지 않았다.

```json
{
  "checked_at": "2026-10-23",
  "scope": "현대자동차 차량용 앱 공모전 후보 A·B·C의 비논문 근거 조사",
  "recommendation": {
    "ranking": ["C", "A", "B"],
    "selected": "C",
    "description": "사용자 지정 준비 항목과 지원되는 도어 상태를 결합하는 주차 후 하차 준비 확인",
    "status": "문서 근거에 따른 기획 추천이며 실환경 동작은 미검증"
  },
  "sources": [
    {
      "id": 1,
      "url": "https://document.pleos.ai/api-reference/connect-sdk-pleos",
      "title": "Pleos Connect SDK 개요 및 API 정책",
      "publisher": "현대자동차그룹 Pleos Playground",
      "published_at": null,
      "checked_at": "2026-10-23",
      "type": "공식 기술문서",
      "evidence": ["Vehicle API 사용 권한은 사전 협의 및 승인 범위 내에서 사용할 수 있다.", "차량 기능 지원은 도메인 capability API로 확인한다."],
      "uncertainties": ["개별 프로젝트의 권한 승인 여부는 확인하지 않았다."]
    },
    {
      "id": 2,
      "url": "https://document.pleos.ai/en/api-reference/connect-sdk-pleos/Vehicle/intro",
      "title": "Vehicle SDK 소개",
      "publisher": "현대자동차그룹 Pleos Playground",
      "published_at": null,
      "checked_at": "2026-10-23",
      "type": "공식 기술문서",
      "evidence": ["Vehicle SDK 2.3.2 및 Vehicle Service 2.3.21 기준이다.", "Vehicle 진입점과 도메인 객체, 비동기 완료·실패 콜백을 설명한다.", "Brake는 주차 브레이크 상태, EvBattery는 충전 상태와 전비 정보를 다루는 도메인이다."],
      "uncertainties": ["도메인 소개만으로 개별 기능의 접근 권한과 반환형을 확정할 수 없다."]
    },
    {
      "id": 3,
      "url": "https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator",
      "title": "Pleos Connect Emulator 설치 및 실행",
      "publisher": "현대자동차그룹 Pleos Playground",
      "published_at": null,
      "checked_at": "2026-10-23",
      "type": "공식 기술문서",
      "evidence": ["Emulator 2.1.0, Android API 34 이미지, 제품 앱 minSDK 28 이상, Gradle 8.0 이상을 안내한다."],
      "uncertainties": ["설치·실행하지 않았다."]
    },
    {
      "id": 4,
      "url": "https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/check-capability",
      "title": "Capability",
      "publisher": "현대자동차그룹 Pleos Playground",
      "published_at": null,
      "checked_at": "2026-10-23",
      "type": "공식 기술문서",
      "evidence": ["AccessType 값은 NONE, READ, WRITE, READ_WRITE다.", "영역별 허용 범위는 AreaBounds의 minValue와 maxValue로 확인한다."],
      "uncertainties": ["차종별 실제 반환 결과는 미확인이다."]
    },
    {
      "id": 5,
      "url": "https://document.pleos.ai/en/docs/pleos-only/create-my-project",
      "title": "프로젝트 생성 및 SDK 접근 요청",
      "publisher": "현대자동차그룹 Pleos Playground",
      "published_at": null,
      "checked_at": "2026-10-23",
      "type": "공식 이용 안내",
      "evidence": ["Vehicle State와 NaviHelper는 Ready to Use로 안내한다.", "FusedLocation, TextToSpeech, SpeechToText, Vehicle Control은 Not Available Yet로 안내한다.", "일부 기능은 제휴 계약이 필요할 수 있다."],
      "uncertainties": ["개별 Workspace의 실제 상태를 확인하지 않았다."]
    },
    {
      "id": 6,
      "url": "https://document.pleos.ai/en/docs/pleos-only/tutorial/vehicle-sdk-sample-app",
      "title": "Vehicle SDK Sample App 안내",
      "publisher": "현대자동차그룹 Pleos Playground",
      "published_at": null,
      "checked_at": "2026-10-23",
      "type": "공식 튜토리얼",
      "evidence": ["기준 환경은 Vehicle SDK 2.3.2, Service 2.3.22다.", "Door Position의 capability·조회·구독·해제 API 호출명을 설명한다.", "조회·구독은 CAR_DOORS, 변경은 CONTROL_CAR_DOORS 권한이 필요하다.", "ROW_1_LEFT 예제 상태는 Closed 0, Open 1이며 실제 허용 범위를 확인하도록 안내한다.", "구독 등록과 실제 알림 수신, 변경 요청 완료와 실제 상태 변경은 다르다."],
      "uncertainties": ["실제 후석·트렁크 지원과 앱 생명주기 동작은 미확인이다."]
    },
    {
      "id": 7,
      "url": "https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/entry",
      "title": "EvBattery API 목록",
      "publisher": "현대자동차그룹 Pleos Playground",
      "published_at": null,
      "checked_at": "2026-10-23",
      "type": "공식 API 목록",
      "evidence": ["HTML 탐색 목록에서 getEvBatteryStateOfCharge, registerEvBatteryStateOfCharge, unregisterEvBatteryStateOfCharge, checkEvBatteryStateOfChargeCapabilities, getEvChargeSwitchState를 확인했다."],
      "uncertainties": ["개별 API 상세 페이지는 제목만 읽혀 반환형·단위·상태값·annotation을 확인하지 못했다."]
    },
    {
      "id": 8,
      "url": "https://document.pleos.ai/en/docs/pleos-only/release-note/sdk-261015",
      "title": "Pleos Connect SDK 릴리스 노트",
      "publisher": "현대자동차그룹 Pleos Playground",
      "published_at": "2026-10-19",
      "checked_at": "2026-10-23",
      "type": "공식 릴리스 노트",
      "evidence": ["Vehicle 2.3.1, NaviHelper 3.0.5, TextToSpeech·SpeechToText 3.3.1, FusedLocation 1.1.2를 기재한다.", "VehicleSpeedListener와 onSpeedUpdated 제거를 명시한다.", "registerEvBatteryStateOfCharge를 신규 읽기·구독 API 예로 제시한다."],
      "uncertainties": ["현재 2.3.2 안내와의 차이를 실제 배포 아티팩트로 대조하지 않았다."]
    },
    {
      "id": 9,
      "url": "https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup",
      "title": "Vehicle SDK 의존성 설정",
      "publisher": "현대자동차그룹 Pleos Playground",
      "published_at": null,
      "checked_at": "2026-10-23",
      "type": "공식 기술문서",
      "evidence": ["의존성 ai.pleos.playground:Vehicle:2.3.2를 안내한다."],
      "uncertainties": ["다운로드·빌드하지 않았다."]
    },
    {
      "id": 10,
      "url": "https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability",
      "title": "API 호환성과 가용성",
      "publisher": "현대자동차그룹 Pleos Playground",
      "published_at": null,
      "checked_at": "2026-10-23",
      "type": "공식 기술정책",
      "evidence": ["Vehicle SDK 지원은 차종별로 다를 수 있다.", "NaviHelper는 내비게이션 탑재 차량에서 호환된다.", "AAOS 속성 지원과 접근성은 차종·속성별로 확인해야 한다.", "Pleos SDK에 없는 특수 속성 직접 구현은 기술지원 검토를 먼저 요청하도록 안내한다."],
      "uncertainties": ["대상 차종의 실제 지원·접근성은 미확인이다."]
    },
    {
      "id": 11,
      "url": "https://www.hyundai.com/au/en/owning/bluelink",
      "title": "Hyundai Bluelink 공식 서비스 소개",
      "publisher": "현대자동차 호주",
      "published_at": null,
      "checked_at": "2026-10-23",
      "type": "공식 선행 서비스",
      "evidence": ["충전 상태·주행 가능 거리·도어 및 트렁크 상태·차량 찾기·충전 예약 기능을 설명한다."],
      "uncertainties": ["호주 서비스 설명이며 한국 또는 Pleos 차종의 동일 제공을 보장하지 않는다."]
    },
    {
      "id": 12,
      "url": "https://support.apple.com/ko-kr/guide/iphone/iph55564cb22/ios",
      "title": "차량에 타고 있는 동안 iPhone을 더 편안하게 사용하기",
      "publisher": "Apple",
      "published_at": null,
      "checked_at": "2026-10-23",
      "type": "공식 선행 기능",
      "evidence": ["차량 모션큐는 동승자의 화면에 움직이는 점으로 차량 움직임을 표시한다.", "운전 중 또는 안전상 집중이 필요한 상황에서는 사용하지 말라고 명시한다."],
      "uncertainties": ["기능 설명이며 후보 B의 의료 효능 근거로 사용할 수 없다."]
    },
    {
      "id": 13,
      "url": "https://www.hyundaiusa.com/us/en/vehicles/2025-santa-fe/se",
      "resolved_url": "https://www.hyundaiusa.com/us/en/vehicles/santa-fe/se",
      "title": "현재 Santa Fe SE 공식 사양 및 안전 기능 설명",
      "publisher": "현대자동차 미국",
      "published_at": null,
      "checked_at": "2026-10-23",
      "type": "공식 선행 차량 기능",
      "evidence": ["현재 페이지에서 AROA의 실내 움직임 감지·알림 설명을 확인했다.", "SEA의 후방 접근 차량 경고와 조건부 차일드록 기능을 확인했다.", "어린이·반려동물을 차량에 방치하지 말라고 안내한다."],
      "uncertainties": ["요청한 과거 연식 주소가 현재 2027년형 페이지로 이동했다.", "한국 판매 차종과 동일 사양임을 검증하지 않았다."]
    },
    {
      "id": 14,
      "url": "https://www.mafra.go.kr/bbs/home/792/594753/download.do",
      "title": "이웃집 3곳 중 1곳, 반려동물과 함께 살아",
      "publisher": "농림축산식품부",
      "published_at": "2026-02-12",
      "checked_at": "2026-10-23",
      "type": "공식 조사 결과",
      "evidence": [
        {"survey": "2025년 반려동물 양육현황조사", "base_year": 2025, "sample": "3000가구", "method": "가구방문 면접", "metric": "반려동물 양육가구 비율", "value": 29.2, "unit": "%"},
        {"survey": "2025년 반려동물 양육현황조사", "base_year": 2025, "denominator": "반려동물 양육가구", "metric": "개 양육 비율", "value": 80.5, "unit": "%"},
        {"survey": "2025년 동물복지에 대한 국민의식조사", "base_year": 2025, "sample": "5000명", "method": "온라인 조사", "metric": "반려견 외출 준수사항을 잘 지킨다는 긍정 응답", "value": 48.8, "unit": "%"}
      ],
      "uncertainties": ["두 조사는 모집단·방법이 달라 비교할 수 없다고 명시한다.", "차량 동반 이동률·하차 누락률·앱 이용 의사 조사가 아니다."]
    },
    {
      "id": 15,
      "url": "https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro",
      "title": "NaviHelper SDK 소개",
      "publisher": "현대자동차그룹 Pleos Playground",
      "published_at": null,
      "checked_at": "2026-10-23",
      "type": "공식 기술문서",
      "evidence": ["3.0.5 기준 진입점·initialize·addListener·removeListener·release 흐름을 안내한다.", "이벤트 전달은 Navigation 앱의 구현과 상태에 따라 달라질 수 있다."],
      "uncertainties": ["도착 상태값·권한·백그라운드 전달은 이번 조사에서 검증하지 못했다."]
    },
    {
      "id": 16,
      "url": "https://developer.android.com/reference/android/car/VehiclePropertyIds",
      "title": "AAOS VehiclePropertyIds 공식 API 명세",
      "publisher": "Google Android Developers",
      "published_at": null,
      "checked_at": "2026-10-23",
      "type": "공식 표준 API 명세",
      "evidence": ["EV_CHARGE_TIME_REMAINING은 Integer·초 단위·읽기·CONTINUOUS이며 충전 중이 아니면 0이다.", "EV_CHARGE_STATE는 실제 충전 상태를 나타내는 Integer·읽기·ONCHANGE 속성이다.", "EV_CHARGE_SWITCH는 사용자가 원하는 충전 시작·중지 설정이다.", "충전 상태·잔여시간 읽기 권한은 Car.PERMISSION_ENERGY다."],
      "uncertainties": ["Google 표준 정의이며 Pleos 대상 환경의 지원·권한 승인을 보장하지 않는다.", "전체 문서 중 요약과 충전 속성 상세 구간을 읽었다."]
    },
    {
      "id": 17,
      "url": "https://document.pleos.ai/en/docs/pleos-only/release-note/emulator-261015",
      "title": "Pleos Connect Emulator 공식 릴리스",
      "publisher": "현대자동차그룹 Pleos Playground",
      "published_at": "2026-10-19",
      "checked_at": "2026-10-23",
      "type": "공식 릴리스 노트",
      "evidence": ["Emulator 2.1.0과 Virtual Console 추가를 안내한다.", "LLM SDK 지원 종료를 명시한다."],
      "uncertainties": ["실제 설치 상태는 확인하지 않았다."]
    },
    {
      "id": 18,
      "url": "https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro",
      "title": "FusedLocation SDK 소개",
      "publisher": "현대자동차그룹 Pleos Playground",
      "published_at": null,
      "checked_at": "2026-10-23",
      "type": "공식 기술문서",
      "evidence": ["1.1.2 기준이며 FUSED_LOCATION 권한을 요구한다.", "getLastKnownLocation은 새 위치를 계산하지 않고 null을 반환할 수 있다.", "일반 Android 위치·백그라운드 접근 정책을 별도로 보장하지 않는다."],
      "uncertainties": ["개별 프로젝트의 SDK 접근 가능 상태와 실차 동작은 미확인이다."]
    },
    {
      "id": 19,
      "url": "https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro",
      "title": "TextToSpeech SDK 소개",
      "publisher": "현대자동차그룹 Pleos Playground",
      "published_at": null,
      "checked_at": "2026-10-23",
      "type": "공식 기술문서",
      "evidence": ["3.3.1 기준이다.", "ON_DEVICE만 사용하면 네트워크·앱 등록은 필요하지 않다고 안내한다.", "기본 모드는 HYBRID다."],
      "uncertainties": ["SDK 접근 상태 안내에는 Not Available Yet가 별도로 기재되어 있다."]
    },
    {
      "id": 20,
      "url": "https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/declaring-permissions",
      "title": "TextToSpeech 권한 선언",
      "publisher": "현대자동차그룹 Pleos Playground",
      "published_at": null,
      "checked_at": "2026-10-23",
      "type": "공식 기술문서",
      "evidence": ["초기화·이벤트 등록·합성 요청 등에 pleos.car.permission.TTS_SERVICE 권한을 요구한다."],
      "uncertainties": ["대상 환경에서의 권한 부여를 확인하지 않았다."]
    },
    {
      "id": 21,
      "url": "https://www.kca.go.kr/smartconsumer/sub.do?menukey=7301&mode=view&no=1003983378",
      "title": "전기차 충전요금 표시 실태조사",
      "publisher": "한국소비자원",
      "published_at": "2025-11-30",
      "checked_at": "2026-10-23",
      "type": "공식 조사 서지",
      "evidence": ["발간일과 26쪽이라는 서지정보 및 공식 첨부 링크를 확인했다."],
      "uncertainties": ["첨부는 바이너리로 반환되었고 문서뷰어 본문이 읽히지 않아 조사 수치를 인용하지 않았다."]
    }
  ],
  "gaps": [
    "현재 유효한 Pleos 주행·주차 판정 API의 상세 계약",
    "SOC 반환형·단위·개별 annotation",
    "대상 환경의 실제 충전 상태 및 충전 잔여시간 지원·접근 권한",
    "후석·트렁크 도어 영역의 실제 지원",
    "전원 종료·백그라운드·앱 종료 이후 이벤트 동작",
    "후보별 직접 불편 발생률·이용 의사·지불 의사",
    "공식 확인된 이전 수상작·심사평",
    "실환경 API 호출 및 사용자 비교 검증"
  ]
}
```

**최종 기획 권고:** C를 선택하되, 제안서의 기술 활용 주장은 **“공식 Door 읽기 API와 사용자 확인의 결합”**까지로 한정한다. 자동 주차·도착 감지, 반려동물 감지, 원격 방치 경고, 안전 보장은 넣지 않는다. A는 실제 충전 상태·잔여시간 접근이 확인되면 재평가할 수 있는 2순위로 유지한다.

## 9. 독립 후보 채점에 따른 최종 선택 이유

2026년 10월 23일 독립 기획 예측점수는 A 50점, B 49점, C 73점이었다. C 개선 1은 86점, C 개선 2는 90점으로 기획 작성 진행 판정 ready를 받았다. 원본은 `.harness/judge/plan-1.json`부터 `plan-5.json`까지이며, 네 기준별 25점은 공식 배점이 아닌 내부 비교 가정이다. 점수는 기획 준비도의 예측이며 실제 앱·수요·효과·매출 검증 결과가 아니다.

최종 선택은 `research/selected-plan.md`의 C 개선 2 ‘문별 하차 준비’다. A는 실제 충전 상태·잔여시간의 대상 환경 지원이 미확인이고, B는 구두 휴식 요청보다 나은 사용자 가치의 근거가 부족했다. C는 공개 Door 읽기 API와 Pleos UX 제한 연동의 문서 근거를 확보했고, 초기 고객을 앞좌석 확인 담당자가 자연스럽게 남는 2인 동반 외출로 좁혔다. 같은 목록·같은 차량 화면의 공정한 대조군과 중단 항목 재탐색 기준을 두어 차량 연동을 유지하거나 기각할 수 있다. 초기 유입·재사용·구매를 고유 가구 단위로 계산하고 실제 지원 환경·동선·유통 도달량이 부족할 때의 중단 조건을 둔 점도 선택 이유다.

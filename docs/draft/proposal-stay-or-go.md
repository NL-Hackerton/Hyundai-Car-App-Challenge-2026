---
project_name: 스테이고
participant_unit: individual
participant_category: student
---

## 1. 앱 한 줄 소개

스테이고는 공용 급속충전 중에 현재 충전기에서 더 채울지, 목적지와 경로상 다음 충전을 보고 이탈할지를 SOC·내비 목적지·충전 사업자 정보로 분기하는 Pleos 차량용 앱이다.

## 2. 기획 배경

### 이미 꽂혀 있는 충전 세션에서의 결정

[가정·설계] 초기 고객은 장거리·교외 이동 중 공용 급속충전기에 정차해 충전하는 전기차 운전자다. 출발 전에 경로와 충전 정차를 짜는 기능은 내비게이션·Bluelink 계열에 이미 있다. 스테이고가 겨냥하는 순간은 그보다 뒤다. 이미 충전기에 연결한 뒤, SOC가 올라가며 체류 시간이 길어질 때 「여기서 더 기다릴 것인가, 지금 뽑고 다음 급속으로 갈 것인가」를 차 안 화면에서 다시 고르는 흐름이다.

이 순간이 불편한 이유는 정보가 여러 앱에 흩어져 있기 때문이다. 차량 화면은 SOC를, 내비는 목적지와 경로를, 충전 앱은 주변 충전기 목록을 각각 보여 주며, 운전자는 머릿속에서 목표 SOC·테이퍼·다음 충전 가능 여부를 조합해야 한다. 스테이고는 주차·충전 중 화면에서 이 세 축을 한곳에 모아 Stay(유지)와 Go(이탈·재경로) 두 행동으로 압축한다.

### 생활·조사 근거

[문헌] 환경부가 공개한 한국환경공단 「전기차 및 충전인프라 보급 확대를 위한 사용자 설문조사」에 따르면, 전기차 이용자 1,000명(2023년 12월 19일~2024년 1월 4일 인터넷 조사) 중 현재 불편 사항으로 충전을 가장 많이 꼽았고(29.0%), 충전소 부족으로 불편을 경험하였다는 응답은 53.8%였다. 충전을 걱정하는 이유로는 충전시설 부족 38.6%, 충전질서 부족 21.2%가 앞섰다.[1]

[문헌] 2017년과 2024년 선택실험 자료를 비교한 연구에서는, 충전소 부족으로 인한 이동시간·대기시간에 대한 비효용이 2024년에 현저히 커진 것으로 나타났다.[2] 이는 단순 충전기 검색에 그치지 않고, 이미 점유한 충전기에서의 대기·이탈 판단이 사용자 효용과 맞닿아 있음을 시사한다. 본 앱은 위 수치를 시장 규모나 MAU로 환산하지 않으며, 공용 급속충전 이용자라는 초기 집단의 문제 맥락만 인용한다.

[문헌] 직류 급속충전에서는 SOC가 높아질수록 충전 전력이 낮아지는 테이퍼가 일반적이다. 아이오닉 5의 공개 충전 곡선·실측 보도에서는 대략 80% 전후에서 전력이 뚜렷이 줄고, 장거리에서는 100%까지 채우기보다 80% 부근에서 출발하는 편이 총 이동시간에 유리할 수 있다고 설명한다.[3][4] 스테이고는 이 사실을 「항상 80%에서 떠나라」는 규칙으로 고정하지 않는다. 목적지까지 필요한 SOC와 예비분을 먼저 계산한 뒤, 현재 SOC가 그 임계를 초과했을 때만 테이퍼 구간 체류의 기회비용을 Stay/Go 문구에 반영한다.

### 기존 기능과의 경계

Bluelink와 차량 EV 루트 플래너는 출발 전·주행 중 충전 정차를 추천하고 충전 상태·주행 가능 거리를 조회한다.[5][6] 차지비·EV 인프라 계열 앱은 충전소 검색·상태·결제에 강점이 있다. 스테이고는 결제·원격 제어·안전 경고를 대체하지 않으며, **이미 진행 중인 충전 세션에서 이탈 여부를 차량 SOC와 내비 목적지에 묶어 결정**하는 데 한정한다. 충전 완료 푸시나 충전기 지도만으로는 같은 결정을 한 화면에서 끝내기 어렵다는 점이 차별 가설이다. 가설은 본선 전 사용성 비교로 검증하며, 기존 앱이 더 빠르면 채택을 중단한다.

## 3. 앱 주요 기능 및 차량 특화 요소

### Stay·Go 분기 화면

[가정·설계] 앱은 사용자가 직접 연다. 충전 시작·문 열림·내비 도착으로 자동 실행하지 않는다. 핵심 화면은 주차 UX가 허용된 상태에서만 상호작용을 제공한다. 주행 중 DO 미선언 화면을 조작하게 하지 않는다.

| 기능 | 입력과 처리 | 사용자 확인·복구 |
|---|---|---|
| 목적지·필요 SOC | 내비 목적지·경로 거리, 사용자 예비 SOC(기본 15%p, 조정 가능)로 목표 SOC를 산정한다. | 목적지 미설정 시 Go를 잠그고 목적지 설정을 요청한다. |
| 현재 SOC 구독 | EvBattery SOC를 조회·구독한다. | 미지원·권한 실패 시 차량 연동을 중단하고 일반 계산기로 위장하지 않는다. |
| Stay 판정 | 현재 SOC < 목표 SOC이면 Stay를 기본 추천한다. | 사용자가 목표를 낮추거나 예비분을 줄일 수 있다. |
| Go 판정 | 현재 SOC ≥ 목표이고, SOC가 테이퍼 주의 구간(기본 80%) 이상이거나 사용자가 Go를 선택한 경우, 경로상·인근 다음 급속 후보와 재경로를 제시한다. | Go는 내비 `requestRoute`/`requestRouteWithWaypoints` 호출로 이어지며, 충전 케이블 분리는 사용자가 한다. |
| 선택적 가용성 | 백엔드가 환경부·한국환경공단 충전소 상태 API를 조회하면 후보 충전기 상태(사용가능/충전중 등)를 보조 표시한다. | API 실패·지연 시 상태 없이 위치·사업자만 보여 주고, 가용성을 단정하지 않는다. |

<p>표 1. 계획한 사용자 기능과 확인 책임.</p>

결정 모델의 최소식은 다음과 같다. S는 현재 SOC(%), St는 목적지 도착에 필요한 SOC와 예비분의 합, T는 테이퍼 주의 임계(기본 80)다.

1. S가 St보다 작으면 Stay. 「목표까지 부족분 (St−S)%」를 표시한다.
2. S가 St 이상이고 T 미만이면 Stay 유지 가능·Go 가능을 함께 보여 주되, 기본 강조는 Stay다.
3. S가 St 이상이고 T 이상이면 Go를 기본 강조한다. 문구는 「한계 전력 구간에 들어 추가 체류 대비 이동 재개가 유리할 수 있음」이며, 시간 단축을 보장하지 않는다.
4. Go 선택 시 후보 충전소 또는 목적지 재경로를 NaviHelper로 전달한다. 후보가 없으면 목적지 직행만 제공한다.

St는 [가정]으로 St = min(100, ceil(d / r) + m)처럼 단순화할 수 있다. d는 남은 경로 거리, r은 사용자·차량이 제공하는 전비 또는 최근 평균, m은 예비 SOC다. 전비·거리 입력이 불완전하면 사용자가 목표 SOC를 직접 입력한다. RANGE_REMAINING을 대상 환경에서 확인하면 d/r 대신 잔여 주행거리와 경로 거리를 비교하는 쪽으로 대체한다. 잔여 충전시간·충전전력(kW) API가 확인되기 전에는 시간 예측을 핵심 기능으로 포함하지 않는다.

### 직접 대안 대비 검증할 차이

| 직접 대안 | 이미 제공하는 강점 | 비교할 차이 |
|---|---|---|
| Bluelink·차량 EV 루트 플래너 | 경로상 충전 정차 추천, 충전 상태·주행거리 조회.[5][6] | 이미 충전 중인 세션에서 Stay/Go를 한 화면으로 압축하는지를 비교한다. |
| 내비 충전소 POI | 주변·경로 충전소 검색 | SOC 목표·테이퍼 주의와 결합한 이탈 추천이 있는지를 비교한다. |
| 차지비·EV 인프라 앱 | 충전소 상태·결제·멤버십 | 차량 SOC 구독·주차 UX·내비 재경로와 같은 차 안 흐름이 있는지를 비교한다. |
| 휴대전화 메모·계산 | 자유롭게 목표 SOC를 적을 수 있다 | 충전 중 SOC 변화 구독과 원탭 재경로가 없는 부담을 비교한다. |

<p>표 2. 직접 대안의 강점과 본 앱의 비교 범위.</p>

## 4. 주요 사용 시나리오

### 급속충전 중 Stay·Go를 고르는 장면

[가정·설계] 운전자가 고속도로 인근 공용 급속충전기에 정차해 충전을 시작하였다. 목적지는 내비에 이미 있다. SOC가 72%일 때는 목표(예: 목적지+예비 78%)에 못 미쳐 Stay가 강조된다. SOC가 83%에 이르면 목표는 충족하였고 테이퍼 주의 구간에 들어 Go가 강조된다. 운전자가 Go를 고르면 앱이 다음 급속 후보를 보여 주고, 선택 시 NaviHelper로 경로를 갱신한다. 케이블을 뽑고 출발하는 동작은 운전자가 한다.

| 단계 | 사람의 행동 | 앱의 응답 |
|---|---|---|
| 시작 | 주차·충전 중 앱을 연다. | UX 제한을 확인한 뒤 SOC·목적지·목표 SOC를 표시한다. |
| Stay | 목표 미만이라 대기한다. | SOC 구독을 갱신하고 부족분을 표시한다. 충전 스위치를 충전 중으로 단정하지 않는다. |
| 분기 | SOC가 목표와 테이퍼 임계를 초과한다. | Go를 기본 강조하고 다음 후보와 직행을 제시한다. |
| Go | 후보를 고른다. | `requestRoute` 또는 경유 포함 경로 요청을 보낸다. |
| 종료 | 케이블을 분리하고 출발한다. | 세션을 종료한다. 원격 알림·자동 정차는 하지 않는다. |

<p>표 3. 주차·충전 중 Stay·Go 시나리오.</p>

경계 조건은 다음과 같다. 목적지 없음, SOC 미지원, 권한 거절, UX 제한 true, Service 단절, 후보 충전소 0건, 환경부 상태 API 지연. 각 경우 기능을 잠그거나 보조 정보를 빼고, 실패를 성공처럼 보이지 않게 한다. 앱은 충전이 실제로 진행 중인지 보장하지 않으며, `getEvChargeSwitchState`를 충전 상태 대신 쓰지 않는다.[7][8]

## 5. 개발 계획

### 개발 환경과 최소 API

[문헌] Vehicle SDK·NaviHelper는 프로젝트 접근 안내에서 Ready to Use로 확인된다. FusedLocation·TTS/STT·Vehicle Control은 Not Available Yet로 안내되어 최소 구현에서 제외한다.[9] Vehicle 기준은 Sample App 문서의 2.3.2 / Service 2.3.22를 따른다.[10] NaviHelper는 3.0.5 샘플의 `requestRoute`, `requestRouteWithWaypoints`, `getDestinationInfo`, `getRouteStateInfo`, `getChargerOperatorInfo`를 사용한다.[11]

| 용도 | 공개 API·권한 | 구현 시 적용할 조건 |
|---|---|---|
| 차량 연결 | `Vehicle.initialize()`, `Vehicle.release()` | 비동기 실패·수명주기를 관리한다. |
| SOC | `checkEvBatteryStateOfChargeCapabilities`, `getEvBatteryStateOfCharge`, `registerEvBatteryStateOfCharge`, `unregisterEvBatteryStateOfCharge` | 권한 `pleos.car.permission.CAR_ENERGY`를 전제로 하되 annotation을 재확인한다.[7][12] |
| 내비 | `NaviHelper.initialize()`, `addListener()`, `getDestinationInfo`, `getRouteStateInfo`, `getChargerOperatorInfo`, `requestRoute`, `requestRouteWithWaypoints`, `release()` | 내비 앱 상태에 의존한다. 실패 시 Go를 잠근다.[11] |
| 화면 제한 | `CarUxRestrictionsManager` | `isRequiresDistractionOptimization()==false`에서만 비-DO 화면을 제공한다.[13] |
| 선택: 잔여 주행거리 | `RANGE_REMAINING` 계열(공식 토론에서 읽기 가능으로 안내)[14] | 메서드·도메인·capability를 대상 환경에서 확인한 뒤에만 목표 SOC 산정에 포함한다. |
| 선택: 충전 상태·잔여시간 | AAOS `EV_CHARGE_STATE`, `EV_CHARGE_TIME_REMAINING`[8] | Pleos 노출·권한이 확인되기 전에는 넣지 않는다. |
| 선택: 충전기 가용성 | 환경부·한국환경공단 충전소 정보·상태 OpenAPI[15] | 백엔드 경유. 차내 최소 데모는 목 데이터로 대체 가능하다. |
| 제외 | 충전 스위치로 충전 중 판정, 도어·공조 제어, 차 밖 푸시, 실내 카메라 | 오탐·미지원·주의 분산을 피한다. |

<p>표 4. 최소 기술 구성과 선택 확장.</p>

### 2주 데모 일정(본선 PT 가정)

아래 일정은 1차 합격 후 12월 3~4일 PT를 가정한 계획이다. 11월 15일 접수 단계에서는 기획서만 필수이며 APK는 선택이다.

| 기간 | 작업 | 통과 증거·미달 시 |
|---|---|---|
| D1~D2 | SDK·에뮬레이터·SOC capability·NaviHelper 초기화 | 로그. SOC 또는 내비 실패 시 해당 축을 목 데이터+명시적 라벨로 축소한다. |
| D3~D5 | 목표 SOC 산정, Stay/Go 판정, 주차 UX 가드 | 단위 테스트와 에뮬레이터 화면. |
| D6~D8 | Go→`requestRoute` 연동, 후보 목록 UI, 오류 경계 | 경로 요청 성공/실패 영상. |
| D9~D10 | 목 충전 곡선·환경부 상태 목서버(선택), 시연 스크립트 | 시연 3분 시나리오. |
| D11~D12 | 회귀·release APK·PT 대본 | 주행 중 조작 불가, SOC 미지원 시 위장 없음. |
| D13~D14 | 버퍼·기기 점검 | 미완료 항목은 시연에서 제외하고 솔직히 고지한다. |

<p>표 5. 2주 데모 계획.</p>

에뮬레이터에서는 SOC를 72→83%로 조작하는 목 구독, 목적지 좌표 고정, 후보 충전기 2개를 로컬 JSON으로 둔다. 실차 capability가 다르면 지원 범위를 표로 고지한다.

## 6. 기대효과 및 발전 가능성

### 검증 설계

[가정] 공용 급속충전 이용 경험자 12명을 모집해 ① 차량 SOC 화면+폰 지도, ② 스테이고(상태 API 없음), ③ 스테이고(상태 목데이터)를 비교한다. 측정은 분기 결정까지 초, 잘못된 Go(목표 미달 이탈) 횟수, 불필요한 Stay(목표·테이퍼 이후 장기 체류) 자기보고다. 사전 유지 기준은 대표 과제에서 결정 시간 중앙값이 ① 대비 20초 이상 짧고, 잘못된 Go가 늘지 않는 것이다. 미달이면 차량 연동 가설을 기각한다.

### 사업 모델

초기에는 Pleos 앱 마켓 무료 앱으로 시작한다. 매출 가설은 (1) 충전사업자(CPO) 대상 유휴 완화·회차 안내 제휴, (2) OEM 인포테인먼트 기능으로의 편입 검토다. 어느 경로도 계약·입점이 확정되지 않았다. 충전 결제·광고 기반 어뷰징은 계획하지 않는다.

[가정·계산] 월 신규 후보 이용자 N=200(EV 커뮤니티·CPO 안내), 환경·권한·공용급속 이용 공동 통과율 e=30%, 설치율 i=40%, 첫 Stay/Go 완료율 a=50%이면 월 신규 활성 U≈12명이다. 재사용률 r=40% 가정 시 M3≈18명 수준이다. 수치는 민감도 예시이며 확보 수요가 아니다. 유료화는 30일 관찰 20명 이상에서 재사용 40% 이상일 때만 검토한다.

| 조건 | e / i / a / r | U | M1→M3 |
|---|---|---|---|
| 낮음 | 20%/30%/40%/20% | 4.8 | 4.8→약 6 |
| 기준 | 30%/40%/50%/40% | 12 | 12→약 18 |
| 높음 | 40%/50%/60%/50% | 24 | 24→약 42 |

<p>표 6. 가정한 월별 활성 민감도.</p>

## 7. 참고자료

### 선택 개발자료 제출 여부

이번 접수에는 시연 영상·GitHub·APK·숏폼을 제출하지 않는다. 아래는 기획 근거이며 구현 완료 증빙이 아니다.

### 참고문헌

확인일은 2026년 11월 6일이다.

- [1] 환경부·한국환경공단 전기차 이용자 설문 결과 보도(비즈니스포스트 인용, 2024-05-26). https://www.businesspost.co.kr/BP?command=article_view&num=353413
- [2] 전기차 시장 캐즘과 소비자 선호 변화(선택실험, 한국경제연구 2025). https://doi.org/10.46665/jkes.2025.12.43.4.37
- [3] electrive, Ioniq 5 충전·프리컨디셔닝 실측·곡선 설명(2023-03-24). https://www.electrive.com/2023/03/24/hyundai-ioniq-5-battery-preconditioning-is-it-worth-the-upgrade/
- [4] InsideEVs, Ioniq 5 급속충전 세션 분석 및 현대차 공식 10–80%·18분 안내 인용(2021). https://insideevs.com/news/503522/hyundai-ioniq5-fast-charging-analysis/
- [5] Hyundai Newsroom, EV Route Planner·Bluelink 충전 정차·SOC 예측 소개. https://www.hyundai.news/eu/articles/stories/embracing-seamless-travel-with-car-features-that-put-customers-first.html
- [6] 현대자동차그룹, 루트 플래너 설명. https://www.hyundaimotorgroup.com/ko/tag/route-planner
- [7] Pleos EvBattery API 목록(SOC 조회·구독·capability, 충전 스위치). https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/entry
- [8] Android VehiclePropertyIds — EV_CHARGE_STATE, EV_CHARGE_TIME_REMAINING, EV_CHARGE_SWITCH. https://developer.android.com/reference/android/car/VehiclePropertyIds
- [9] Pleos 프로젝트 생성·SDK 접근 안내(Ready / Not Available Yet). https://document.pleos.ai/en/docs/pleos-only/create-my-project
- [10] Vehicle SDK Sample App. https://document.pleos.ai/en/docs/pleos-only/tutorial/vehicle-sdk-sample-app
- [11] Pleos NaviHelper 샘플 API 목록(requestRoute, getChargerOperatorInfo 등). https://github.com/Pleos-Playground/pleos-sample-navi-app
- [12] Pleos Connect SDK 릴리스 노트(registerEvBatteryStateOfCharge 등). https://document.pleos.ai/en/docs/pleos-only/release-note/sdk-261015
- [13] Pleos 주행 제한 개발 지침. https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide
- [14] Pleos Playground Discussion #26 — RANGE_REMAINING. https://github.com/Pleos-Playground/pleos-playground-api/discussions/26
- [15] 공공데이터포털 한국환경공단 전기자동차 충전소 정보(getChargerInfo·getChargerStatus). https://www.data.go.kr/data/15076352/openapi.do
- [16] 현대자동차그룹 차량용 앱 개발 공모전 심사·시상. https://appdevchallenge.auto-hmg.io/guide/judging-awards
- [17] 공모전 개발 환경 안내. https://appdevchallenge.auto-hmg.io/guide/development

### AI 활용 고지

기획 준비에 생성형 AI 도구를 활용하여 공개 자료를 조사·정리하고, 후보를 비교하며, 사용자 흐름·개발 일정·수치 가정을 구성하고 본문 초안 작성·문체 정리를 수행하였다. 실제 사용자 조사·앱 구동 검증은 수행하지 않았다. 사실과 가정의 구분, 출처·권리 확인 책임은 참가자에게 있다.

## 8. 숏폼 링크

선택 제출 항목인 숏폼은 제출하지 않는다.

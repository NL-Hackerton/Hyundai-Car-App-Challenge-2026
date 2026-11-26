# Pleos 양산 품질 벤치마크: StayGo 평가 기준 수립용 조사

- 작성일: 2026-11-26. 이 문서에 인용한 모든 URL의 확인일은 2026-11-26이다. 원문 게시일을 확인한 경우 표에 따로 적었다.
- 조사 범위: Pleos Playground 공식 문서(document.pleos.ai), 현대자동차그룹·현대차·기아 공식 뉴스룸과 사용자 매뉴얼, Android 공식 문서(developer.android.com, source.android.com), 일부 언론 보도.
- 조사 방법: 공개 웹 페이지를 사람이 읽는 수준의 개별 요청으로 확인했다. 공모전 사이트(appdevchallenge.auto-hmg.io)는 약관상 자동 수집이 금지되어 있어 이번 조사에서 열람하지 않았다.
- 표기 규칙: 출처 문장을 그대로 옮긴 사실은 출처 URL을 붙인다. 출처에서 직접 확인하지 못한 판단은 `[추론]`, 공개 자료로 확인하지 못한 사항은 `미확인`으로 표시한다. 수치는 출처에 있는 값만 쓴다.
- 검증 경로 표기: `EMU`는 Pleos Connect Emulator에서 우리가 직접 확인할 수 있는 항목이다. `CODE`는 소스·매니페스트·APK 정적 분석으로 확인할 수 있는 항목이다. `불가(실차)`는 실제 차량이 있어야 하는 항목이다. `불가(심사)`는 현대차그룹(Pleos App Market) 내부 심사·승인으로만 확정되는 항목이다. 마지막 두 경로는 우리가 검증할 수 없다.
- 조사 중 열람한 페이지에서 실행·전송을 지시하는 문구(프롬프트 인젝션 시도)는 발견하지 못했다.

## 0. 핵심 결론

1. Pleos App Market 심사는 운전자 주의 분산과 사이버보안을 중점으로 검증하며, 가이드를 모두 지켜도 심사 단계에서 추가 위험이 판단되면 거부될 수 있다고 명시한다. 따라서 "가이드 준수"는 통과의 필요조건이며 충분조건이 아니다.[P2]
2. 정량 기준이 공개된 항목은 다음과 같다. 앱 실행 10초 이내, 콘텐츠 로드 10초 이내, 버튼 반응 2초 이내, 2초 초과 시 진행 표시, 터치 후 응답 0.25초 이내, CPU 사용률(주행 중 비내비 앱 5% 미만, 정차 중 25% 이하), RAM 400 MB 이하, 대비율 4.5:1 이상, 중요 텍스트 높이 5 mm·보조 텍스트 3 mm 이상, 단일 메시지 120자·24단어(영문) 이하, 한 줄 80자 이하, 3줄 이하이다.[P4][P9][P20]
3. DO(Distraction Optimization)를 선언하지 않은 앱은 주차 중 사용 가능하고 주행 중 사용할 수 없다는 것이 Pleos 기본 정책이다. 에뮬레이터에서 `adb shell cmd hmg_car_service --service drivingstate driving_regulation on|off`로 주행 규제를 켜고 끌 수 있어 이 동작을 직접 검증할 수 있다.[P7][P17]
4. 양산 EV 기능 중 StayGo와 겹치는 것은 충전 한도(최대 충전량) 설정, DC/AC 목표 충전량별 예상 충전 시간·주행 가능 거리, 주변 충전소 검색과 상태, 충전소 경유 경로, EV 경로 플래너의 정차지·목적지 SOC 예측, 실시간 충전 전력 표시, 플러그 앤 차지다. 충전 세션 도중 "지금 떠나도 된다"를 동적으로 권고하는 기능은 공개 자료에서 확인하지 못했다(미확인). 이 빈칸이 StayGo의 차별 지점이다.[S5][S6][S7][S8][S9]
5. 2026-11 현재 Pleos Connect는 국내 더 뉴 그랜저·디 올 뉴 아반떼, 미국·캐나다 2027 투싼, 유럽 IONIQ 3에 적용되었다고 공식 발표되었다. 국내 앱 마켓 앱은 18종이며 공개된 신규 7종에 충전 전용 앱은 없다. 초기 11종의 전체 목록은 미확인이다.[S1][S2][S3][S4]

---

## 1. Pleos 공식 요구사항

### 1.1 출처 목록(Pleos)

| 기호 | 문서 | URL |
|---|---|---|
| P1 | 앱 심사 제출 | https://document.pleos.ai/docs/pleos-only/distribute/app-submission-guide |
| P2 | App Review Guidelines(총론) | https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/ |
| P3 | 등록정보 심사 | https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-app-registration-info |
| P4 | 앱 기능 및 성능 안정성 심사 | https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-functionality-performance-stability |
| P5 | 운전자 주의 분산 요소 최소화 설계 심사 | https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-driver-distraction-elements |
| P6 | 사용자 개인정보 및 보안 심사 | https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-user-privacy-security |
| P7 | 지역별 주행 규제 정책 | https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy |
| P8 | 주행 규제 개발 가이드(영문) | https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide |
| P9 | 성능 및 리소스 효율을 고려한 설계 | https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/performance-resource-mgmt |
| P10 | 주행 안전을 고려한 설계 | https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline |
| P11 | 앱 구조 및 동작 모델 | https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model |
| P12 | 앱 카테고리 | https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-category |
| P13 | API 호환성 및 가용성 | https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability |
| P14 | 앱 보안 가이드 | https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-security-guide |
| P15 | 앱 업데이트 가이드 | https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-update-guide |
| P16 | 앱 버전 관리 정책 | https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-version-management-policy |
| P17 | ADB Command 활용법(주행 규제 제어 포함) | https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/adb-command-reference-guide |
| P18 | 폼 팩터 테스트 가이드 | https://document.pleos.ai/docs/pleos-only/app-test-guide/form-factor-test-guide |
| P19 | CCP 개발 가이드 | https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/ccp-rotary-controller-development |
| P20 | Pleos Connect UI Design Guide(총론) | https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro |
| P21 | Typography | https://document.pleos.ai/docs/connect/guide/docs-design/foundations/typography |
| P22 | Display Layout | https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/layout |
| P23 | Window Policy | https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/window |
| P24 | Interaction | https://document.pleos.ai/docs/connect/guide/docs-design/foundations/interaction |
| P25 | 미사용 앱 BG→CCH 전환 가이드 | https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/bg-to-cch-transition-guide |
| P26 | IME 동작에 따른 window resize 처리 가이드 | https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/ime-window-resize-handling-guide |
| P27 | FAQ - 앱 실행 환경 및 플랫폼 리소스 | https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq |
| P28 | TTS / 음성 안내 선행 묵음 적용 가이드 | https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/voice-guidance-preroll-silence-guide |
| P29 | NaviHelper SDK 소개(영문) | https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro |

### 1.2 심사 제출·등록정보

| ID | 요구사항 | 출처 | 검증 방법 |
|---|---|---|---|
| PL-01 | 패키지는 APK 형식이며 최대 600 MB이다. 대상 플랫폼은 Connect 또는 InfoLINK 중에서 선택한다. | P1, P3 | CODE: 릴리스 APK 크기 확인 |
| PL-02 | 앱 아이콘은 PNG 또는 JPEG, 최대 1 MB, 512×512 px이다. 아이콘·스크린샷이 실제 앱과 다르면 심사가 거부될 수 있다. | P1, P3 | CODE: 파일 규격 확인. EMU: 실제 화면과 대조 |
| PL-03 | IVI 스크린샷은 최소 2장, 최대 8장, 장당 8 MB 이하이며 2560×1380, 1700×1212, 840×1212 px 중 하나다. P1 표에는 "840 x 121"로 적혀 있어 P3의 "840 x 1212"와 다르다. 오기로 보이나 확정하지 않는다. | P1, P3 | EMU: 폼 팩터별 캡처. 규격 불일치는 불가(심사) |
| PL-04 | 앱 제목은 영문 기준 30자를 넘지 않는다. 기본 정보에 텍스트 이모티콘·반복 특수문자·확인 불가능한 실적·순위를 쓰지 않는다. | P3 | CODE: 메타데이터 검토 |
| PL-05 | 출시 국가·지역의 모든 언어로 앱 이름·간단한 설명·자세한 설명·개인정보처리방침을 제출한다. | P1, P3 | CODE: 문서·URL 존재 확인 |
| PL-06 | 개인정보 처리 항목, 위치정보 활용 여부, 접근 권한, SDK 추가 권한(프로젝트 ID), 광고성 정보 전송 여부를 정직하게 기재한다. 위치정보를 활용하면 위치정보 이용약관 URL이 조건부 필수다. | P1, P3 | CODE: 매니페스트 권한과 신고 내용 대조 |
| PL-07 | IARC 연령 등급을 받고 증적을 제출한다. | P1 | 불가(심사) |
| PL-08 | 로그인이 필요하면 모든 기능에 접근 가능한 일반 사용자 기준 계정을 제출한다. 외부 서버 연동 요소는 제출 전에 활성화한다. | P1, P3 | 불가(심사). 준비 여부는 CODE로 점검 |
| PL-09 | 심사나 배포를 요청할 때마다 앱 버전을 변경한다. 버전 미변경 시 심사가 진행되지 않거나 반려될 수 있다. | P16 | CODE: versionCode·versionName 증가 확인 |
| PL-10 | App Market은 자동 업데이트를 지원하지 않는다. 앱이 실행 시 최신 버전을 확인하고, 안내 팝업 후 `market://detail?package={패키지명}`으로 이동시키는 흐름을 개발사가 구현한다. | P15 | EMU: 구버전 판정 시 팝업·이동 확인. CODE |
| PL-11 | 심사 통과 후에도 주기적 샘플링 모니터링과 재심사가 있으며, 장기간 미업데이트·다수 신고·심각한 오류 보고 앱은 제외될 수 있다. | P1 | 불가(심사) |

### 1.3 기능·성능·안정성

| ID | 요구사항 | 출처 | 검증 방법 |
|---|---|---|---|
| PL-12 | 설치·삭제 시 오류가 없어야 하며 삭제 후 아이콘·저장 파일이 남지 않아야 한다. | P4 | EMU: `adb install`/`adb uninstall` 후 잔존 파일 확인 |
| PL-13 | 실행 시 강제 종료, 사용 중 화면 정지, 터치 미인식, 그래픽 깨짐이 없어야 한다. | P4 | EMU: 시나리오 반복, `adb shell monkey` |
| PL-14 | 제출 앱은 실제 서비스 앱과 같아야 하며 테스트용 더미 데이터·임시 파일·임시 콘텐츠가 없어야 한다. | P4 | CODE: 리소스·자산·디버그 플래그 검사 |
| PL-15 | 앱은 10초 이내에 실행되고 10초 이내에 콘텐츠를 로드한다. 특정 버튼은 2초 이내에 반응하며, 2초 안에 처리하지 못하면 동작 중임을 UI로 알린다. | P4 | EMU: `adb shell am start -W` TotalTime, 화면 녹화로 반응 시간 측정 |
| PL-16 | 입력 후 차량 시스템 응답(터치 후 터치 효과 애니메이션 발생까지)이 0.25초를 넘지 않는다. | P4, P20 | EMU: 화면 녹화 프레임 분석. 실차 성능은 불가(실차) |
| PL-17 | 스터터링·프레임 저하·화면 끊김이 없어야 한다. | P4 | EMU: `adb shell dumpsys gfxinfo <pkg>` |
| PL-18 | CPU 사용률은 주행 중 비내비 앱 5% 미만, 정차 중 모든 앱 25% 이하, RAM 사용량은 400 MB 이하로 유지한다. | P9 | EMU: `top`, `dumpsys cpuinfo`, `dumpsys meminfo`. 실차 SoC 기준 수치는 불가(실차) |
| PL-19 | Cold start 시 스플래시에서 오래 머물지 않도록 최소 초기화·지연 로딩을 적용한다. | P9 | CODE: Application·첫 화면 초기화 경로 검토. EMU: 콜드 스타트 측정 |
| PL-20 | 백그라운드에서 돌아오면 마지막 사용 상태를 복원한다. 수 시간 이상 미사용 시에만 기본 상태 시작이 허용된다. | P4, P5 | EMU: 홈 이동 후 복귀, 다른 앱 실행 후 복귀 |
| PL-21 | 프로세스가 종료·재생성되어도 사용자 데이터와 진행 상태를 복원할 저장 전략을 마련한다. | P11 | EMU: 백그라운드 상태에서 `adb shell am kill <pkg>` 후 복귀 |
| PL-22 | 사용자에게 기기 재시작이나 핵심 기능과 무관한 시스템 설정 변경을 요구하지 않는다. | P4 | EMU·CODE: 안내 문구 검토 |
| PL-23 | 앱 카테고리를 벗어나는 기능이 있거나 서비스와 다른 카테고리를 고르면 제출이 거부될 수 있다. | P4 | 불가(심사). CODE로 기능-카테고리 대응표 작성 |
| PL-24 | Vehicle SDK API를 쓰려면 필요한 퍼미션을 선언해야 하며, 이 퍼미션은 Pleos App Market과 사전 협의·승인된 경우에만 선언할 수 있다. 승인되지 않은 퍼미션을 선언한 앱은 심사를 통과할 수 없다. | P4 | CODE: 매니페스트 퍼미션 목록. 승인 여부는 불가(심사) |
| PL-25 | 화면을 점유하지 않고 사용자 제공 기능이 없는 BG 상태에서는 FGS를 종료하고 CCH 상태로 전환되거나 프로세스 종료를 허용해야 한다. 이 기준은 필수이며 미준수 시 차량 탑재 심사에서 미통과될 수 있다. | P25 | EMU: 다른 앱으로 전환 후 `adb shell dumpsys activity lru`로 상태 확인 |

### 1.4 주행 안전(DO·UX 제한)

| ID | 요구사항 | 출처 | 검증 방법 |
|---|---|---|---|
| PL-26 | Pleos Connect 앱은 플랫폼이 제공하는 `CarUxRestrictions` 상태에 따라 UX 제한을 적용해야 한다. `CarUxRestrictionsManager.registerListener()` 등록 후 `onUxRestrictionsChanged()`로 변경을 받는다. | P8 | CODE: 등록·해제 쌍 확인. EMU: 규제 on/off 전환 시 반응 확인 |
| PL-27 | DO 미선언 앱은 Parking에서 사용 가능, Drive에서 사용 불가가 기본이다. 정책은 플랫폼 버전과 양산 차량에 따라 다를 수 있다. | P7 | EMU: `adb shell cmd hmg_car_service --service drivingstate driving_regulation on` 후 앱 차단 확인. 차종별 차이는 불가(실차) |
| PL-28 | DO를 선언한 앱은 `CarUxRestrictions`와 `ActiveRestrictions`를 확인해 기능별 제한을 스스로 적용한다. 북미는 주행 중 키보드·다이얼패드·설정(UX_RESTRICTIONS_NO_SETUP)·영상·대용량 텍스트(LIMIT_CONTENT, LIMIT_STRING_LENGTH)가 제한되고 검색은 음성 한정이다. 유럽·그 외 지역은 표가 다르다. | P7, P8 | CODE: 플래그별 분기. EMU: 규제 on 상태에서 화면 확인. 지역별 실제 플래그 값은 불가(실차) |
| PL-29 | 앱은 `product.sw.target_region`(KOR, EUR, USA, CAN)으로 지역을 구분하고 지역별 UX 정책을 적용해야 한다. | P27 | CODE. 실제 값은 불가(실차) |
| PL-30 | 주행 중 시선을 끄는 동영상·애니메이션·움직이는 이미지와 장식적 반복 시각 요소를 최소화하거나 제한한다. 텍스트 자동 스크롤을 쓰지 않는다. | P10, P20 | EMU: 규제 on 상태 화면 녹화 |
| PL-31 | 필요한 기능에 최소 조작으로 도달하도록 구조를 단순화하고, 드롭다운·다단 메뉴를 남용하지 않는다. 한 손으로 조작 가능해야 하며 양손을 쓰게 해서는 안 된다. | P10, P20 | EMU: 주요 과업 탭 수 계수 |
| PL-32 | 검색·로그인에서도 키보드 입력보다 프리셋·최근 항목·음성 입력을 우선한다. | P10 | EMU·CODE |
| PL-33 | 게임·인터넷 서핑·피트니스 등 주의를 분산시키는 활동을 유도하지 않는다. | P10 | CODE: 기능 목록 검토 |
| PL-34 | Mobility Services 카테고리는 주행 중 "전기차 충전소 찾기 및 경유지 설정", "현재 위치 기반의 즉각적인 정보 제공" 등이 제한적으로 허용될 수 있다. 실제 적용은 주행 상태와 지역 정책에 따른다. | P12 | 불가(심사). 카테고리 선택 근거는 CODE |
| PL-35 | 가이드 전반의 준수가 위험 요소의 완전한 배제를 뜻하지 않으며, 심사 단계에서 추가 위험이 판단되면 등록이 거부될 수 있다. | P2, P5 | 불가(심사) |
| PL-36 | 클러스터와 같은 속도를 쓰려면 GPS 계산값 대신 `CarPropertyManager`로 `PERF_VEHICLE_SPEED`를 읽는다. | P27 | CODE |

### 1.5 화면·디자인

| ID | 요구사항 | 출처 | 검증 방법 |
|---|---|---|---|
| PL-37 | 앱 영역은 1개 또는 2개 window로 나뉘며, 가로 전용 게임처럼 한 모드만 가능한 앱을 제외한 모든 앱은 landscape와 portrait 두 레이아웃을 지원해야 한다. | P11, P22, P23 | EMU: window bar 드래그, 3핑거 제스처로 전환 |
| PL-38 | `screenOrientation` 미지정 Activity는 portrait로 1개 window를 쓰고, `landscape` 지정 시 2개 window 영역을 쓴다. Portrait에는 핵심 기능, landscape에는 부가 정보를 배치한다. | P11 | CODE: 매니페스트. EMU: 두 모드 캡처 비교 |
| PL-39 | 레터박스 없이 디스플레이를 채우고(한 방향 미세 보정만 허용) 호환성 모드로 실행되지 않는다. | P4 | EMU: FormFactorConsole 프리셋별 캡처 |
| PL-40 | 해상도 전환 중 사용 중이던 화면·작업 내용이 손실되지 않고, 이미지 늘어짐·앨리어싱·블러·터치 영역 불일치가 없으며, 모든 화면 상태에서 기능 패리티를 유지한다. | P4 | EMU: 전환 전후 상태·버튼 대조 |
| PL-41 | Connect-S(17, 14.6, 12.9인치), Connect-W(Wide, Extended), Connect-WF(Waterfall) 디스플레이를 고려한다. 폼 팩터 테스트에서 요소 잘림·겹침, 텍스트·아이콘 정렬, 방향별 배치, 터치·전환을 확인한다. | P18, P22 | EMU: FormFactorConsole + Host Display Bridge. 실제 패널은 불가(실차) |
| PL-42 | 디스플레이 컷아웃·인셋·소프트웨어 키보드·시스템 표시줄의 안전 영역을 준수한다. | P4 | EMU: 시스템 UI 표시 상태 캡처 |
| PL-43 | EditText가 없는 window는 IME 표시 시 view가 밀려 올라가지 않아야 한다. Dialog는 fit insets를 `statusBars`와 `navigationBars`로 한정한다. | P26 | EMU: 다른 앱 IME 표시 중 화면 확인. CODE |
| PL-44 | 아이콘·텍스트·기타 이미지의 대비율은 4.5:1 이상이다. | P20 | EMU: 캡처 후 대비 계산 |
| PL-45 | 중요 정보 텍스트 높이 5 mm 이상, 보조 텍스트 3 mm 이상이다. 최소 폰트는 12sp 이상 권장, 실제 픽셀 높이 18 px 이상을 안전값으로 제시한다. | P20, P21 | EMU: 폼 팩터 DPI로 환산. 물리 높이 최종 확인은 불가(실차) |
| PL-46 | 영문 단일 메시지 120자 또는 24단어 이하, 한 줄 80자 이하, 문장·단락 3줄 이하이다. | P20 | CODE: 문자열 리소스 길이 검사 |
| PL-47 | 운전자는 1~2초 안에 정보를 파악해야 하므로 핵심 정보만 노출한다. | P20 | EMU: 주 화면 정보 개수 점검. 시선 시간 측정은 불가(실차) |
| PL-48 | 야간/주간 조명 환경에 따라 UI 밝기와 대비를 조정한다. | P20 | EMU: 다크/라이트 테마 전환 캡처 |
| PL-49 | 알림은 운전자의 필요와 관련된 경우에만 표시하고, 광고나 광고주·제품명 외 텍스트 광고를 넣지 않는다. | P20 | CODE: 알림 발생 경로 |
| PL-50 | 큰 터치 영역, 높은 대비, 텍스트 최소화를 적용하고 버튼 크기·간격은 UI Design Guide를 따른다. 버튼 크기·간격 수치는 텍스트로 공개되지 않고 이미지·Figma 자료로만 제공되어 수치는 미확인이다. | P10 | EMU: dp 계측. 기준 수치는 미확인 |
| PL-51 | 터치 가능한 요소는 눌림 시 95% 축소와 배경색 변경(shrink effect)을 적용한다. | P24 | EMU: 화면 녹화 |

### 1.6 개인정보·보안

| ID | 요구사항 | 출처 | 검증 방법 |
|---|---|---|---|
| PL-52 | 접근 권한을 서비스에 필요한 범위로 최소화한다. 수집 항목·목적·보유기간·거부권을 고지하고 명시적 동의 절차를 구현한다. "동의 버튼" 외 동작을 동의로 간주하지 않는다. | P6 | CODE: 권한 목록. EMU: 동의 화면 흐름 |
| PL-53 | 앱 자체에서 수집 데이터 목록, 사용 방식, 제3자 공유 내용을 명시한다. | P6 | EMU: 앱 내 고지 화면 확인 |
| PL-54 | 기기에 저장하는 민감정보는 위치와 무관하게 암호화하며 키는 Android Keystore 등으로 보호하고 하드코딩하지 않는다. | P14 | CODE: 저장소·키 관리 코드 검토 |
| PL-55 | 디버깅 로그에 민감정보를 기록하지 않으며 출시 전 로그를 비활성화하거나 마스킹한다. | P14 | CODE: 로그 호출 검색. EMU: `adb logcat` 점검 |
| PL-56 | 외부 통신은 HTTPS, TLS 1.2 이상(1.3 권장)을 쓴다. 인증 토큰은 URL 경로·쿼리에 넣지 않는다. | P14 | CODE: network security config, HTTP 클라이언트 설정 |
| PL-57 | 외부 호출이 필요 없는 Activity·Service·Receiver·Provider는 노출하지 않는다. 민감정보를 암시적 브로드캐스트로 전달하지 않는다. | P14 | CODE: `exported` 속성 검사 |
| PL-58 | 배포 앱은 정식 배포 인증서로 서명하고 디버깅·디버그 빌드 설정·WebView 원격 디버깅을 비활성화한다. | P14 | CODE: `apksigner verify`, `debuggable` 확인 |
| PL-59 | Pleos가 허용한 API만 쓰고, 차량 API는 기능과 직접 관련된 범위에서만 호출한다. | P14 | CODE. 허용 여부 최종 판단은 불가(심사) |
| PL-60 | 사용하지 않는 자동 백업은 비활성화를 권장한다(non-GAS 환경에서는 Google 백업이 동작하지 않는다). | P14 | CODE: `allowBackup`, `dataExtractionRules` |
| PL-61 | SDK·라이브러리의 알려진 취약점을 주기적으로 확인한다. 네이티브 라이브러리는 RELRO·스택 카나리·NX 적용을 권장한다. | P14 | CODE: 의존성 취약점 스캔 |

### 1.7 입력·오디오·플랫폼 API

| ID | 요구사항 | 출처 | 검증 방법 |
|---|---|---|---|
| PL-62 | User-installed 앱은 CCP 지원이 선택이지만, 미지원 앱은 필수 조작을 터치만으로 완결해야 한다. 미지원 시 `<meta-data android:name="com.hmg.rotary.ROTARY_SUPPORTED" android:value="false"/>`로 선언한다. | P19 | CODE: 매니페스트. EMU: 터치만으로 전 과업 수행 |
| PL-63 | CCP Back 하드키 처리에서 음악 외 카테고리는 Hide(`moveTaskToBack(true)`) 또는 Finish(`finishAffinity()`) 중 하나를 일관되게 적용하고, 한 입력에 둘을 동시에 실행하지 않는다. | P19 | CODE: BackHandler 검토. 실제 하드키 입력은 불가(실차) |
| PL-64 | 오디오를 쓰는 앱은 Pleos 오디오 포커스 정책을 따르고 포커스 획득·상실 대응을 구현한다. | P9, P10 | EMU: 다른 오디오 앱과 동시 재생 시험 |
| PL-65 | 음성 안내 재생 전 150~200 ms 선행 묵음을 권장하며 묵음 없이 즉시 재생하지 않는다. | P28 | CODE: TTS 재생 경로. EMU: 첫 음절 청취 |
| PL-66 | Vehicle SDK 가용성은 차종별로 다르며 도메인별 `checkXXXCapabilities`로 확인한다. NaviHelper는 내비 탑재 차량에서만 호환된다. 모델명으로 분기하지 않고 실제 지원 여부를 확인한다. | P13 | CODE: capability 확인 후 분기. 차종별 결과는 불가(실차) |
| PL-67 | NaviHelper 이벤트는 Navigation 앱의 구현과 상태에 따라 달라질 수 있다. `initialize()`→`addListener()`→요청→`removeListener()`·`release()` 순서를 따른다. | P29 | CODE. 이벤트 실제 전달은 EMU 일부, 실차 차이는 불가(실차) |
| PL-68 | 에뮬레이터의 플랫폼 식별값(`Build.DEVICE`)과 미디어 코덱은 실차와 다를 수 있어 에뮬레이터 결과를 실차에 그대로 적용하지 않는다. | P27 | 불가(실차) |

---

## 2. Android Automotive OS 품질 지침

Pleos Connect는 AAOS 기반이지만 Google Play가 아닌 Pleos App Market으로 배포된다. 아래 Google 지침은 Google Play 심사 기준이므로 Pleos 심사를 직접 구속한다는 근거는 없다(미확인). 다만 Pleos 문서가 AAOS 운전자 주의 분산 가이드라인 준수를 직접 요구하므로(P10), 해당 항목은 구속력이 있는 것으로 본다. 나머지는 양산 수준 비교 기준으로 쓴다.

### 2.1 출처 목록(Android)

| 기호 | 문서 | URL |
|---|---|---|
| A1 | Car app quality guidelines | https://developer.android.com/docs/quality-guidelines/car-app-quality |
| A2 | Distribute parked apps on Android Automotive OS | https://developer.android.com/training/cars/parked/automotive-os |
| A3 | AAOS Driver distraction guidelines | https://source.android.com/docs/automotive/driver_distraction/guidelines |
| A4 | Core app quality guidelines | https://developer.android.com/docs/quality-guidelines/core-app-quality |
| A5 | Android vitals | https://developer.android.com/topic/performance/vitals |
| A6 | CarUxRestrictions API reference | https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictions |
| A7 | Test using the Android Automotive OS emulator | https://developer.android.com/training/cars/testing/emulator |
| A8 | Adaptive app quality guidelines(대형 화면 지침 대체) | https://developer.android.com/docs/quality-guidelines/large-screen-app-quality |

### 2.2 요구사항

| ID | 요구사항 | 출처 | Pleos 구속력 | 검증 방법 |
|---|---|---|---|---|
| AA-01 | 주차 앱 범주는 Tier 3 Car ready를 모두 충족해야 Play에 등록된다. Tier 3은 대형 화면 호환과 주차 중 사용을 뜻한다. | A1 | 참고 | CODE·EMU |
| AA-02 | DO 앱이 아니면 Activity에 `distractionOptimized=true` 메타데이터를 넣지 않는다. UX 제한이 활성화되면 OS Activity가 앱을 가리고 최소 `onPause`로 전이한다. | A2, A3 | 구속(P10 경유) | CODE: 매니페스트. EMU: 규제 on 시 lifecycle 로그 |
| AA-03 | UX 제한 상태는 `CarUxRestrictionsManager`로 읽으며, 기어·속도 같은 다른 하드웨어 상태로 추정하지 않는다. 제한은 차량 내 디스플레이마다 다를 수 있다. | A2 | 구속(P8과 일치) | CODE |
| AA-04 | 비-DO Activity가 참조할 값은 `isRequiresDistractionOptimization()`의 반환값뿐이다. 다른 값은 DO로 표시된 Activity에만 관련된다. | A2, A6 | 구속(P8과 일치) | CODE |
| AA-05 | 플랫폼은 매니페스트 선언만 확인하며 실제 준수 여부는 강제하지 못한다. 준수는 스토어 심사에서 확인한다. | A3 | 참고 | 불가(심사) |
| AA-06 | AR-1: 앱 Activity의 상호작용 UI 요소가 시스템 바·컷아웃에 가려지지 않는다. | A1 | 참고(P4 PL-42와 일치) | EMU |
| AA-07 | AN-1: 시스템·하드웨어 Back 없이도 막다른 화면 없이 이동할 수 있어야 한다. AAOS 내비게이션 바에는 Back이 없을 수 있고 제스처 내비게이션은 지원되지 않는다. | A1, A2 | 참고(Video·Games·Browsers 대상) | EMU: 모든 화면에서 앱 내 복귀 경로 확인 |
| AA-08 | EP-2: 홈 화면에서 다시 실행하면 이전 상태에 최대한 가깝게 복원한다. | A1 | 참고(P4 PL-20과 일치) | EMU |
| AA-09 | DR-1/DR-2/DR-3: 앱 버튼 반응 2초 이내, 실행 10초 이내, 콘텐츠 로드 10초 이내. | A1 | 참고(P4와 수치 동일) | EMU |
| AA-10 | SA-1, ST-1: 애니메이션 그래픽·자동 스크롤 텍스트를 표시하지 않는다(주행 중 사용 범주). | A1 | 참고(P20과 일치) | EMU |
| AA-11 | IN-1, NA-1, AD-1: 운전자 필요와 관련된 알림만 표시하고 알림 광고·텍스트 광고를 금지한다. | A1 | 참고(P20과 일치) | CODE |
| AA-12 | UX-1/UX-2/UX-3(Tier 1, Video·Games·Browsers): 터치 타깃 64dp 이상, 타깃 간·화면 가장자리 24dp 이상, 폰트 24sp 이상. | A1 | 참고 | EMU: Layout Inspector로 dp 계측 |
| AA-13 | Touch_Target_Size: 터치 타깃 48dp 이상. Visual_Contrast: 작은 텍스트 4.5:1, 큰 텍스트·그래픽 3:1. Content_Description: TextView 외 UI 요소에 `contentDescription`을 제공한다. | A4 | 참고 | EMU: Accessibility Scanner 또는 Layout Inspector |
| AA-14 | App_Startup_Time: 2초 넘게 걸리면 진행 표시를 제공한다. Rendering_Performance: 16 ms 이내 프레임. StrictMode_Compliance: StrictMode 활성화 시 경고가 없어야 한다. | A4 | 참고 | EMU: StrictMode `detectAll()`·`penaltyLog()` 디버그 빌드 |
| AA-15 | Stability_ANR: 크래시와 UI 스레드 차단(ANR)이 없어야 한다. | A4 | 참고(P4와 일치) | EMU: monkey, `adb shell dumpsys dropbox` |
| AA-16 | Runtime_Permissions: 기능 요청 시점에 런타임 권한을 요청한다. Permission_Rationale: 권한 필요 이유를 설명한다. Graceful_Degradation: 거부 시 앱 전체 접근을 막지 않는다. | A4 | 참고(P6 최소 권한과 정합) | EMU: 권한 거부 시나리오 |
| AA-17 | Sensitive_Data_Logging: 개인·민감 데이터를 시스템 로그와 앱 로그에 기록하지 않는다. | A4 | 참고(P14와 일치) | CODE·EMU |
| AA-18 | Orientation_Transitions·Display_State_Parity: 빠른 방향 전환에도 렌더링 오류와 상태 손실이 없고 기능 패리티를 유지한다. | A4 | 참고(P4와 일치) | EMU |
| AA-19 | Theme_Support: 앱 콘텐츠가 라이트·다크 테마를 모두 지원한다. | A4 | 참고(P20 야간/주간과 정합) | EMU |
| AA-20 | LS-U4/LS-I3: 로터리 입력은 키보드 탭 내비게이션과 같은 API를 쓰므로 포커스 탐색을 지원해야 한다(Tier 1 주차 앱). | A1 | 참고(Pleos는 user-installed 앱의 CCP를 선택으로 둔다, P19) | EMU: 키보드 Tab·방향키로 포커스 순회 |
| AA-21 | Android vitals 불량 기준: 전체 사용자 체감 크래시율 1.09%, ANR율 0.47%. DEX 10 MB 초과 앱은 최적화·난독화·축소 비율 25% 이상. | A5 | 참고(Play 지표이며 Pleos 기준은 미확인) | 불가(실차·운영 지표) |
| AA-22 | 운전자 주의 분산 검증 절차: 주행 시뮬레이션 중 앱이 열리지 않는지, 재생 중 주행 시작 시 일시정지되는지 확인한다. | A2, A7 | 참고 | EMU: Pleos는 P17 명령으로 동일 시험 |

비고: A1의 `AC-1` 행은 기준명이 "App Doesn't Crash"이나 설명은 "다섯 화면 이하로 과업 완료"로 되어 있다. 원문 표기를 그대로 옮겼으며 어느 쪽이 의도인지는 미확인이다.

---

## 3. 양산 서비스 기준선

### 3.1 출처 목록(양산)

| 기호 | 문서 | 게시일 | URL |
|---|---|---|---|
| S1 | HMG 뉴스룸, Pleos Connect 공개 | 2026-04-30 | https://www.hyundaimotorgroup.com/en/news/hyundai-motor-group-redefines-in-vehicle-experience-with-pleos-connect-next-generation-infotainment-system |
| S2 | HMG 저널, 차세대 인포테인먼트 Pleos Connect | 2026-04-30(페이지 HTML의 첫 날짜 표기) | https://www.hyundaimotorgroup.com/ko/story/pleos-connect-next-generation-infotainment-system |
| S3 | HMG 뉴스룸, 앱 마켓 신규 앱 7종 추가 | 2026-09-09 | https://www.hyundaimotorgroup.com/ko/news/42dot-pleos-connect-app-market-new-apps |
| S4 | WardsAuto, 2027 투싼 Pleos Connect 미국 출시 | 2026-10 | https://www.wardsauto.com/news/hyundais-pleos-connect-infotainment-debuts-for-us-in-2027-tucson/832162 |
| S4b | Hyundai USA 보도자료(원문, 본문 추출 실패) | 2026-10-01 | https://www.hyundainews.com/releases/hyundai-introduces-pleos-connect-in-the-us-on-the-all-new-2027-tucson |
| S4c | Car and Driver 시승기 | 미확인 | https://www.caranddriver.com/news/a73594337/hyundai-pleos-connect-infotainment |
| S5 | 현대차 ccNC 사용자 매뉴얼(EV 메뉴, 한국 영문판) | 미확인 | http://webmanual.hyundai.com/ccNC/AVNT/KOR/English/EV.html |
| S6 | 현대차 Gen5 내비 매뉴얼(EV, 미국) | 미확인 | https://ownersmanual.hyundai.com/ivi/STD_GEN5/AVNT/USA/English/017_EV_electricvehicle.html |
| S7 | 기아 ccNC 사용자 매뉴얼(EV, 미국) | 미확인 | https://ownersmanual.kia.com/ivi/ccNC/AVNT/USA/English/EV.html |
| S8 | Hyundai Europe 뉴스, EV Route Planner | 2024-03-14 | https://www.hyundai.news/eu/articles/stories/embracing-seamless-travel-with-car-features-that-put-customers-first.html |
| S8b | Kia Europe, EV Route Planner | 미확인(면책 문구상 2023 EV9 기준) | https://www.kia.com/eu/about-kia/experience-kia/technology/ev-route-planner |
| S9 | HMG 뉴스룸, 채비 PnC 확대 | 2026-06-29 | https://www.hyundaimotorgroup.com/ko/news/hyundai-motor-group-chaevi-plug-charge-ecosystem-expansion |
| S9b | 아시아경제, 국가 PnC 인증체계 | 2026-10-01 | https://view.asiae.co.kr/article/2026100114134655391 |
| S10 | HMG 저널, 코나 일렉트릭 ccNC EV 기능 | 2023-06-02 | https://www.hyundaimotorgroup.com/ko/story/CONT0000000000094580 |
| S11 | Hyundai Europe, IONIQ 3 기능 | 미확인 | https://www.hyundai.com/eu/en/models/ioniq-3/features.html |
| S12 | HMG 뉴스룸, IONIQ 3 공개 | 2026-04-21 | https://www.hyundaimotorgroup.com/en/news/hyundai-motor-company-ioniq-3-world-premiere |
| S13 | 파이낸셜뉴스(영문), 네이버지도·카카오내비 Pleos 입점 | 2026-11-01 | https://en.fnnews.com/news/202611011810549415 |
| S14 | Make My Day 회사 소개 | 미확인 | https://www.makemydayapp.com/ |
| S15 | Pleos 공식, Pleos Connect 소개 | 미확인 | https://pleos.ai/service-solution/pleos-connect?locale=en-US |

### 3.2 Pleos Connect 플랫폼과 출시 차량

| 항목 | 내용 | 출처 |
|---|---|---|
| 출시 차량 | 2026년 5월 국내 신형 그랜저에서 시작해 단계적으로 확대한다. 2026-09 기준 국내 앱 마켓은 '더 뉴 그랜저', '디 올 뉴 아반떼' 등에서 이용 가능하다. 미국·캐나다 첫 차종은 2027 투싼(12.9·17인치 디스플레이)이다. 유럽 첫 차종은 IONIQ 3(12.9·14.6인치)이다. 2030년까지 약 2,000만 대 적용이 목표다. | S1, S3, S4, S12 |
| 화면 구성 | 대화면 좌측 주행 정보 화면(속도·경고등·주변 객체 3D), 앱 화면(단일·분할), 하단 바(최근·고정 앱), 운전자 전방 슬림 디스플레이(속도·미디어·TBT). 앱 화면 콘텐츠는 주행 정보 화면을 침범할 수 없도록 분리되어 있다. | S1, S2 |
| 주차 전용 UX | 전기차 유틸리티 모드나 하이브리드 스테이 모드 같은 정차 상태에서는 풀스크린 UX로 전환해 대형 디스플레이 전체를 미디어 화면으로 쓸 수 있다. | S2 |
| 위젯·글랜서빌리티 | 슬림 디스플레이는 속도·연비·경로 안내를 위젯으로 조합하며, 눈부심을 줄이는 다크 테마가 기본이고 라이트 테마로 바꿀 수 있다. 하단 유틸리티 위젯은 주행 상황에 따라 맥락 아이콘을 보인다. | S2, S4c |
| 입력 | 스티어링 휠과 대화면 하단 물리 버튼, 3핑거 제스처로 앱 위치 변경·종료, 아래에서 위로 쓸어 올려 공조 패널 호출. | S1, S2 |
| 음성 | LLM 기반 Gleo AI가 맥락 이해, 다중 명령, 웹 검색, 차량 제어, 발화 좌석 인식을 제공한다. 2026-04 기준 동작은 차량 제어·편의 기능 중심이며 서드파티 앱 연동은 향후 계획이다. | S1, S2 |
| 내비게이션 | 데이터 기반으로 고빈도 기능 중심 재배치, 전체 경로·ETA를 플로팅 카드로 표시, 실시간 교통. 미국은 HERE 지도와 Google Places를 쓴다. 전기차는 목적지 도착 시 예상 배터리 잔량을 고려해 충전소를 자동 추천한다. | S1, S2, S4 |
| 앱 마켓 | 국내 18종. 2026-09 신규 7종은 카카오내비, what3words, Make My Day - Route Planner, 멜론, Radioline, 래디오오토, 픽클이다. 초기 제공 앱으로 네이버 오토, 네이버 웨일, 유튜브, 스포티파이, SBS고릴라, 에센셜, 지니가 언급된다. 초기 11종의 전체 목록은 미확인이다. 2026-11 보도로 네이버 지도도 입점했다. 유럽 IONIQ 3는 Bluelink 활성화·myHyundai 연동·Pleos Package 구독이 있어야 앱 마켓을 쓸 수 있다. 앱은 안전·데이터 보안 심사를 거친다. | S1, S2, S3, S4c, S11, S13 |
| 스마트폰 연동 | 무선 Apple CarPlay·Android Auto를 함께 지원한다. | S4, S4c |

### 3.3 StayGo와 겹치는 양산 EV 기능

| 기능 | 차량·플랫폼 | 보여 주는 것과 시점 | 하지 않는 것 | 출처 |
|---|---|---|---|---|
| 충전 한도(Charging limit) | 현대·기아 ccNC | 고전압 배터리의 최대 충전량(%)을 설정한다. EV 화면의 차량 이미지를 누르면 진입한다. | 사용자가 정한 정적 상한이다. 일정·다음 목적지·충전 곡선을 보고 상한을 권고한다는 기술은 없다(미확인). | S5, S7 |
| DC·AC 목표 충전량별 예상 시간·주행거리 | 현대 Gen5 내비 | 목표 충전량까지 DC·AC 충전 시 예상 충전 시간과 주행 가능 거리를 보인다. 주행 가능 거리는 실시간 전비 기반 추정이며 주행 패턴에 따라 달라진다. | 도착 후 필요 에너지와 비교해 "이만큼이면 충분하다"를 판정하지 않는다(미확인). | S6 |
| 실시간 충전 정보 | 기아 ccNC(옵션) | 현재 충전 전력과 연결된 충전기의 최대 충전 전력을 표시한다. 차량 보조기기 소비 전력은 포함하지 않는다. | 충전 전력 저하를 근거로 이탈 시점을 권고하지 않는다(미확인). | S7 |
| 주변 충전소 검색·상태 | 현대·기아 ccNC, 코나 일렉트릭 | 검색 범위·충전 속도·운영 기관 필터, 실시간 상태(사업자에 따라 다를 수 있음). 코나 일렉트릭은 분할 화면에 즐겨찾는·경유지·가까운 충전소, 충전기 종류·수, 최근 이용 시간을 보인다. | 표시 정보가 실제와 다를 수 있다고 매뉴얼이 고지한다. | S5, S7, S10 |
| 충전소 경유 경로 | 현대·기아 ccNC | 목적지까지 거리가 주행 가능 거리를 넘으면 주행 가능 거리 안의 충전소를 경유하는 경로를 안내한다. | 충전량 목표를 함께 제시하는지는 미확인이다. | S5, S7 |
| EV Route Planner | 현대·기아(유럽 서비스), ccNC 설정 | 최대 4개 충전 정차지를 자동 추천(고속 충전기 우선)하고 배터리 소비·교통·도로 유형·지형·날씨를 반영한다. 충전 시간을 ETA에 포함하고 정차지와 목적지의 SOC를 예측한다. 경로와 배터리 컨디셔닝이 연동된다. ccNC 설정에서는 경로 주행 시 허용 최소 충전량을 정한다. | 정차지별 권장 충전량·출발 시점을 충전 중에 실시간 갱신하는지는 미확인이다. 기아 면책 문구상 Kia Connect 가입이 필요하고 일부 EV만 지원한다. | S5, S8, S8b |
| Smart Charging Route Planning | IONIQ 3(Pleos Connect) | 배터리 잔량과 경로에 따라 충전 정차지를 자동 추가하고 속도·커넥터·선호 네트워크로 필터한다. | 충전 중 이탈 권고 기능은 언급이 없다(미확인). | S11, S12 |
| 도착 SOC 고려 충전소 추천 | Pleos Connect 내비 | 전기차는 목적지 도착 시 예상 배터리 잔량을 고려해 충전소를 자동 추천한다. | 추천 조건·임계값은 공개되지 않았다(미확인). | S2 |
| 배터리 컨디셔닝 | 현대·기아 ccNC | 급속 충전 성능 유지를 위해 배터리 온도를 조정한다. 코나 일렉트릭은 목적지를 충전소로 설정하지 않아도 쓸 수 있도록 개선했다. | — | S5, S10 |
| 충전 시작 음성 알림 | 현대·기아 ccNC | 충전 시작 시 충전 상태를 음성으로 알린다. | 충전 중간·완료 판단 안내는 언급이 없다(미확인). | S5, S7 |
| Plug & Charge | 현대·기아·제네시스 지원 EV | 케이블 연결만으로 인증·충전·결제를 자동 처리한다. 국내는 E-pit 83곳에서 2026-06-29 채비 전국 1,500여 곳으로 확대되었다. 국가 PnC 인증체계는 2026년 12월까지 시범 운영 후 2027년 2월 공식 서비스를 계획한다. | 충전 종료 시점 결정에는 관여하지 않는다. | S5, S9, S9b, S8 |
| Make My Day - Route Planner | Pleos App Market(국내) | 개발사는 EV 경로·충전 최적화를 플릿 대상으로 소개한다. | Pleos 앱의 실제 기능·화면·충전 중 권고 여부는 미확인이다. | S3, S14 |
| 충전 세션 중 Stay/Go 권고 | — | 공개 자료에서 확인하지 못했다. | — | 미확인 |

### 3.4 StayGo에 주는 함의

- 양산 기능은 "얼마나 충전할지"를 사용자가 정하거나(충전 한도), 출발 전 경로 계획 단계에서 정한다(EV Route Planner). 충전 세션 도중 실측 충전 전력·SOC와 내비 도착 SOC를 결합해 이탈 시점을 권고하는 기능은 확인되지 않았다. StayGo의 차별성은 이 시점에 둔다. `[추론]`
- 양산 기능의 화면은 충전기·충전 전력·예상 시간·주행 가능 거리를 숫자로 보이며, 추정치가 실제와 다를 수 있음을 매뉴얼에서 고지한다(S5, S6, S7). StayGo도 같은 수준의 불확실성 고지를 기준으로 삼는다. `[추론]`
- 충전 중 차량은 정차 상태이므로 StayGo의 상세 화면은 비-DO Activity로 설계할 수 있다. 다만 주행 시작 즉시 화면이 가려지는 동작을 전제로 해야 한다(P7, A2). `[추론]`
- 충전 한도 설정·충전 중지 같은 차량 제어는 Vehicle SDK 퍼미션 사전 승인 대상이며(P4), StayGo가 이를 직접 바꾸는 기능은 공개 자료만으로 실현 가능성을 확인할 수 없다(미확인).

---

## 4. StayGo 평가 기준(안)

### 4.1 배점 요약

| 번호 | 기준 | 배점 | 비교 기준(양산 레퍼런스) |
|---|---|---:|---|
| R1 | 주행 안전·UX 제한 준수 | 15 | Pleos 지역별 주행 규제 정책·주행 규제 개발 가이드(P7, P8), AAOS DD(A2, A3) |
| R2 | 권고의 정확성·데이터 정직성 | 15 | ccNC 목표 충전량별 예상 시간·주행거리(S6), EV Route Planner SOC 예측(S8), 기아 실시간 충전 정보(S7) |
| R3 | 즉시성·가독성 | 10 | Pleos UI Design Guide 원칙(P20, P21), 슬림 디스플레이 위젯·다크 기본(S2) |
| R4 | 화면·멀티윈도우 적응 | 10 | Window Policy·Display Layout(P22, P23), 디스플레이 호환성 심사(P4) |
| R5 | 성능·자원 사용 | 10 | 성능 심사 수치(P4), 성능·리소스 가이드(P9), BG→CCH(P25) |
| R6 | 안정성·수명주기 | 10 | 앱 완성도 심사(P4), 앱 구조 및 동작 모델(P11), Core app quality(A4) |
| R7 | 개인정보·보안 | 10 | 개인정보·보안 심사(P6), 앱 보안 가이드(P14) |
| R8 | 플랫폼 API 정합성·가용성 처리 | 8 | API 호환성(P13), SDK 퍼미션 심사(P4), NaviHelper(P29) |
| R9 | 입력·음성·알림 | 6 | CCP 가이드(P19), 알림 원칙(P20), 선행 묵음(P28) |
| R10 | 출시 준비·운영 | 6 | 심사 제출 항목(P1, P3), 업데이트·버전 정책(P15, P16) |
| | 합계 | 100 | |

배점 근거: Pleos 심사 총론이 운전자 주의 분산과 사이버보안을 중점 검증 대상으로 명시하므로(P2) R1과 R7을 합해 25점으로 둔다. R2는 StayGo 고유 가치이며 오권고가 곧 운전자 피해(불필요한 대기 또는 도착 불가)로 이어지므로 R1과 같은 15점으로 둔다. `[추론]`

### 4.2 기준별 통과·실패 점검

각 점검은 관찰 가능한 결과로 판정한다. 한 기준 안에서 `필수` 표시 점검이 하나라도 실패하면 그 기준은 0점으로 처리하고, 나머지 점검은 통과 비율로 배점을 나눈다. `[추론: 제안 규칙]`

#### R1 주행 안전·UX 제한 준수(15)

- EMU(필수): `adb shell cmd hmg_car_service --service drivingstate driving_regulation on` 실행 시 비-DO 화면이 가려지거나 실행되지 않는다. `off` 후 정상 사용으로 돌아온다.(P7, P17)
- EMU(필수): 규제 on 상태에서 앱 런처 아이콘을 눌러도 비-DO Activity가 열리지 않는다.(A2)
- EMU: 화면 사용 중 규제 on으로 바뀌면 `onPause`가 호출되고, 진행 중 TTS·애니메이션이 멈추며 off 전까지 재개되지 않는다.(A2, P10)
- CODE(필수): `distractionOptimized=true`는 실제로 DO 기준을 충족한 Activity에만 선언되어 있다. 비-DO Activity는 `isRequiresDistractionOptimization()`만 참조한다.(A2, A6)
- CODE(필수): 주행 판정에 속도·기어 조합을 쓰지 않고 `CarUxRestrictionsManager`를 쓴다. 리스너 등록과 해제가 쌍을 이룬다.(A2, P8)
- CODE: Manager 연결 실패·조회 실패 시 상호작용을 잠그는 fail-closed 분기가 있다.(`[추론]` P8 요구를 안전 측으로 해석)
- CODE: DO 화면이 있다면 북미 기준 `UX_RESTRICTIONS_NO_KEYBOARD`, `NO_SETUP`, `LIMIT_STRING_LENGTH`, `LIMIT_CONTENT`에 따라 입력·설정·긴 텍스트를 숨긴다.(P7)
- 불가(실차): 차종·플랫폼 버전·지역별로 실제 전달되는 `ActiveRestrictions` 값.(P7, P27)
- 불가(심사): 심사관의 추가 위험 판단.(P2, P5)

#### R2 권고의 정확성·데이터 정직성(15)

- CODE(필수): 권고 계산식(필요 에너지, 현재 SOC, 충전 전력, 도착 SOC, 여유분)이 문서화되어 있고 단위 테스트가 경계값(SOC 0·100, 전력 0, 도착 SOC 결측)을 포함한다.
- EMU(필수): Virtual Console로 SOC·충전 상태를 바꿨을 때 화면의 입력값과 권고가 계산식과 일치한다. 결측 입력이면 권고를 만들지 않고 "판단 불가"를 표시한다.(P13의 가용성 확인 원칙)
- EMU: 화면에 권고의 근거(도착 예상 SOC, 목표 여유 SOC, 예상 추가 대기 시간)가 숫자로 함께 보인다. 양산 화면이 예상 시간·주행거리를 숫자로 보이는 수준을 기준으로 한다.(S6, S7)
- EMU: 추정치가 실제와 다를 수 있다는 고지가 있다. 양산 매뉴얼의 고지 수준을 기준으로 한다.(S5, S6)
- CODE: 내비 도착 SOC와 앱 자체 추정이 다를 때 어느 값을 쓰는지 규칙이 명시되어 있다.
- 불가(실차): 실제 충전 곡선·기온·충전기 출력 제한에서의 권고 적중률, 내비 도착 SOC의 실차 오차.
- 불가(실차): 양산 EV Route Planner와 같은 조건에서의 비교 시험.

#### R3 즉시성·가독성(10)

- EMU(필수): 주 화면 대비율이 4.5:1 이상이다(캡처 후 전경·배경 색 측정).(P20)
- CODE(필수): 단일 메시지 120자(영문 기준)·24단어 이하, 한 줄 80자 이하, 단락 3줄 이하를 넘는 문자열이 없다. 한국어 문자열의 상한은 공개 기준이 없어 미확인이며, 영문 기준을 준용한다.(P20)
- EMU: Stay/Go 결론이 주 화면 첫 시선 영역에 단어 하나와 숫자 하나 수준으로 보인다. 1~2초 파악 원칙을 기준으로 한다.(P20)
- EMU: 다크·라이트 테마 전환 시 모든 화면이 판독 가능하다.(P20, A4)
- EMU: 폼 팩터 DPI 기준으로 환산한 중요 텍스트 높이가 5 mm, 보조 텍스트가 3 mm 이상이다. 최소 12sp를 지킨다.(P20, P21)
- EMU: 주 화면에 자동 반복 애니메이션·자동 스크롤 텍스트가 없다.(P20, A1)
- 불가(실차): 실제 패널 PPI·시야 거리·주광 조건에서의 판독성, 운전자 시선 체류 시간.

#### R4 화면·멀티윈도우 적응(10)

- EMU(필수): portrait(1 window)와 landscape(2 window)에서 핵심 기능이 모두 노출되고 버튼 위치·기능이 같다.(P4, P11)
- EMU(필수): FormFactorConsole의 12.9·14.6·17인치와 Wide 프리셋에서 요소 잘림·겹침·레터박스·호환성 모드가 없다.(P4, P18)
- EMU: 사용 중 window 크기 전환 시 입력 중 값·스크롤 위치·선택 상태가 유지된다.(P4)
- EMU: 다른 앱의 IME 표시 중에도 StayGo 화면이 밀려 올라가지 않는다.(P26)
- EMU: 상태 표시줄·GNB·키보드가 덮어도 상호작용 요소가 가려지지 않는다.(P4, A1)
- 불가(실차): Connect-W 실제 차량의 높이 확장(Extended) 동작, 조수석 window 실행 동작.

#### R5 성능·자원 사용(10)

- EMU(필수): `adb shell am start -W`로 콜드 스타트가 10초 이내이며, 2초를 넘으면 진행 표시가 나온다.(P4, A4)
- EMU(필수): 다른 앱으로 전환해 사용자 제공 기능이 없는 상태에서 `adb shell dumpsys activity lru`로 FGS가 남아 있지 않다.(P25)
- EMU: 버튼 반응 2초 이내, 터치 피드백 0.25초 이내(화면 녹화 프레임 기준).(P4, P20)
- EMU: 정차 상태 CPU 25% 이하, 규제 on 상태 CPU 5% 미만, `dumpsys meminfo` PSS 400 MB 이하.(P9)
- EMU: `dumpsys gfxinfo`에서 잰크 프레임이 반복되지 않는다.(P4, A4)
- CODE: 백그라운드 폴링·타이머가 없고 상태 구독은 화면 표시 수명주기에 묶여 있다.(P9, P11)
- 불가(실차): 실차 SoC에서의 CPU·메모리·전력 수치.

#### R6 안정성·수명주기(10)

- EMU(필수): 주요 흐름 반복과 `adb shell monkey` 실행 중 크래시·ANR이 없다.(P4, A4)
- EMU(필수): 백그라운드에서 `adb shell am kill <패키지>` 후 복귀하면 직전 화면과 진행 상태가 복원된다.(P11)
- EMU: 홈 이동·다른 앱 실행·window 전환 후 복귀 시 마지막 상태가 복원된다.(P4)
- EMU: 디버그 빌드에서 StrictMode `detectAll()` 경고가 없다.(A4)
- CODE: Back 처리에서 Hide 또는 Finish 중 하나를 일관되게 쓰며, Finish를 택하면 저장·리소스 정리를 한다.(P19)
- EMU: 설치·삭제 후 잔존 파일이 없다.(P4)
- 불가(실차): 장시간 충전 세션(수십 분), 차량 전원 사이클·Suspend 복귀에서의 안정성.

#### R7 개인정보·보안(10)

- CODE(필수): 릴리스 APK가 `debuggable=false`이고 정식 배포 인증서로 서명된다.(P14)
- CODE(필수): 로그에 위치·VIN·SOC 이력·계정 식별자 등 민감정보가 출력되지 않는다. `adb logcat`으로도 확인한다.(P14, A4)
- CODE: 외부 통신은 HTTPS·TLS 1.2 이상이며 토큰을 URL에 넣지 않는다. 키·토큰 하드코딩이 없다.(P14)
- CODE: 외부 호출이 불필요한 컴포넌트는 `exported=false`다. 불필요한 권한이 없다.(P6, P14)
- EMU: 수집 항목·목적·보유기간·거부권 고지와 명시적 동의 버튼이 있고, 거부 시에도 핵심 기능이 축소 동작한다.(P6, A4)
- CODE: 저장하는 민감정보는 Keystore 기반으로 암호화한다. 사용하지 않는 자동 백업은 끈다.(P14)
- 불가(심사): Pleos 보안 심사 결과, 위치정보 사업 신고 등 국가별 법적 증빙 확인.(P6)

#### R8 플랫폼 API 정합성·가용성 처리(8)

- CODE(필수): Vehicle SDK 도메인은 `checkXXXCapabilities`로, AAOS 속성은 `CarPropertyManager`로 지원 여부를 확인한 뒤 쓴다. 모델명 분기가 없다.(P13)
- CODE(필수): 매니페스트에 선언한 Vehicle SDK 퍼미션 목록과 사용 이유가 문서화되어 있다. 승인 대상임을 명시한다.(P4)
- EMU: NaviHelper 미연결·목적지 미설정·이벤트 미수신 상황에서 앱이 멈추지 않고 대체 화면을 보인다.(P13, P29)
- CODE: SDK 초기화·해제 순서가 공식 순서와 일치한다.(P29)
- 불가(심사): Vehicle SDK 퍼미션 승인 여부.(P4)
- 불가(실차): 차종별 capability 결과, 내비 비탑재 차량에서의 동작.(P13, P27)

#### R9 입력·음성·알림(6)

- EMU(필수): CCP 없이 터치만으로 모든 필수 과업을 끝낼 수 있다. CCP를 지원하지 않으면 `ROTARY_SUPPORTED=false`를 선언한다.(P19)
- EMU: 키보드 Tab·방향키로 모든 상호작용 요소에 포커스가 이동한다(로터리와 같은 API).(A1 LS-U4)
- CODE: 알림은 충전 상태 변화 등 운전자 필요 사항에만 쓰며 광고가 없다.(P20, A1)
- CODE·EMU: 음성 안내가 있으면 150~200 ms 선행 묵음과 오디오 포커스 처리를 적용한다.(P28, P9)
- 불가(실차): 실제 CCP 하드키·로터리 입력, 차량 스피커 출력 경로 초기화 지연.(P19, P28)
- 불가(심사): Gleo AI 연동. 2026-04 기준 서드파티 앱 연동은 향후 계획으로만 공개되었다.(S1)

#### R10 출시 준비·운영(6)

- CODE(필수): versionCode·versionName이 이전 제출보다 증가한다.(P16)
- CODE: 아이콘 512×512 px·1 MB 이하, 스크린샷 규격 2~8장, 앱 제목 영문 30자 이하, 출시 언어별 이름·설명·개인정보처리방침 URL이 준비되어 있다.(P1, P3)
- EMU: 최신 버전 확인 → 안내 팝업 → `market://detail?package=` 이동 흐름이 동작한다.(P15)
- CODE: 더미 데이터·테스트 리소스·임시 파일이 릴리스 빌드에 없다.(P4)
- CODE: 심사 노트용 기능 사용 설명서와 테스트 계정 준비 상태를 기록한다.(P1)
- 불가(심사): IARC 등급, 카테고리 적합성 판단, 심사 통과 여부.(P1, P4)

### 4.3 우리가 검증할 수 없는 항목(통합)

| 항목 | 이유 | 관련 기준 |
|---|---|---|
| 차종·지역·플랫폼 버전별 실제 UX 제한 값 | 정책이 플랫폼 버전·양산차에 따라 다르다고 명시되어 있다(P7). | R1 |
| 심사관의 추가 주의 분산 판단 | 가이드 준수와 별개로 거부 가능하다(P2, P5). | R1 |
| 실차 충전 곡선·내비 도착 SOC 오차 기반 권고 적중률 | 에뮬레이터 값은 주입값이다. | R2 |
| 실제 패널에서의 텍스트 물리 높이·주광 판독성 | 패널 PPI와 시야 거리 의존. | R3 |
| Connect-W Extended·조수석 window 동작 | 실차 디스플레이 필요. | R4 |
| 실차 SoC의 CPU·메모리·전력 | 에뮬레이터 성능과 다르다(P27). | R5 |
| 장시간 충전·전원 사이클·Suspend 복귀 | 실차 전원 상태 필요. | R6 |
| 보안 심사, 위치정보 법적 증빙 | Pleos 심사 절차(P6). | R7 |
| Vehicle SDK 퍼미션 승인, 차종별 capability | 사전 협의·승인(P4), 차종별 차이(P13). | R8 |
| CCP 하드키·로터리 실제 입력, Gleo AI 연동 | 하드웨어 필요, 연동 미공개(S1). | R9 |
| IARC 등급, 카테고리 판단, 최종 심사 결과 | 심사 절차(P1). | R10 |

---

## 5. 시제품이 흔히 놓치는 양산 필수 항목

태그 정의: `출처 요구(Pleos)`는 Pleos 공식 문서가 요구한다. `출처 요구(Google)`는 Google 품질 지침이 요구하나 Pleos 구속력은 미확인이다. `권장`은 공개 출처에서 해당 요구를 찾지 못한 일반 관행이다.

| 항목 | 시제품의 흔한 빈틈 | 태그·출처 | 점검 방법 |
|---|---|---|---|
| 접근성·TalkBack | 아이콘 버튼에 설명이 없다. | 출처 요구(Google): Content_Description, https://developer.android.com/docs/quality-guidelines/core-app-quality. Pleos 문서에서 TalkBack 요구는 찾지 못했고, Pleos 에뮬레이터의 TalkBack 탑재 여부도 미확인이다. | CODE: `contentDescription` 누락 검사 |
| RTL·로캘 | 한국어 고정 문자열, 지역 정책 미분기. | 출처 요구(Pleos): 다국어 지원 UI 설계(P20), 출시 언어별 메타데이터(P1), `product.sw.target_region`별 UX 정책 적용(P27). RTL 레이아웃 자체는 권장. | CODE: 문자열 외부화, `supportsRtl`, 의사 로캘 시험 |
| 글꼴 크기 배율 | sp 대신 dp·px 고정, 큰 배율에서 잘림. | 권장. 최소 12sp 권장은 출처 요구(Pleos, P21). | EMU: `adb shell settings put system font_scale 1.3` 후 잘림 확인 |
| 프로세스 종료 후 복원 | ViewModel 메모리에만 상태 보관. | 출처 요구(Pleos): https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model | EMU: `am kill` 후 복귀 |
| 콜드 스타트 시간 | 초기화에서 네트워크·SDK 동기 연결. | 출처 요구(Pleos): 10초 이내(P4), 최소 초기화(P9) | EMU: `am start -W` |
| 메모리 | 이미지·차트 캐시 무제한. | 출처 요구(Pleos): RAM 400 MB 이하, https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/performance-resource-mgmt | EMU: `dumpsys meminfo` |
| StrictMode | 메인 스레드 디스크·네트워크 접근. | 출처 요구(Google): StrictMode_Compliance, https://developer.android.com/docs/quality-guidelines/core-app-quality | EMU: 디버그 빌드 StrictMode |
| R8 축소·난독화 | `minifyEnabled false`로 출시. | 권장. Android vitals의 코드 최적화 기준은 Play 지표이다(A5). Pleos 요구는 미확인. | CODE: 릴리스 빌드 설정, 매핑 파일 보관 |
| 개인정보 로그 | 디버그 로그에 위치·SOC·토큰 출력. | 출처 요구(Pleos): 로그 비활성화·마스킹, https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-security-guide | CODE·EMU: `adb logcat` |
| 크래시 리포팅 정책 | 외부 크래시 SDK가 기기·위치 정보를 동의 없이 전송. | 출처 요구(Pleos): 제3자 공유 고지·동의, https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-user-privacy-security. 크래시 SDK에 이 요구가 적용된다는 해석은 `[추론]`이다. | CODE: SDK 목록·전송 항목, 개인정보 신고서 대조 |
| 권한 요청 근거 | 실행 직후 일괄 권한 요청, 근거 문구 없음. | 출처 요구(Pleos): 권한 최소화·명시적 동의(P6). 요청 시점·근거 문구는 출처 요구(Google): Runtime_Permissions, Permission_Rationale(A4). | EMU: 권한 거부·철회 시나리오 |
| 주간/야간 | 라이트 테마 하나만 구현. | 출처 요구(Pleos): 야간/주간 모드 지원, https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro | EMU: 테마 전환 캡처 |
| 멀티윈도우 | 고정 가로 레이아웃, 크기 전환 시 상태 손실. | 출처 요구(Pleos): portrait·landscape 모두 설계, https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/layout | EMU: FormFactorConsole |
| 로터리·포커스 탐색 | 포커스 순서 미정의. | 출처 요구(Pleos): CCP 미지원 시 터치 완결·미지원 선언(P19). 포커스 탐색 자체는 출처 요구(Google): LS-U4(A1). | EMU: Tab·방향키 순회 |
| 음성 | 화면 조작만 가능. | 권장. Pleos는 음성 인터랙션 활용 검토를 권하며(P20), Gleo AI 서드파티 연동은 미공개(S1). TTS를 쓰면 선행 묵음은 출처 요구(Pleos, P28). | CODE·EMU |
| 백그라운드 FGS | 충전 감시를 이유로 FGS 상주. | 출처 요구(Pleos): BG→CCH 필수, https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/bg-to-cch-transition-guide | EMU: `dumpsys activity lru` |
| 기능 가용성 분기 | SDK가 항상 있다고 가정. | 출처 요구(Pleos): capability 확인, https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability | CODE |
| 업데이트 안내 흐름 | 버전 확인·마켓 이동 미구현. | 출처 요구(Pleos): https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-update-guide | EMU |
| 릴리스 서명·디버그 비활성화 | 디버그 키 서명 APK 제출. | 출처 요구(Pleos): P14 | CODE: `apksigner verify --print-certs` |

### 5.1 에뮬레이터 점검 명령 모음

| 목적 | 명령 | 출처 |
|---|---|---|
| 주행 규제 켜기·끄기 | `adb shell cmd hmg_car_service --service drivingstate driving_regulation on` / `off` | P17 |
| 앱 실행(런처와 동일) | `adb shell monkey -p <패키지> -c android.intent.category.LAUNCHER 1` | P17 |
| 콜드 스타트 시간 | `adb shell am force-stop <패키지>` 후 `adb shell am start -W -n <패키지>/<Activity>` | P17(실행·종료 명령), 측정 해석은 `[추론]` |
| 프로세스 상태(FGS·CCH) | `adb shell dumpsys activity lru` | P25 |
| 폼 팩터 전환 | FormFactorConsole + Host Display Bridge(`go run . --avd "<AVD 이름>"`) | P18 |
| 화면 캡처·녹화 | `adb exec-out screencap -p`, `adb exec-out screenrecord --output-format=h264 -` | P17 |
| 유틸리티 모드 진입 | 차속 0·주차 브레이크 on·P단 설정 후 P17의 `vcs_simulator_rx` 명령 | P17 |

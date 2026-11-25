# Stay-or-Go 본선 라이브 시연 실행 계획 (Runbook)

- 시연 길이: 2분 30초(10분판) / 2분(7분판). 대본은 `pt/script_ko.md` 8번 슬라이드.
- 보여 줄 장면: **Stay(도착 SOC 부족) → Go 전환(도착 15%·전력 후반) → Go 탭 → 순정 내비로 경로 전달 → D단 주행 잠금.**
- 백업: `submission/stay-or-go/staygo-demo.mp4`(2분 30초, 같은 에뮬레이터 실제 녹화).

## 1. 무엇으로 돌리나

| 항목 | 값 | 근거 |
|---|---|---|
| 에뮬레이터 | Pleos Connect **v2.1.0** x86_64 (`crp_car_emulator.zip`, nexus-playground.pleos.ai 공개 sys-img), Android 14 / API 34, `automotive` | `apps/pleos-stayorgo-probe/RESULTS.md` Run 2 |
| AVD | 2560×1440 @160 dpi, RAM 8 GB, 4코어, `tag.id=android-automotive`, `image.sysdir.1=system-images/android-34/pleos_car_emulator/x86_64/` | 같은 문서 |
| 실행 | `emulator -avd pleos21 -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect -accel on -port 5560` (시연에서는 `-no-window`를 빼서 창을 띄운다) | 같은 문서 |
| 호스트 | **Linux + KVM + GPU** 에서만 부팅을 확인했다(Ubuntu 24.04, 16코어/30 GB, RTX 4060 Ti). GPU 없는 Linux 박스(Run 1)는 부팅 실패. **Windows 호스트에서는 시도하지 않았다.** | 같은 문서 |
| 앱 | `apk/StayGo_release.apk` — 패키지 `ai.staygo.app`, 런처 액티비티 `ai.staygo.MainActivity`, minSdk 28 / targetSdk 34 (aapt로 확인) | |
| 내비 목적지 설정 | 검증용 프로브 앱(`ai.probe.staygo`)을 intent로 실행해 부산시청 경로 안내 시작, 또는 내비 화면에서 직접 「부산시청」 검색·안내 시작 | `tools/run5.sh`, `apps/staygo/README.md` |
| 차량 값 입력 | 에뮬레이터 내장 Pleos 가상 콘솔(HTTP, `adb forward tcp:18088 tcp:8088`) | 기획서 5항 |
| 시연 스크립트 | **`apps/staygo/tools/demo_live.sh`**(이번에 추가: 녹화·자동 탭 없이 단계별 실행) — 기존 `demo_charge.sh`는 녹화용 | |

> **결정 필요(팀):** 에뮬레이터가 부팅되는 호스트는 데스크톱(4060 Ti)뿐이다. 행사장에 그 PC를 가져갈지, 노트북(Linux+KVM+GPU)에 같은 AVD를 다시 구성할지 정해야 한다. 노트북에서 부팅 확인이 안 되면 라이브 시연은 할 수 없고 녹화 영상으로만 보여야 한다. Windows용 에뮬레이터(WHPX)로 Pleos 이미지가 뜨는지는 확인하지 않았다.

## 2. 시나리오 데이터

| 값 | 설정 | 화면에 기대하는 것 |
|---|---|---|
| 목적지 | 부산시청(35.1796, 129.0756), 경로 약 375 km | 상단 「목적지 부산시청 · 375 km」 |
| 배터리 용량 | 77,400 Wh(에뮬레이터 기본) | |
| 시작 잔량 | `START_KWH=57.3`(SOC 74%) | 「지금 출발하면 목적지에 4%로 도착」, Stay 권장 |
| 충전 곡선 | ≤78%: 230 kW, 78→80%: 230→150, 80→82%: 150→110, ≥82%: 95 kW (1초마다 잔량 입력) | 세션 최대 230 kW, 후반 판정선 115 kW |
| Stay 구간 | 76~85% | 도착 6→14%, 「예비 SOC까지 n%p 부족」, 80%까지는 경유 충전 카드(덕산졸음쉼터 등) |
| Go 전환 | **SOC 86%** | 「목적지에 15%로 도착 · 예비 SOC 15%p 충족」, 「Go · 지금 출발 권장」 파란 버튼 |
| 주행 잠금 | 콘솔 D단·30 km/h | 「주행 중에는 조작할 수 없습니다」 + 요약 한 줄 |

시간 계산(실시간 1배속, 녹화본처럼 빨리 감을 수 없다 — 전력은 잔량의 실제 시간 변화로 계산되므로 입력을 빠르게 하면 화면 전력값도 부풀려진다):
74→78%: 약 48초, 78→80%: 약 29초, 80→82%: 약 43초, 82→86%: 약 1분 57초. **74%에서 Go까지 약 3분 57초.**
→ 그래서 충전 입력(`ramp`)을 **LIVE 슬라이드 2분 10초 전**에 시작한다. 전환 시점 SOC는 약 82%이고 Go는 시연 시작 약 1분 50초 뒤에 나온다.
- 10분판: 4번 슬라이드(한 화면 해법) 시작과 동시에 `ramp`.
- 7분판: 3번 슬라이드(왜 어려운가) 시작과 동시에 `ramp`(LIVE 약 2분 25초 전 → 전환 시 약 83%, Go는 시연 약 1분 35초 뒤).
- 첫 1분 동안 230 kW가 유지되어야 앱이 Pmax=230 kW를 신뢰한다. `open`과 `ramp` 사이가 너무 멀어지지 않게 하고, `ramp`는 반드시 74%(57.3 kWh)부터 시작한다.

## 3. 클릭·명령 순서

준비(발표 30분 전, 대기실):

```bash
# 0) 에뮬레이터 부팅 (창 표시). 약 20초 뒤 sys.boot_completed=1
emulator -avd pleos21 -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect -accel on -port 5560 &
adb -s emulator-5560 wait-for-device
adb -s emulator-5560 shell getprop sys.boot_completed      # 1
adb -s emulator-5560 forward tcp:18088 tcp:8088
# 1) 앱 설치 (이미 설치돼 있으면 -r)
adb -s emulator-5560 install -r -g apk/StayGo_release.apk
adb -s emulator-5560 install -r -g <probe-debug.apk>        # 내비 경로를 intent로 켤 때만 필요
# 2) 내비 경로 안내 시작 (부산시청). 약 8초 뒤 ROUTE_GUIDING
apps/staygo/tools/demo_live.sh route                         # 또는 내비 화면에서 직접 검색·안내 시작
# 3) 앱 열기 + 잔량 74%
apps/staygo/tools/demo_live.sh open
# 4) 리허설 1회: ramp → Go 확인 → stop → drive → park → open(초기화)
```

- `install -g`로 dangerous 권한(android.car.permission.CAR_ENERGY)을 미리 허용한다. 허용하지 않았다면 첫 실행 때 권한 대화상자에서 「허용」을 누른다.
- 프로브 APK는 저장소에 빌드물이 없다. `apps/pleos-stayorgo-probe`에서 `./gradlew :app:assembleDebug`로 만들거나, 내비 화면에서 목적지를 직접 설정한다.

본 시연(팀원 B가 터미널, 발표자 A가 화면):

| 시점 | 누가 | 동작 |
|---|---|---|
| LIVE −2:10 (10분판 4번 슬라이드 시작) | B | `demo_live.sh ramp` 실행. 터미널에 `SOC 75% ... 230 kW` 줄이 1%p마다 찍히는지 확인 |
| LIVE 0:00 | A | 프로젝터 입력을 에뮬레이터 창(전체 화면)으로 전환 |
| 0:15~1:00 | A | 그래프(세션 최대 230 kW, 판정선 115 kW), 「목적지에 1x%로 도착 · 예비 SOC까지 n%p 부족」, Stay 버튼을 짚는다 |
| 약 1:50 | A | 「Go · 지금 출발 권장」으로 바뀌면 **Go 버튼(화면 오른쪽 아래 파란 버튼, 2560×1440 기준 약 x=2100, y=1120)** 을 클릭 → 내비 경로 화면으로 전환 확인 |
| 2:05 | B | `demo_live.sh stop` → 런처 독의 StayGo 아이콘(현재 기본 안드로이드 아이콘)을 눌러 앱으로 복귀 |
| 2:10 | B | `demo_live.sh drive` → 조작 화면이 숨겨지고 요약 한 줄 표시 |
| 2:25 | B | `demo_live.sh park` → 프로젝터를 슬라이드로 되돌림 |

## 4. 실패 시 백업

| 증상 | 판단 기준 | 조치 |
|---|---|---|
| 에뮬레이터가 뜨지 않음·멈춤 | 대기실 리허설에서 `boot_completed` ≠ 1 | 라이브 포기, 8번 슬라이드에서 바로 녹화 영상 |
| 앱이 「충전 중이 아닙니다」에 머묾 | `ramp` 시작 후 40초 넘게 충전 인식 없음 | `stop` → `open` → `ramp` 한 번만 재시도. 시간 없으면 영상 |
| 도착 예상 SOC가 비어 있음 | 내비 경로 안내가 꺼져 있거나 응답 없음 | 시연 전 `route` 재실행. 본 시연 중이면 영상 |
| Go가 시연 시간 안에 안 나옴 | LIVE 2:00까지 Stay | 「SOC 86%에서 Go로 바뀝니다」라고 말하고 영상의 해당 구간으로 전환 |
| Go 탭 후 내비가 안 바뀜 | 3초 이상 변화 없음 | 「내비 요청이 실패하면 내비 화면을 열어 직접 고르게 합니다」(설계상 동작)라고 설명하고 진행 |

백업 영상: `submission/stay-or-go/staygo-demo.mp4`(1920×1080, 2분 30초). 전체를 다 틀지 말고 **0:50~1:55**(Stay 78% → Go 86% → 내비 연동 → 주행 잠금) 구간만 재생한다. 녹화본은 3~6배속 구간이 있고 화면에 배속이 표시되어 있으므로 그 점을 말한다. 노트북 로컬에 복사해 두고(네트워크 의존 금지) 영상 플레이어를 미리 해당 시점에 멈춰 둔다. 45초짜리 `staygo-reel.mp4`는 시간이 아주 부족할 때의 대안이다.

## 5. 행사 전 점검표

- [ ] 시연 호스트 결정(데스크톱 반입 / 노트북 재구성) 및 그 호스트에서 에뮬레이터 부팅 확인 — **12/1까지**
- [ ] 같은 호스트에서 `demo_live.sh`로 처음부터 끝까지 3회 리허설, 74%→Go 소요 시간 실측(예상 3분 57초)
- [ ] 프로젝터/HDMI 출력에서 2560×1440 에뮬레이터 창이 잘리지 않는지(창 크기 조정 또는 `-scale`) 확인
- [ ] 마우스 포인터가 보이게(`adb shell settings put system show_touches 1`) 설정
- [ ] 백업 영상 로컬 복사, 0:50 지점 대기, 소리 끔(배경음악)
- [ ] PPTX와 PDF를 노트북·USB 두 곳에 저장
- [ ] 노트북 전원·충전기, HDMI/USB-C 어댑터, 무선 마우스
- [ ] 발표 30분 전 대기실에서 부팅·설치·경로·`open`까지 완료해 두고 화면 꺼짐 방지
- [ ] 대본의 시연 타이밍 큐(LIVE −2:10)를 팀원 B가 숙지

## 6. 이번 작업에서 확인한 것 / 12/3 전에 고칠 것

**확인한 것 (이 PC, 2026-11-25)**
- release APK: `aapt dump badging` 결과 패키지 `ai.staygo.app` 1.0.0, 런처 `ai.staygo.MainActivity`, Pleos·AAOS 권한 6종 선언.
- 빌드·단위 테스트: 결과는 아래 「빌드 기록」 참고.
- 데모 영상에서 시연 경로의 화면 값(78% Stay, 83% 95 kW Stay, 86% Go, 주행 잠금)을 프레임으로 확인했고 슬라이드 4·6과 대본 숫자를 이 값에 맞췄다.

**이 PC에서 하지 못한 것**
- **Pleos 에뮬레이터 실행은 하지 않았다.** 이 PC(Windows)에는 Pleos 시스템 이미지가 없고(일반 `Medium_Phone_API_37` AVD만 있음), 에뮬레이터를 띄운 기록은 Linux+KVM+GPU 호스트뿐이다. 따라서 라이브 경로의 동작은 11/6 녹화 영상으로만 확인된 상태다.

**12/3 전에 고칠 것 / 위험**
1. 시연 호스트 확보(위 「결정 필요」). 가장 큰 위험.
2. 앱 런처 아이콘이 없다(매니페스트에 `android:icon` 없음 → 독에 기본 안드로이드 아이콘 두 개가 보인다. 영상에서도 확인). 심사위원 앞에서 앱 복귀 시 헷갈리므로 아이콘을 넣고 release APK를 다시 만드는 것을 권한다.
3. 74%에서 Go까지 실시간 약 4분 — 시연 시간보다 길다. 이번 계획은 `ramp`를 미리 시작하는 방식으로 해결했다. 더 짧게 하려면 부산보다 가까운 목적지(직행 소비가 약 67%인 경로)를 고르면 83% 부근에서 Go가 나와 약 1분 단축된다. 단, 이 경우 슬라이드 6의 숫자를 바꿔야 한다.
4. 프로브 APK 빌드물이 저장소에 없다 — intent로 경로를 켤 계획이면 미리 빌드해 둘 것.
5. 기획서 7항대로 아직 미구현: 선택 백엔드(후보 출력 조회), 3%p SOC 어긋남 표시, 충전 상태 확인 불가·권한 거절 안내, 목표 SOC 직접 입력. 시연에서 이 화면을 보여 준다고 말하지 않는다.

## 빌드 기록

2026-11-25, Windows 11, Temurin JDK 17.0.21, Gradle 8.10.2 wrapper, AGP 8.5.2, Android SDK Platform 34(빌드 중 자동 설치):

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL in 20m 27s   (첫 빌드, Pleos Maven 의존성 내려받기 포함)
DecisionModelTest   tests=12 failures=0 errors=0
PowerEstimatorTest  tests=4  failures=0 errors=0
app/build/outputs/apk/debug/app-debug.apk  (15.4 MB)
```

- 기획서의 「단위 테스트 16건」과 일치한다(12 + 4).
- 이 PC의 기본 JDK(25)로는 Gradle 8.10.2가 돌지 않는다. JDK 17을 `JAVA_HOME`으로 지정해야 한다.
- release APK는 다시 만들지 않았다(서명용 `keystore.properties`가 저장소 밖에 있음). 시연에는 기존 `apk/StayGo_release.apk`를 쓴다.

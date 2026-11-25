# Stay-or-Go 본선 라이브 시연 실행 계획 (Runbook)

- 시연 길이: 2분 30초(10분판) / 2분(7분판). 대본은 `pt/script_ko.md` 8번 슬라이드.
- 보여 줄 장면: **Stay(도착 SOC 부족) → Go 전환(도착 15%·전력 후반) → Go 탭 → 순정 내비로 경로 전달 → D단 주행 잠금.**
- 백업: **`pt/demo_backup_2026-11.mp4`**(2026-11-25, 같은 에뮬레이터에서 처음부터 끝까지 1배속 실시간 녹화, 4분 37초, 1920×1080, 5.6 MB). 예전 `submission/stay-or-go/staygo-demo.mp4`(배속 구간 있음)는 보조.
- 주요 화면 캡처: `pt/demo_shots/01_open_74.png` … `08_parked.png`, 런처 아이콘 `pt/demo_shots/launcher_icon_zoom.png`, 실측 로그 `pt/demo_shots/demo_timeline_2026-11-25.txt`.

## 0. 2026-11-25 실측 결과 (Linux 데스크톱, 처음부터 끝까지 동작 확인)

전체 경로를 `apps/staygo/tools/demo_rehearse.py`로 5회 돌렸다. 아래 버그를 고친 뒤 마지막 3회(run 3·4·5)는 모두 성공했고 타이밍이 1초 안에서 같았다.

| 단계 | ramp 시작 기준 | 백업 영상 시각 | 화면 |
|---|---|---|---|
| `route` 확인(내비가 목적지 보고) | ramp 전 약 12초 | — | — |
| `open` (74%, 「목적지에 4%로 도착」) | ramp 전 약 8초 | 0:00 | `01_open_74.png` |
| `ramp` 시작 (= **LIVE −2:10 큐**) | 0:00 | 0:04 | |
| 충전 인식 + **Stay 권장**(Pmax 230 kW) | +0:33 (SOC 76%) | 0:37 | |
| SOC 78% (230 kW, 도착 8%, Stay) | +0:49 | 0:54 | `02_stay_78.png` |
| SOC 82% (= LIVE 0:00 시점) | +2:01 | 2:05 | |
| SOC 83% (100 kW, 도착 12%, Stay) | +2:30 | 2:35 | `03_stay_83.png` |
| **Go 전환**(SOC 86%, 도착 15%, 판단 규칙 4) | **+3:58.6** (= LIVE +1:49) | 4:03 | `04_go.png` |
| Go 탭 → 내비 전면(1초 이내), 경로 선택 화면 + 5초 카운트다운 후 안내 시작 | +4:01 | 4:05 / 4:13 | `05_after_go_navi.png` |
| `stop` → 앱 복귀(목적지·Go 유지) | +4:17 | 4:22 | `06_back_in_app.png` |
| `drive` → 주행 잠금 「주행 중에는 조작할 수 없습니다」 | +4:22 | 4:27 | `07_drive_lock.png` |
| `park` | +4:27 | 4:32 | `08_parked.png` |

- **예상 3분 57초 → 실측 3분 58.6초.** 대본 큐(LIVE 2분 10초 전 `ramp`)는 그대로 두면 된다. 이때 LIVE 0:00의 SOC는 약 82%(Stay), Go는 LIVE 약 1:49에 나온다.
- 화면 잘림 없음: 2560×1440 헤드유닛 해상도에서 카드·버튼·그래프가 모두 들어온다(그래프의 「후반 판정선 115 kW」 글자가 곡선과 살짝 겹치는 정도).
- 런처 아이콘: 앱 목록과 독에 새 아이콘(파란 배경, 충전 플러그에서 갈라지는 Stay/Go 화살표)이 「스테이고」로 보인다.
- 실측 당시 호스트는 다른 작업으로 load 25~30(16코어)이었다. 이 부하에서도 고친 뒤에는 타이밍이 흔들리지 않았다.

### 이번에 고친 것 (시연 경로에서 실제로 깨진 것만)

| 증상 (리허설에서 실제로 봄) | 원인 | 수정 |
|---|---|---|
| `demo_live.sh`의 모든 쓰기가 **503** — 차량 값이 하나도 안 바뀜 | Virtual Console은 브라우저 세션이 1초마다 `POST /browser/session`을 보내야 쓰기를 받는다(마지막 핑 후 약 3초 뒤 만료). 스크립트·런북에 이 하트비트가 없었다 | 쓰기 명령마다 백그라운드 하트비트를 자동 시작(`hb-stop`으로 종료). `ramp`는 쓰기 한 번 실패해도 멈추지 않고 경고만 출력 |
| `apps/staygo/tools/demo_live.sh route` → 「허가 거부」 | 저장소에 실행 권한 없이 들어가 있음(Windows에서 커밋) | git 모드 `+x` |
| 앱 상단에 **「목적지 ProbeDest」** | 프로브가 목적지 이름 기본값 `ProbeDest`를 씀. 두 번째 `route`는 실행 중인 프로브가 옛 intent를 재사용해 무시됨 | `route`가 프로브를 강제 종료 후 내비 검색 결과와 같은 이름·POI ID·주소로 경로 요청(아래) |
| **Go 탭 후 내비에 「경로 탐색 오류 — 일시적으로 서비스를 이용할 수 없습니다」 팝업** | 외부 앱의 경로 요청에서 POI ID가 비어 있으면 내비가 POI 상세 조회를 실패하고 팝업을 띄운다(경로 자체는 정상 생성). StayGo가 목적지의 POI ID·주소를 버리고 이름·좌표만 다시 보냈다 | `NaviClient`가 내비 `DestinationInfo`의 poiId·poiSubId·address·poiProvider·표시 좌표를 보관했다가 Go 때 그대로 전달(`NaviRouteTest`). 시연용 목적지는 내비 검색 결과 「부산광역시청 부산」(POI `3688dcq7hqkncm5nqhe4`)과 같게 설정 → 팝업 없이 경로 선택 화면이 뜬다 |
| 화면에 **「세션 최대 836 kW」**, 판정선 418 kW | 호스트가 바쁘면 콘솔 쓰기가 밀렸다가 한꺼번에 도착하고, `ramp`가 밀린 몫을 쉬지 않고 연속으로 보내 앱이 짧은 시간에 큰 잔량 증가를 봄 → 30초 중앙값까지 부풀어 Pmax로 굳음 | ① `ramp`는 실제 경과 시간으로 적분하고 항상 약 1초 쉰다(몰아서 보내지 않음). ② `PowerEstimator`: 400 kW 초과 추정치는 버리고, Pmax는 10초 이상 유지된 전력만 인정. 같은 입력이 수정 전 754/1426 kW → 수정 후 230 kW (`stallThenBurstDoesNotInflateSessionMax`) |
| 부팅 직후 `route`가 조용히 무시되어 「목적지 없음」, Go 잠김 | 부팅 직후 내비가 준비되기 전 요청 | `route`가 내비의 목적지 보고를 최대 30초 기다려 `route OK` / `ROUTE NOT SET`을 출력. NOT SET이면 한 번 더 실행 |

단위 테스트: `./gradlew :app:testDebugUnitTest` → DecisionModelTest 12, PowerEstimatorTest 5, NaviRouteTest 1 — **18건 모두 통과**.

## 1. 무엇으로 돌리나

| 항목 | 값 | 근거 |
|---|---|---|
| 에뮬레이터 | Pleos Connect **v2.1.0** x86_64 (`crp_car_emulator.zip` 4.1 GB, nexus-playground.pleos.ai 공개 sys-img), Android 14 / API 34, `automotive` | `apps/pleos-stayorgo-probe/RESULTS.md` Run 2 |
| AVD | `pleos21`: 2560×1440 @160 dpi, RAM 8 GB, 4코어, `tag.id=android-automotive`, `image.sysdir.1=system-images/android-34/pleos_car_emulator/x86_64/` (아래 config.ini) | 같은 문서, 11/25 재구성 |
| 실행 | `emulator -avd pleos21 -no-audio -no-snapshot -no-boot-anim -no-metrics -gpu swiftshader_indirect -accel on -port 5560` (창 표시. 녹화만 할 때는 `-no-window`) | 11/25 실측 |
| 호스트 | **Linux + KVM** 에서 부팅 확인: 11/6 Ubuntu 24.04·RTX 4060 Ti, **11/25 같은 계열 데스크톱(Ubuntu 24.04, 16코어/30 GB, RTX 5080)에서 15~20초에 `boot_completed=1`.** GPU 없는 Linux 박스(Run 1)는 부팅 실패. Windows 호스트는 시도하지 않았다 | |
| 앱 | `apk/StayGo_release.apk` — 패키지 `ai.staygo.app`, 런처 액티비티 `ai.staygo.MainActivity`, minSdk 28 / targetSdk 34 | |
| 내비 목적지 설정 | `demo_live.sh route`(프로브 앱 `ai.probe.staygo`를 intent로 실행). 프로브 APK는 `apps/pleos-stayorgo-probe`에서 `./gradlew :app:assembleDebug` | |
| 차량 값 입력 | 에뮬레이터 내장 Pleos Virtual Console(HTTP, `adb forward tcp:18088 tcp:8088`) + 1초 하트비트(스크립트가 자동) | RESULTS.md Run 3 |
| 시연 스크립트 | **`apps/staygo/tools/demo_live.sh`**(단계별, 녹화·자동 탭 없음), 리허설·녹화 자동화는 **`apps/staygo/tools/demo_rehearse.py`** | |

> **결정 필요(팀):** 부팅이 확인된 호스트는 Linux 데스크톱뿐이다. 행사장에 그 PC를 가져갈지, 노트북(Linux+KVM)에 같은 AVD를 다시 구성할지 정해야 한다. 아래 「호스트 구성」대로 하면 새 Linux 머신에서 약 30분이면 된다(다운로드 4.1 GB). 노트북에서 부팅이 안 되면 라이브 시연은 하지 말고 백업 영상으로 보인다.

### 호스트 구성 (새 Linux 머신, 11/25에 이 순서로 재구성함)

```bash
SDK=<Android SDK>   # emulator 37.2.x, platform-tools 필요
D=$SDK/system-images/android-34/pleos_car_emulator/x86_64; mkdir -p $D
curl -L -o crp.zip https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp_car_emulator.zip
cd $D && unzip -q ~/crp.zip -x 'crp_avd_out_x86_64/system.img' && cp -r crp_avd_out_x86_64/. . && rm -rf crp_avd_out_x86_64
# system.img는 18 GB지만 대부분 0이다. 희소 파일로 풀면 실제 디스크는 이미지 전체 약 7 GB
unzip -p ~/crp.zip crp_avd_out_x86_64/system.img | cp --sparse=always /dev/stdin system.img && rm ~/crp.zip
```

`~/.android/avd/pleos21.ini`(`path=…/pleos21.avd`, `target=android-34`)와 `pleos21.avd/config.ini`:

```
AvdId=pleos21
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.ramSize=8192
hw.lcd.width=2560
hw.lcd.height=1440
hw.lcd.density=160
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.initialOrientation=landscape
disk.dataPartition.size=6G
image.sysdir.1=system-images/android-34/pleos_car_emulator/x86_64/
tag.id=android-automotive
showDeviceFrame=no
```

**호스트 함정 (11/25에 실제로 걸린 것)**
- 창 모드에서 `Could not load the Qt platform plugin "xcb"` 로 죽는다 → `libxcb-cursor0` 필요. 전역 설치가 싫으면 `apt download libxcb-cursor0 && dpkg -x … ./hostlibs` 후 `LD_LIBRARY_PATH=./hostlibs/usr/lib/x86_64-linux-gnu`.
- SSH·에이전트 셸에서 띄우면 `DISPLAY`가 비어 있다 → `DISPLAY=:0 XAUTHORITY=/run/user/$(id -u)/gdm/Xauthority`.
- 한 번 크래시하면 다음 실행 때 「크래시 리포트 보낼까요?」 창이 떠서 부팅이 멈춘다 → `/tmp/android-$USER/emu-crash-*.db` 삭제 후 실행.
- **에뮬레이터가 떠 있는 동안 Gradle 빌드를 돌리지 말 것.** CPU를 다 쓰면 QEMU가 15초 이상 멈추고 에뮬레이터 워치독이 프로세스를 죽인다(11/25 1회 발생).
- 창을 실수로 닫으면 에뮬레이터가 그대로 종료된다(원격 데스크톱 입력으로 1회 발생). 리허설·녹화는 `-no-window`로 돌려도 된다(`screenrecord`·`screencap`은 창 없이 동작).
- 디스크 여유 약 12 GB 이상 필요(이미지 7 GB + userdata 확장).

## 2. 시나리오 데이터

| 값 | 설정 | 화면에 기대하는 것 (11/25 실측) |
|---|---|---|
| 목적지 | 「부산광역시청 부산」(35.18048, 129.07445, POI `3688dcq7hqkncm5nqhe4` — 내비 검색 1번 결과와 동일), 경로 375 km | 상단 「목적지 부산광역시청 부산 · 375 km」 |
| 배터리 용량 | 77,400 Wh(에뮬레이터 기본) | |
| 시작 잔량 | `START_KWH=57.3`(SOC 74%) | 「지금 출발하면 목적지에 4%로 도착」, 경유 충전 카드(청도새마을휴게소) |
| 충전 곡선 | ≤78%: 230 kW, 78→80%: 230→150, 80→82%: 150→110, ≥82%: 95 kW (약 1초마다 잔량 입력, 실제 경과 시간으로 적분) | 세션 최대 230 kW, 후반 판정선 115 kW |
| Stay 구간 | 76~85% | 도착 6→14%, 「예비 SOC까지 n%p 부족」, 80%까지는 경유 충전 카드(덕산졸음쉼터 등) |
| Go 전환 | **SOC 86%** | 「목적지에 15%로 도착 · 예비 SOC 15%p 충족」, 「Go · 지금 출발 권장」 파란 버튼 |
| 주행 잠금 | 콘솔 D단·30 km/h | 「주행 중에는 조작할 수 없습니다」 + 「목적지 도착 예상 SOC 15% · 충전 경유 없음」 |

시간 계산(실시간 1배속, 녹화본처럼 빨리 감을 수 없다 — 전력은 잔량의 실제 시간 변화로 계산되므로 입력을 빠르게 하면 화면 전력값도 부풀려진다):
74→78%: 49초, 78→80%: 29초, 80→82%: 43초, 82→86%: 1분 57초(실측 = 계획). **74%에서 Go까지 3분 58.6초(실측 3회 동일).**
→ 그래서 충전 입력(`ramp`)을 **LIVE 슬라이드 2분 10초 전**에 시작한다. 전환 시점 SOC는 약 82%이고 Go는 시연 시작 약 1분 49초 뒤에 나온다.
- 10분판: 4번 슬라이드(한 화면 해법) 시작과 동시에 `ramp`.
- 7분판: 3번 슬라이드(왜 어려운가) 시작과 동시에 `ramp`(LIVE 약 2분 25초 전 → 전환 시 약 83%, Go는 시연 약 1분 35초 뒤).
- 첫 1분 동안 230 kW가 유지되어야 앱이 Pmax=230 kW를 신뢰한다. `open`과 `ramp` 사이가 너무 멀어지지 않게 하고, `ramp`는 반드시 74%(57.3 kWh)부터 시작한다.

## 3. 클릭·명령 순서

준비(발표 30분 전, 대기실). 저장소 루트에서:

```bash
export SERIAL=emulator-5560
# 0) 에뮬레이터 부팅 (창 표시). 15~20초 뒤 sys.boot_completed=1, 홈 화면이 다 뜰 때까지 20초 더 기다린다
emulator -avd pleos21 -no-audio -no-snapshot -no-boot-anim -no-metrics -gpu swiftshader_indirect -accel on -port 5560 &
adb -s $SERIAL wait-for-device
adb -s $SERIAL shell getprop sys.boot_completed      # 1
adb -s $SERIAL forward tcp:18088 tcp:8088
# 1) 앱 설치 (이미 설치돼 있으면 -r). 서명이 다른 이전 빌드가 있으면 먼저 adb uninstall ai.staygo.app
adb -s $SERIAL install -r -g apk/StayGo_release.apk
adb -s $SERIAL install -r -g apps/pleos-stayorgo-probe/app/build/outputs/apk/debug/app-debug.apk
adb -s $SERIAL shell settings put system show_touches 1
# 2) 내비 경로 안내 시작. 「route OK: 부산광역시청 부산」이 나와야 한다. 「ROUTE NOT SET」이면 한 번 더
apps/staygo/tools/demo_live.sh route
# 3) 앱 열기 + 잔량 74%  →  화면: 74%, 목적지에 4%로 도착, 목적지 부산광역시청 부산 · 375 km
apps/staygo/tools/demo_live.sh open
# 4) 리허설 1회(약 5분, 자동 탭·스크린샷 포함): route → open → ramp → Go 탭 → stop → 복귀 → drive → park
python3 apps/staygo/tools/demo_rehearse.py /tmp/rehearsal --no-record
# 5) 본 시연 직전 초기화
apps/staygo/tools/demo_live.sh route && apps/staygo/tools/demo_live.sh open
```

- `install -g`로 dangerous 권한(android.car.permission.CAR_ENERGY)을 미리 허용한다. 허용하지 않았다면 첫 실행 때 권한 대화상자에서 「허용」을 누른다.
- `demo_live.sh`는 Virtual Console 하트비트를 자동으로 띄운다. 터미널에 `PUT … 503`이 보이면 하트비트가 죽은 것 → `demo_live.sh hb-stop` 후 다시 실행.
- `ramp` 출력은 SOC가 1%p 오를 때마다 한 줄(74→75%는 약 12초 뒤). `WARN … write failed`가 연속으로 나오면 `adb forward`를 다시 건다.

본 시연(팀원 B가 터미널, 발표자 A가 화면):

| 시점 | 누가 | 동작 |
|---|---|---|
| LIVE −2:10 (10분판 4번 슬라이드 시작) | B | `demo_live.sh ramp` 실행. 약 12초 뒤 `SOC 75% … power 230 kW` 줄이 찍히는지 확인 |
| LIVE 0:00 | A | 프로젝터 입력을 에뮬레이터 창(전체 화면)으로 전환. 화면은 SOC 약 82%, Stay |
| 0:15~1:00 | A | 그래프(세션 최대 230 kW, 판정선 115 kW), 「목적지에 1x%로 도착 · 예비 SOC까지 n%p 부족」, Stay 버튼을 짚는다 |
| 약 1:49 | A | 「Go · 지금 출발 권장」으로 바뀌면 **Go 버튼(화면 오른쪽 아래 파란 버튼, 2560×1440 기준 x≈2100, y≈1120)** 을 클릭 → 1초 안에 내비 경로 선택 화면, 약 5초 카운트다운 뒤 안내 시작 |
| 2:05 | B | `demo_live.sh stop` → 런처 독의 **StayGo 아이콘(파란 플러그·갈림 화살표)** 을 눌러 앱으로 복귀. 내비 카운트다운이 끝나기 전에 돌아오면 잠깐 「목적지 없음」이 보이므로 Go 후 10초 이상 지나서 돌아온다 |
| 2:10 | B | `demo_live.sh drive` → 조작 화면이 숨겨지고 요약 한 줄 표시 |
| 2:25 | B | `demo_live.sh park` → 프로젝터를 슬라이드로 되돌림 |

## 4. 실패 시 백업

| 증상 | 판단 기준 | 조치 |
|---|---|---|
| 에뮬레이터가 뜨지 않음·멈춤 | 대기실 리허설에서 `boot_completed` ≠ 1 | 라이브 포기, 8번 슬라이드에서 바로 녹화 영상 |
| 차량 값이 안 바뀜 | `demo_live.sh` 출력에 `503` | `hb-stop` → 같은 명령 다시. 본 시연 중이면 영상 |
| 앱이 「충전 중이 아닙니다」에 머묾 | `ramp` 시작 후 40초 넘게 충전 인식 없음(정상은 33초) | `stop` → `open` → `ramp` 한 번만 재시도. 시간 없으면 영상 |
| 「목적지 없음」, 도착 예상 SOC가 비어 있음 | 내비 경로 안내가 꺼져 있음 | 시연 전 `route` 재실행(`route OK` 확인). 본 시연 중이면 영상 |
| Go가 시연 시간 안에 안 나옴 | LIVE 2:00까지 Stay | 「SOC 86%에서 Go로 바뀝니다」라고 말하고 영상의 4:03 지점으로 전환 |
| Go 탭 후 내비가 안 바뀜 | 3초 이상 변화 없음 | 「내비 요청이 실패하면 내비 화면을 열어 직접 고르게 합니다」(설계상 동작)라고 설명하고 진행 |

백업 영상: **`pt/demo_backup_2026-11.mp4`**(1920×1080, 4분 37초, 1배속 실시간, 소리 없음). **2:14~4:35** 구간이 LIVE 0:00~2:21과 그대로 겹친다(2:14 SOC 82% Stay → 4:03 Go → 4:05 탭 → 4:13 내비 경로 → 4:27 주행 잠금). 배속 구간이 없으니 배속 설명은 필요 없다. 노트북 로컬에 복사해 두고(네트워크 의존 금지) 영상 플레이어를 2:14에 멈춰 둔다. 시간이 아주 부족하면 3:50부터 재생(85% Stay → Go → 잠금, 45초). 45초짜리 `submission/stay-or-go/staygo-reel.mp4`도 대안이다.

## 5. 행사 전 점검표

- [ ] 시연 호스트 결정(데스크톱 반입 / 노트북 재구성) 및 그 호스트에서 에뮬레이터 부팅 확인 — **12/1까지**
- [x] 처음부터 끝까지 리허설, 74%→Go 소요 시간 실측 — 11/25 Linux 데스크톱에서 3회 성공, **3분 58.6초**
- [ ] 시연 호스트에서 `demo_rehearse.py`로 같은 리허설 3회(호스트가 바뀌면 다시)
- [ ] 프로젝터/HDMI 출력에서 2560×1440 에뮬레이터 창이 잘리지 않는지(창 크기 조정 또는 `-scale`) 확인
- [ ] 마우스 포인터가 보이게(`adb shell settings put system show_touches 1`) 설정
- [ ] 백업 영상 `pt/demo_backup_2026-11.mp4` 로컬 복사, 2:14 지점 대기
- [ ] PPTX와 PDF를 노트북·USB 두 곳에 저장
- [ ] 노트북 전원·충전기, HDMI/USB-C 어댑터, 무선 마우스
- [ ] 발표 30분 전 대기실에서 부팅·설치·`route OK`·`open`까지 완료해 두고 화면 꺼짐 방지
- [ ] 대본의 시연 타이밍 큐(LIVE −2:10)를 팀원 B가 숙지
- [ ] 시연 중 그 호스트에서 다른 무거운 작업(빌드 등)을 돌리지 않는다

## 6. 남은 위험 / 메모

1. 시연 호스트 확보(위 「결정 필요」). 가장 큰 위험.
2. 74%에서 Go까지 실시간 약 4분 — 시연 시간보다 길어 `ramp`를 미리 시작하는 방식으로 해결했다. 더 짧게 하려면 부산보다 가까운 목적지를 골라야 하고 슬라이드 6의 숫자가 바뀐다.
3. 내비의 POI 상세 조회 서버는 11/25 기준 실패 중이다(내비 자체 검색 경로에서도 `poiDetails error` 로그). 화면에는 영향이 없지만, 다른 목적지를 쓸 때는 반드시 내비 검색 결과의 POI ID를 `demo_live.sh`에 넣어야 Go 뒤 오류 팝업이 안 뜬다.
4. 기획서 7항대로 아직 미구현: 선택 백엔드(후보 출력 조회), 3%p SOC 어긋남 표시, 충전 상태 확인 불가·권한 거절 안내, 목표 SOC 직접 입력. 시연에서 이 화면을 보여 준다고 말하지 않는다.

## 빌드 기록

2026-11-25 (Linux 데스크톱, OpenJDK 21, Gradle 8.10.2 wrapper, AGP 8.5.2, SDK Platform 34):

```
./gradlew :app:testDebugUnitTest :app:assembleRelease
DecisionModelTest   tests=12 failures=0 errors=0
PowerEstimatorTest  tests=5  failures=0 errors=0
NaviRouteTest       tests=1  failures=0 errors=0
```

- Gradle 8.10.2는 JDK 17·21에서 돈다(JDK 25는 안 됨).
- release 서명은 저장소 밖 `apps/staygo/keystore.properties`를 읽는다(없으면 서명 없는 release가 나온다).

2026-11-25 이전 (Windows 11, JDK 17): 첫 빌드 20분 27초(Pleos Maven 의존성 내려받기 포함), 테스트 12 + 4 = 16건 통과.

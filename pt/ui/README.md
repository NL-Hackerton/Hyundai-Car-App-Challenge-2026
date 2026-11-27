# StayGo UI 개편 — Pleos Connect 순정 스타일 (2026-11-25)

전후 비교: `before_after.png` · 창 크기·다크 모드별 결과: `after_layouts.png` · 개별 캡처: `before_*.png`, `after_*.png`
(모두 Pleos Connect v2.1.0 에뮬레이터 실제 화면, 같은 충전 시나리오 74% → 86%).

## 무엇을 바꿨나

| 전 | 후 |
|---|---|
| 카드 4개가 같은 무게(SOC·전력 그래프·도착 SOC·내비 경로) — 판단은 아래 버튼 색으로만 보임 | **판단 하나가 주인공**: 큰 판정 알약(**STAY** 초록 / **GO** 파랑 / 대기 회색) + 한 줄 제목 + 한 줄 이유 + 상태 칩(부족분·대기 시간 또는 예비 SOC 충족) |
| 숫자·문장이 여러 카드에 흩어짐 | **핵심 숫자 3개**만 오른쪽에: 현재 SOC(막대), 충전 전력(↑→↓ 추세 + 작은 그래프 + 판정선), 도착 예상 SOC(막대 + 예비 15% 눈금) |
| Stay/Go 버튼 둘 다 비슷한 크기·테두리 | **권장 쪽만 채운 큰 버튼**(Pleos 기본 버튼 large 규격, 반대쪽은 회색 basic). 위치는 Stay 왼쪽·Go 오른쪽으로 고정(손이 기억하는 자리가 바뀌지 않게) |
| Pretendard, 임의 색 | **Asta Sans**(Pleos 시스템 글꼴, OFL) + **Pleos Connect 2.1.5 색 토큰**, 라이트/다크 자동 |
| 가로 고정(`screenOrientation=landscape`), 한 레이아웃 | 창 크기별 레이아웃 3종(넓은 가로 / 좁은 창 / 짧은 창) + 세로, 창 크기·주야 전환 때 충전 상태 유지 |
| Go로 바뀌어도 알림 없음 | **Go 알림(Pleos 토스트)**: 충전 세션당 한 번 「지금 출발해도 됩니다 · 목적지 15% 도착」, 4초 |
| 주행 잠금: 검은 화면 + 문장 | 같은 구조에 자물쇠 아이콘, 정적 토큰 색 |

판단 로직(`DecisionModel`, `PowerEstimator`)은 그대로다. 화면 문구는 새 `ui/HeroPresenter.kt`(순수 함수)가 만들고 단위 테스트(`HeroPresenterTest`)로 고정했다.

## 근거: 공식 Pleos 자료에서 가져온 값

가이드 본문(document.pleos.ai)은 규칙만 글로 주고 수치는 이미지에만 있다. 그래서 공식 Figma 키트
`Pleos_Connect_design_assets_215.fig`, `Pleos_Connect_display_guide_215.fig`를 내려받아 Figma 변수(Light/Dark 모드,
form_factor 모드)를 직접 풀어 썼다(`.fig` = zip + kiwi 바이너리, 키트의 Android codeSyntax 이름 포함).

| 항목 | 값 (Connect-S) | 코드 |
|---|---|---|
| 배경 / 카드 | background/primary `#F7F8FA` · surface/basic `#FFFFFF` (다크: background/secondary `#131417` · surface/basic `#1D1E21`) | `values(-night)/colors.xml` |
| 글자 | text/dark/primary·secondary·tertiary `#131417` 100/84/64% (다크: 흰색 같은 비율) | `color_label_gray_*` |
| Go | informative/positive `#0064FF` | `sg_go` |
| Stay(충전) | informative/active `#02C265` / 다크 `#00E074` | `sg_stay` |
| 경고 | regulation/orange `#FF8A00` | `sg_warn` |
| 글꼴 크기 | Feature 112/134, Headline 60/80, Title 48/64, Body 36/44, Label 32/40, Caption 26/34 (sp) | `themes.xml` `T.*` |
| 버튼 | basic large 높이 100dp, radius 32, 좌우 padding 40 | `dimens.xml` |
| 토스트 | padding 40/24, radius 16, 최대 폭 792, 아이콘 48 | `overlays.xml` |
| 아이콘 | 48dp 격자, 상황에 따라 채움/외곽선 | `drawable/ic_*.xml` |
| 창 크기 | 세로 840×1184, 좁은 창 840×1280, 넓은 창 1040×850 (display guide) | `layout`, `layout-w1100dp`, `values-*` |

## 운전자 주의 분산 규칙 확인

| 가이드 규칙 | 어떻게 지켰나 |
|---|---|
| 대비 4.5:1 이상 | 쓰는 글자/배경 조합 13쌍 × 라이트·다크 = 26쌍 모두 통과(최저 4.92:1). `apps/staygo/tools/check_contrast.py`가 검사. 토큰 그대로는 미달인 두 곳만 조정: 초록 위 글자는 흰색 대신 검정(7.8:1), 다크 모드 파란 글자는 `#0082FF`(4.47:1) 대신 밝은 `#3394FF`(5.41:1) |
| 핵심 글자 5 mm 이상 | 판정 알약 48~72sp, 핵심 숫자 64~112sp, 제목 48~60sp (17″ 2560×1440, 약 173 ppi에서 48sp ≈ 7 mm) |
| 1~2초 안에 파악 | 판단 1개 + 이유 1줄 + 숫자 3개. 문구는 한 줄 80자 이하(테스트로 확인) |
| 한 손·큰 터치 영역 | 버튼 100~128dp 높이, 화면 폭 절반 |
| 드롭다운 금지 | 없음 |
| 응답 0.25초 이하 | 버튼 누름 피드백은 리플(즉시), 판단 갱신 1초 |
| CarUxRestrictions | 주행 중(DO 필요) 전체를 덮는 잠금 화면, 요약 한 줄만. Go 알림도 주행 중에는 띄우지 않음 |
| Connect-S 1:1 / Connect-W 2:1 / 전체 / 세로 | 1682×1210(전체), 1280×1210(1:1), 1040×850(넓은 창), 840×1184(세로)에서 실제로 창 크기를 바꿔 확인 — `after_layouts.png` |

## 확인한 것

- 단위 테스트 24건 통과(기존 18 + HeroPresenter 6).
- 시연 경로 전체를 새 UI로 4회 실행: STAY +34초, **GO +3분 58.8초(86%, 도착 15%)**, Go 탭 → 내비 경로 화면, 앱 복귀, 주행 잠금. 백업 영상 `pt/demo_backup_2026-11.mp4`를 새 UI로 다시 녹화했다.
- 시연 버튼 위치가 바뀌었다: Go 버튼 중심 2560×1440 기준 (2100, 1165).

## 하지 않은 것 / 다음 단계

- 홈 화면 위젯(Pleos 위젯 규격)은 만들지 않았다(선택 사항). Go 알림은 앱 안 토스트로만 띄운다.
- 키트의 아이콘 원본(48dp) 대신 같은 격자로 직접 그린 아이콘 8개를 썼다.
- release APK는 서명 키가 없어 다시 만들지 않았다(`pt/demo_plan.md` 점검표).

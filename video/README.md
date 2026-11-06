# StayGo 시연 영상 · 숏폼 (Remotion)

Remotion 4.0.541 + TypeScript. 두 컴포지션:

| ID | 크기 | 길이 | 용도 |
|---|---|---|---|
| `StayGoDemo` | 1920×1080, 30fps | 150 s (4500 f) | 시연 영상 |
| `StayGoReel` | 1080×1920, 30fps | 45 s (1350 f) | 세로 숏폼 |

길이는 배경음악(`bgm-demo.wav` 150.0 s, `bgm-reel.wav` 45.0 s)과 1:1로 고정되어 있다.

## 명령

```bash
npm install
npx remotion studio                       # 미리보기
npm run render:demo                       # out/staygo-demo.mp4
npm run render:reel                       # out/staygo-reel.mp4
# 직접 지정
npx remotion render StayGoDemo out/staygo-demo-draft.mp4 --codec=h264 --pixel-format=yuv420p --crf=18
npx remotion render StayGoReel out/staygo-reel-draft.mp4 --codec=h264 --pixel-format=yuv420p --crf=18
# 무음 렌더
npx remotion render StayGoDemo out/demo-silent.mp4 --props='{"music":false,"musicVolume":1}'
# 프레임 점검용 스틸
npx remotion still StayGoDemo out/check/demo-2000.png --frame=2000
npm run typecheck
```

`remotion.config.ts`가 h264 · yuv420p · CRF 18을 기본값으로 둔다.

## 장면 구성

StayGoDemo (초 기준)

| 구간 | 장면 |
|---|---|
| 0–8 | 훅: 「충전기 꽂고 기다릴까, 지금 떠날까?」 + 충전 막대 |
| 8–30 | 문제: 사실 4개 × 5.5 s (충전 곡선, 1시간 타이머, 과태료 막대, 정보 흩어짐) |
| 30–42 | 해결: 워드마크, 입력 3개 → Stay/Go |
| 42–122 | 시연: `timeline.json` 본편 클립(80 s) + 하단 단계 자막 + 우측 단계 레일 |
| 122–140 | 동작 구조 도식 |
| 140–150 | 아웃트로 + 고지 문구 |

StayGoReel

| 구간 | 장면 |
|---|---|
| 0–3 | 훅 |
| 3–11 | 사실 2개 × 4 s |
| 11–37 | 시연: `timeline.json`의 `reel` 클립(26 s), 위 단계 제목 · 아래 설명 |
| 37–45 | 워드마크, 해시태그, 고지 문구 |

숏폼 핵심 텍스트는 위 220 px, 아래 380 px를 피한다(`src/reel/scenes.tsx`의 `SAFE_TOP`/`SAFE_BOTTOM`).

## 자료 위치

| 경로 | 내용 |
|---|---|
| `public/demo/` | 에뮬레이터 화면 녹화(`staygo-run-1.mp4`, `staygo-run-2.mp4`, 1920×1080 30fps)와 `timeline.json` |
| `public/audio/bgm-demo.wav`, `bgm-reel.wav` | 최종 배경음악. `mp3`/`m4a`도 인식. 없으면 무음으로 렌더 |
| `public/audio/src/` | 음악 원본. 렌더에 쓰지 않는다 |
| `public/fonts/` | Pretendard woff2 (SIL OFL 1.1, `LICENSE-Pretendard-OFL.txt`) |

음악은 이미 길이 · 루프 · 페이드아웃이 처리된 파일이라 코드는 0.3 s 페이드인만 적용하고 볼륨 1.0으로 재생한다(`musicVolume` prop으로 조정).

## timeline.json 스키마

`public/demo/timeline.json`은 렌더 시작 시 `calculateMetadata`에서 읽는다. 코드 수정 없이 파일만 바꾸면 된다. 시간 단위는 초.

```jsonc
{
  "clips": [                       // 본편 시연 구간, 순서대로 이어 붙임
    {
      "file": "demo/01-charging.mp4", // public/ 기준 경로. png/jpg/webp는 정지 화면
      "startFrom": 12.0,              // 원본 시작점(기본 0)
      "endAt": 52.0,                  // 원본 끝점
      "playbackRate": 4,              // 배속(기본 1). 화면 길이 = (endAt - startFrom) / playbackRate
      "label": "실제 화면 녹화 · 4배속", // 좌상단 배지. 생략 시 배속으로 자동 생성, ""이면 배지 숨김
      "fit": "contain",               // contain(기본) | cover. crop이 없을 때만 적용
      "crop": {"x": 641, "y": 0, "w": 1279, "h": 975} // 선택. 원본 픽셀 기준 잘라낼 영역
    }
  ],
  "annotations": [                 // 단계 자막. 번호는 배열 순서(1부터)
    {"at": 0, "title": "충전 감지", "body": "배터리 잔량이 30초 동안 계속 늘면 충전 중으로 본다"}
    // at = 시연 구간 시작 기준 화면 시간(배속 적용 후)
  ],
  "reel": {                        // 선택. 숏폼용 별도 컷(같은 형식)
    "clips": [ ... ],
    "annotations": [ ... ]
  }
}
```

규칙

- 본편 `clips`의 화면 길이 합은 정확히 **80.0 s(2400 f)**, `reel.clips` 합은 정확히 **26.0 s(780 f)**여야 한다. 음악 길이와 맞추기 위한 제약이며, 어긋나면 렌더가 길이를 알려 주는 오류로 멈춘다. 클립별 화면 길이는 `round(((endAt - startFrom) / playbackRate) × 30)` 프레임.
- `reel`이 없으면 본편 컷을 26 s로 압축해 쓴다(배속 자동 조정, 배지는 자동 문구).
- `crop`은 원본 픽셀 좌표(`x`, `y`, `w`, `h`)다. 원본 크기가 1920×1080이 아니면 `sourceWidth`/`sourceHeight`를 함께 적는다. 잘라낸 영역이 화면을 가득 채우도록 확대·이동되고, 구간의 차량 디스플레이 프레임은 **첫 클립 crop의 비율**을 따른다(같은 구간의 클립은 같은 crop을 쓰는 것이 원칙). crop이 원본 범위를 벗어나면 렌더가 오류로 멈춘다.
- 현재 숏폼(`reel.clips`)은 모두 앱 패널(x 641–1920, 상태 표시줄부터 시스템 독 바로 위까지)을 잘라 1000 px 폭 프레임에 크게 보여 준다. 본편은 차량 맥락을 위해 crop 없이 전체 디스플레이를 쓴다.
- 본편 하단 자막은 한 줄로 표시된다. `title`은 8자 안팎, `body`는 30자 안팎이 적당하다. 숏폼 `body`는 18자 안팎(2줄까지 허용).
- 영상 소리는 항상 음소거한다.

## 화면 교체 후 재렌더

1. 녹화 파일을 `public/demo/`에 넣는다.
2. `timeline.json`의 `clips`/`annotations`/`reel`을 실제 구간으로 고친다(길이 합 80 s / 26 s).
3. `npx remotion studio`로 확인 후 `npm run render:demo`, `npm run render:reel`.

## 소스 구조

```
src/
  index.ts, Root.tsx        컴포지션 등록, timeline/음악 로딩, 길이 검증
  theme.ts                  색, 폰트 로딩, 고지 문구
  timeline.ts               timeline.json 타입·검증·시간 계산, 음악 파일 탐지
  components/               motion(전환), charts(충전 곡선), facts(타이머·막대·칩),
                            device(차량 디스플레이 베젤·클립 재생·배지), brand(워드마크), music
  demo/scenes.tsx           16:9 장면
  reel/scenes.tsx           9:16 장면
```

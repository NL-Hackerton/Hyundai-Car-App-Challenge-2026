# 현대자동차그룹 차량용 앱 개발 공모전

- 상태: 팀원 직접 제출 인계 — 최종 90/100·ready, 에이전트 자동 제출 중단·미발송
- 공식 공고: https://appdevchallenge.auto-hmg.io/landing
- 마감: 2026-11-15T23:59+09:00
- 유형·레벨: idea / 로컬 레벨 3, 레지스트리 레벨 2(진행 중 게이트 재기록 CLI 제한)
- 공고 정리: [CONTEST.md](./CONTEST.md)

## 진행 메모

### 2026-10-23 — 공식 양식 확보 및 조건 갱신

- [문헌: 직접 확보] 공식 DOCX를 공개 다운로드 주소에서 1회 확보했다. 원본은 [`docs/form/contest-application-consent.docx`](docs/form/contest-application-consent.docx), 항목별 구조·원본 SHA-256·승인 경계는 [`docs/form-map.md`](docs/form-map.md)에 기록했다. HTTP 200, 55,208바이트이며 원본을 수정하지 않았다.
- [문헌: 직접 확보] 양식 미공개와 마감 시각 추정 기록을 정정했다. DOCX에 2026-11-15 23:59가 명시되어 있고 운영은 KST로 관리한다. 소개자료 분량 제한은 없다. 01 A/B → 02 1~8 → 03 → 04 및 10개 표 구조를 보존한다. 같은 발신자가 마감 전 최종 발송한 수정본이 심사 대상이다.
- [문헌: 직접 확보] 02와 참고 링크·첨부는 개인정보·계정정보·개발자 실명 비노출 대상이다. 01 개인 값·03 동의·04 본인 서명은 제출 승인 후에만 작성한다.
- [문헌: head 열람 기록] 1차 심사기준은 차량특화성·창의성·기술 활용성·사업성이다. 단계별 50/50을 현행 기준에서 제거했으며, 각 25점은 공식 배점이 아닌 내부 [가정]이다. 선택 개발자료 가산점 배점은 미공개다.
- [문헌: head 열람 기록] 최초 접수는 기획서만으로 가능하지만 1차 합격 20팀은 2026-12-03~04 서울 오프라인 PT에서 실제 동작 앱 시연이 필수다. 최종 10팀은 2027년 2월 행사 참석·APK 제출 의무가 있다. PT 참석 인지·진행 지시는 공통 계약에 기록되어 있다.
- 본선 구현 필수에 따라 `.harness/gate.json`에 최신 근거와 과거 근거를 분리하고, `.harness/gate-refresh.json`에 레벨 3 및 기존 접수 개시 전 hold 해소 제안을 작성했다. 로컬 파일 수정일 뿐 `ach gate`나 상태 변경은 하지 않았다. 상세 출처와 직접 확보/head 기록 구분은 `CONTEST.md`에 있다.
- AI 명시 허용·금지·표기 조항 미발견은 허용으로 해석하지 않는다. 순수 창작·독립 기획·개발 및 권리·라이선스 준수 요구와 별도로 실제 도구 활용을 기록한다. 약관의 자동화 접근·수집 금지도 기록하며 적용 범위를 단정하지 않는다.
- 이 기록 시점에는 본문·렌더·시각 검수 전이었다. 이후 진행 결과는 아래 날짜별 기록에 이어 적는다. 개인정보·서명은 승인 전까지 공란으로 유지한다.

### 2026-10-23 — 기획 채점·집필·교정

- 조사 원문과 출처를 `research/`에 보존하고 세 후보를 독립 채점했다. A 50점, B 49점, C 73점에서 C를 선택했으며 개선 1은 86점, 개선 2는 90점·ready였다. 모두 `ach score`에 기록했다. 공식 배점은 미공개이므로 네 기준 각 25점은 내부 가정이다.
- 선택안은 두 사람이 역할을 나누는 반려견 동반 외출에서 차량 문 맥락과 미확인 준비 항목을 연결하는 ‘문별 하차 준비’다. 실제 고객 수요·SDK 동작·앱 구현을 검증했다는 주장은 하지 않는다.
- 본문은 작성 워커가 공식 1~8항목으로 작성했다. 초안 86점·revise 이후 하차 비감지, 개폐 관련성 판단의 한계, 후보 가구 분모와 비교 집계를 명확히 했다.
- cs-report 구간 검수와 ContestEditor 직접 편집을 수행했다. 지정 Argon 호출은 429 할당량 오류 뒤 gpt-6.1-sol로 자동 폴백되었다. 실제 모델 증거·1차 제안·직접 편집·2차 검수 기록은 `.harness/reviews/`에 있다.
- 공식 DOCX 생성기의 경로 보호·링크 보존·외부 입력 거부 결함을 독립 리뷰로 수정했다. 실제 원본·이전 출력 보존 부정경로 smoke 3건은 제작 워커가 확인했으며 최종 증거를 검수 패키지에 남긴다.
- 최종 DOCX/PDF 생성과 제작 담당·head·ContestJudge의 16/16쪽 직접 시각 검수를 완료했다. 원본 구성원표·동의절·서명표와 본문 8개 항목의 텍스트 보존을 확인했다. 최종 90/100·ready를 `ach score`에 기록했으며 개인정보 입력·접수는 수행하지 않았다.

## 팀원 전달 파일과 재생성

- 기획서: [`submission/application-review.docx`](submission/application-review.docx)
- 검수용 PDF: [`submission/application-review.pdf`](submission/application-review.pdf)
- 팀원 제출 절차: [`SUBMISSION.md`](SUBMISSION.md)
- 점수·검수 범위: [`JUDGE.md`](JUDGE.md), [`RUBRIC.md`](RUBRIC.md)
- 원고 입력 계약과 문서 도구: [입력 계약](.harness/work-orders/document-interface.md), `tools/build_document.py`
- 문서 재생성은 `tools/.venv/bin/python tools/build_document.py --replace`로 수행한다. python-docx·LibreOffice·Poppler가 필요하며 최종 파일이 바뀌면 전 페이지 시각 검수를 새로 수행한다. 이 명령은 개인정보를 채우는 기능이 아니며 승인 후 서명한 사본에 적용하지 않는다.
- 이번 산출물은 기획서다. 실행 가능한 앱·시연 영상·APK가 포함된 저장소로 제출하지 않는다.

### 2026-10-23 — 승인용 최종 판정

- 최종 내부 점수는 차량특화성 23/25, 창의성 23/25, 기술 활용성 23/25, 사업성 21/25로 총 90/100·ready다. 실제 모델·원본 판정은 `.harness/judge/final-1-model.json`, `final-1.json`에 있다.
- 승인 대상은 개인정보·동의·서명 공란인 공식 DOCX와 대응 PDF다. 승인 후 개인 값·서명으로 파일이 변경되면 전 페이지를 다시 검수해야 한다. 실제 앱·SDK·고객 수요·수익성의 검증 완료를 주장하지 않는다.
- 최종 파일 해시·관찰 기록·제출 범위는 `JUDGE.md`와 `SUBMISSION.md`를 따른다. 공개되지 않은 공식 배점을 추정한 내부 점수이며 수상 확률이 아니다.
- `ach check` 0건과 최종 점수 기록·commit/push 후 `awaiting_approval`로 전환했다. 컨트롤 승인 요청은 `.private/note.txt`에 남겼으며 승인·개인정보 입력·발송·접수는 수행하지 않았다.

### 2026-10-23 — 팀원 직접 제출로 전환

- 최신 사용자 지시에 따라 자동 제출을 중단했다. 메일 작성·첨부·업로드·발송·접수 및 `ach submit`은 수행하지 않았다.
- 팀원은 위 DOCX의 참가자 정보·동의·서명을 작성하여 직접 제출한다. 현재 개인·학생 선택을 실제 참가 구성과 대조하고, 팀 참가라면 전원 기재·동의·서명·CC를 적용한다.
- 저장소의 기획서와 PDF는 개인정보·서명이 공란인 검수 완료본이다. 중단된 개인정보·서명 사본은 `.private/`에만 보존하며 push하지 않는다.
- 기존 자동 승인 기록은 최신 지시로 발송 재개에 사용할 수 없다. 자동 승인·제출 재개를 막는 최신 인계 메모를 하네스에 기록한다.

### 2026-11-06 — Stay-or-Go 제출 패키지와 프로브 코드

- Stay-or-Go 제출 패키지는 [`submission/stay-or-go/`](submission/stay-or-go/)에 있다. 공식 DOCX는 `application.docx`, 검수용 PDF는 `application.pdf`, 7항 로그 발췌는 `probe-log-excerpt.txt`다. 개인정보·동의·서명은 공란이다.
- Stay-or-Go 프로브 코드는 [`apps/pleos-stayorgo-probe/`](apps/pleos-stayorgo-probe/)에 있다. `https://github.com/yoobinkim541/pleos-stayorgo-probe`의 소스를 가져왔다. 앱, Gradle 래퍼, `tools/`, `logs/`, `RESULTS.md`, README가 포함된다. 커밋된 APK는 없다. 디버그 APK는 그 README의 `./gradlew :app:assembleDebug`로 만든다.
- 이 공모전 저장소는 현재 private다. 심사자가 여는 GitHub 링크가 되려면 공개가 필요하다.
- 제출 절차는 [`SUBMISSION.md`](SUBMISSION.md)의 Stay-or-Go 제출 체크리스트를 따른다. 에이전트는 메일을 보내지 않는다. 사용자·팀이 서명한 뒤 직접 보낸다.

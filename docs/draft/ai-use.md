# AI 활용 기록 초안

## 실제 활용 범위

생성형 AI 도구와 에이전트를 공개 자료 조사·출처 정리, 후보 기획 비교·예측 채점, 사용자 흐름·API 계약·수요 및 사업 검증 계획 구성, 기획서 초안 작성·구간 검토·문체 편집에 활용했다. 문서 작성 도구를 사용하여 `docs/draft/proposal.md`를 작성하고 출처 장부를 보완했다. 초안 평가 원본은 `.harness/judge/draft-1.json`, 구간 검토 제안은 `.harness/reviews/s1.json`, `s2.json`에 있다. 최종 문체 편집자가 내용 수정 뒤의 원고에 구간 제안을 적용하고, 용어 정의·설명 대상·문장 완결성·시간 단위를 다듬었다. 변경 기록은 `.harness/reviews/editor-changes.md`에 남겼다. 본문 7항에 문체 편집까지 포함한 실제 활동 범위의 짧은 AI 활용 고지를 통합했다.

이번 원고 작성 담당의 실행 모델은 `openai-codex/gpt-6.1-sol`이다. 후보 예측 채점의 원본과 실제 모델 근거는 `.harness/judge/plan-1.json`~`plan-5.json` 및 해당 모델 기록에서 관리한다. 원고 작성 담당이 외부 원문을 새로 열람한 것으로 기록하지 않으며, `research/research.md`, `research/sources.json`, `research/selected-plan.md`, `research/ux-restrictions.md`, 공식 양식 지도와 공고 기록에 보존된 확인 결과를 사용했다.

문체 편집의 실제 실행 모델도 `openai-codex/gpt-6.1-sol`이다. 지정된 `google-antigravity/gemini-4-argon:medium` 경로의 구간 검수는 429 `QUOTA_EXHAUSTED` 뒤 허용된 자동 폴백으로 수행되었다. 호출 경로와 실제 모델은 `.harness/reviews/method.md`, `s1-model.json`에 구분해 기록되어 있으며, Argon이 검수를 완료했다고 표시하지 않는다. 최종 편집 세션의 실제 모델 증빙은 호출 세션의 metadata로 관리한다.

## 수행하지 않은 활동

실제 사용자 모집·인터뷰·설문·비교 실험·30일 관찰, 앱 개발·SDK 설치·API 호출·에뮬레이터 구동, 매출·제휴·지불 의사 확인은 수행하지 않았다. 사람의 검토·개발·권리 확인이 완료되었다고 기재하지 않았다. 문체 편집은 수행했으나 최종 채점·DOCX/PDF 생성·전 페이지 시각 검수는 이번 편집 시점에 미실행이다. 문장 교정 완료를 최종 서식 검수 완료로 기록하지 않는다.

## 적용한 작성 기준

- `skill://cs-report`와 `skill://contest-harness`를 읽었다.
- 정본 `/Users/hajung/Hajung/PersonaGraph/memory/writing/report-writing.md`의 완결 문장·설명 대상·용어 정의·사실과 가정 구분 기준을 읽었다.
- 승인 기준본 `/Users/hajung/Hajung/DataCenter/reports/deepvoice-dacon-20261006/current_work/deliver_jy_20261006/1_model_report/1_overview_approach.md` 1~165행을 실제 읽고 문단 연결과 설계 표의 완결 문장을 대조했다.
- `.harness/work-orders/document-production.md`와 `document-interface.md`의 공식 항목·Markdown 입력·개인정보 경계를 적용했다.

## 규정과 책임

공개 공고·FAQ·약관·공지·공식 DOCX의 확인 기록에서 AI 명시 허용·금지·표기 조항이 발견되지 않은 상태다. 이를 허용 보장으로 해석하지 않는다. 순수 창작·독립 기획 및 개발·표절 금지·사실 기재·구성요소 권리와 오픈소스 라이선스 준수 의무는 별도로 적용한다. AI 활용 기록은 이 의무에 대한 참가자의 보증을 대신하지 않는다.

개인정보·프로필·서명 값을 조회하거나 문서에 입력하지 않았으며 접수·외부 업로드·계정 가입을 수행하지 않았다. 참가자의 실제 검토·수정 기여는 이후 수행된 사실만 별도 기록해야 한다.

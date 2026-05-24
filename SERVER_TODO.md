# 서버 작업 요청 (이현서)

앱 쪽에서 통계 일치 수정 + AI 분석 프롬프트 숨김 작업을 했어요.
서버에서 아래 3가지가 받쳐줘야 완성됩니다.

베이스 URL: `https://short-cut-server-production.up.railway.app`
인증: 모든 요청에 `Authorization: Bearer <Firebase ID 토큰>` (기존 `/userlogs` 등과 동일한 미들웨어)

---

## 1. `POST /analyze` 신규 (AI 통계 분석)

앱이 통계 프롬프트를 보내면, 서버가 AI API를 호출해 **분석 결과 텍스트만** 돌려주는 엔드포인트.
(프롬프트를 사용자에게 노출하지 않으려고 클립보드/외부앱 방식을 없앴습니다. 서버 중계가 필요합니다.)

**요청**
```
POST /analyze
Content-Type: application/json

{
  "userId": "<uid>",
  "prompt": "<통계 분석 프롬프트 전문 (긴 한국어 텍스트, 줄바꿈 포함)>"
}
```

**응답 (200)**
```json
{ "analysis": "<AI가 생성한 분석 텍스트>" }
```

**사용할 AI: Google Gemini (확정)**
- API 키: Google AI Studio(https://aistudio.google.com/apikey)에서 발급. 무료 티어 충분.
- **키는 서버 환경변수(예: `GEMINI_API_KEY`)에만 보관** — 앱엔 절대 안 넣음.
- 모델: `gemini-2.5-flash`(또는 `gemini-2.0-flash`) 같은 flash 계열이면 충분 (싸고 빠름). 현재 사용 가능한 모델명은 AI Studio에서 확인.
- 호출 예 (REST):
  ```
  POST https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$GEMINI_API_KEY
  Content-Type: application/json

  { "contents": [ { "parts": [ { "text": "<앱이 보낸 prompt>" } ] } ] }
  ```
  응답에서 `candidates[0].content.parts[0].text` 를 꺼내 우리 응답의 `analysis` 로 반환.

**요청사항**
- 받은 `prompt`를 그대로 Gemini에 전달하고, 답변 텍스트를 `analysis`에 담아 반환.
- 실패 시 4xx/5xx면 됨 (앱이 "다시 시도" 버튼 표시). 응답 본문 형식은 신경 안 써도 됨.
- 프롬프트가 김(최근 14일 통계) → 타임아웃 넉넉히.
- ⚠️ `/analyze` 배포 전까지는 앱에서 AI 분석 버튼 누르면 "다시 시도" 에러만 뜸 (정상).

---

## 2. `/stats/.../daily` 집계 기준 — ✅ 확인됨, 수정 불필요

서버가 이미 로그의 `timestamp` 필드로 필터링/합산하고 있어서 OK.
(`where('timestamp','>=',startOfDay).where('timestamp','<=',endOfDay)`)
원인은 앱이 그 `timestamp`에 "전송 시각"을 넣던 것 → 앱에서 "배치 첫 스크롤 시각"으로 보내도록 수정 완료.

남은 확인 1개: `endOfDay`가 그날 마지막 순간(`23:59:59.999`, 또는 `< 다음날 00:00`)까지 포함하는지.
`23:59:00.000`으로 잡혀 있으면 23:59:00~59.999 가 오늘·내일 양쪽에서 누락될 수 있음.

> 배경: 같은 날인데 화면 위치마다 스크롤 수가 248 / 240 식으로 달랐던 문제.

---

## 3. `/userlogs` 중복 제거 (idempotency) — **dedup 부탁**

앱이 전송 실패 시 재시도하도록 바꿨습니다(데이터 유실 방지). 그런데 "서버는 저장했는데 응답이 유실"되면
앱이 같은 배치를 다시 보낼 수 있어 → 중복 집계되면 이번엔 서버 > 로컬이 됩니다.

그래서 앱이 `/userlogs` payload에 **고유 `logId`를 추가**했습니다:

```
POST /userlogs
{
  "userId": "<uid>",
  "logId": "<UUID 문자열>",   // ← 추가됨
  "timestamp": <ms>,
  "scrollCount": <int>
}
```

**부탁**: 같은 `logId`가 다시 들어오면 **무시(중복 저장 안 함)** 처리해 주세요 (upsert/unique index 등).
이렇게 하면 재전송이 있어도 정확히 한 번만 집계됩니다.

---

작성: 박정은 (앱) · 날짜: 2026-05-24

# Autique 백엔드 API 명세서

프론트엔드 연동용 **전체 API** 명세서입니다(경매·입찰·상품 분석·가격·추천·인증·큐레이션 등 모든 엔드포인트). 아래 API들은 요청/응답 구조가 확정된 상태이며, 변경이 생기면 이 문서를 갱신합니다.

> **문서 범위와 관계**
> - 이 문서(`api/spec.md`)는 프론트가 참조하는 **전체 엔드포인트**의 요청/응답·예시를 담습니다.
> - 경매 도메인의 **business semantics(상태 전이·정책·tie-break 등) 정본**은 `docs/api/auction-spec.md`(FROZEN)입니다. 경매 동작 규칙이 두 문서에서 갈리면 그쪽을 따릅니다.
> - 경매 엔드포인트의 "계약 vs 현재 구현" 차이는 `docs/api/auction-audit.md`에서 관리합니다.
> - 공통 정책(응답 envelope·인증·에러 코드표)이 이 문서와 `auction-spec.md`에 **중복**으로 적혀 있습니다. 지금은 양쪽을 함께 유지하며, 어느 파일을 공통 정책의 단일 출처로 삼을지는 **후속 정리 항목**으로 남깁니다(이번에는 계약을 변경하지 않습니다).
> - 백엔드 전체 동작·흐름 안내는 `docs/backend/overview.md` 참고.

## 배포 서버 정보

시연 환경은 Caddy가 자동 발급한 Let's Encrypt 인증서로 **HTTPS**만 공개합니다. 평문 HTTP(8080)는 외부에 노출되지 않습니다.

```
Base URL : https://<api-공인IP-하이픈>.sslip.io
```

예: 공인 IP가 `3.34.56.78`이면 `https://3-34-56-78.sslip.io`.

고정 도메인·Elastic IP를 쓰지 않으므로 **EC2를 중지했다가 다시 시작하면 공인 IP가 바뀌고 Base URL도 함께 바뀝니다.** 현재 유효한 주소는 배포 담당자에게 확인하세요(배포 절차는 `docs/deployment/runbook.md` 참고). 로컬 개발 환경은 `http://localhost:8080`을 사용합니다.

## 공통 응답 형식

모든 API는 공통 응답 DTO를 적용하여 `success`, `data`, `error` 형식으로 응답합니다.

성공 시 `data`에 결과가 담기고 `error`는 `null`, 실패 시 `data`는 `null`이고 `error`에 오류 코드와 메시지가 담깁니다.

```json
{
  "success": true,
  "data": { },
  "error": null
}
```

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40001,
    "message": "유효하지 않은 요청입니다."
  }
}
```

## 인증

인증 방식은 **실행 환경(Spring profile)에 따라 다릅니다.** 어느 환경이든 "인증이 필요한 API"의 목록과 성공/실패 동작은 동일하고, 사용자를 식별하는 **헤더만** 달라집니다.

| 환경 (profile) | 인증 방식 | 헤더 |
| --- | --- | --- |
| **dev / prod** (배포 서버) | 카카오 로그인으로 발급받은 **JWT Access Token** | `Authorization: Bearer {accessToken}` |
| **local** (로컬 개발) | `X-User-Id` **mock 인증** (헤더 값은 `users` 테이블에 존재하는 사용자 ID) | `X-User-Id: {userId}` |

> 아래 개별 API 설명의 요청 예시에 `Authorization: Bearer ...` 또는 `X-User-Id: ...` 중 하나만 적혀 있어도, 실제로는 위 표에 따라 **실행 환경에 맞는 헤더**를 사용하면 됩니다. 두 헤더는 모두 서버 내부에서 동일한 "현재 사용자(currentUserId)"로 해석됩니다.

인증이 필요한지는 API마다 다음과 같다(기준: dev/prod `JwtSecurityConfig`의 permitAll 매트릭스 — 아래
"선택"·"불필요"의 **GET만** 비로그인 허용이고 그 외는 전부 로그인 필수. local은 Security 필터가 없고
`X-User-Id`로 식별). 로그인 외에 **소유자·역할 제한**이 따로 있으면 "추가 제한"에 표시했다.

### 인증 필수

| API | 추가 제한 |
| --- | --- |
| `POST /api/auctions/{id}/bids` (직접 입찰) | Idempotency-Key. Agent 관리 중이면 `40917` |
| `POST /api/auctions/{id}/auto-bids` (자동입찰 등록) | Idempotency-Key |
| `GET` / `PATCH` / `DELETE /api/auctions/{id}/auto-bids/me` (내 자동입찰 조회/수정/중단) | PATCH는 Idempotency-Key. PATCH·DELETE는 Agent 관리 중이면 `40917` |
| `POST` / `DELETE /api/auctions/{id}/likes` (찜/해제) | — |
| `GET /api/auctions/{id}/result` (낙찰 결과) | — |
| `GET /api/auctions/{id}/live` (실시간 경매) | — |
| `GET /api/auctions/{id}/auto-bid/recommendation` (추천 상한) | — |
| `POST /api/auctions/{id}/award/forfeit` (낙찰 포기) | 낙찰자 본인(아니면 `40303`) |
| `POST /api/auctions/{id}/cancel` (경매 취소) | 판매자 본인(`40307`) |
| `PATCH /api/auctions/{id}/start-price` (시작가 수정) | 판매자 본인(`40307`) |
| `POST /api/auctions/{id}/relist` (재경매) | 판매자 본인(`40307`) |
| `GET /api/orders/{id}` · `POST /api/orders/{id}/pay` (주문 조회·결제) | 주문 소유자(아니면 `40304`) |
| `GET /api/backup-offers/{id}` · `POST .../accept` · `POST .../decline` (차순위) | 제안 candidate 본인(아니면 `40305`). accept는 Idempotency-Key |
| `GET /api/me/penalties` (내 페널티) | 본인 |
| `POST /api/products/analyze` · `POST /api/products/photos` · `GET /api/products/analyze/{taskId}` · `POST /api/products/analyze/{taskId}/cancel` · `POST /api/products/calculate-price` · `POST /api/products` (상품 등록) | 분석 세션 소유자(타인 조회/취소는 `404`) |
| `POST /api/purchase-goals/parse` · `POST` · `GET` · `GET /{id}` · `GET /{id}/matches` · `DELETE /{id}` `/api/purchase-goals` (구매 목표) | 목표 소유자(아니면 `40306`/`40406`) |
| `GET /api/notifications` · `PATCH /api/notifications/{id}/read` · `GET /api/notifications/unread-count` (알림) | 본인 알림(아니면 `40405`) |

### 인증 선택 (비로그인 허용 · 로그인하면 개인화/로그)

| API | 로그인 시 차이 |
| --- | --- |
| `GET /api/auctions/{id}` (경매 상세) | 조회 로그 기록, `myState` 채움 |
| `GET /api/auctions/{id}/bids` (입찰 이력) | `isMine` 채움 |
| `GET /api/auctions/{id}/similar` (비슷한 상품) | `isLiked` 채움 |
| `GET /api/recommendations/auctions` (추천) | 개인화(`personalized: true`), 없으면 Fallback |

### 인증 불필요 (비로그인)

| API | 비고 |
| --- | --- |
| `GET /api/products` (상품 목록) | — |
| `GET /api/curations` (큐레이션) | — |
| `POST /api/auth/kakao` · `POST /api/auth/refresh` · `POST /api/auth/logout` (로그인·토큰) | permitAll(로그인 전/토큰 자체로 인증). 상세는 하단 "# 인증 — …" 절 |

> dev/prod에서 위 "선택"·"불필요"는 **GET만** 비로그인 허용이다(같은 경로의 POST/PATCH/DELETE는 로그인
> 필수). `GET /actuator/health`·Swagger도 permitAll(인프라). **로그인·토큰·알림 API의 요청/응답·필드·예시·
> 오류 상세는 이 문서 하단 "# 인증 — …" / "# 알림 — …" 절**에 있다(이전에는 auction-spec.md에 있었으나
> 이 문서로 통합).

인증에 실패한 경우(**dev/prod**: `Authorization` 헤더 누락·토큰 만료·서명 불일치 등 / **local**: `X-User-Id` 헤더 누락·숫자가 아님·존재하지 않는 사용자 ID) 모두 `401 Unauthorized`(`40101`)를 반환합니다.

## 에러 코드 정리

전체 도메인의 오류 코드를 한곳에 모은 **대표 표**다(`GlobalExceptionHandler` 기준). 경매 도메인 코드의
symbolic 이름·정책 상세는 [`docs/api/auction-spec.md`](auction-spec.md) §0-A가 함께 관리한다. 인증 실패는
dev/prod에서 `Authorization: Bearer` 누락·토큰 만료·서명 불일치, local에서 `X-User-Id` 누락·형식 오류·
존재하지 않는 사용자일 때 발생한다.

| HTTP 상태 | code | 상황 |
| --- | --- | --- |
| 400 | 40001 | 요청 필수값 누락 등 Bean Validation 실패 |
| 400 | 40002 | 이미지 파일 없음 |
| 400 | 40003 | 잘못된 분석 상태에서의 요청 |
| 400 | 40004 | 필수 요청 헤더 누락 (예: `Idempotency-Key`) |
| 400 | 40005 | 잘못된 구매 목표 요청 |
| 400 | 40006 | 경매 시간 설정이 올바르지 않음 (재경매 start/end) |
| 400 | 40007 | photoIds 요청 자체가 잘못됨(중복 photoId/slot, 필수 slot 누락) |
| 401 | 40101 | 인증 실패 (사용자 미식별. `UserNotFoundException`도 방어적으로 이 코드로 매핑) |
| 401 | 40102 | Kakao access token 무효 (`POST /api/auth/kakao`) |
| 401 | 40103 | Refresh Token 무효 (`POST /api/auth/refresh`·`/logout`) |
| 403 | 40301 | 판매자 본인 입찰 시도 |
| 403 | 40302 | 입찰 제한(패널티) 기간 중인 사용자의 입찰 시도 |
| 403 | 40303 | 낙찰자가 아닌 사용자의 낙찰 포기 시도 |
| 403 | 40304 | 주문 소유자가 아닌 접근(조회/결제) |
| 403 | 40305 | 본인 명의가 아닌 차순위 제안 접근(조회/수락/거절) |
| 403 | 40306 | 본인 소유가 아닌 구매 목표 접근 |
| 403 | 40307 | 경매 판매자 본인만 가능한 작업(취소/시작가 수정/재경매) |
| 404 | 40400 | 존재하지 않는 경로 (URL 오타 등) |
| 404 | 40401 | 존재하지 않는 경매 |
| 404 | 40402 | 존재하지 않는 주문 |
| 404 | 40403 | 존재하지 않는 차순위 제안 |
| 404 | 40404 | 등록된 자동입찰 없음 |
| 404 | 40405 | 존재하지 않는(또는 본인 소유가 아닌) 알림 |
| 404 | 40406 | 존재하지 않는 구매 목표 |
| 404 | 40407 | 존재하지 않는 상품 |
| 404 | 40408 | 존재하지 않는 분석 세션 |
| 404 | 40409 | 존재하지 않는 photoId, 또는 타인 소유의 photoId |
| 409 | 40901 | 이미 현재 최고입찰자인 사용자의 추가 입찰 시도 |
| 409 | 40902 | 아직 시작되지 않은 경매에 대한 입찰 시도 |
| 409 | 40903 | 종료/취소된 경매에 대한 입찰 시도 |
| 409 | 40904 | 최소 입찰 금액 미만 입찰 시도 |
| 409 | 40905 | 동일 Idempotency-Key에 다른 요청 내용 |
| 409 | 40906 | 자동입찰 상한가가 너무 낮음 |
| 409 | 40907 | 자동입찰 상한가가 인상되지 않음 |
| 409 | 40908 | 이미 등록된 자동입찰 존재 |
| 409 | 40909 | 동시성 충돌 (재시도 가능) |
| 409 | 40910 | 결제 기한 만료 |
| 409 | 40911 | 차순위 구매 기한 만료 |
| 409 | 40912 | 이미 처리된 차순위 제안에 재시도 |
| 409 | 40913 | 입찰 금액이 입찰 단위 배수가 아님 |
| 409 | 40914 | 결제 완료 후 낙찰 포기 시도 |
| 409 | 40915 | 취소된 주문에 결제 시도 |
| 409 | 40916 | 처리할 수 없는 구매 목표 상태 |
| 409 | 40917 | Purchase Agent가 관리 중인 경매(직접 입찰·자동입찰 수정/취소 차단) |
| 409 | 40918 | 재경매 — 이미 진행/예약 중인 경매 존재 |
| 409 | 40919 | 재경매 — 등록 한도 초과(2회) |
| 409 | 40920 | 재경매 — 실제 입찰이 있던 경매는 재경매 불가 |
| 409 | 40921 | 경매 취소 불가(이미 시작/비 SCHEDULED) |
| 409 | 40922 | 시작가 수정 불가(시작 1시간 전 이후) |
| 409 | 40923 | 이미 다른 분석에 사용된 photoId로 재요청 |
| 500 | 50001 | 서버 내부 오류(미분류 예외의 catch-all) |
| 500 | 50002 | S3 이미지 업로드 실패 |
| 500 | 50003 | AI(OpenAI/Claude) 통신 오류 |
| 500 | 50004 | 분석 작업 Queue 적재 실패 |
| 502 | 50201 | Kakao 사용자 정보 조회 실패(upstream) |

> **확인 필요.** `Order` 상태 가드(`InvalidOrderStatusException`)와 `Order.create`의
> `IllegalArgumentException`은 전용 핸들러가 없어 현재 catch-all **500/50001**로 떨어진다(정상 흐름에선
> 서비스가 먼저 상태를 검사하므로 도달하지 않는 프로그래밍 가드). 전용 4xx 코드를 둘지는 미결 —
> `docs/api/auction-audit.md` 참고.

---

# API 상세 설명

`POST /api/products/analyze`

신발 이미지 파일을 업로드하면 이미지를 S3에 저장하고, AI Vision 분석 작업을 비동기 Queue에 적재하는 API입니다.

분석 결과는 즉시 반환하지 않습니다. 요청이 정상적으로 접수되면 **202 Accepted**와 함께 `analysisId`를 반환하며, 실제 AI Vision 분석은 백그라운드 Worker에서 진행됩니다.

프론트에서는 반환받은 `analysisId`를 `taskId`로 사용하여 `GET /api/products/analyze/{taskId}` API를 폴링하고 분석 진행 상태 및 최종 결과를 조회합니다.

## Request ✔️

### Request Header

```
Content-Type: multipart/form-data
Authorization: Bearer {accessToken}
```

로그인이 필요한 API입니다. `Authorization` 헤더가 없거나 토큰이 유효하지 않으면 `401 Unauthorized`가 반환됩니다.

### Request Body

`multipart/form-data`

| 파트명 | 타입 | 설명 |
| --- | --- | --- |
| `images` | `file` (여러 개) | AI 분석을 수행할 신발 이미지 파일 목록 |

```
images: (신발 이미지 파일 1)
images: (신발 이미지 파일 2)
images: (신발 이미지 파일 3)
```

### 업로드 파일 형식·크기 (실제 구현 기준, #141)

`POST /api/products/analyze`(multipart `images`)와 `POST /api/products/photos`(`image`)에 공통으로 적용됩니다.

| 항목 | 실제 동작 |
| --- | --- |
| 파일 1개 최대 크기 | **50MB** (`spring.servlet.multipart.max-file-size`) |
| 요청 1건 전체 최대 크기 | **100MB** (`spring.servlet.multipart.max-request-size`) |
| 허용 형식 | **서버가 형식(확장자·Content-Type)을 검사하지 않습니다.** 받은 파일을 그대로 S3 원본으로 저장하고, 요청의 Content-Type을 그대로 객체에 붙입니다. 프론트는 **JPEG 또는 PNG**로 보내 주세요 |
| 분석용 사본 | 서버가 읽을 수 있는 이미지(JPEG·PNG 등)는 긴 변 기준으로 줄인 JPEG 사본을 따로 만들어 AI 분석에 씁니다. 읽지 못하는 형식(예: HEIC)은 사본 없이 원본으로 분석을 시도하며, 이때 AI 분석이 실패할 수 있습니다 |
| 장수 (multipart 분석) | 요청 자체는 장수를 검사하지 않습니다(빈 파일은 건너뜀, 0장이면 `40002`). 단, **상품 등록은 세션 이미지 3~4장일 때만 가능**하므로 3~4장을 보내야 합니다 |
| 크기 초과 시 | 크기 초과 전용 오류 코드는 없습니다. Spring의 업로드 크기 초과 예외가 공통 처리기로 가므로 `500`(`50001`)으로 응답될 것으로 보이며, **실제 응답은 확인하지 않았습니다** |

## Response ✔️

### Success ✅

### 202 Accepted - 분석 작업 접수 성공

```json
{
  "success": true,
  "data": {
    "analysisId": 1,
    "status": "QUEUED"
  },
  "error": null
}
```

`analysisId`는 생성된 AI 분석 작업의 식별자입니다.

분석 작업은 응답 이후 백그라운드에서 비동기로 진행되며, 반환받은 `analysisId`를 저장한 뒤 다음 폴링 API의 `{taskId}` 값으로 사용합니다.

```
GET /api/products/analyze/{taskId}
```

예를 들어 `analysisId`가 `1`인 경우 다음과 같이 조회합니다.

```
GET /api/products/analyze/1
```

### Failure ❌

### 400 Bad Request - 이미지 파일 없음

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40002,
    "message": "이미지 파일이 존재하지 않습니다."
  }
}
```

분석할 이미지가 요청에 포함되지 않은 경우 반환됩니다.

---

### 500 Internal Server Error - S3 이미지 업로드 실패

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 50002,
    "message": "S3 이미지 업로드 중 문제가 발생했습니다."
  }
}
```

업로드된 이미지를 S3에 저장하는 과정에서 오류가 발생한 경우 반환됩니다.

---

### 500 Internal Server Error - 분석 작업 Queue 적재 실패

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 50004,
    "message": "분석 작업을 큐에 적재하는 중 오류가 발생했습니다: ..."
  }
}
```

S3 이미지 업로드 이후 AI 분석 작업을 비동기 Queue에 등록하는 과정에서 오류가 발생한 경우 반환됩니다.

공통 응답 DTO를 적용하여 모든 응답을 `success`, `data`, `error` 형식으로 반환합니다. 성공적으로 분석 요청이 접수된 경우 `status`는 `QUEUED`이며, 이후 실제 분석 상태 및 결과는 `GET /api/products/analyze/{taskId}` API를 통해 확인합니다.

---

# API 상세 설명

`POST /api/products/photos`

사진 한 장을 칸(slot)에 넣는 즉시 S3에 저장하고 AI가 촬영 면을 판정하는 API입니다("사진 선업로드"). 판정 결과는 안내용이며, `MISMATCH`/`UNKNOWN`이어도 이후 분석 요청을 막지 않습니다.

## Request ✔️

### Request Header

```
Content-Type: multipart/form-data
Authorization: Bearer {accessToken}
```

### Request Body

`multipart/form-data`

| 파트명 | 타입 | 설명 |
| --- | --- | --- |
| `image` | `file` | 판정·업로드할 사진 1장 |
| `slot` | 텍스트 | `SIDE` \| `FRONT` \| `OUTSOLE` \| `DEFECT` 중 하나 |

`DEFECT`는 판정을 생략합니다(`check`/`detectedView`/`message`가 모두 `null`로 내려갑니다).

파일 형식·크기 제한은 위 `POST /api/products/analyze`의 "업로드 파일 형식·크기"와 같습니다(1장 최대 50MB,
서버는 형식을 검사하지 않음 — JPEG/PNG 권장).

## Response ✔️

### Success ✅

### 200 OK - 업로드 및 판정 성공

```json
{
  "success": true,
  "data": {
    "photoId": 123,
    "slot": "OUTSOLE",
    "check": "MISMATCH",
    "detectedView": "SIDE",
    "message": "밑창이 보이게 신발을 뒤집어 찍어주세요."
  },
  "error": null
}
```

- `check`: `MATCH` / `MISMATCH` / `UNKNOWN` / `null`(DEFECT)
- `detectedView`: AI가 실제로 판정한 면(`SIDE`/`FRONT`/`BACK`/`TOP`/`OUTSOLE`/`OTHER`). 판정 생략·실패 시 `null`
- `message`: 불일치 시 보여줄 안내 문구. `MATCH`/`UNKNOWN`/`DEFECT`는 `null`
- AI 판정 호출이 실패(API 오류·타임아웃·응답 파싱 실패)해도 `check`는 `UNKNOWN`으로 내려가며 업로드 자체는 그대로 성공합니다. S3 업로드나 사진 메타데이터 저장이 실패하면 이 API는 500으로 응답합니다(아래 실패 응답 참고).

### Failure ❌

이미지 파일이 없으면 `40002`, S3 업로드 실패는 `50002`, AI 통신 오류(판정 실패를 UNKNOWN으로 접지 못할 정도의 내부 오류)는 `50003`를 그대로 따릅니다(기존 `/analyze`와 동일한 에러 코드 재사용).

---

# API 상세 설명

`POST /api/products/analyze` (photoIds)

기존 multipart 요청(`images`)과 같은 경로를 쓰며, `Content-Type`으로 구분되는 또 다른 요청 형태입니다. `POST /api/products/photos`로 미리 올려 둔 사진들의 `photoId`로 분석을 시작합니다.

## Request ✔️

### Request Header

```
Content-Type: application/json
Authorization: Bearer {accessToken}
```

### Request Body

```json
{
  "photoIds": [123, 124, 125]
}
```

## Response ✔️

응답은 기존 multipart 요청과 완전히 동일합니다 - `202 Accepted` + `analysisId` + `status: "QUEUED"`. 이후 `GET /api/products/analyze/{taskId}` 폴링도 그대로 재사용합니다.

### 검증 규칙

- `photoIds`가 비어 있거나 중복된 id가 포함되면 `40007`
- 요청에 포함된 사진들의 slot이 중복되거나(같은 slot이 두 번 이상) `SIDE`/`FRONT`/`OUTSOLE`이 하나라도 빠지면 `40007`(`DEFECT`는 선택)
- 존재하지 않는 photoId, 또는 로그인한 사용자 소유가 아닌 photoId가 포함되면 `40409`(타인 소유 여부는 "없음"과 구분해 노출하지 않습니다)
- 이미 다른 분석 요청에 사용된 photoId가 포함되면 `40923`
- 같은 photoId로 동시에 분석을 요청해도 한쪽만 성공하고 나머지는 `40923`으로 거절됩니다(서버가 행 단위로 보장)
- `MISMATCH`/`UNKNOWN` 판정이었던 사진도 분석 요청을 막지 않습니다

### Failure ❌

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40923,
    "message": "이미 다른 분석에 사용된 사진이 포함되어 있습니다."
  }
}
```

---

# API 상세 설명

`GET /api/products/analyze/{taskId}`

`POST /api/products/analyze` 요청 시 반환받은 `analysisId`를 이용하여 **AI 분석 작업의 진행 상태와 Vision 분석 결과를 조회하는 API**입니다.

프론트에서는 반환받은 `analysisId`를 `{taskId}`로 사용해 주기적으로 해당 API를 호출합니다.

Vision 분석이 완료되어 `AWAITING_USER_CONFIRMATION` 상태가 되거나, 분석 과정에서 `*_FAILED` 상태가 될 때까지 폴링합니다.

## 상태(status) 흐름

```text
CREATED
  → IMAGE_UPLOADED
  → QUEUED
  → VISION_PROCESSING
  → AWAITING_USER_CONFIRMATION
  → PRICING_PROCESSING
  → COMPLETED
```

| status | 의미 |
| --- | --- |
| `CREATED` | 분석 세션이 생성된 상태 |
| `IMAGE_UPLOADED` | S3 이미지 업로드가 완료된 상태 |
| `QUEUED` | 분석 작업이 Queue에 적재된 상태 (`POST /api/products/analyze`의 202 응답 시점) |
| `VISION_PROCESSING` | AI Vision 분석이 진행 중인 상태 |
| `AWAITING_USER_CONFIRMATION` | **Vision 분석 완료.** 사용자가 분석 결과를 확인하거나 수정하는 단계 |
| `PRICING_PROCESSING` | 사용자 확인 이후 가격 계산이 진행 중인 상태 |
| `COMPLETED` | 가격 계산까지 모두 완료된 상태 |
| `CANCELLED` | 사용자가 분석 세션을 취소한 상태(`POST /api/products/analyze/{taskId}/cancel`). 아직 상품 등록에 사용되지 않은 세션만 취소할 수 있다 |
| `IMAGE_UPLOAD_FAILED` / `QUEUE_FAILED` / `VISION_FAILED` / `PRICING_FAILED` | 각 처리 단계에서 오류가 발생한 상태. `failureStage`, `failureMessage`에 오류 정보가 포함됨 |

---

## Request ✔️

### Request Header

```http
Authorization: Bearer {accessToken}
```

로그인이 필요한 API입니다. 분석을 요청한 본인의 세션만 조회할 수 있으며, 다른 사용자의 `taskId`를 조회하면 `404 Not Found`가 반환됩니다.

### Path Variable

| 이름 | 타입 | 설명 |
| --- | --- | --- |
| `taskId` | `Long` | 분석 요청 시 반환받은 `analysisId` |

---

## Response ✔️

### Success ✅

### 200 OK - 분석 진행 중 (`VISION_PROCESSING`)

AI Vision 분석이 아직 완료되지 않은 상태입니다.

분석 결과가 생성되기 전이므로 상품 정보 관련 필드는 `null`, 리스트 타입 필드는 빈 배열(`[]`)로 반환됩니다.

Vision 분석은 3단계(① 전체 형태 → ② 라벨 판독 → ③ 상태 판정)로 진행되며, 전체는 10~20초가 걸리지만 ①단계는 몇 초면 끝납니다. **단계가 끝날 때마다 `visionProgress`(끝난 단계 수)와 `preliminary`(잠정 결과)가 채워지므로, 최종 결과를 기다리는 동안 브랜드·모델을 먼저 보여줄 수 있습니다.** (#106)

- ①단계 전에는 `visionProgress`, `preliminary` 모두 `null`입니다.
- ①단계 후: `preliminary`에 `brand`·`modelName`·`color`가 채워집니다(근거가 부족한 값은 `null`).
- ②단계 후: 라벨에서 읽은 값으로 `brand`·`modelName`이 **바뀔 수 있고**, `size`가 채워집니다.
- **`preliminary`는 잠정값입니다.** 확인·수정 화면에는 `AWAITING_USER_CONFIRMATION`의 최종 필드(`brand` 등)를 써야 합니다. 최종 결과가 나오면 `visionProgress`, `preliminary`는 다시 `null`이 됩니다.
- 새 필드만 추가됐고 `status` 값은 바뀌지 않았습니다. 이 필드를 쓰지 않아도 기존 화면은 그대로 동작합니다.

```json
{
  "success": true,
  "data": {
    "analysisId": 1,
    "status": "VISION_PROCESSING",
    "imageUrls": [
      "https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/example_shoe_1.png",
      "https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/example_shoe_2.png",
      "https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/example_shoe_3.png"
    ],
    "visionProgress": {
      "completedStages": 1,
      "totalStages": 3
    },
    "preliminary": {
      "brand": "Nike",
      "modelName": "Dunk Low",
      "color": "Panda",
      "size": null
    },
    "brand": null,
    "modelName": null,
    "color": null,
    "size": null,
    "boxIncluded": null,
    "conditionDescription": null,
    "conditionGrade": null,
    "defects": [],
    "candidates": [],
    "confidence": null,
    "needsUserConfirmation": null,
    "warnings": [],
    "failureStage": null,
    "failureMessage": null
  },
  "error": null
}
```

`VISION_PROCESSING` 상태인 경우 프론트에서는 일정한 간격으로 동일한 API를 다시 호출하여 분석 완료 여부를 확인합니다.

---

### 200 OK - Vision 분석 완료 (`AWAITING_USER_CONFIRMATION`)

AI Vision 분석이 완료되어 **사용자가 결과를 확인하거나 수정할 수 있는 상태**입니다.

브랜드, 모델명, 색상, 사이즈, 상품 상태, 하자 정보 등 사용자 확인 화면을 구성하는 데 필요한 분석 결과가 반환됩니다.

Vision 분석 과정에서 충분한 근거를 확인하지 못한 항목은 `null`로 반환될 수 있으며, 사용자 확인이 필요한 내용은 `warnings`와 `needsUserConfirmation`을 통해 전달합니다.

```json
{
  "success": true,
  "data": {
    "analysisId": 1,
    "status": "AWAITING_USER_CONFIRMATION",
    "imageUrls": [
      "https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/example_shoe_1.png",
      "https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/example_shoe_2.png",
      "https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/example_shoe_3.png"
    ],
    "visionProgress": null,
    "preliminary": null,
    "brand": "Nike",
    "modelName": "Dunk Low",
    "color": "Panda",
    "size": 270,
    "boxIncluded": true,
    "conditionDescription": "토박스에 얕은 주름이 있고 아웃솔에 사용감이 보이는 중고 상태입니다.",
    "conditionGrade": "B",
    "defects": [
      {
        "type": "crease",
        "location": "toe_box",
        "severity": "minor",
        "description": "토박스에 얕은 주름이 있습니다."
      },
      {
        "type": "sole_wear",
        "location": "outsole",
        "severity": "moderate",
        "description": "아웃솔 뒤꿈치 부분에 마모가 있습니다."
      }
    ],
    "candidates": [
      {
        "brand": "Nike",
        "modelName": "Dunk Low",
        "color": "Panda",
        "confidence": 0.92
      }
    ],
    "confidence": 0.92,
    "needsUserConfirmation": true,
    "warnings": [
      "사이즈 라벨이 흐릿하게 보여 사이즈는 사용자 확인이 필요합니다."
    ],
    "failureStage": null,
    "failureMessage": null
  },
  "error": null
}
```

`AWAITING_USER_CONFIRMATION` 상태가 반환되면 Vision 분석 단계의 폴링을 중단하고, 반환된 분석 결과를 사용자 확인/수정 화면에 표시합니다.

**`warnings`·`needsUserConfirmation`·`null` 필드의 후속 처리(현재 동작).** 서버는 이 값들로 이후 요청을
막지 않습니다 — 경고가 있어도 가격 계산·등록이 가능합니다. 근거 부족으로 `null`인 필드(브랜드·모델명·
색상·사이즈)는 가격 계산과 등록에서 필수이므로 사용자가 채워야 하고, 비우면 `40001`입니다. `conditionGrade =
UNKNOWN`은 그대로 보내도 되고 사용자가 고른 등급으로 바꿔 보내도 됩니다("상태 등급 정리" 참고). 사진 칸 판정
(`POST /api/products/photos`의 `MISMATCH`/`UNKNOWN`)도 분석 요청을 막지 않는 안내용입니다.

---

### 응답 필드 설명

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `analysisId` | `Long` | 분석 세션 ID |
| `status` | `String` | 현재 분석 진행 상태 |
| `imageUrls` | `String[]` | 분석 세션 이미지의 **표시용 서명 URL**(조회마다 새로 서명, 6시간 유효). 상품 등록 요청에 다시 보낼 필요 없음 — 등록 시 서버가 세션의 원본 이미지를 직접 사용 |
| `visionProgress` | 객체 | `VISION_PROCESSING` 중 끝난 단계 수 (`completedStages`, `totalStages`). 그 밖의 상태이거나 ①단계 전이면 `null` |
| `preliminary` | 객체 | `VISION_PROCESSING` 중 잠정 결과 (`brand`, `modelName`, `color`, `size`). 최종 결과가 아니며 바뀔 수 있음. 그 밖의 상태이거나 ①단계 전이면 `null` |
| `brand` | `String` | AI가 인식한 브랜드. 근거가 부족하면 `null` |
| `modelName` | `String` | AI가 인식한 모델명. 근거가 부족하면 `null` |
| `color` | `String` | AI가 인식한 컬러웨이. 근거가 부족하면 `null` |
| `size` | `Integer` | 한국 신발 사이즈(mm 단위, 예: `270`) |
| `boxIncluded` | `Boolean` | 박스 포함 여부 |
| `conditionDescription` | `String` | 상품 상태에 대한 AI 분석 요약. 판매자가 고친 값을 상품 등록 요청의 `conditionDescription`에 담으면 그 값이, 생략하면 이 값이 상품에 저장됨(판매자 설명 `sellerDescription`과 별도 저장) |
| `conditionGrade` | `String` | 상품 상태 등급: `DS` / `A` / `B` / `C` / `UNKNOWN`. `UNKNOWN`은 AI가 등급을 판단하지 못한 경우로, 사용자가 직접 선택해야 합니다 |
| `defects` | 객체 배열 | 사진에서 발견된 하자 목록 (`type`, `location`, `severity`, `description`) |
| `candidates` | 객체 배열 | 모델 인식 후보 목록 (`brand`, `modelName`, `color`, `confidence`) |
| `confidence` | `Double` | 모델 인식 신뢰도 (`0.0 ~ 1.0`) |
| `needsUserConfirmation` | `Boolean` | 사용자 확인이 필요한 항목이 존재하는지 여부 |
| `warnings` | `String[]` | 사용자에게 추가 확인이 필요한 항목에 대한 안내 문구 |
| `failureStage` | `String` | 실패 단계: `IMAGE_UPLOAD` / `QUEUE` / `VISION` / `PRICING`. 정상 처리 시 `null` |
| `failureMessage` | `String` | 실패 상세 메시지. 정상 처리 시 `null` |

### `defects` 객체

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `type` | `String` | 하자 유형 (예: `crease`, `stain`, `sole_wear`, `scuff`) |
| `location` | `String` | 하자가 발견된 위치 (예: `toe_box`, `outsole`, `upper`) |
| `severity` | `String` | 하자 심각도 (`minor` / `moderate` / `severe`) |
| `description` | `String` | 하자에 대한 상세 설명 |

### `candidates` 객체

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `brand` | `String` | 후보 상품 브랜드 |
| `modelName` | `String` | 후보 상품 모델명 |
| `color` | `String` | 후보 상품 컬러웨이 |
| `confidence` | `Double` | 해당 후보에 대한 AI 인식 신뢰도 |

---

### 200 OK - 분석 실패 (`VISION_FAILED`)

비동기 분석 작업 자체가 실패한 경우에도 **조회 API 요청은 정상적으로 처리되었기 때문에 HTTP Status는 200 OK**로 반환됩니다.

분석 작업의 실패 여부는 `status`, `failureStage`, `failureMessage`를 통해 확인합니다.

```json
{
  "success": true,
  "data": {
    "analysisId": 1,
    "status": "VISION_FAILED",
    "imageUrls": [
      "https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/example_shoe_1.png"
    ],
    "visionProgress": null,
    "preliminary": null,
    "brand": null,
    "modelName": null,
    "color": null,
    "size": null,
    "boxIncluded": null,
    "conditionDescription": null,
    "conditionGrade": null,
    "defects": [],
    "candidates": [],
    "confidence": null,
    "needsUserConfirmation": null,
    "warnings": [],
    "failureStage": "VISION",
    "failureMessage": "OpenAI Vision API 호출 중 오류가 발생했습니다."
  },
  "error": null
}
```

`IMAGE_UPLOAD_FAILED`, `QUEUE_FAILED`, `VISION_FAILED`, `PRICING_FAILED` 상태가 반환되면 해당 분석 작업은 실패한 것으로 판단하고 추가 폴링을 중단합니다.

---

### Failure ❌

### 404 Not Found - 존재하지 않는 분석 세션

요청한 `taskId`에 해당하는 분석 세션이 존재하지 않는 경우 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40408,
    "message": "분석 세션을 찾을 수 없습니다. analysisId: 999"
  }
}
```

공통 응답 DTO를 적용하여 모든 응답을 `success`, `data`, `error` 형식으로 반환합니다.

비동기 분석 작업의 실패와 API 요청 자체의 실패는 다음과 같이 구분합니다.

- **분석 작업 실패**: `200 OK` + `success: true` + `status: *_FAILED`
- **API 요청 실패**: `4xx` + `success: false` + `error` 정보 반환

---

# API 상세 설명

`POST /api/products/calculate-price`

사용자가 AI Vision 분석 결과를 확인하거나 수정한 뒤, **최종 확정한 상품 정보**를 기준으로 추천 판매가를 계산하는 API입니다.

### 추천가 산정 방식 (우선순위)

우리 서비스는 중고 경매이므로, **같은 모델의 중고 실거래 시세를 1순위**로 씁니다.

| 순위 | 근거 | 계산 |
| --- | --- | --- |
| 1 | **당근마켓·후르츠패밀리 중고 실거래** (모델별 10건 이상, 현재 45개 모델·5,742건) | 실거래 중앙값 × 상태비율 × 구성품 반영률. 권장 범위는 실거래 IQR(25~75% 구간) |
| 2 | KREAM 새제품 시세 (1순위 매칭이 없을 때) | KREAM 평균 × 상태계수 × 구성품 반영률. 권장 범위는 ±5% |
| — | 둘 다 없으면 | `0원 + "시세 정보 없음"` |

2순위에서 **eBay는 기준 시세에 섞지 않습니다** (실측 근거: 당근 실거래 예측 오차가 KREAM 단독 25% vs 혼합 0.7/0.3 38% vs eBay 단독 155% — eBay는 해외 호가라 국내 실거래보다 중앙값 기준 약 2.5배 높습니다). eBay 매칭은 `ebayAveragePrice`/`ebayMatches`로 계속 내려가되 참고 정보이며, KREAM 매칭이 없으면 eBay만으로 가격을 만들지 않고 "시세 정보 없음"으로 응답합니다.

어느 경로로 계산됐는지는 `reason` 문구로 구분됩니다. 1순위 경로에서는 `kreamAveragePrice`/`ebayAveragePrice`가 `0`, `kreamMatches`/`ebayMatches`가 빈 배열로 내려갑니다 — 그 근거를 쓰지 않았다는 뜻이며 오류가 아닙니다.

**색상 반영(#93)**: 1순위 안에서 **같은 색상 계열의 시세가 서 있으면 그것을 우선**합니다.
색상 표기는 계열로 정규화해 비교하므로 `Wolf Gray`/`그레이`/`회색`이 전부 grey 버킷에
붙습니다(표기 차이로 매칭이 깨지지 않음). 색상 버킷은 표본 10건 이상일 때만 존재하며
(현재 84개), 없으면 KREAM 색상 프리미엄(모델 평균 대비 배율, 체결 5건+)으로 모델 시세를 보정하고, 그것도 없으면 모델 전체 시세로 폴백합니다 — 색상 시세는 통계적으로 설 때만 쓰고,
정확도가 나빠지는 경로는 없습니다. 어느 쪽을 썼는지는 `reason` 첫 문장이 밝힙니다
("...중고 매물 중 같은 색상 계열(blue) 31건을 근거로...").

1순위 경로의 상태 반영은 "전체 매물 대비 비율"입니다. 시세 중앙값이 상태 불문 전체 매물에서 나온 값이라, 분모도 같은 모집단(전체 매물 계수 `ALL`)을 씁니다. 예를 들어 `DS`(새상품)는 실측 계수 비율(약 1.6배)만큼 올라가고 `B`(약 0.7배)는 내려갑니다. `UNKNOWN`은 비율 1.0으로 시세 중앙값이 그대로 추천가가 됩니다.

상태 등급별 계수의 출처(원본은 `condition_rates.csv` — 재측정 시 아래 수치는 갱신됩니다. 2026-09 기준):

| 등급 | 계수 | 출처 |
| --- | --- | --- |
| `DS` | 0.701 | 실측 (당근 202건) |
| `S` | 0.404 | 실측 (매물 설명 재분류, 105건) |
| `A` | 0.369 | 실측 (매물 설명 재분류, 628건) |
| `B` | 0.266 | 실측 (매물 설명 재분류, 300건) |
| `C` | 0.20 | 기본값 (부정어·정도 정제 후 실측 0.23·28건 — 서열은 맞으나 표본 부족. 실측이 기본값을 근사 검증) |
| `ALL`(기준선) | 0.430 | 실측 (전체 매물 1,145건) |

실측/기본값 여부는 `reason` 문구 끝의 괄호로 응답마다 공개됩니다. 일부 모델(에어포스1, 가젤 등)은 모델 전용 실측 계수가 우선 적용됩니다.

`analysisId`를 통해 기존 AI 분석 세션과 연결되며, 가격 계산이 시작되면 분석 세션의 상태가 다음과 같이 변경됩니다.

```text
AWAITING_USER_CONFIRMATION
  → PRICING_PROCESSING
  → COMPLETED
```

가격 계산 요청은 Vision 분석이 완료되어 `AWAITING_USER_CONFIRMATION` 상태인 세션에서만 수행할 수 있습니다.

또한 **가격 계산은 분석 세션당 1회만 요청할 수 있습니다.** 계산이 완료되면 세션 상태가 `COMPLETED`로 변경되므로, 같은 `analysisId`로 다시 요청하면 `400 Bad Request`(`40003`)가 반환됩니다. 같은 입력으로 다시 보내도 멱등 처리되지 않고 똑같이 `40003`입니다(이전 계산 결과를 다시 돌려주는 기능은 없습니다).

**가격 계산 후 입력값을 바꾸려는 경우의 현재 제약.**

- 같은 `analysisId`로 재계산할 수 없습니다(`40003`). 가격 계산이 실패해 `PRICING_FAILED`가 된 세션도 다시 계산할 수 없습니다.
- 새로 계산하려면 새 분석 세션이 필요합니다. 그런데 **사진 ID 방식에서는 한 번 분석에 쓰인 `photoId`를 다른 분석에 다시 쓸 수 없으므로**(`409` `40923`), 사진을 `POST /api/products/photos`로 다시 올린 뒤 새 `photoIds`로 분석을 요청해야 합니다. multipart 방식도 이미지를 다시 보내야 합니다.
- 다만 `POST /api/products`는 가격 계산 결과와 요청 값이 같은지 대조하지 않습니다. 가격 계산 후 브랜드·등급 등을 화면에서 고쳐 등록 요청에 보내면 고친 값이 그대로 저장되고, `recommendedPrice`는 재계산되지 않습니다(재계산 허용 여부는 별도 결정 사항).

## 요청 필드 값 범위

| 필드 | 허용 값 |
| --- | --- |
| `conditionGrade` | `DS`(새상품) / `S` / `A` / `B` / `C` / `UNKNOWN`(판정 불가) |
| `componentStatus` | `FULL`(모두 포함) / `PARTIAL`(일부 포함) / `NONE`(없음) |

`conditionGrade`에 대해 두 가지를 유의해 주세요.

- **`UNKNOWN`을 그대로 보낼 수 있습니다.** Vision이 사진에서 상태를 판정할 근거를 찾지
  못하면 `UNKNOWN`을 반환합니다. 사용자가 고치지 않고 그대로 보내도 가격 계산은
  정상 동작하며, 기본 반영률이 적용됩니다.
- **`S`는 Vision이 반환하지 않습니다.** Vision의 등급은 `DS` / `A` / `B` / `C` / `UNKNOWN`
  다섯 가지입니다. `S`는 사용자가 직접 등급을 선택하는 화면에서만 들어옵니다.

### 상태 등급 정리 (API별 실제 범위·검증, #141)

등급 정책을 새로 정한 것이 아니라 **현재 구현 그대로**를 정리한 것입니다.

| API | 필드 | 실제로 오가는 값 | 서버 검증 |
| --- | --- | --- | --- |
| `GET /api/products/analyze/{taskId}` (분석 결과) | `conditionGrade` | `DS` / `A` / `B` / `C` / `UNKNOWN` (enum). 근거가 부족하면 서버가 `UNKNOWN`으로 낮춤. 분석 전/실패 시 `null` | 응답 값이므로 해당 없음 |
| `POST /api/products/calculate-price` | `conditionGrade` | 문서상 허용 값 `DS` / `S` / `A` / `B` / `C` / `UNKNOWN` | **필수(`@NotBlank`)만 검사.** 값 범위는 검사하지 않는다. 앞뒤 공백 제거·대문자 변환 후 계산하며, 정의되지 않은 값(예: `X`)은 400이 아니라 "기타 상태"로 기본 반영률이 적용된다 |
| `POST /api/products` (상품 등록) | `conditionGrade` | 클라이언트가 보낸 문자열 | **필수(`@NotBlank`)만 검사.** 값 범위·대소문자 변환 없이 그대로 저장한다. 가격 계산 때 보낸 등급과 같은지도 대조하지 않는다 |
| `GET /api/auctions/{id}` | `product.grade` | 등록 때 저장된 문자열 그대로 | 변환 없음 — `DS`/`S`/`UNKNOWN`도 반환될 수 있다(A/B/C로 제한되지 않음) |
| `GET /api/auctions/{id}/similar` | `items[].grade` | 위와 동일 | 변환 없음 |

**알려진 제약(정책 미정).** 등록 API가 등급 값 범위를 검사하지 않으므로, 클라이언트가 문서상 허용 값
(`DS`/`S`/`A`/`B`/`C`/`UNKNOWN`) 밖의 값을 보내면 그대로 저장·노출됩니다. 허용 등급 통일(S 유지 여부,
UNKNOWN 등록 허용 여부)은 별도 결정 사항입니다.

**분석 결과가 `UNKNOWN`일 때 사용자가 고른 등급 전달 방법.** 별도 필드는 없습니다. 사용자가 화면에서
고른 등급을 `POST /api/products/calculate-price`와 `POST /api/products`의 `conditionGrade`에 **같은 값으로**
넣어 보냅니다. 서버는 분석 결과의 `UNKNOWN`을 등록 시 강제하거나 덮어쓰지 않습니다.

---

## Request ✔️

### Request Header

```http
Content-Type: application/json
Authorization: Bearer {accessToken}
```

### Request Body

```json
{
  "analysisId": 1,
  "brand": "Nike",
  "modelName": "Dunk Low",
  "color": "Panda",
  "size": 270,
  "conditionGrade": "C",
  "componentStatus": "FULL"
}
```

### Request Body 필드 설명

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `analysisId` | `Long` | AI 분석 요청 시 생성된 분석 세션 ID |
| `brand` | `String` | 사용자가 최종 확인한 브랜드 |
| `modelName` | `String` | 사용자가 최종 확인한 모델명 |
| `color` | `String` | 사용자가 최종 확인한 컬러웨이 |
| `size` | `Integer` | 한국 신발 사이즈(mm 단위, 예: `270`) |
| `conditionGrade` | `String` | 최종 상품 상태 등급 |
| `componentStatus` | `String` | 박스 등 구성품 포함 상태 |

---

## Response ✔️

### Success ✅

### 200 OK - 중고 실거래 시세 기반 (1순위)

```json
{
  "success": true,
  "data": {
    "recommendedPrice": 40000,
    "baseMarketPrice": 40000,
    "kreamAveragePrice": 0,
    "ebayAveragePrice": 0,
    "minRecommendedPrice": 25000,
    "maxRecommendedPrice": 81000,
    "priceRange": "25,000원 ~ 81,000원",
    "reason": "당근마켓·후르츠패밀리에 올라온 Dunk Low 중고 매물 116건을 근거로 계산했습니다. 실거래가 중앙값은 40,000원이고, 매물의 절반이 25,000원 ~ 81,000원 사이에 있습니다. 상품 상태 UNKNOWN(기타 상태)는 전체 매물 시세 대비 100% 수준으로 반영했습니다. 구성품이 모두 포함되어 있어 100% 반영률을 적용했습니다. 이를 바탕으로 최종 추천가는 40,000원이며, 판매 권장 범위는 실거래 분포를 따라 25,000원 ~ 81,000원입니다.",
    "kreamMatches": [],
    "ebayMatches": []
  },
  "error": null
}
```

권장 범위가 ±5%보다 넓게 보일 수 있는데, 이는 **실제 거래 분포(IQR)**이기 때문입니다. 임의로 좁힌 범위보다 정직한 값입니다.

---

### 200 OK - KREAM/eBay 시세 기반 (2순위)

1순위 중고 시세에 매칭이 없을 때, KREAM에서 조건에 맞는 새제품 유사 거래를 조회한 뒤 상품 상태·구성품 상태를 반영하여 추천 판매가를 계산합니다. eBay 매칭은 참고 정보로만 함께 내려갑니다.

```json
{
  "success": true,
  "data": {
    "recommendedPrice": 23000,
    "baseMarketPrice": 114000,
    "kreamAveragePrice": 114000,
    "ebayAveragePrice": 137280,
    "minRecommendedPrice": 22000,
    "maxRecommendedPrice": 24000,
    "priceRange": "22,000원 ~ 24,000원",
    "reason": "KREAM 새제품 유사 거래 1건의 평균가 114,000원을 기준 시세로 사용했습니다. eBay 해외 매물 50건(평균 137,280원)은 국내 실거래와 차이가 커 참고로만 표시합니다. 상품 상태는 C(하자 있음)로 판단하여 20% 반영률을 적용했습니다. (실거래 표본이 부족해 기본값을 사용했습니다) 구성품이 모두 포함되어 있어 100% 반영률을 적용했습니다. 이를 바탕으로 최종 추천가는 23,000원으로 산정했으며, 판매 권장 범위는 22,000원 ~ 24,000원입니다. 추천가는 KREAM 평균가 대비 약 80% 낮은 수준입니다.",
    "kreamMatches": [
      {
        "source": "KREAM",
        "brand": "Nike",
        "modelName": "Dunk Low",
        "color": "Panda",
        "size": 270,
        "conditionGrade": "DS",
        "componentStatus": "NONE",
        "price": 114000,
        "url": "https://kream.co.kr/products/548447"
      }
    ],
    "ebayMatches": [
      {
        "source": "EBAY",
        "brand": "Nike",
        "modelName": "Dunk Low",
        "color": "Black White Panda",
        "size": 270,
        "conditionGrade": "B",
        "componentStatus": "NONE",
        "price": 27000,
        "url": "https://www.ebay.com/..."
      }
    ]
  },
  "error": null
}
```

가격 계산이 정상적으로 완료되면 해당 분석 세션의 상태는 `COMPLETED`로 변경됩니다.

---

### 응답 필드 설명

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `recommendedPrice` | `Integer` | 최종 추천 판매가 |
| `baseMarketPrice` | `Integer` | 기준 시세. 1순위면 중고 실거래 중앙값, 2순위면 KREAM 평균가 |
| `kreamAveragePrice` | `Integer` | KREAM 유사 상품 평균가. 1순위 경로에서는 `0` |
| `ebayAveragePrice` | `Integer` | eBay 유사 매물 평균가. **참고용이며 추천가 계산에는 쓰지 않음**. 1순위 경로에서는 `0` |
| `minRecommendedPrice` | `Integer` | 추천 판매가 범위의 최솟값 |
| `maxRecommendedPrice` | `Integer` | 추천 판매가 범위의 최댓값 |
| `priceRange` | `String` | 사용자에게 표시할 추천 가격 범위 |
| `reason` | `String` | 시세 및 상태 반영 기준을 포함한 추천가 산정 설명 |
| `kreamMatches` | 객체 배열 | 추천가 계산에 사용된 KREAM 유사 거래 목록. 1순위 경로에서는 빈 배열 |
| `ebayMatches` | 객체 배열 | eBay 유사 매물 목록(참고용). 1순위 경로에서는 빈 배열 |

### 시세 Match 객체

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `source` | `String` | 시세 데이터 출처 (`KREAM` / `EBAY`) |
| `brand` | `String` | 유사 거래 상품 브랜드 |
| `modelName` | `String` | 유사 거래 상품 모델명 |
| `color` | `String` | 유사 거래 상품 컬러웨이 |
| `size` | `Integer` | 상품 사이즈 |
| `conditionGrade` | `String` | 해당 거래 상품 상태 등급 |
| `componentStatus` | `String` | 해당 거래 상품 구성품 상태 |
| `price` | `Integer` | 거래 가격 |
| `url` | `String` | 해당 상품 또는 거래 페이지 URL |

---

### 200 OK - 시세 데이터 없음

입력한 상품 조건과 일치하거나 추천가 산정에 사용할 수 있는 KREAM/eBay 유사 거래 데이터를 찾지 못한 경우입니다.

API 요청 자체는 정상적으로 처리되었으므로 `200 OK`가 반환되지만, 추천 가격 관련 값은 `0`으로 반환됩니다.

```json
{
  "success": true,
  "data": {
    "recommendedPrice": 0,
    "baseMarketPrice": 0,
    "kreamAveragePrice": 0,
    "ebayAveragePrice": 0,
    "minRecommendedPrice": 0,
    "maxRecommendedPrice": 0,
    "priceRange": "시세 정보 없음",
    "reason": "입력한 브랜드, 모델명, 색상, 사이즈와 일치하는 시세 데이터를 찾지 못했습니다. 추천 가격 산정을 위해서는 유사 거래 데이터가 추가로 필요합니다.",
    "kreamMatches": [],
    "ebayMatches": []
  },
  "error": null
}
```

이 경우에도 가격 계산 요청 자체는 완료된 것으로 처리되어 분석 세션 상태는 `COMPLETED`로 변경됩니다.

**추천가 0원과 상품 등록의 관계.** 추천가가 `0`이어도 같은 세션으로 `POST /api/products`를 호출할 수
있습니다(`recommendedPrice: 0`은 `@NotNull`만 검사하므로 허용). 단, 경매 시작가 `auctionStartPrice`는
`@Positive`(0보다 커야 함)라 사용자가 직접 1원 이상을 입력해야 하며, 0이나 음수를 보내면 `400`
`40001`("경매 시작가는 0보다 커야 합니다.")입니다. 추천가를 시작가로 자동 채우는 화면이라면 0원일 때
입력을 요구해야 합니다.

국내 시세(중고 실거래·KREAM)는 없고 eBay 매물만 있는 경우에도 추천가는 `0`이지만, `ebayAveragePrice`와 `ebayMatches`는 참고용으로 채워져 내려갑니다. 이때 `reason`은 다음과 같습니다.

```text
국내 시세(중고 실거래·KREAM)를 찾지 못했습니다. eBay 해외 매물은 확인되지만 국내 실거래와 차이가 커 추천가 근거로 쓰지 않았으며, 참고로만 표시합니다.
```

---

### Failure ❌

### 400 Bad Request - 필수값 누락

가격 계산에 필요한 필수 요청값이 누락된 경우 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40001,
    "message": "브랜드는 필수입니다."
  }
}
```

필수값별 오류 메시지는 다음과 같습니다.

- `분석 세션 ID는 필수입니다.`
- `브랜드는 필수입니다.`
- `모델명은 필수입니다.`
- `컬러웨이는 필수입니다.`
- `한국 사이즈는 필수입니다.`
- `상품 상태 등급은 필수입니다.`
- `구성품 상태는 필수입니다.`

---

### 400 Bad Request - 가격 계산이 불가능한 분석 상태

해당 분석 세션의 현재 상태가 `AWAITING_USER_CONFIRMATION`이 아닌 경우 반환됩니다.

Vision 분석이 아직 진행 중인 경우뿐만 아니라, 이미 가격 계산이 완료되어 `COMPLETED` 상태가 된 세션으로 다시 가격 계산을 요청하는 경우도 포함됩니다.

예를 들어 현재 세션이 `VISION_PROCESSING` 상태인 경우 다음과 같이 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40003,
    "message": "가격 계산을 요청할 수 없는 분석 상태입니다. 현재 상태: VISION_PROCESSING"
  }
}
```

이미 가격 계산이 완료된 세션으로 다시 요청하는 경우에는 현재 상태가 `COMPLETED`로 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40003,
    "message": "가격 계산을 요청할 수 없는 분석 상태입니다. 현재 상태: COMPLETED"
  }
}
```

가격 계산은 `AWAITING_USER_CONFIRMATION` 상태에서만 **분석 세션당 1회** 요청할 수 있습니다.

가격 계산이 완료된 이후 사용자가 상품 상태 등급이나 구성품 상태 등을 변경하여 다시 가격을 계산하려는 경우에는 기존 `analysisId`를 재사용할 수 없으며, `POST /api/products/analyze`부터 새로운 분석 세션을 생성해야 합니다.

---

### 401 Unauthorized - 로그인하지 않은 요청

`Authorization` 헤더가 없거나 토큰이 유효하지 않은 경우 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40101,
    "message": "인증이 필요합니다."
  }
}
```

---

### 404 Not Found - 존재하지 않는 분석 세션

요청한 `analysisId`에 해당하는 분석 세션이 존재하지 않거나, 다른 사용자가 만든 분석 세션인 경우 반환됩니다. 다른 사용자의 세션 여부를 노출하지 않기 위해 두 경우를 같은 응답으로 처리합니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40408,
    "message": "분석 세션을 찾을 수 없습니다. analysisId: 999"
  }
}
```

공통 응답 DTO를 적용하여 모든 응답을 `success`, `data`, `error` 형식으로 반환합니다.

---

### 500 Internal Server Error - 가격 계산 중 서버 오류

가격 계산 도중 예상하지 못한 오류가 발생한 경우 반환됩니다. 분석 세션은 `PRICING_FAILED` 상태가 되며, 같은 `analysisId`로 다시 계산할 수 없습니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 50001,
    "message": "서버 내부 오류: ..."
  }
}
```

---

# API 상세 설명

`POST /api/products/analyze/{taskId}/cancel`

진행 중이거나 사용자 확인 대기 중인 **분석 세션을 취소**하는 API입니다. 로그인 필수이며 본인 세션만
취소할 수 있습니다(타인 `taskId`는 `404`). 이미 상품 등록에 사용된 세션은 취소할 수 없습니다. 같은 요청을
다시 보내도 안전합니다(이미 `CANCELLED`면 그대로 반환).

취소 가능 여부는 **분석 상태가 아니라 "상품 등록에 쓰였는지"로만** 판단합니다. `QUEUED`, `VISION_PROCESSING`,
`AWAITING_USER_CONFIRMATION`, `PRICING_PROCESSING`, `COMPLETED`, `*_FAILED` 모두 등록 전이면 취소됩니다.
취소하면 분석·가격 결과가 지워지고, 이후 이 세션으로 가격 계산(`40003`)이나 상품 등록(`40003`)을 할 수
없습니다. 취소와 등록이 동시에 들어오면 세션 행 잠금으로 먼저 커밋된 쪽만 성공합니다.

> 취소해도 사진 ID 방식에서 이 분석에 쓰인 `photoId`는 "사용됨" 상태로 남습니다. 다시 분석하려면 사진을
> 새로 올려야 합니다(`40923`).

### Request

```http
POST /api/products/analyze/1/cancel
Authorization: Bearer {accessToken}
```

요청 본문 없음.

### Response — 200 OK

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `id` | `Long` | 분석 세션 ID |
| `status` | `String` | `CANCELLED` 고정 |

```json
{ "success": true, "data": { "id": 1, "status": "CANCELLED" }, "error": null }
```

이미 `CANCELLED`인 세션을 다시 취소해도 **같은 200 응답**이 반환됩니다(멱등). 등록에 쓰인 세션인지는
멱등 판정보다 늦게 보므로, 취소된 세션은 항상 200입니다.

### Failure

| HTTP | code | 발생 조건 |
| --- | --- | --- |
| `404` | `40408` | `taskId`에 해당하는 세션이 없거나, 다른 사용자의 세션(둘을 구분하지 않음) |
| `400` | `40003` | 이미 `POST /api/products`에 사용된(등록 확정된) 세션 |
| `401` | `40101` | 미인증 |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40408,
    "message": "분석 세션을 찾을 수 없습니다. analysisId: 999"
  }
}
```

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40003,
    "message": "이미 상품 등록에 사용된 분석 세션은 취소할 수 없습니다. 현재 상태: COMPLETED"
  }
}
```

---

# API 상세 설명

`POST /api/products`

AI 분석·가격 계산을 마친 세션(`analysisId`)을 확정해 **상품과 첫 경매를 한 번에 등록**하는 API입니다.
로그인 필수. 한 분석 세션은 상품 등록에 **한 번만** 사용됩니다(등록 시 세션이 봉인됨). 입찰 단위
(`bidIncrement`)는 요청으로 받지 않고 서버가 고정합니다.

> **성공 응답은 `200 OK`입니다** (리소스를 생성하지만 현재 구현은 `201`이 아닌 `200`을 반환합니다 —
> 계약상 `201`이 바람직한지는 `docs/api/auction-audit.md`의 차이 항목 참고).

### 상품 이미지 결정 규칙 (#141)

상품 이미지는 **`analysisId`로 연결된 분석 세션에 서버가 저장해 둔 이미지로만** 정합니다. 요청
`imageUrls`는 어떤 경우에도 저장되지 않습니다.

| 분석 세션 상태 | 저장되는 상품 이미지 | 결과 |
| --- | --- | --- |
| 세션 이미지 3~4장 (사진 ID 분석·multipart 분석으로 만든 정상 세션) | 세션 이미지(사진 ID 방식이면 그 분석에 사용 처리된 `photoId`들의 원본, `SIDE`→`FRONT`→`OUTSOLE`→`DEFECT` 순. multipart면 업로드 순) | 등록 성공. 요청 `imageUrls`는 생략 가능하고, 보내도 무시됩니다(형식 검증 `@Size`만 적용) |
| 세션 이미지 0장 (이미지 업로드 전 `CREATED` 세션, 업로드 실패 `IMAGE_UPLOAD_FAILED` 세션) 또는 3장 미만·4장 초과 | — | **`400` `40002`로 거절.** 요청에 `imageUrls`를 보내도 대신 쓰지 않습니다 |

> **#141 변경.** 이전에는 세션 이미지가 없을 때 요청 `imageUrls`(서명 제거)를 대신 저장했지만 제거했습니다.
> 서명을 뗀 URL이라도 그 객체가 이 판매자의 사진인지 확인할 수 없기 때문입니다. 정상 분석 경로(사진 ID,
> multipart)는 둘 다 큐 적재 전에 세션 이미지를 저장하므로 이 변경으로 막히는 정상 흐름은 없습니다.

- 세션 이미지는 서명 없는 S3 원본 경로(`https://<bucket>.s3.<region>.amazonaws.com/<key>`)로 저장돼
  있어 만료되지 않습니다. 분석 조회(`GET /api/products/analyze/{taskId}`)가 주는 **6시간짜리 서명 URL은
  화면 표시용**이며, 이를 등록 요청에 다시 보낼 필요가 없습니다. 보내더라도 저장되지 않습니다.
- 상품 이미지는 조회 API(상품 등록 응답·경매 상세 등)에서 요청마다 새로 24시간짜리 서명 URL로 바뀌어
  나갑니다. 상품 등록 응답의 `imageUrls`도 서명 URL입니다.
- 실제로 저장될 이미지가 3장 미만이거나 4장 초과면 등록이 거절됩니다(`400` `40002`). 이때 상품·경매는
  저장되지 않고 세션의 등록 확정도 롤백됩니다.
- 사진 소유권·중복·필수 칸 검증은 분석 요청(`POST /api/products/analyze`, photoIds) 시점에 이미 끝납니다.
  등록 시에는 **세션 소유권**(본인 세션이 아니면 `40408`)만 다시 확인하므로, 다른 사용자의 사진으로 상품을
  만들 수 없습니다.

### 사진 ID 방식 전체 흐름 예시

```http
POST /api/products/photos            (multipart: image, slot=SIDE)    → { "photoId": 123, ... }
POST /api/products/photos            (multipart: image, slot=FRONT)   → { "photoId": 124, ... }
POST /api/products/photos            (multipart: image, slot=OUTSOLE) → { "photoId": 125, ... }

POST /api/products/analyze           { "photoIds": [123, 124, 125] }  → 202 { "analysisId": 1, "status": "QUEUED" }
GET  /api/products/analyze/1         (status가 AWAITING_USER_CONFIRMATION이 될 때까지 폴링)
POST /api/products/calculate-price   { "analysisId": 1, "brand": "Nike", ..., "conditionGrade": "B", "componentStatus": "FULL" }
POST /api/products                   (아래 본문 — imageUrls 없음)
```

```json
{
  "analysisId": 1,
  "brand": "Nike",
  "modelName": "Dunk Low",
  "color": "Panda",
  "size": 270,
  "conditionGrade": "B",
  "componentStatus": "FULL",
  "recommendedPrice": 40000,
  "baseMarketPrice": 40000,
  "priceRange": "25,000원 ~ 81,000원",
  "sellingPrice": 45000,
  "reason": "당근마켓·후르츠패밀리에 올라온 ...",
  "sellerDescription": "실착 3회, 박스 포함입니다.",
  "conditionDescription": "토박스에 얕은 주름이 있습니다.",
  "auctionStartPrice": 30000,
  "auctionStartAt": "2026-10-27T20:00:00+09:00",
  "auctionEndAt": "2026-10-27T22:00:00+09:00"
}
```

**기존 URL(multipart) 방식과의 차이.** multipart `POST /api/products/analyze`(`images`)로 만든 세션도
서버가 업로드한 원본 URL을 세션에 저장하므로 **같은 규칙**이 적용됩니다 — `imageUrls`를 생략할 수 있고,
보내도 세션 이미지가 저장됩니다. 기존처럼 `imageUrls`를 보내는 클라이언트도 그대로 동작합니다(값만 무시됨).

### 상태 설명(`conditionDescription`) 규칙 (#141)

상품에는 두 가지 설명이 **서로 다른 컬럼**에 저장됩니다.

| 요청 필드 | 저장 위치 | 경매 상세(auction-spec §1) 응답 |
| --- | --- | --- |
| `sellerDescription` | `products.description` (판매자 설명) | `description` |
| `conditionDescription` | `products.condition_description` (상품 상태 설명) | `product.conditionDescription` |

- `conditionDescription`을 보내면(공백 아닌 문자열) **그 값을 그대로** 저장합니다. 판매자가 AI 상태 설명을
  고친 값을 담는 용도입니다. 최대 1000자(`40001`).
- **생략하거나 `null`·공백뿐이면** 이 세션의 분석 결과 `conditionDescription`(`GET /api/products/analyze/{taskId}`
  응답과 같은 값)을 저장합니다. 분석 결과가 아직 없거나(Vision 완료 전) 그 값이 `null`이면 `null`로 저장됩니다.
- 분석 세션의 원본 분석 결과는 바뀌지 않습니다. 두 설명은 서로 섞이지 않습니다 — `sellerDescription`을 생략해도
  상태 설명이 `description`으로 들어가지 않습니다.
- 이 필드가 생기기 전에 등록된 상품은 `conditionDescription`이 `null`입니다(소급해 채우지 않음).

### Request Body

| 필드 | 타입 | 필수 | nullable | 검증/비고 |
| --- | --- | --- | --- | --- |
| `analysisId` | `Long` | O | X | `@NotNull`. 본인 소유이고, 취소되지 않았고, 아직 등록에 쓰이지 않은 세션 |
| `imageUrls` | `Array<String>` | X | O | 보내면 `@Size(min=3, max=4)`, 각 항목 `@NotBlank`. 위 "상품 이미지 결정 규칙" 참고 |
| `brand` / `modelName` / `color` / `conditionGrade` / `componentStatus` | `String` | O | X | `@NotBlank`. 값 범위는 검사하지 않음(등급은 "상태 등급 정리" 참고) |
| `size` / `recommendedPrice` / `sellingPrice` | `Integer` | O | X | `@NotNull`(`recommendedPrice: 0` 허용) |
| `auctionStartPrice` | `Long` | O | X | `@NotNull @Positive` |
| `auctionStartAt` / `auctionEndAt` | `String`(ISO-8601 offset) | O | X | `@NotNull`, 시작은 현재 이후·진행시간 1시간 이상(`40006`) |
| `baseMarketPrice` / `priceRange` / `reason` / `sellerDescription` | — | X | O | 선택. `sellerDescription`이 상품 상세의 `description`이 됨 |
| `conditionDescription` | `String` | X | O | 선택, `@Size(max=1000)`. 생략·`null`·공백이면 분석 결과의 상태 설명 사용. 위 "상태 설명 규칙" 참고 |

> **현재 구현의 제약(정책 미정, 변경하지 않음).** 등록 시 세션의 분석 상태는 검사하지 않습니다 —
> `COMPLETED`(가격 계산 완료)가 아닌 세션으로도 등록됩니다. 요청의 가격·등급이 가격 계산 결과와 같은지도
> 대조하지 않습니다. 이전 문서의 "`COMPLETED` 상태의 본인 세션" 표기는 실제 구현과 달라 정정했습니다.

### Response — 200 OK

상품 정보와 함께 **생성된 첫 경매 요약**(`auctionId`, `auctionStatus`, `auctionStartPrice`,
`bidIncrement`, `auctionStartAt`, `auctionEndAt`)을 반환합니다. `imageUrls`는 위 규칙으로 결정된 상품
이미지를 24시간 서명 URL로 바꾼 값입니다. 경매 상세 조회는 `GET /api/auctions/{auctionId}`
(auction-spec §1)로 이어집니다.

### Failure

| HTTP | code | 발생 조건 |
| --- | --- | --- |
| `400` | `40001` | 요청 본문 검증 실패(필수값 누락, `imageUrls`를 보냈는데 3~4개가 아님, `conditionDescription` 1000자 초과, `auctionStartPrice` ≤ 0 등). 메시지는 첫 번째 위반 항목 |
| `400` | `40002` | 분석 세션에 저장된 이미지가 3~4장이 아님(이미지 업로드 전·업로드 실패 세션 등). 요청 `imageUrls`를 보내도 같은 오류 |
| `400` | `40003` | 취소된 세션(`취소된 분석 세션은 상품 등록에 사용할 수 없습니다...`) 또는 이미 등록에 쓰인 세션(`이미 다른 상품 등록에 사용된 분석 세션입니다.`) |
| `400` | `40006` | 경매 시작/종료 시각 정책 위반 |
| `401` | `40101` | 미인증 |
| `404` | `40408` | 존재하지 않거나 본인 소유가 아닌 분석 세션 |

모든 실패에서 상품·경매는 저장되지 않고 세션의 등록 확정도 롤백됩니다(같은 트랜잭션).

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40002,
    "message": "분석 세션에 저장된 상품 이미지는 최소 3개, 최대 4개여야 합니다. 현재 이미지 수: 0"
  }
}
```

> **실서버 500 보고에 대해.** 이전에는 요청 `imageUrls`를 그대로 `product_image_urls.image_url`
> (`VARCHAR(1000)`)에 저장했으므로, 분석 조회의 서명 URL(배포 환경은 IAM 역할 서명이라
> `X-Amz-Security-Token`이 포함되어 길어질 수 있음)을 보내면 길이 초과로 catch-all `500`/`50001`이 날 수
> 있었습니다. 이 가설은 **실서버 로그로 확인되지 않았습니다.** 이번 변경으로 서명 URL은 저장 경로에
> 들어가지 않습니다.

---

# 경매·입찰 API (상세는 auction-spec.md)

경매·입찰·자동입찰·낙찰·정산·주문·차순위 엔드포인트의 **요청/응답·상태·오류·정책 상세는
[`docs/api/auction-spec.md`](auction-spec.md)**가 관리한다(이 문서와 중복 서술하지 않는다). 아래는
목록과 연결만 둔다.

| 엔드포인트 | 설명 | 상세 |
| --- | --- | --- |
| `GET /api/auctions/{auctionId}` | 경매 상세(product/seller/myState 중첩, AI 추정가, 내 참여 상태. #141: `product.conditionDescription` 추가) | auction-spec §1 |
| `GET /api/auctions/{auctionId}/live` | 실시간 경매 상태(가벼운 polling. #141: 내 마지막 입찰 금액 `myLastBidAmount` 추가) | auction-spec §2 |
| `GET /api/auctions/{auctionId}/bids` | 입찰 내역(page/size/order 페이지네이션) | auction-spec §3 |
| `GET /api/auctions/{auctionId}/auto-bid/recommendation` | AI 자동입찰 추천 | auction-spec §4 |
| `POST /api/auctions/{auctionId}/auto-bids` | 자동입찰 등록(Idempotency-Key) | auction-spec §5 |
| `GET/PATCH/DELETE /api/auctions/{auctionId}/auto-bids/me` | 내 자동입찰 조회/수정/중단 | auction-spec §6–8 |
| `POST /api/auctions/{auctionId}/bids` | 직접 입찰(Idempotency-Key) | auction-spec §9 |
| `GET /api/auctions/{auctionId}/result` | 경매 결과(#141: 본인 주문 상태 `orderStatus` 추가, 없으면 null) | auction-spec §10 |
| `POST /api/auctions/{auctionId}/award/forfeit` | 낙찰 포기 | auction-spec §11 |
| `GET /api/orders/{id}`, `POST /api/orders/{id}/pay` | 주문 조회·Mock 결제(#141: 주문 유형 `orderType` 추가) | auction-spec §12–13 |
| `GET /api/me/penalties` | 내 페널티 | auction-spec §14 |
| `GET/POST /api/backup-offers/...` | 차순위 제안 조회/수락/거절 | auction-spec §15–17 |
| `GET /api/auctions/{auctionId}/similar` | 비슷한 상품(#141: 경매 `status`·상품 `grade`·`startPrice`·`currentPrice` 추가) | auction-spec §18 |
| `POST/DELETE /api/auctions/{auctionId}/likes` | 관심 등록/해제 | auction-spec §19–20 |
| `POST /{id}/cancel`, `PATCH /{id}/start-price`, `POST /{id}/relist` | 경매 취소·시작가 수정·재경매(판매자용, freeze 이후 추가) | auction-spec §경매 관리 API |

> 과거 이 자리에 있던 flat 경매 상세 예시(`{id, productId, sellerId, ...}`)와 "자동입찰 기능 아직 구현 전",
> "경매 시작/종료 자동 전환 미구현" 설명은 현재 구현과 달라 삭제했다 — 현재는 중첩 `AuctionDetailResponse`
> (product/seller/myState), 자동입찰(Proxy) 동작, 시작/종료 스케줄러가 모두 구현돼 있다(auction-spec §0.13,
> `docs/backend/overview.md`). 입찰 인증·오류 코드는 아래 "인증"·"에러 코드 정리"와 auction-spec §0-A 참고.


# API 상세 설명

`GET /api/recommendations/auctions`

사용자의 행동 로그(조회·찜·입찰)를 기반으로 취향에 맞는 진행 중 경매를 추천하는 API입니다.

### `GET /api/auctions/{auctionId}/similar`와의 차이

이름이 비슷해 헷갈리기 쉬운데 **기준이 되는 축이 다릅니다.** 둘 다 필요하며 대체 관계가 아닙니다.

| | `/recommendations/auctions` | `/auctions/{id}/similar` |
| --- | --- | --- |
| 기준 | **사용자** (X-User-Id) | **경매 하나** (auctionId) |
| 방식 | 행동 로그 → 취향 벡터 → 코사인 유사도 | 같은 브랜드 |
| 정렬 | 유사도 높은 순 | 마감 임박 순 |
| 쓰는 화면 | 홈 "회원님을 위한 추천" | 상세 페이지 "비슷한 상품" |

상세 페이지에서 `/recommendations`를 쓰면 "이 신발과 비슷한 것"을 물었는데
"내가 예전에 본 것"이 나오므로 화면에 맞는 쪽을 호출해야 합니다.

추천은 두 가지 경로로 동작하며, 어느 경로로 만들어진 결과인지 응답의 `personalized` 필드로 구분합니다.

| `personalized` | 동작 | 조건 |
| --- | --- | --- |
| `true` | **개인화 추천.** 최근 행동 로그로 취향 벡터를 만들고, 각 경매 상품 벡터와의 코사인 유사도 순으로 정렬합니다. | `X-User-Id`가 있고, 해당 유저의 행동 로그가 **3건 이상**이며, 그 행동에 해당하는 상품 벡터가 존재할 때 |
| `false` | **Cold Start Fallback.** 마감 임박 경매와 인기(입찰 많은) 경매를 번갈아 섞어 내려줍니다. | 위 조건을 만족하지 못하는 모든 경우 (비로그인, 신규 가입자, 벡터 미생성 등) |

Fallback도 정상 응답(`200 OK`)입니다. 추천할 데이터가 없다는 이유로 에러를 반환하지 않습니다.

## 취향 벡터 계산 방식

행동 종류마다 취향을 드러내는 강도가 다르므로 가중치를 다르게 둡니다.

| 행동 | 가중치 | 비고 |
| --- | --- | --- |
| `BID` (입찰) | 3.0 | 돈이 걸린 행동이라 취향을 가장 강하게 드러냅니다 |
| `LIKE` (찜) | 2.0 | `POST /api/auctions/{id}/likes` 호출 시 자동 기록됩니다. 찜을 해제하면 기록도 지워집니다 |
| `VIEW` (조회) | 1.0 | 가장 흔하지만 신호는 약합니다 |
| `DWELL` (체류) | 1.0 | 프론트에서 전송해야 하며 현재는 수집하지 않습니다 |

최근 **100건**의 행동만 사용하며, 같은 상품을 여러 번 조회하면 가중치가 누적됩니다.

## Request ✔️

### Request Header

| 헤더 | 필수 | 설명 |
| --- | --- | --- |
| `X-User-Id` | ❌ | 추천 대상 사용자 ID. **없으면 Fallback 결과를 반환합니다.** |

### Query Parameter

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- |
| `limit` | `int` | ❌ | `10` | 추천 개수. 최대 `50`까지이며, 초과 입력 시 50으로 잘립니다. |

```
GET /api/recommendations/auctions?limit=10
X-User-Id: 3
```

## Response ✔️

### Success ✅

### 200 OK - 개인화 추천 (`personalized: true`)

```json
{
  "success": true,
  "data": {
    "personalized": true,
    "reason": "최근 관심 상품과 비슷한 경매를 추천합니다.",
    "items": [
      {
        "auctionId": 1,
        "productId": 5,
        "brand": "Nike",
        "model": "Dunk Low",
        "colorway": "Panda",
        "sizeKr": 270,
        "currentPrice": 100000,
        "endAt": "2026-08-21T15:30:00",
        "similarity": 0.9774
      }
    ]
  },
  "error": null
}
```

---

### 200 OK - Cold Start Fallback (`personalized: false`)

```json
{
  "success": true,
  "data": {
    "personalized": false,
    "reason": "아직 취향을 파악할 정보가 부족해 마감 임박·인기 경매를 보여드립니다.",
    "items": [
      {
        "auctionId": 1,
        "productId": 5,
        "brand": "Nike",
        "model": "Dunk Low",
        "colorway": "Panda",
        "sizeKr": 270,
        "currentPrice": 100000,
        "endAt": "2026-08-21T15:30:00",
        "similarity": null
      }
    ]
  },
  "error": null
}
```

### 응답 필드 설명

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `personalized` | `boolean` | 개인화 추천 여부. `false`면 Fallback 결과입니다. |
| `reason` | `string` | 추천 근거 문구. 프론트에서 그대로 노출할 수 있습니다. |
| `items[].auctionId` | `number` | 경매 ID |
| `items[].productId` | `number` | 상품 ID |
| `items[].brand` | `string` | 브랜드 |
| `items[].model` | `string` | 모델명 |
| `items[].colorway` | `string` | 컬러웨이 |
| `items[].sizeKr` | `number` | 사이즈(mm) |
| `items[].currentPrice` | `number` | 현재 입찰가 |
| `items[].endAt` | `string` | 경매 종료 시각 |
| `items[].similarity` | `number \| null` | 취향 벡터와의 코사인 유사도(0~1). **Fallback이거나, 개인화 목록 뒤쪽에 붙는 순위 없는 항목이면 `null`입니다.** |

### 프론트 적용 시 유의사항

- `personalized` 값에 따라 섹션 문구를 다르게 노출해 주세요. `true`면 "회원님을 위한 추천", `false`면 "지금 인기 있는 경매"처럼 표현하는 것을 권장합니다.
- `similarity`는 `null`일 수 있으므로 화면에 표시한다면 항상 null 체크가 필요합니다. `personalized: true`여도 목록 **뒤쪽에는 `similarity: null`인 항목이 붙을 수 있습니다** — 벡터가 아직 만들어지지 않아 순위를 매길 수 없는 경매를 버리는 대신, 유사도 순 뒤에 마감 임박 순으로 이어 붙입니다.
- 추천 대상은 **진행 중(`SCHEDULED`, `LIVE`) 경매**뿐입니다. 종료된 경매는 포함되지 않습니다.
- 행동 로그는 아래 API 호출 시 `X-User-Id` 헤더가 있으면 **자동으로 기록**됩니다. 프론트에서 별도 로그 전송 API를 호출할 필요는 없습니다.
  - `GET /api/auctions/{auctionId}` → `VIEW`
  - `POST /api/auctions/{auctionId}/likes` → `LIKE` / `DELETE`는 기록 삭제
  - `POST /api/auctions/{auctionId}/bids` → `BID`

### Failure ❌

추천 API는 데이터가 부족해도 Fallback으로 응답하므로 고유한 에러 코드가 없습니다. 서버 내부 오류(`50001`)만 공통 형식으로 반환됩니다.

---

# API 상세 설명

`POST /api/purchase-goals/parse`

Purchase Agent(#95)의 첫 단계입니다. 사용자가 자유롭게 쓴 구매 목표 문장을 구조화된 **초안(GoalDraft)** 으로 바꿔 돌려줍니다.

- **DB에 아무것도 저장하지 않습니다.** 응답은 초안이고, 사용자가 확인 화면에서 고친 값을 `POST /api/purchase-goals`(백엔드 담당, 별도 명세 예정)로 보내야 Goal이 등록됩니다.
- AI 해석에 실패하면 500이 아니라 **규칙 기반 초안**을 `warnings` 첫 줄에 안내를 붙여 돌려줍니다. 그래도 실패하면(50003) 프론트는 빈 폼으로 전환해 사용자가 직접 입력하게 해 주세요. 파싱 실패가 등록을 막으면 안 됩니다.
- 인증이 필요합니다(`X-User-Id`). 유료 LLM 호출이라 익명에 열지 않습니다.

## Request ✔️

### Request Header

```
Content-Type: application/json
X-User-Id: {userId}
```

### Request Body

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `text` | `string` | O | 구매 목표 문장. 1~300자 |

```json
{
  "text": "뉴발 990, A급 이상, 15만원 이하로 하나"
}
```

## Response ✔️

### Success ✅

### 200 OK

```json
{
  "success": true,
  "data": {
    "modelQuery": "New Balance 990",
    "brand": "New Balance",
    "modelKey": "nb990",
    "minCondition": "A",
    "hardMaxAmount": 150000,
    "sizeKr": null,
    "freeTextConditions": null,
    "confidence": 0.9,
    "warnings": [
      "사이즈가 없습니다. 사이즈를 지정하지 않으면 모든 사이즈가 후보가 됩니다."
    ]
  },
  "error": null
}
```

### 응답 필드 설명

모든 값은 **초안**입니다. 못 알아본 필드는 `null`이고 이유가 `warnings`에 실립니다. 확인 화면에서 전부 수정 가능해야 합니다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `modelQuery` | `string \| null` | 사람이 읽는 모델명. 시세 카탈로그에 있으면 카탈로그 표기("New Balance 990"), 없으면 AI가 이해한 이름 |
| `brand` | `string \| null` | 브랜드 영문 표기 (Nike, Adidas, New Balance, ...) |
| `modelKey` | `string \| null` | 시세 카탈로그 키(예: `nb990`). **`null`이면 시세가 없는 모델이라 v1 구매 Agent가 후보를 찾지 못합니다.** 확인 화면에서 모델 선택을 유도해 주세요 |
| `minCondition` | `"DS" \| "S" \| "A" \| "B" \| "C" \| null` | 최소 상태 등급. "A급 이상" → `A`. 없으면 `null`(모든 등급) |
| `hardMaxAmount` | `number \| null` | 예산 상한(원). "이하/까지/안쪽/정도/선"만 상한으로 봅니다. "이상"만 있으면 `null` |
| `sizeKr` | `number \| null` | 사이즈(mm, 220~320). 없으면 `null` |
| `freeTextConditions` | `string \| null` | 위 필드에 담기지 않은 희망 사항 원문("박스 있으면 좋음", "시카고 컬러"). **v1에서는 필수 조건이 아니라 참고 사항**으로만 쓰입니다 |
| `confidence` | `number` | 0~1. 초안 전체가 의도와 맞을 확신도. **0.7 미만이면 확인 화면에서 수정 유도를 강조**해 주세요(설계안 6-1). 서버 로직에는 쓰이지 않습니다 |
| `warnings` | `string[]` | 확인 화면에 그대로 보여줄 안내문. 비어 있을 수 있습니다 |

### 프론트 적용 시 유의사항

- `warnings`는 문구를 그대로 노출해도 되는 형태로 내려갑니다. 첫 줄이 "AI 해석에 실패해..."로 시작하면 규칙 기반 초안이므로 수정 유도를 강조해 주세요.
- `freeTextConditions`에 "필수/꼭/무조건" 같은 표현이 있으면 "v1에서는 참고 사항"이라는 안내가 `warnings`에 함께 옵니다. 등록 전에 사용자가 이 점을 보게 해 주세요.
- 파싱을 거치지 않고 빈 폼에 직접 입력하는 경로도 항상 열어 두세요.

### Failure ❌

| 상태 | 코드 | 상황 |
| --- | --- | --- |
| 400 | 40001 | `text`가 비었거나 300자 초과 |
| 500 | 50003 | AI 호출 실패 + 규칙 기반 fallback도 실패(정상이라면 발생하지 않음) |

---

> 아래 **인증·알림 API 상세**는 auction-spec.md에서 이동한 것으로, 전체 API 명세인 이 문서가 단독 관리한다. 인증 필요 여부 요약은 위 "## 인증" 표를 참고한다.

# 인증 — Kakao 로그인

```
POST /api/auth/kakao
```

## API 상세 설명

#75-4C에서 신규 도입. Kakao는 최초 신원 확인에만 사용한다 - 프론트가 Kakao SDK로 발급받은
Kakao access token을 이 endpoint에 보내면, 서버가 Kakao 사용자 정보 API로 그 token을
검증하고 내부 User를 find-or-create한 뒤 자체 Access/Refresh JWT를 발급한다. 이후 모든 API
인증은 이 응답의 `accessToken`(Bearer)만 사용한다 - Kakao access token은 이 endpoint
호출 이후 서버에 다시 제출되지 않는다.

이 endpoint는 인증 없이 호출한다(로그인 자체이므로 anonymous).

identity 기준은 Kakao의 안정적 회원번호(kakaoUserId)뿐이다 - email/nickname/프로필
이미지는 매 로그인마다 최신값으로 갱신되지 않고(#75-4C 최초 가입 시점 값만 저장), 신규
User 생성 여부 판단에도 관여하지 않는다. 같은 Kakao 계정으로 이메일이 바뀌어도 항상 같은
내부 User로 연결된다.

이 endpoint는 발급한 Refresh Token을 Redis(`refresh:{jti} -> userId`)에 등록하는 것까지
성공해야 응답을 반환한다(#75-4D) - Redis 등록 이후의 조회/삭제는 `POST /api/auth/refresh`
(아래 「# 인증 — Access Token 재발급」), `POST /api/auth/logout`(아래 「# 인증 — 로그아웃」) 참고.

## Request ✔️

### Request Header (0)

없음 - anonymous.

### Request Body (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| accessToken | String | Kakao SDK가 발급한 access token | O |

```json
{
  "accessToken": "kakao-access-token"
}
```

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — accessToken | String | 자체 발급 Access JWT | O |
| — refreshToken | String | 자체 발급 Refresh JWT | O |
| — accessTokenExpiresAt | String | Access JWT 만료 시각(ISO-8601 절대시각) | O |
| — refreshTokenExpiresAt | String | Refresh JWT 만료 시각(ISO-8601 절대시각) | O |

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "eyJhbGciOi...",
    "accessTokenExpiresAt": "2026-09-02T21:00:00+09:00",
    "refreshTokenExpiresAt": "2026-09-16T20:30:00+09:00"
  },
  "error": null
}
```

### Failure ❌

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | false | O |
| data | Object | API 관련 데이터(여기선 무조건 null) | O |
| error | Object | API 관련 에러 | O |
| — code | Int | 커스텀 코드 (분기처리용 / 오류 표 참고) | O |
| — message | String | 에러 메시지 (개발자 확인용 / 오류 표 참고) | O |

#### 400 Bad Request

`accessToken` 누락/공백 - `40001 INVALID_REQUEST`.

#### 401 Unauthorized

Kakao가 해당 access token을 invalid/expired로 판단(실제 신원 확인 실패로 한정 - Kakao의
모든 4xx를 이 코드로 매핑하지 않는다).

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40102,
    "message": "Kakao access token이 유효하지 않습니다."
  }
}
```

#### 502 Bad Gateway

Kakao 5xx/timeout/network 등 예기치 않은 upstream 실패 - Kakao 응답 payload는 그대로
노출하지 않는다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 50201,
    "message": "Kakao 사용자 정보 조회에 실패했습니다."
  }
}
```

---

# 인증 — Access Token 재발급

```
POST /api/auth/refresh
```

## API 상세 설명

#75-4D에서 신규 도입. 기존 Refresh Token을 검증하고 새 Access Token만 발급한다 - Refresh
Token 자체는 재발급하지 않는다(rotation 없음, 이번 버전의 확정 정책). 기존 Refresh Token은
그대로 유효하게 남는다.

내부적으로 Refresh JWT의 서명/type/만료를 검증한 뒤, Redis에 등록된 `refresh:{jti}`의
userId가 JWT subject와 일치하는지 추가로 확인한다 - 그중 하나라도 실패하면 40103.

anonymous로 호출한다(로그인 상태를 증명하는 것이 이 요청 자체의 목적이므로 Authorization
Bearer Access Token은 필요/사용하지 않는다).

## Request ✔️

### Request Header (0)

없음 - anonymous.

### Request Body (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| refreshToken | String | 로그인 시 발급받은 Refresh JWT | O |

```json
{
  "refreshToken": "eyJhbGciOi..."
}
```

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — accessToken | String | 신규 발급 Access JWT | O |
| — accessTokenExpiresAt | String | Access JWT 만료 시각(ISO-8601 절대시각) | O |

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOi...",
    "accessTokenExpiresAt": "2026-09-02T21:30:00+09:00"
  },
  "error": null
}
```

### Failure ❌

#### 400 Bad Request

`refreshToken` 누락/공백 - `40001 INVALID_REQUEST`.

#### 401 Unauthorized

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40103,
    "message": "유효하지 않은 Refresh Token입니다."
  }
}
```

---

# 인증 — 로그아웃

```
POST /api/auth/logout
```

## API 상세 설명

#75-4D에서 신규 도입. 제출한 Refresh Token의 Redis 등록(`refresh:{jti}`)을 삭제한다.

**idempotent**: 암호학적으로 유효(서명/type/만료 정상)한 Refresh Token인데 Redis에 이미
entry가 없는 경우(먼저 로그아웃했거나 TTL로 자연 만료) 실패로 취급하지 않고 200으로
응답한다 - "이미 로그아웃된 상태"와 동치로 본다. 반대로 malformed/서명 불일치/만료/Access
Token 오사용은 여전히 실패(40103)다.

**limitation**: 이 endpoint는 Refresh Token만 무효화한다. 이미 발급된 Access Token은
자체 만료(기본 30분)까지 계속 유효할 수 있다 - Access Token blacklist는 이번 버전 범위가
아니다.

anonymous로 호출한다(「# 인증 — Access Token 재발급」과 동일한 이유).

## Request ✔️

### Request Header (0)

없음 - anonymous.

### Request Body (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| refreshToken | String | 로그아웃할 Refresh JWT | O |

```json
{
  "refreshToken": "eyJhbGciOi..."
}
```

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | null(반환할 데이터 없음) | O |

#### 200 Ok

```json
{
  "success": true,
  "data": null,
  "error": null
}
```

### Failure ❌

#### 400 Bad Request

`refreshToken` 누락/공백 - `40001 INVALID_REQUEST`.

#### 401 Unauthorized

malformed/서명 불일치/만료/Access Token 오사용만 - Redis entry 없음은 실패가 아니다(위
idempotent 설명 참고).

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40103,
    "message": "유효하지 않은 Refresh Token입니다."
  }
}
```

---

# 알림 — 목록 조회

```
GET /api/notifications
```

## API 상세 설명

#75에서 신규 도입. 인증된 본인의 Notification만 반환한다 - 개인정보성 데이터라 auction-spec §3 입찰 내역과
달리 anonymous를 허용하지 않는다(`Authorization` 필수).

정렬은 `createdAt DESC, id DESC` 고정이다 - `id DESC`로 항상 안정적인(stable) 순서를 보장한다.

## Request ✔️

### Request Header

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | O |

```
Authorization: Bearer {accessToken}
```

### Request Parameter

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| page | Int | 페이지 번호 (기본 0) | X |
| size | Int | 페이지 크기 (기본 20) | X |

### Request Body

없음

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — notifications | Array<Object> | 알림 목록 | O |
| —— id | Long | 알림 ID | O |
| —— type | String | `AUCTION_WON` / `BACKUP_OFFER_CREATED` / `PAYMENT_EXPIRED` | O |
| —— auctionId | Long | 관련 경매 ID | O |
| —— resourceId | Long | 이벤트를 발생시킨 소스 엔티티 ID(타입별로 `Order.id` 또는 `BackupOffer.id`) | O |
| —— title | String | 고정 정적 문구(타입별, 상품명 등 미포함) | O |
| —— body | String | 고정 정적 문구(타입별, 상품명 등 미포함) | O |
| —— readAt | String | 읽은 시각(안 읽었으면 null) | X |
| —— createdAt | String | 생성 시각 | O |
| — page | Int | 현재 페이지 | O |
| — size | Int | 페이지 크기 | O |
| — hasNext | Boolean | 다음 페이지 존재 여부 | O |

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "notifications": [
      {
        "id": 12,
        "type": "AUCTION_WON",
        "auctionId": 30,
        "resourceId": 45,
        "title": "낙찰되었습니다",
        "body": "낙찰되었습니다. 결제를 진행해주세요.",
        "readAt": null,
        "createdAt": "2026-08-17T20:32:00+09:00"
      }
    ],
    "page": 0,
    "size": 20,
    "hasNext": false
  },
  "error": null
}
```

### Failure ❌

#### 401 Unauthorized

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40101,
    "message": "인증이 필요합니다."
  }
}
```

---

# 알림 — 읽음 처리

```
PATCH /api/notifications/{notificationId}/read
```

## API 상세 설명

#75에서 신규 도입. 본인 알림만 읽음 처리할 수 있다 - 존재하지 않는 알림과 타인 소유 알림을
구분해서 노출하지 않고 둘 다 `404 40405 NOTIFICATION_NOT_FOUND`로 통일한다(별도 403 없음,
#75 사용자 확정).

**idempotent**: 이미 읽은 알림에 다시 호출해도 실패하지 않고 200을 반환하며, `readAt`은 최초
읽은 시각 그대로 유지된다(재호출 시각으로 덮어쓰지 않음).

## Request ✔️

### Path Variable

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| notificationId | Long | 알림 ID | O |

### Request Header

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | O |

```
Authorization: Bearer {accessToken}
```

### Request Body

없음

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — notificationId | Long | 알림 ID | O |
| — readAt | String | 읽은 시각(최초 읽은 시각, 재호출해도 갱신 안 됨) | O |

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "notificationId": 12,
    "readAt": "2026-08-17T21:00:00+09:00"
  },
  "error": null
}
```

### Failure ❌

#### 404 Not Found

존재하지 않는 알림과 타인 소유 알림을 구분하지 않는다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40405,
    "message": "존재하지 않는 알림입니다."
  }
}
```

#### 401 Unauthorized

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40101,
    "message": "인증이 필요합니다."
  }
}
```

---

# 알림 — 안 읽은 수

```
GET /api/notifications/unread-count
```

## API 상세 설명

#75에서 신규 도입. 상단 벨 unread badge 전용 - `readAt IS NULL`인 본인 Notification 개수만
반환한다.

## Request ✔️

### Request Header

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | O |

```
Authorization: Bearer {accessToken}
```

### Request Body

없음

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — unreadCount | Long | 안 읽은 알림 수 | O |

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "unreadCount": 3
  },
  "error": null
}
```

### Failure ❌

#### 401 Unauthorized

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40101,
    "message": "인증이 필요합니다."
  }
}
```

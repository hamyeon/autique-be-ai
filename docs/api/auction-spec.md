> **Contract Status: FROZEN**
> Freeze baseline: 2026-08-20 · Verified/finalized in: #36
>
> 이 문서가 확정하는 것은 프론트/백엔드가 합의한 **외부 API shape와 business
> semantics**다. 현재 서버 구현이 이 문서의 모든 endpoint를 아직 제공하지 않거나
> 일부 규칙(닉네임 마스킹, 입찰 단위 정렬 검증, 오류 코드 매핑, `40909` 처리 등)을
> 아직 따르지 않는 것은 freeze를 막지 않는다 — "계약 확정"과 "구현 완료"는 다른
> 문제다. 미구현/미이행 항목은 `docs/api/auction-audit.md`에서 관리한다.

> **문서 범위.** 이 문서는 **경매 도메인(경매·입찰·자동입찰·낙찰·차순위·주문)** 의 외부 API
> shape와 business semantics를 동결한 정본(source of truth)이다. 전체 서비스의 프론트 연동
> 명세(상품 분석·가격·추천·인증·큐레이션 등 포함)는 `docs/api/spec.md`에 있으며, 이 문서를
> 전체 API의 정본으로 삼지 않는다. 계약과 현재 구현의 차이는 `docs/api/auction-audit.md`,
> 백엔드 전체 동작 안내는 `docs/backend/overview.md`를 참고한다.

> **FROZEN 범위와 그 이후.** 위 freeze(baseline 2026-08-20)는 당시 합의한 **경매 20개 엔드포인트의 외부
> shape·business semantics**를 동결한 것이다. 이후 구현에서 추가·확정된 사항 — Proxy 엔진 실제 구현(§0.13),
> `myState`/내 자동입찰의 Purchase Agent 필드, Purchase Agent 관리 중 제한(§0.14, `40917`), 경매 취소·시작가
> 수정·재경매 API(§경매 관리 API — freeze 이후 추가), 오류 코드표의 신규 코드 — 는 **freeze 이후 반영분**이며
> 이 문서가 현재 구현 기준으로 함께 안내한다. freeze는 "변경 불가"가 아니라 "그 시점 계약의 기준선"이다.
> 계약과 현재 구현의 차이는 `docs/api/auction-audit.md`가 관리한다.

# 0. 공통 규칙

## 0.1 Base

```
Base URL : /api
JSON Request Body가 있는 요청 : Content-Type: application/json
인증 : Bearer {accessToken}
```

## 0.2 Response Envelope

모든 API는 아래 형식을 사용한다.

**Success**

```json
{
  "success": true,
  "data": { },
  "error": null
}
```

**Failure**

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40904,
    "message": "이미 더 높은 입찰가가 있습니다."
  }
}
```

## 0.3 금액

모든 금액은 **원 단위 정수형(Long)** 이다.

## 0.4 시간

모든 시각은 ISO 8601 절대시각(String)이다.

```
2026-08-17T20:00:00+09:00
```

프론트는 응답의 `serverTime`으로 기기 시계 오차를 보정하고, polling 응답의 최신 `endsAt`을 사용한다.

## 0.5 Auction 상태

```
SCHEDULED
LIVE
ENDED
CANCELED
```

유찰은 별도 `FAILED` 상태를 만들지 않는다.

```
Auction.status = ENDED
AuctionResult  = NO_BIDS
```

## 0.6 AutoBid 상태

```
RESERVED
ACTIVE
CAP_REACHED
CANCELED
```

`CAP_REACHED`는 terminal이 아니다. 상한가를 올리면 다시 `ACTIVE`가 될 수 있다.

## 0.7 BidType

```
MANUAL
AUTO
```

## 0.8 실시간 갱신

v1은 SSE/WebSocket이 아니라 polling을 사용한다.

```
GET /api/auctions/{auctionId}/live
```

## 0.9 닉네임 마스킹

서버에서 수행한다.

```
닉네임 3자 이상 → 앞 3글자 + ****
닉네임 1~2자    → 첫 1글자 + ****
```

- 는 원본 길이와 무관하게 **항상 4개**로 고정한다.

```
mmaybeii  → mma****
hamburger → ham****
abc       → abc****
ab        → a****
a         → a****
```

## 0.10 기한 산출 규칙

```
낙찰자 결제 기한        = auction.endsAt + 24h
차순위 제안 응답 기한    = backupOffer.createdAt + 24h
차순위 수락 후 결제 기한 = backupOffer.acceptedAt + 24h
```

낙찰자의 `paymentDeadline`은 `/result`와 `/orders/{orderId}`에서 **항상 동일한 값**을 내려준다.

## 0.11 Idempotency

다음 API는 `Idempotency-Key` 헤더를 필수로 사용한다.

```
POST  /api/auctions/{auctionId}/bids
POST  /api/auctions/{auctionId}/auto-bids
PATCH /api/auctions/{auctionId}/auto-bids/me
POST  /api/backup-offers/{backupOfferId}/accept
```

서버 식별 기준:

```
UNIQUE(user_id, operation_scope, idempotency_key)
```

operation scope 예:

```
PLACE_BID:15
CREATE_AUTO_BID:15
UPDATE_AUTO_BID:15
ACCEPT_BACKUP_OFFER:90
```

```
Idempotency-Key 누락  → 400 / 40004
동일 key + 동일 payload → 기존 성공 결과 반환 (신규 생성 없음)
동일 key + 다른 payload → 409 / 40905
```

`POST /api/orders/{orderId}/pay`는 상태 전이 자체가 멱등이므로 key를 요구하지 않는다.

## 0.12 동률 처리

동일한 입찰 금액 또는 동일한 자동입찰 우선순위가 경쟁하는 경우, **먼저 접수/등록된
요청이 우선한다.**

```
same bid / same priority condition
→ earlier request wins
```

Proxy Bidding에서 동일 `maxAmount`를 가진 AutoBid끼리 경쟁하는 경우에도 **먼저
등록된 AutoBid가 우선권**을 갖는다.

이 규칙은 business semantics(어느 쪽이 이겨야 하는가)만 확정한다. "먼저"를 판정할
실제 ordering key(생성 시각의 정밀도, auto-increment ID, 별도 sequence 등)는 Proxy
Bidding 구현 시점의 기술적 세부사항으로 남겨둔다 — 이 문서는 그 컬럼/구현 방식을
지정하지 않는다.

## 0.13 Proxy Bidding 가격 결정 정책

> **구현 완료 (현재 코드 기준 정정).** 이 절의 가격 결정 규칙·상태 전이·FIRST-IN-WINS·실효 상한은
> 현재 `autobid/proxy/ProxyPriceEngine`(`resolve(ProxyResolutionInput)`)로 구현돼 있고, 직접 입찰·자동입찰
> 등록/수정·경매 시작(RESERVED 일괄 정산)에서 호출된다. 종료 연장(트리거 1분 이내 / +3분 / 최대 3회)도
> 구현됐다(아래 "종료 연장과 Proxy 연쇄 — RESOLVED" 절). 구현·검증 상세는 `docs/api/auction-audit.md`의
> "Proxy Bidding 실제 구현" 참고. (과거 "Proxy 엔진 아직 미구현(#40 기준)" 표기는 현재 사실과 달라 정정함.)
>

### 공통 원칙

```
1. currentPrice monotonic — 경매 진행 중 newCurrentPrice >= oldCurrentPrice. 감소하지 않는다.
2. bid increment — 직접 입찰은 §9 alignment validation을 따른다.
   AutoBid의 maxAmount 자체는 배수 정렬을 요구하지 않는다(§5).
3. AutoBid ceiling — 자동입찰이 생성하는 Bid는 자신의 maxAmount를 절대 초과하지 않는다.
4. FIRST-IN WINS — §0.12를 그대로 따른다. 동일 maxAmount AutoBid끼리 경쟁하면
   먼저 등록된 AutoBid가 우선한다.
```

### 비정렬 AutoBid maxAmount의 실효 상한(effectiveCap)

현재 price grid 기준 AutoBid가 실제로 도달할 수 있는 최고가:

```
effectiveCap = currentPrice + floor((maxAmount - currentPrice) / bidIncrement) * bidIncrement

선행 조건: maxAmount >= minCapAmount
```

예: `currentPrice=105000, bidIncrement=5000, maxAmount=121000` →
도달 가능한 가격은 `110000 → 115000 → 120000`이므로 `effectiveCap=120000`.

`AutoBidSetting.maxAmount`는 121000 그대로 DB에 유지한다 — `effectiveCap`은 가격 결정
시점에 매번 계산되는 값이며, `maxAmount` 자체를 120000으로 내려 저장하지 않는다.

### Manual vs 기존 AutoBid 경쟁

Manual bidder 자신의 기존 `ACTIVE`/`CAP_REACHED` AutoBid는 §9 정책에 따라 먼저
`CANCELED` 처리된다(`autoBidCanceled=true`). 그 다음 **다른 사용자**의 AutoBid와 비교한다.
Manual bid 금액을 `M`이라 할 때:

| 조건 | 결과 |
| --- | --- |
| 경쟁 AutoBid 없음 | manual bidder 승리, `currentPrice = M` |
| 최고 AutoBid `effectiveCap < M` | AutoBid가 이길 수 없음. manual bidder 승리, `currentPrice = M`. 해당 AutoBid는 결과적으로 `CAP_REACHED`가 될 수 있다 |
| 최고 AutoBid `effectiveCap > M` | AutoBid가 최소 필요한 한 단계만 응찰: `proxyPrice = min(M + bidIncrement, effectiveCap)`. AutoBid가 최고입찰자가 된다 |
| 최고 AutoBid `effectiveCap == M` | 동일 가격 경쟁. 그 AutoBid가 manual 요청보다 먼저 등록돼 있었다면 FIRST-IN WINS에 따라 AutoBid가 `M`에서 우선권을 유지한다. `M + bidIncrement`로 불필요하게 올리지 않는다 |

### Multiple AutoBid 경쟁

복수 AutoBid는 다음 우선순위로 정렬한다.

```
1. effectiveCap 높은 순
2. 동일 effectiveCap이면 먼저 등록된 순(FIRST-IN WINS)
```

winner는 1순위 AutoBid이며, 최종 가격은 개념적으로:

```
min(winnerEffectiveCap, secondEffectiveCap + bidIncrement)
```

동일 effectiveCap이면 FIRST-IN WINS로 먼저 등록된 AutoBid가 승리하고, `currentPrice`는
해당 cap을 넘지 않는다. 3개 이상이어도 핵심 가격 결정에는 최고/차순위 effectiveCap만
사용되며, 나머지는 자신의 cap이 현재 경쟁가를 넘지 못하면 `CAP_REACHED` 후보가 된다.
구현은 무한 proxy recursion 없이 한 번의 price resolution으로 계산 가능해야 한다.

### AutoBid 상태 전이 semantics

```
RESERVED     — 경매 시작 전 등록.
ACTIVE       — LIVE이고 현재 설정이 자동입찰 경쟁에 참여 가능한 상태. 현재 최고입찰자이면서
               cap까지 도달했더라도, 다른 bidder에게 추월당하기 전까지 무조건 CAP_REACHED로
               바꾸지 않는다.
CAP_REACHED  — 다른 bidder/AutoBid에 의해 currentPrice가 자신의 effectiveCap을 넘어
               더 이상 응찰할 수 없게 된 상태. 예: myCap=120000, currentPrice=125000.
CAP_REACHED → ACTIVE — cap을 상향해 새 effectiveCap이 다시 경쟁 참여 가능한 수준이 되고
               price resolution을 수행하면 복귀할 수 있다(§0.6 "terminal이 아니다"와 일치).
CANCELED     — terminal. 동일 row를 ACTIVE로 되살리지 않는다. 재등록은 새 AutoBidSetting
               생성(§5).
```

### 경매 시작 시 RESERVED 일괄 정산

`SCHEDULED → LIVE` 전환 시 RESERVED 설정을 일괄 평가한다.

| 예약자 수 | 결과 |
| --- | --- |
| 0명 | `currentPrice`는 `startPrice` 유지, Bid 생성 없음 |
| 1명 | `RESERVED → ACTIVE`. `maxAmount`가 유효하면 시작 시점 최소가(`startPrice + bidIncrement`)로 첫 AUTO Bid 생성, `currentPrice`를 그 실제 금액으로 갱신. `maxAmount`를 초과하지 않는다 |
| 2명 이상 | RESERVED를 ACTIVE 후보로 전환, effectiveCap + FIRST-IN WINS로 경쟁. highest/second-highest cap을 사용해 한 번의 price resolution으로 초기 `currentPrice`와 winner를 결정. 생성되는 가격은 bidIncrement grid를 지키고 winner cap을 넘지 않는다 |

예약 AutoBid들의 recursive bid chain을 실제 반복 호출로 구현해야 한다는 의미는 아니다 —
deterministic price resolver 한 번으로 계산 가능해야 한다.

### 종료 연장과 Proxy 연쇄 — RESOLVED (#43)

```
트리거 시점: 종료 1분 이내(inclusive)
연장 폭:     +3분
최대 횟수:   3회 (Auction.extensionCount, MAX_EXTENSIONS=3)
```

연장 판단 기준은 "가격/승자 변동 여부"가 아니라 **"해당 사용자 command로 실제 Bid가
발생했는가"**다.

```
Manual Bid 성공        → 항상 실제 MANUAL Bid가 생성되므로 연장 판단 대상
LIVE AutoBid POST/PATCH → bidOccurred=true(entrant 자신의 AUTO Bid가 실제 저장)일 때만
                          연장 판단 대상. 설정만 생성/수정되고 경쟁이 없어
                          bidOccurred=false면 연장하지 않는다
```

한 사용자 요청으로 발생한 Proxy 연쇄는 종료 연장을 추가로 여러 번 발생시키지 않는다.

```
manual/auto 사용자 action → price resolution → proxy-generated bid(s)

위 흐름 전체에서 extension 판단은 원 사용자 action 기준 최대 1회다.
Proxy 내부 자동응찰 각각은 별도의 extension trigger가 아니다. priceChanged=false라도
FIRST-IN WINS 등으로 resultingAutoBid가 실제 저장되는 경우(bidOccurred=true)에는
연장 판단 대상이다.
```

대상: Manual Bid, LIVE AutoBid POST, LIVE AutoBid PATCH.
제외: Proxy 내부 AUTO Bid, GET/DELETE, scheduler/lifecycle.

### 정책 검증용 테스트 케이스 (구현 시 그대로 사용)

| # | 케이스 | initial currentPrice | increment | 참가자/cap | expected currentPrice | expected winner | expected AutoBid 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | manual only | 10000 | 5000 | manual M=15000 | 15000 | manual | — |
| 2 | manual > auto cap | 105000 | 5000 | auto effectiveCap=110000, manual M=120000 | 120000 | manual | auto → CAP_REACHED |
| 3 | manual < auto cap | 105000 | 5000 | auto effectiveCap=150000, manual M=120000 | min(120000+5000, 150000)=125000 | auto | auto ACTIVE |
| 4 | manual == auto cap (auto 선등록) | 105000 | 5000 | auto effectiveCap=120000(선등록), manual M=120000 | 120000 | auto(FIRST-IN) | auto ACTIVE |
| 5 | one AutoBid, 경쟁자 없음 | 105000 | 5000 | auto effectiveCap=130000 | 응찰 안 함(경쟁 없음) | — | ACTIVE |
| 6 | two different caps | 105000 | 5000 | auto A effectiveCap=130000, auto B effectiveCap=120000 | min(130000, 120000+5000)=125000 | A | A ACTIVE, B CAP_REACHED |
| 7 | two equal caps → first-in wins | 105000 | 5000 | auto A effectiveCap=120000(선등록), auto B effectiveCap=120000 | 120000 | A(FIRST-IN) | A ACTIVE, B CAP_REACHED |
| 8 | three AutoBids | 105000 | 5000 | A cap=140000, B cap=130000, C cap=120000 | min(140000, 130000+5000)=135000 | A | A ACTIVE, B/C CAP_REACHED |
| 9 | unaligned cap | 105000 | 5000 | maxAmount=121000 → effectiveCap=120000 | effectiveCap 계산에 121000 그대로 사용, 실제 도달가는 120000 이하 | 케이스별 | maxAmount는 121000 유지 |
| 10 | CAP_REACHED | 105000 | 5000 | myCap=120000, 타 경쟁으로 currentPrice=125000 | 125000(변경 없음) | 타 사용자 | CAP_REACHED |
| 11 | CAP_REACHED cap increase | CAP_REACHED 상태, myCap 105000→150000로 상향 | 5000 | 상향 후 재경쟁 | 새 effectiveCap 기준 재계산 | 케이스별 | 경쟁 재참여 가능해지면 ACTIVE로 복귀 |
| 12 | auction start / 0 reserved | startPrice=50000 | 5000 | 예약자 없음 | 50000(변경 없음) | 없음 | — |
| 13 | auction start / 1 reserved | startPrice=50000 | 5000 | 예약자 1명 cap=100000 | 55000(startPrice+increment) | 예약자 | RESERVED → ACTIVE |
| 14 | auction start / multiple reserved | startPrice=50000 | 5000 | 예약자 A cap=100000(선등록), B cap=90000 | min(100000, 90000+5000)=95000 | A | A ACTIVE, B CAP_REACHED |
| 15 | Proxy chain + extension once | 임의 | 임의 | manual 1회 action → proxy 응찰 발생 | — | — | extension 판단은 원 action 기준 1회만, proxy 응찰 각각은 추가 트리거 아님 |

각 케이스의 실제 숫자는 정책에서 명확히 계산되는 범위만 기재했다 — #9/#11/#15는 세부
수치가 아니라 semantics(실효 상한 계산 방식, cap 상향 후 재판정, extension 1회 원칙)를
검증하는 데 목적이 있다.

---

## 0.14 Purchase Agent 관리 중 제한 (40917)

Purchase Agent(구매 에이전트)가 사용자를 대신해 어떤 경매에 자동입찰을 걸고 참여 중이면(구매 목표가
`ENGAGED`/`CANCEL_REQUESTED`이고 그 경매가 목표의 현재 대상 경매), 사용자가 **같은 경매에서** 아래
조작을 직접 하려 하면 **409 `40917 AGENT_MANAGED_AUCTION`**("Purchase Agent가 관리 중인 경매입니다.")로
거부된다.

| 조작 | 차단 |
| --- | --- |
| 직접 입찰 (§9 `POST /bids`) | 차단 — 단, 그 사용자 **본인의 활성 자동입찰**이 Agent 관리 설정일 때만 |
| 자동입찰 수정 (§7 `PATCH /auto-bids/me`) | 차단 |
| 자동입찰 중단 (§8 `DELETE /auto-bids/me`) | 차단 |
| 자동입찰 등록 (§5 `POST /auto-bids`) | **차단하지 않음** — 등록 요청 본문에 목표 연결 필드가 없어 Agent 관리 설정이 되지 않는다 |

- 경매 상세 `myState.managedByPurchaseAgent` / 내 자동입찰 조회 `managedByPurchaseAgent`가 `true`면 위
  조작 버튼을 비활성화하도록 안내한다(서버도 `40917`로 최종 방어한다).
- 탐색 스케줄러(`PURCHASE_AGENT_SCAN_ENABLED`, 기본 off)가 꺼진 환경에서는 Agent가 경매에 참여하지
  않으므로 이 제한이 발생하지 않는다. 백엔드 동작은 `docs/purchase-agent/backend.md` 참고.

---

# 0-A. 공통 오류 코드표

> 이 표는 **경매·입찰·자동입찰·정산·주문·차순위 도메인**의 오류 코드다. 분석/사진/구매목표/상품/인증 등을
> 포함한 **전체 도메인의 코드 목록은 `docs/api/spec.md`**가 대표로 관리한다. "신규(freeze 이후)" 표시는
> freeze(2026-08-20) 이후 구현에서 추가된 코드이며, 실제 매핑은 `GlobalExceptionHandler` 기준이다.

| HTTP | code | symbolic | message | 의미 | 상태 |
| --- | --- | --- | --- | --- | --- |
| 400 | 40001 | `INVALID_REQUEST` | 유효하지 않은 요청입니다. | 파라미터/바디 검증 실패(Bean Validation), 목록 `page`/`size`/`limit`/`state` 범위 오류, 요청 형식 오류(잘못된 JSON·본문 필드 타입, 경로 변수·쿼리 파라미터 타입 변환 실패 — #147) | 기존 |
| 400 | 40004 | `IDEMPOTENCY_KEY_MISSING` | Idempotency-Key 헤더가 필요합니다. | 필수 헤더 누락 | 기존 |
| 400 | 40006 | `INVALID_AUCTION_TIME` | 경매 시간 설정이 올바르지 않습니다. | 상품·첫 경매 등록(`POST /api/products`)과 재경매의 start/end 시각 유효성 | 신규(freeze 이후) |
| 401 | 40101 | `UNAUTHORIZED` | 인증이 필요합니다. | 토큰 없음/만료 | 신규 |
| 401 | 40102 | `KAKAO_TOKEN_INVALID` | Kakao access token이 유효하지 않습니다. | Kakao가 해당 access token을 invalid/expired로 판단(신원 확인 실패), `POST /api/auth/kakao` 전용(#75) | 신규 |
| 401 | 40103 | `REFRESH_TOKEN_INVALID` | 유효하지 않은 Refresh Token입니다. | malformed/서명 불일치/만료/Access token 오사용/Redis entry 없음(revoked)/Redis userId 불일치. `POST /api/auth/refresh`·`POST /api/auth/logout` 전용(#75-4D). logout은 예외 - 토큰 자체가 유효한데 Redis entry만 없으면 이미 로그아웃된 것으로 보고 200 | 신규 |
| 403 | 40301 | `SELLER_CANNOT_BID` | 본인이 등록한 경매에는 입찰할 수 없습니다. | 판매자 본인 경매 | 기존 |
| 403 | 40302 | `PENALTY_RESTRICTED` | 경매 참여가 제한된 상태입니다. | 노쇼 페널티 | 기존 |
| 403 | 40303 | `NOT_AWARDEE` | 낙찰자가 아닙니다. | **경매 낙찰 권한 없음** (낙찰 포기 전용) | 신규 |
| 403 | 40304 | `ORDER_ACCESS_DENIED` | 접근 권한이 없는 주문입니다. | **주문 소유자 아님** (조회/결제 전용) | 신규 |
| 403 | 40305 | `BACKUP_OFFER_ACCESS_DENIED` | 본인 명의의 차순위 제안이 아닙니다. | **BackupOffer 소유자(candidate) 아님** (조회/수락/거절 전용, #75) | 신규 |
| 403 | 40307 | `AUCTION_SELLER_MISMATCH` | 판매자 본인만 가능한 작업입니다. | 경매 취소/시작가 수정/재경매 — 판매자 아님 | 신규(freeze 이후) |
| 404 | 40401 | `AUCTION_NOT_FOUND` | 존재하지 않는 경매입니다. |  | 신규 |
| 404 | 40402 | `ORDER_NOT_FOUND` | 존재하지 않는 주문입니다. |  | 신규 |
| 404 | 40403 | `BACKUP_OFFER_NOT_FOUND` | 존재하지 않는 차순위 제안입니다. |  | 신규 |
| 404 | 40404 | `AUTO_BID_NOT_FOUND` | 등록된 자동입찰이 없습니다. |  | 신규 |
| 404 | 40405 | `NOTIFICATION_NOT_FOUND` | 존재하지 않는 알림입니다. | 존재하지 않거나 본인 소유가 아닌 알림(구분 노출 안 함, #75) | 신규 |
| 409 | 40901 | `ALREADY_HIGHEST_BIDDER` | 이미 최고 입찰자입니다. | 최고 입찰자의 추가 직접입찰 | 기존 |
| 409 | 40902 | `AUCTION_NOT_STARTED` | 아직 시작되지 않은 경매입니다. | 시작 전 직접입찰 | 기존 |
| 409 | 40903 | `AUCTION_CLOSED` | 종료된 경매입니다. | 종료/취소된 경매 | 기존 |
| 409 | 40904 | `BID_AMOUNT_TOO_LOW` | 이미 더 높은 입찰가가 있습니다. | `minNextBidAmount` 미만 | 기존 |
| 409 | 40905 | `IDEMPOTENCY_PAYLOAD_MISMATCH` | 동일한 키로 다른 요청이 접수되었습니다. | 같은 key + 다른 payload | 기존 |
| 409 | 40906 | `CAP_TOO_LOW` | 자동입찰 상한가가 너무 낮습니다. | `minCapAmount` 미만 | 신규 |
| 409 | 40907 | `CAP_NOT_INCREASED` | 상한가는 현재 설정값보다 높아야 합니다. | **`ACTIVE`** **/** **`CAP_REACHED`에서만 발생** | 신규 |
| 409 | 40908 | `AUTO_BID_ALREADY_EXISTS` | 이미 자동입찰이 등록되어 있습니다. | 중복 등록 | 신규 |
| 409 | 40909 | `CONCURRENT_CONFLICT` | 다른 요청과 충돌이 발생했습니다. 잠시 후 다시 시도해주세요. | lock timeout / 동시성 충돌. **재시도 가능** | 신규 |
| 409 | 40910 | `PAYMENT_EXPIRED` | 결제 기한이 만료되었습니다. |  | 신규 |
| 409 | 40911 | `BACKUP_OFFER_EXPIRED` | 차순위 구매 기한이 만료되었습니다. |  | 신규 |
| 409 | 40912 | `BACKUP_OFFER_ALREADY_RESOLVED` | 이미 처리된 제안입니다. | 수락/거절 완료 후 재요청 | 신규 |
| 409 | 40913 | `BID_NOT_ALIGNED` | 입찰 단위에 맞지 않는 금액입니다. | `(amount - currentPrice) % bidIncrement != 0` | 신규 |
| 409 | 40914 | `ALREADY_PAID` | 이미 결제가 완료된 주문입니다. | 결제 완료 후 낙찰 포기 시도 | 신규 |
| 409 | 40915 | `ORDER_CANCELED` | 취소된 주문입니다. | 포기로 취소된 주문에 결제 시도 | 신규 |
| 409 | 40916 | `INVALID_PURCHASE_GOAL_STATUS` | 처리할 수 없는 구매 목표 상태입니다. | Purchase Agent 목표 상태 전이 위반 | 신규(freeze 이후) |
| 409 | 40917 | `AGENT_MANAGED_AUCTION` | Purchase Agent가 관리 중인 경매입니다. | §0.14 — 직접 입찰·자동입찰 수정/취소 차단 | 신규(freeze 이후) |
| 409 | 40918 | `ACTIVE_AUCTION_ALREADY_EXISTS` | 이미 진행/예약 중인 경매가 있습니다. | 재경매 — 활성 경매 존재 | 신규(freeze 이후) |
| 409 | 40919 | `AUCTION_REGISTRATION_LIMIT_EXCEEDED` | 재경매 등록 한도를 초과했습니다. | 재경매 — 최대 등록 횟수 초과(2회) | 신규(freeze 이후) |
| 409 | 40920 | `AUCTION_NOT_ELIGIBLE_FOR_REREGISTRATION` | 재경매할 수 없는 경매입니다. | 재경매 — 실제 입찰이 있던 경매 | 신규(freeze 이후) |
| 409 | 40921 | `AUCTION_CANCEL_WINDOW_CLOSED` | 취소할 수 없는 경매입니다. | 경매 취소 — 이미 시작/비 SCHEDULED | 신규(freeze 이후) |
| 409 | 40922 | `START_PRICE_CHANGE_WINDOW_CLOSED` | 시작가를 변경할 수 없는 경매입니다. | 시작가 수정 — 시작 1시간 전 이후 | 신규(freeze 이후) |
| 405 | 40501 | `METHOD_NOT_ALLOWED` | 지원하지 않는 HTTP 메서드입니다. | 경로는 있지만 메서드가 다름. 응답 `Allow` 헤더에 허용 메서드(#147) | 신규(#147) |
| 415 | 41501 | `UNSUPPORTED_MEDIA_TYPE` | 지원하지 않는 Content-Type입니다. | 요청 `Content-Type`을 받지 않는 API(#147) | 신규(#147) |
| 502 | 50201 | `KAKAO_API_ERROR` | Kakao 사용자 정보 조회에 실패했습니다. | Kakao 5xx/timeout/network 등 upstream 실패(내부 payload 미노출), `POST /api/auth/kakao` 전용(#75) | 신규 |

> **`message` 열은 대표 문구다.** 대부분의 오류는 발생 지점의 설명(대상 ID 등)을 그대로 담으므로 실제 문구가 다를 수 있다
> (고정 문구는 `40909`, `40501`, `41501`, 요청 형식 오류의 `40001` 등 일부). **FE는 `code`로 분기**한다.
> `symbolic`은 문서용 이름이며 응답에 포함되지 않는다(응답은 `success`/`data`/`error.code`/`error.message`).
>

> **번호 배정 원칙** `40001`, `40004`, `403xx`, `409xx` 중 “기존” 표시 항목은 현재 서버 구현 번호를 그대로 유지한다. 특히 `40905 IDEMPOTENCY_PAYLOAD_MISMATCH`는 이미 구현되어 있으므로 변경하지 않고, 신규 코드를 `40906`부터 뒤로 배정했다. `502xx`는 이번(#75)에 처음 쓰인 HTTP status 계열이다 - 기존 계열(`400xx`~`409xx`)과 동일하게 HTTP status를 그대로 앞 3자리로 사용한다.
>

> `40909 CONCURRENT_CONFLICT`만 프론트에서 자동 재시도(최대 1회)를 허용한다. 나머지는 사용자에게 메시지를 노출한다.
>

---

# 1. 경매 상품 상세 O

```
GET /api/auctions/{auctionId}
```

## API 상세 설명

경매 상태(`SCHEDULED` / `LIVE` / `ENDED`)와 무관하게 하나의 상세 API를 사용한다. 상품 정보, 판매자, AI 시세, 내 참여 상태를 함께 반환한다.

비로그인 접근을 허용한다. Authorization이 있으면 `myState`/`isLiked` 등 개인화 필드를 채우고, 없으면 중립값을 반환한다.

## Request ✔️

### Path Variable

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| auctionId | Long | 경매 ID | O |

### Request Header

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | X |

```
Authorization: Bearer {accessToken}
```

### Request Parameter

없음

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
| — auctionId | Long | 경매 ID | O |
| — status | String | SCHEDULED / LIVE / ENDED / CANCELED | O |
| — product | Object | 상품 정보 | O |
| —— productId | Long | 상품 ID | O |
| —— name | String | 상품명 | O |
| —— brand | String | 브랜드 | O |
| —— subName | String | 모델명(`Product.model` 그대로 — 아래 "상품 표시명 규칙") | O |
| —— grade | String | 상품 등급. 등록 시 저장된 `conditionGrade` 문자열을 변환 없이 그대로 반환한다(A/B/C만 오는 것이 아니다 — `DS`/`S`/`UNKNOWN` 등도 올 수 있음, spec.md "상태 등급 정리" 참고) | O |
| —— imageUrls | Array<String> | 상품 이미지 목록(조회마다 새로 서명한 24시간 유효 URL) | O |
| —— conditionDescription | String | 상품 상태 설명 (#141). 등록 시 판매자가 수정해 보낸 값, 생략했으면 AI 분석의 상태 설명. 상위 `description`(판매자 설명)과 별개 값이다. **이 필드 도입 전에 등록된 상품, 또는 상태 설명 없이 등록된 상품은 `null`** — FE는 null이면 상태 설명 영역을 숨긴다 | X |

**상품 표시명 규칙(`ProductDisplayName`, #141 정정).** Product에는 별도 상품명/영문명 컬럼이 없다.
`name` = `brand` + `modelName` + `color`를 공백으로 이은 값(빈 값은 건너뜀, 각 값 trim), `subName` =
등록 시 입력한 `modelName` 그대로다. 아래 예시의 한글/영문 상품명은 이 규칙으로 만들어지지 않는다 —
실제 응답은 예: `name: "Nike Dunk Low Panda"`, `subName: "Dunk Low"`. 경매 결과·주문·차순위 응답의
`product.name`/`subName`도 같은 규칙이다.
| — seller | Object | 판매자 정보 | O |
| —— sellerId | Long | 판매자 ID | O |
| —— nickname | String | 판매자 닉네임 (마스킹 없음) | O |
| —— profileImageUrl | String | 프로필 이미지 | X |
| —— completedSalesCount | Int | 누적 판매 건수 | O |
| — description | String | 판매자 설명 | O |
| — startPrice | Long | 시작가 | O |
| — currentPrice | Long | 현재가 | O |
| — bidIncrement | Long | 최소 입찰 단위 | O |
| — minNextBidAmount | Long | 최소 다음 입찰가 | O |
| — minCapAmount | Long | 자동입찰 최소 상한가 | O |
| — startsAt | String | 경매 시작 시각 | O |
| — endsAt | String | 경매 종료 시각 | O |
| — serverTime | String | 서버 기준 현재 시각 | O |
| — aiEstimatedPrice | Long | AI 적정 시세 — **이 상품이 시장에서 얼마짜리인지**(조회 시 서버가 가격 엔진으로 다시 계산한 값, 판매자가 등록 때 보낸 `recommendedPrice`가 아님). 시세를 못 내면 `null`. 아래 "AI 시세와 추천 상한의 차이" 참고 | X |
| — aiRecommendedAutoBidCap | Long | AI 추천 상한가 — **자동입찰 상한 입력칸의 초기값**. 현재는 항상 `minNextBidAmount`(= `minCapAmount`)와 같다(§4 `aiRecommendedCap`과 같은 정책). 시세와 무관 | X |
| — aiPriceReason | String | AI 가격 산정 근거 | X |
| — bidCount | Int | 입찰 수 | O |
| — isLiked | Boolean | 내 관심 등록 여부 | O |
| — likeCount | Int | 관심 수 | O |
| — myState | Object | 내 참여 상태 | O |
| —— isSeller | Boolean | 판매자 본인 여부 | O |
| —— isHighestBidder | Boolean | 현재 최고 입찰자 여부 | O |
| —— canBid | Boolean | 직접 입찰 버튼 활성화 가능 여부 | O |
| —— cannotBidReason | String | 입찰 불가 사유 (canBid=true면 null) | X |
| —— bidRestrictedUntil | String | 입찰 제한 해제 시각 (페널티일 때만) | X |
| —— autoBidStatus | String | RESERVED / ACTIVE / CAP_REACHED / CANCELED | X |
| —— autoBidCap | Long | 내 자동입찰 상한가 | X |
| —— purchaseGoalId | Long | 이 경매에 연결된 내 구매 목표(Purchase Agent) ID. 없으면 null | X |
| —— managedByPurchaseAgent | Boolean | 이 경매를 내 Purchase Agent가 관리 중인지 여부(관리 중이면 직접 입찰·자동입찰 수정/취소가 `40917`로 차단된다 — §Purchase Agent 관리 중 제한) | O |
| — finalPrice | Long | 최종 낙찰가 (ENDED에서만) | X |

**AI 시세와 추천 상한의 차이 (#141).**

| 필드 | 의미 | 출처 | 값이 바뀌는 시점 |
| --- | --- | --- | --- |
| `aiEstimatedPrice` (§1) | 이 상품의 적정 시세(얼마짜리 물건인가) | 조회 시 서버 가격 엔진(`PriceEstimateProvider`)이 상품의 브랜드·모델·색상·사이즈·등급으로 계산 | 입찰과 무관. 카탈로그 밖 모델이면 `null` |
| `aiRecommendedAutoBidCap` (§1), `aiRecommendedCap` (§4) | 자동입찰 상한 입력칸 초기값(얼마까지 걸지 제안) | 구매자용 추천 소스가 아직 없어 **항상 `minCapAmount`(= 최소 다음 입찰가)** | 현재가가 오를 때마다 함께 오름 |

두 값은 비교하거나 합쳐 쓰는 값이 아니다. "AI 추천 상한"이 시세를 반영한다고 표시하지 않는다.

**`myState.cannotBidReason`** **enum**

```
AUCTION_NOT_STARTED
AUCTION_CLOSED
SELLER_CANNOT_BID
PENALTY_RESTRICTED
ALREADY_HIGHEST_BIDDER
```

금액 검증(`BID_AMOUNT_TOO_LOW`)은 사용자가 금액을 입력한 뒤 결정되므로 `canBid`에 포함하지 않는다. `canBid`는 **입찰 버튼 자체를 눌러도 되는가**만 의미한다.

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "auctionId": 1,
    "status": "SCHEDULED",
    "product": {
      "productId": 10,
      "name": "ASICS Novablast 6 Black",
      "brand": "ASICS",
      "subName": "Novablast 6",
      "grade": "A",
      "imageUrls": [
        "https://...",
        "https://..."
      ],
      "conditionDescription": "토박스에 얕은 주름이 있고 아웃솔에 사용감이 있습니다."
    },
    "seller": {
      "sellerId": 2,
      "nickname": "mmaybeii",
      "profileImageUrl": "https://...",
      "completedSalesCount": 4
    },
    "description": "상품 상태가 완전히 좋습니다.",
    "startPrice": 50000,
    "currentPrice": 50000,
    "bidIncrement": 5000,
    "minNextBidAmount": 55000,
    "minCapAmount": 55000,
    "startsAt": "2026-08-17T20:00:00+09:00",
    "endsAt": "2026-08-17T22:00:00+09:00",
    "serverTime": "2026-08-17T17:30:00+09:00",
    "aiEstimatedPrice": 100000,
    "aiRecommendedAutoBidCap": 55000,
    "aiPriceReason": "유사 거래 데이터를 기반으로 산정했습니다.",
    "bidCount": 0,
    "isLiked": false,
    "likeCount": 556,
    "myState": {
      "isSeller": false,
      "isHighestBidder": false,
      "canBid": true,
      "cannotBidReason": null,
      "bidRestrictedUntil": null,
      "autoBidStatus": null,
      "autoBidCap": null,
      "purchaseGoalId": null,
      "managedByPurchaseAgent": false
    },
    "finalPrice": null
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

#### 404 Not Found

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40401,
    "message": "존재하지 않는 경매입니다."
  }
}
```

---

# 2. 실시간 경매 상태 O

```
GET /api/auctions/{auctionId}/live
```

## API 상세 설명

상품 설명/AI 분석을 제외한 가벼운 polling API. 가격, 남은 시간, 입찰 단위, 내 자동입찰 상태, 현재 직접 입찰 가능 여부를 반환한다. 입찰 내역은 포함하지 않는다. 실시간 화면은 이 API와 `GET /api/auctions/{auctionId}/bids`를 함께 호출한다.

## Request ✔️

### Path Variable

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| auctionId | Long | 경매 ID | O |

### Request Header

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | O |

```
Authorization: Bearer {accessToken}
```

### Request Parameter

없음

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
| — auctionId | Long | 경매 ID | O |
| — status | String | SCHEDULED / LIVE / ENDED / CANCELED | O |
| — currentPrice | Long | 현재가 | O |
| — minNextBidAmount | Long | 최소 다음 입찰가 | O |
| — bidIncrement | Long | 직접 입찰 stepper 증감 단위 | O |
| — highestBidderMasked | String | 마스킹된 최고 입찰자 | X |
| — isMine | Boolean | 내가 최고 입찰자인지 | O |
| — canBid | Boolean | 현재 직접 입찰 가능 여부 | O |
| — cannotBidReason | String | 입찰 불가 사유 (canBid=true면 null) | X |
| — bidRestrictedUntil | String | 페널티로 입찰 제한 중일 때 해제 시각 | X |
| — endsAt | String | 종료 시각 (연장 반영) | O |
| — serverTime | String | 서버 기준 현재 시각 | O |
| — extensionCount | Int | 종료 연장 횟수 | O |
| — maxExtensions | Int | 최대 연장 횟수 | O |
| — myAutoBidStatus | String | 내 자동입찰 상태 (미사용 시 null) | X |
| — myCap | Long | 내 자동입찰 상한가 (미사용 시 null) | X |
| — minCapAmount | Long | 자동입찰 최소 상한가 | O |
| — myLastBidAmount | Long | 내가 이 경매에 실제로 남긴 마지막 입찰 금액 (#141). 실제 입찰 이력이 없으면 `null` | X |

`minCapAmount`는 상한가 수정 바텀시트의 최소값으로 쓰이므로 **모든 응답에 포함**한다.

**`myLastBidAmount` (#141).** 로그인 사용자 본인의 입찰 기록(`bids` 행) 중 가장 최근 것의 금액이다. 한
사용자의 입찰 금액은 시간 순으로 항상 커지므로 "마지막 입찰 = 내 최고 입찰"이다(`/result`의 `myLastBidAmount`와 같은 기준).

- 직접 입찰과, 자동입찰 엔진이 내 상한 안에서 **실제로 넣은 입찰**(`bidType = AUTO`)을 모두 센다.
- 자동입찰을 예약·설정만 하고(`myAutoBidStatus`/`myCap`만 있음) 아직 입찰이 만들어지지 않았으면 `null`이다.
- `isMine`과 의미가 다르다 — `isMine`은 "지금 내가 최고 입찰자인가"이고, `myLastBidAmount`는 "내가 입찰한 적이
  있는가, 얼마였나"다. 밀린 입찰자는 `isMine = false`이면서 `myLastBidAmount`에 값이 있다.
- 이 API는 로그인 필수라 비로그인 응답은 없다(`401`).

`canBid`, `cannotBidReason`, `bidRestrictedUntil`은 경매 상세의 `myState`와 동일한 직접 입찰 가능성 규칙을 사용한다.

```
canBid = true  → cannotBidReason = null
canBid = false → cannotBidReason 필수

cannotBidReason:
AUCTION_NOT_STARTED
AUCTION_CLOSED
SELLER_CANNOT_BID
PENALTY_RESTRICTED
ALREADY_HIGHEST_BIDDER
```

`bidRestrictedUntil`은 `cannotBidReason = PENALTY_RESTRICTED`일 때만 값을 갖는다.

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "auctionId": 1,
    "status": "LIVE",
    "currentPrice": 105000,
    "minNextBidAmount": 110000,
    "bidIncrement": 5000,
    "highestBidderMasked": "mma****",
    "isMine": true,
    "canBid": false,
    "cannotBidReason": "ALREADY_HIGHEST_BIDDER",
    "bidRestrictedUntil": null,
    "endsAt": "2026-08-17T22:00:00+09:00",
    "serverTime": "2026-08-17T20:32:10+09:00",
    "extensionCount": 0,
    "maxExtensions": 3,
    "myAutoBidStatus": "ACTIVE",
    "myCap": 120000,
    "minCapAmount": 110000,
    "myLastBidAmount": 105000
  },
  "error": null
}
```

#### 200 Ok — CAP_REACHED

```json
{
  "success": true,
  "data": {
    "auctionId": 1,
    "status": "LIVE",
    "currentPrice": 125000,
    "minNextBidAmount": 130000,
    "bidIncrement": 5000,
    "highestBidderMasked": "ham****",
    "isMine": false,
    "canBid": true,
    "cannotBidReason": null,
    "bidRestrictedUntil": null,
    "endsAt": "2026-08-17T22:00:00+09:00",
    "serverTime": "2026-08-17T20:40:00+09:00",
    "extensionCount": 0,
    "maxExtensions": 3,
    "myAutoBidStatus": "CAP_REACHED",
    "myCap": 120000,
    "minCapAmount": 130000,
    "myLastBidAmount": 120000
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

#### 404 Not Found

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40401,
    "message": "존재하지 않는 경매입니다."
  }
}
```

---

# 3. 입찰 내역 O

```
GET /api/auctions/{auctionId}/bids
```

## API 상세 설명

성공한 유효 입찰만 공개한다. 닉네임 마스킹은 서버에서 수행한다.

비로그인 접근을 허용한다. Authorization이 있으면 `isMine` 등 개인화 필드를 채우고, 없으면 중립값을 반환한다.

## Request ✔️

### Path Variable

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| auctionId | Long | 경매 ID | O |

### Request Header

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | X |

```
Authorization: Bearer {accessToken}
```

### Request Parameter

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| page | Int | 페이지 번호 (기본 0) | X |
| size | Int | 페이지 크기 (기본 20) | X |
| order | String | latest / oldest (기본 latest) | X |

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
| — bids | Array<Object> | 입찰 목록 | O |
| —— bidId | Long | 입찰 ID | O |
| —— bidderMasked | String | 마스킹된 입찰자 닉네임 | O |
| —— isMine | Boolean | 내 입찰 여부 | O |
| —— amount | Long | 입찰가 | O |
| —— bidType | String | MANUAL / AUTO | O |
| —— bidAt | String | 입찰 시각 | O |
| —— isHighest | Boolean | 최고가 여부 | O |
| — page | Int | 현재 페이지 | O |
| — size | Int | 페이지 크기 | O |
| — hasNext | Boolean | 다음 페이지 존재 여부 | O |

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "bids": [
      {
        "bidId": 30,
        "bidderMasked": "ham****",
        "isMine": false,
        "amount": 105000,
        "bidType": "AUTO",
        "bidAt": "2026-08-17T20:32:00+09:00",
        "isHighest": true
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

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | false | O |
| data | Object | API 관련 데이터(여기선 무조건 null) | O |
| error | Object | API 관련 에러 | O |
| — code | Int | 커스텀 코드 (분기처리용 / 오류 표 참고) | O |
| — message | String | 에러 메시지 (개발자 확인용 / 오류 표 참고) | O |

#### 404 Not Found

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40401,
    "message": "존재하지 않는 경매입니다."
  }
}
```

---

# 4. AI 자동입찰 추천 O

```
GET /api/auctions/{auctionId}/auto-bid/recommendation
```

## API 상세 설명

자동입찰 상한가 설정 바텀시트의 초기값과 최소값을 제공한다.

## Request ✔️

### Path Variable (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| auctionId | Long | 경매 ID | O |

### Request Header (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | O |

```
Authorization: Bearer {accessToken}
```

### Request Parameter (0)

없음

### Request Body (0)

없음

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — auctionId | Long | 경매 ID | O |
| — aiRecommendedCap | Long | AI 추천 상한가 (stepper 초기값). 현재는 항상 `minCapAmount`와 같다. §1 `aiEstimatedPrice`(AI 적정 시세)와 다른 값이다 — §1 "AI 시세와 추천 상한의 차이" 참고 | O |
| — currentPrice | Long | 현재가 | O |
| — minCapAmount | Long | 최소 상한가 (stepper 하한) | O |
| — bidIncrement | Long | stepper 증감 단위 | O |

**`aiRecommendedCap`** **fallback/clamp 정책**

```
buyer(구매자)용 AutoBid 상한가 추천 소스가 있는 경우
→ aiRecommendedCap = max(buyerRecommendedCap, minCapAmount)

buyer용 추천 소스가 없는 경우 (#40 시점 기준 현재 상태)
→ aiRecommendedCap = minCapAmount
```

`Product`의 판매가 추천값(seller가 경매를 얼마에 시작할지 돕는 값, KREAM/eBay 유사거래 기반)은
seller-side listing recommendation이며 buyer가 자동입찰을 얼마까지 걸어야 하는지와는 다른 개념이므로
AutoBid 추천 소스로 재사용하지 않는다. buyer 전용 추천 소스가 아직 없으므로 이번 계약 확정 시점에는
`aiRecommendedCap`이 항상 `minCapAmount`와 같다. 향후 buyer-specific 추천값이 생기면 위 clamp 공식으로
확장한다 — `aiRecommendedCap`이 `minCapAmount` 미만으로 내려가는 응답은 만들지 않는다(stepper 초기값이
최소값보다 낮아지는 것을 막기 위함).

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "auctionId": 1,
    "aiRecommendedCap": 55000,
    "currentPrice": 50000,
    "minCapAmount": 55000,
    "bidIncrement": 5000
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

#### 404 Not Found

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40401,
    "message": "존재하지 않는 경매입니다."
  }
}
```

---

# 5. 자동입찰 등록 O

```
POST /api/auctions/{auctionId}/auto-bids
```

## API 상세 설명

자동입찰 상한가를 등록한다.

```
경매 시작 전 → RESERVED
경매 진행 중 → ACTIVE 또는 가격 결정 결과 CAP_REACHED
```

LIVE 중 등록 시 즉시 응찰이 발생할 수 있으며, 이때 `bidOccurred = true`로 내려온다.

`maxAmount`는 직접 입찰과 달리 **`bidIncrement` 배수 정렬을 요구하지 않는다.** 상한가는 입찰가가 아니라 천장이므로, 정렬되지 않은 값은 도달 가능한 마지막 입찰가까지만 자동 응찰하는 실효 상한으로 동작한다.

```
bidIncrement = 5,000, maxAmount = 121,000
→ 자동 응찰은 최대 120,000까지 발생 (실효 상한)
→ 40913 BID_NOT_ALIGNED 미적용
```

### 기존 AutoBid가 있는 경우

```
기존 상태 = RESERVED / ACTIVE / CAP_REACHED
→ 40908 AUTO_BID_ALREADY_EXISTS

기존 상태 = CANCELED
→ 새 자동입찰 등록 허용
```

`CANCELED`는 terminal 상태이므로 기존 설정을 다시 `RESERVED` 또는 `ACTIVE`로 되돌리지 않는다. 재등록 시 새로운 AutoBidSetting을 생성한다.

## Request ✔️

### Path Variable (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| auctionId | Long | 경매 ID | O |

### Request Header (3)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Content-Type | String | application/json | O |
| Authorization | String | Bearer {accessToken} | O |
| Idempotency-Key | String | UUID. 동일 논리 요청 retry 시 재사용 | O |

```
Content-Type: application/json
Authorization: Bearer {accessToken}
Idempotency-Key: {uuid}
```

### Request Parameter (0)

없음

### Request Body (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| maxAmount | Long | 자동입찰 상한가. `maxAmount >= minCapAmount` | O |

```json
{
  "maxAmount": 120000
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
| — autoBidSettingId | Long | 자동입찰 설정 ID | O |
| — auctionId | Long | 경매 ID | O |
| — status | String | RESERVED / ACTIVE / CAP_REACHED | O |
| — maxAmount | Long | 등록된 상한가 | O |
| — currentPrice | Long | 처리 후 현재가 | O |
| — minNextBidAmount | Long | 처리 후 최소 다음 입찰가 | O |
| — minCapAmount | Long | 처리 후 최소 상한가 | O |
| — startsAt | String | 경매 시작 시각 | O |
| — bidOccurred | Boolean | 등록 즉시 응찰 발생 여부 | O |
| — resultingBidAmount | Long | 발생한 입찰가 (없으면 null) | X |
| — isHighestBidder | Boolean | 처리 후 내가 최고 입찰자인지 | X |

#### 201 Created — 경매 시작 전 예약

```json
{
  "success": true,
  "data": {
    "autoBidSettingId": 15,
    "auctionId": 1,
    "status": "RESERVED",
    "maxAmount": 120000,
    "currentPrice": 50000,
    "minNextBidAmount": 55000,
    "minCapAmount": 55000,
    "startsAt": "2026-08-17T20:00:00+09:00",
    "bidOccurred": false,
    "resultingBidAmount": null,
    "isHighestBidder": false
  },
  "error": null
}
```

#### 201 Created — LIVE 중 즉시 응찰

```json
{
  "success": true,
  "data": {
    "autoBidSettingId": 15,
    "auctionId": 1,
    "status": "ACTIVE",
    "maxAmount": 120000,
    "currentPrice": 105000,
    "minNextBidAmount": 110000,
    "minCapAmount": 110000,
    "startsAt": "2026-08-17T20:00:00+09:00",
    "bidOccurred": true,
    "resultingBidAmount": 105000,
    "isHighestBidder": true
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

#### 403 Forbidden

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40301,
    "message": "본인이 등록한 경매에는 입찰할 수 없습니다."
  }
}
```

#### 409 Conflict

발생 가능: `40906 CAP_TOO_LOW`, `40908 AUTO_BID_ALREADY_EXISTS`, `40903 AUCTION_CLOSED`, `40905 IDEMPOTENCY_PAYLOAD_MISMATCH`, `40909 CONCURRENT_CONFLICT`

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40906,
    "message": "자동입찰 상한가가 너무 낮습니다."
  }
}
```

---

# 6. 내 자동입찰 조회 O

```
GET /api/auctions/{auctionId}/auto-bids/me
```

## API 상세 설명

현재 경매에 등록한 내 자동입찰 설정을 조회한다. 상한가 수정 바텀시트 진입 시 사용한다.

## Request ✔️

### Path Variable (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| auctionId | Long | 경매 ID | O |

### Request Header (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | O |

```
Authorization: Bearer {accessToken}
```

### Request Parameter (0)

없음

### Request Body (0)

없음

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — autoBidSettingId | Long | 자동입찰 설정 ID | O |
| — auctionId | Long | 경매 ID | O |
| — status | String | RESERVED / ACTIVE / CAP_REACHED (CANCELED 설정은 이 조회에 포함되지 않는다 — 활성 slot만 조회) | O |
| — maxAmount | Long | 현재 상한가 | O |
| — currentPrice | Long | 현재가 | O |
| — minCapAmount | Long | 최소 상한가 | O |
| — startsAt | String | 경매 시작 시각 | O |
| — serverTime | String | 서버 기준 현재 시각 (시작까지 countdown 계산용) | O |
| — canModify | Boolean | 상한가 수정 가능 여부 (현재 구현은 항상 true. 단 `managedByPurchaseAgent=true`면 실제 수정은 `40917`로 거부된다) | O |
| — canCancel | Boolean | 예약 취소/중단 가능 여부 (현재 구현은 항상 true. 단 `managedByPurchaseAgent=true`면 실제 취소는 `40917`로 거부된다) | O |
| — purchaseGoalId | Long | 이 설정에 연결된 구매 목표(Purchase Agent) ID. 없으면 null | X |
| — managedByPurchaseAgent | Boolean | 이 경매를 내 Purchase Agent가 관리 중인지 여부 | O |

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "autoBidSettingId": 15,
    "auctionId": 1,
    "status": "RESERVED",
    "maxAmount": 120000,
    "currentPrice": 50000,
    "minCapAmount": 55000,
    "startsAt": "2026-08-17T20:00:00+09:00",
    "serverTime": "2026-08-17T17:00:00+09:00",
    "canModify": true,
    "canCancel": true,
    "purchaseGoalId": null,
    "managedByPurchaseAgent": false
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

#### 404 Not Found

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40404,
    "message": "등록된 자동입찰이 없습니다."
  }
}
```

---

# 7. 자동입찰 상한가 수정 O

```
PATCH /api/auctions/{auctionId}/auto-bids/me
```

## API 상세 설명

단순 DB update가 아니라 **cap 변경 → 가격 결정 → 상태 재판정**을 하나의 처리 흐름으로 수행한다.

검증 규칙은 상태에 따라 다르다.

**경매 시작 전 (`RESERVED`)**

```
newMaxAmount >= minCapAmount

상향 허용 / 하향 허용 / 동일값 허용
```

아직 입찰이 발생하지 않은 예약값이므로 자유롭게 수정한다. 상한가 수정 바텀시트의 `-` 버튼은 이 상태에서 의미를 갖는다.

**경매 진행 중 (`ACTIVE`** **/** **`CAP_REACHED`)**

```
newMaxAmount > 기존 maxAmount
newMaxAmount >= minCapAmount

상향만 허용
```

`40907 CAP_NOT_INCREASED`는 이 두 상태에서만 발생한다.

`maxAmount`의 배수 정렬 규칙은 등록(§5)과 동일하다 — 정렬을 요구하지 않으며 실효 상한으로 동작한다.

## Request ✔️

### Path Variable (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| auctionId | Long | 경매 ID | O |

### Request Header (3)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Content-Type | String | application/json | O |
| Authorization | String | Bearer {accessToken} | O |
| Idempotency-Key | String | UUID. 동일 논리 요청 retry 시 재사용 | O |

```
Content-Type: application/json
Authorization: Bearer {accessToken}
Idempotency-Key: {uuid}
```

### Request Parameter (0)

없음

### Request Body (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| maxAmount | Long | 새 상한가 | O |

```json
{
  "maxAmount": 140000
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
| — autoBidSettingId | Long | 자동입찰 설정 ID | O |
| — status | String | RESERVED / ACTIVE / CAP_REACHED | O |
| — maxAmount | Long | 변경된 상한가 | O |
| — currentPrice | Long | 처리 후 현재가 | O |
| — minCapAmount | Long | 처리 후 최소 상한가 | O |
| — bidOccurred | Boolean | 수정 직후 응찰 발생 여부 | O |
| — resultingBidAmount | Long | 발생한 입찰가 (없으면 null) | X |
| — isHighestBidder | Boolean | 처리 후 내가 최고 입찰자인지 | O |

#### 200 Ok — 응찰 없음

```json
{
  "success": true,
  "data": {
    "autoBidSettingId": 15,
    "status": "ACTIVE",
    "maxAmount": 140000,
    "currentPrice": 125000,
    "minCapAmount": 130000,
    "bidOccurred": false,
    "resultingBidAmount": null,
    "isHighestBidder": false
  },
  "error": null
}
```

#### 200 Ok — 수정 직후 가격 결정 발생

```json
{
  "success": true,
  "data": {
    "autoBidSettingId": 15,
    "status": "ACTIVE",
    "maxAmount": 140000,
    "currentPrice": 130000,
    "minCapAmount": 135000,
    "bidOccurred": true,
    "resultingBidAmount": 130000,
    "isHighestBidder": true
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

#### 409 Conflict

발생 가능: `40907 CAP_NOT_INCREASED`, `40906 CAP_TOO_LOW`, `40903 AUCTION_CLOSED`, `40905 IDEMPOTENCY_PAYLOAD_MISMATCH`, `40909 CONCURRENT_CONFLICT`, `40917 AGENT_MANAGED_AUCTION`(Purchase Agent가 관리 중인 설정은 사용자가 직접 수정할 수 없다 — §Purchase Agent 관리 중 제한)

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40907,
    "message": "상한가는 현재 설정값보다 높아야 합니다."
  }
}
```

#### 404 Not Found

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40404,
    "message": "등록된 자동입찰이 없습니다."
  }
}
```

---

# 8. 자동입찰 중단 / 예약 취소 O

```
DELETE /api/auctions/{auctionId}/auto-bids/me
```

## API 상세 설명

row를 삭제하지 않고 상태를 `CANCELED`로 변경한다. **기존에 발생한 Bid는 삭제하지 않는다.**

## Request ✔️

### Path Variable (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| auctionId | Long | 경매 ID | O |

### Request Header (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | O |

```
Authorization: Bearer {accessToken}
```

### Request Parameter (0)

없음

### Request Body (0)

없음

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — autoBidSettingId | Long | 자동입찰 설정 ID | O |
| — status | String | CANCELED 고정 | O |
| — canceledAt | String | 취소 시각 | O |

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "autoBidSettingId": 15,
    "status": "CANCELED",
    "canceledAt": "2026-08-17T20:35:00+09:00"
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

#### 404 Not Found

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40404,
    "message": "등록된 자동입찰이 없습니다."
  }
}
```

#### 409 Conflict

발생 가능: `40917 AGENT_MANAGED_AUCTION`(Purchase Agent가 관리 중인 설정은 사용자가 직접 취소할 수 없다 — §Purchase Agent 관리 중 제한)

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40917,
    "message": "Purchase Agent가 관리 중인 경매입니다. auctionId: 1"
  }
}
```

---

# 9. 직접 입찰 O

```
POST /api/auctions/{auctionId}/bids
```

## API 상세 설명

제출 금액(`submittedAmount`)과 처리 후 최종 현재가(`currentPrice`)를 분리해 반환한다.

**Validation**

```
Auction = LIVE
seller != bidder
bidder가 제재 중이 아님
amount >= minNextBidAmount
(amount - currentPrice) % bidIncrement == 0
현재 최고 입찰자가 아님
```

금액은 최소 다음 입찰가 이상이면서 **현재가로부터** **`bidIncrement`의 배수**여야 한다. 프론트 stepper는 `minNextBidAmount`를 시작값으로 `bidIncrement` 단위로만 증감시켜야 하며, 직접 입력을 허용하는 경우 전송 전에 동일 규칙으로 검증한다.

```
currentPrice     = 105000
bidIncrement     = 5000
minNextBidAmount = 110000

110000 → OK
115000 → OK
112000 → 40913 BID_NOT_ALIGNED
108000 → 40904 BID_AMOUNT_TOO_LOW
```

자동입찰을 사용 중인 사용자가 직접 입찰하면 해당 AutoBidSetting은 `CANCELED` 처리된다(`autoBidCanceled = true`). 이후 다시 자동입찰을 등록하는 것은 가능하다.

> **프론트 요구사항** `myState.autoBidStatus`가 `ACTIVE` 또는 `CAP_REACHED`인 사용자가 직접 입찰 바텀시트를 열면 “직접 입찰하면 현재 자동입찰이 중단됩니다.” 안내를 반드시 노출한다.
>

종료 연장(§0.13 참고)은 사용자 요청 1회 기준으로 적용하며, Proxy 연쇄 자체는 추가 연장을
발생시키지 않는다. Manual Bid 성공은 항상 실제 Bid를 만들어내므로 종료 1분 이내면 +3분
연장(최대 3회)된다.

## Request ✔️

### Path Variable (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| auctionId | Long | 경매 ID | O |

### Request Header (3)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Content-Type | String | application/json | O |
| Authorization | String | Bearer {accessToken} | O |
| Idempotency-Key | String | UUID. 동일 논리 요청 retry 시 재사용 | O |

```
Content-Type: application/json
Authorization: Bearer {accessToken}
Idempotency-Key: {uuid}
```

### Request Parameter (0)

없음

### Request Body (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| amount | Long | 입찰 금액. `amount >= minNextBidAmount` 이고 `(amount - currentPrice) % bidIncrement == 0` | O |

```json
{
  "amount": 120000
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
| — bidId | Long | 입찰 ID | O |
| — submittedAmount | Long | 사용자가 제출한 금액 | O |
| — currentPrice | Long | 처리 후 최종 현재가 | O |
| — minNextBidAmount | Long | 처리 후 최소 다음 입찰가 | O |
| — highestBidderMasked | String | 처리 후 최고 입찰자 (마스킹) | O |
| — isHighestBidder | Boolean | 처리 후 내가 최고 입찰자인지 | O |
| — autoBidCanceled | Boolean | 내 자동입찰이 취소되었는지 | O |
| — proxyResponded | Boolean | 타 사용자 자동입찰이 즉시 반격했는지 | O |
| — endsAt | String | 처리 후 종료 시각 (연장 반영) | O |
| — extensionCount | Int | 종료 연장 횟수 | O |

#### 201 Created — 내가 최고가 유지

```json
{
  "success": true,
  "data": {
    "bidId": 31,
    "submittedAmount": 120000,
    "currentPrice": 120000,
    "minNextBidAmount": 125000,
    "highestBidderMasked": "mma****",
    "isHighestBidder": true,
    "autoBidCanceled": false,
    "proxyResponded": false,
    "endsAt": "2026-08-17T22:00:00+09:00",
    "extensionCount": 0
  },
  "error": null
}
```

#### 201 Created — Proxy Bidding 즉시 반격

```json
{
  "success": true,
  "data": {
    "bidId": 31,
    "submittedAmount": 120000,
    "currentPrice": 125000,
    "minNextBidAmount": 130000,
    "highestBidderMasked": "ham****",
    "isHighestBidder": false,
    "autoBidCanceled": false,
    "proxyResponded": true,
    "endsAt": "2026-08-17T22:03:00+09:00",
    "extensionCount": 1
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

#### 403 Forbidden

발생 가능: `40301 SELLER_CANNOT_BID`, `40302 PENALTY_RESTRICTED`

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40302,
    "message": "경매 참여가 제한된 상태입니다."
  }
}
```

#### 409 Conflict

발생 가능: `40901 ALREADY_HIGHEST_BIDDER`, `40902 AUCTION_NOT_STARTED`, `40903 AUCTION_CLOSED`, `40904 BID_AMOUNT_TOO_LOW`, `40913 BID_NOT_ALIGNED`, `40905 IDEMPOTENCY_PAYLOAD_MISMATCH`, `40909 CONCURRENT_CONFLICT`, `40917 AGENT_MANAGED_AUCTION`(내 자동입찰이 Purchase Agent 관리 중인 경매면 직접 입찰이 차단된다 — §Purchase Agent 관리 중 제한)

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40904,
    "message": "이미 더 높은 입찰가가 있습니다."
  }
}
```

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40913,
    "message": "입찰 단위에 맞지 않는 금액입니다."
  }
}
```

> `40904` 수신 시 프론트는 `GET /api/auctions/{auctionId}/live`를 재호출해 최신 `currentPrice`, `minNextBidAmount`를 반영한다. `40913` 수신 시에는 재호출 없이 입력값을 `bidIncrement` 배수로 보정해 다시 제출한다.
>

---

# 10. 경매 결과 O

```
GET /api/auctions/{auctionId}/result
```

## API 상세 설명

낙찰/패찰/차순위 결과를 반환한다.

페널티 정보(`noShowCount`, `bidRestrictedUntil`)는 이 API에 포함하지 않는다. `GET /api/me/penalties`를 single source of truth로 사용한다. 결제 기한 만료 화면은 **`/result`** **+** **`/me/penalties`** **2회 호출**로 구성한다.

**Result enum**

```
NO_BIDS
WON
LOST
BACKUP_WAITING
FORFEITED
PAYMENT_EXPIRED
```

`BACKUP_WAITING`은 실제 차순위 구매 제안이 생성된 이후에만 사용한다.

## Request ✔️

### Path Variable (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| auctionId | Long | 경매 ID | O |

### Request Header (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | O |

```
Authorization: Bearer {accessToken}
```

### Request Parameter (0)

없음

### Request Body (0)

없음

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — auctionId | Long | 경매 ID | O |
| — result | String | Result enum | O |
| — product | Object | 상품 요약 | O |
| —— productId | Long | 상품 ID | O |
| —— name | String | 상품명 | O |
| —— subName | String | 모델명(§1 "상품 표시명 규칙") | O |
| —— imageUrl | String | 대표 이미지 | O |
| — rank | Int | 내 순위 | X |
| — finalPrice | Long | 최종 낙찰가 | X |
| — myLastBidAmount | Long | 내 마지막 입찰가 | X |
| — shippingFee | Long | 배송비 (WON일 때) | X |
| — totalAmount | Long | 최종 결제 예정 금액 (WON일 때) | X |
| — paymentDeadline | String | 결제 기한 (WON일 때) | X |
| — serverTime | String | 서버 기준 현재 시각 | O |
| — orderId | Long | 주문 ID (WON일 때만) | X |
| — backupOfferId | Long | 차순위 제안 ID (BACKUP_WAITING일 때만) | X |
| — backupEligible | Boolean | 차순위 후보 자격 여부 | O |
| — orderStatus | String | **조회자 본인**의 이 경매 주문 상태: `PAYMENT_PENDING` / `PAID` / `PAYMENT_EXPIRED` / `CANCELED`. 본인 주문이 없으면 `null` (#141) | X |

`orderId`는 `result = WON`일 때만, `backupOfferId`는 `result = BACKUP_WAITING`일 때만 값을 갖는다. 프론트는 이 두 값으로 결제 화면과 차순위 구매 기회 화면에 진입한다.

**`orderStatus` (#141 추가).** 서버는 `(auctionId, 로그인 사용자)`로 본인 주문만 조회하므로 다른 사용자의
주문 상태·ID는 이 필드로 노출되지 않는다(낙찰자가 아닌 사용자는 낙찰자가 결제했더라도 `null`). 원 낙찰
주문과 차순위 수락 주문을 구분하지 않는다(Order에 구분 정보가 없다). `result` 판정은 바뀌지 않았다 —
`result = WON`은 여전히 `PAYMENT_PENDING`과 `PAID`를 모두 포함하므로(결제 완료자도 낙찰자다), 결제
여부는 `orderStatus`로 구분한다.

| result | orderStatus | orderId | FE 처리 예시 |
| --- | --- | --- | --- |
| `WON` | `PAYMENT_PENDING` | 값 있음 | "결제하기" 버튼 → `GET /api/orders/{orderId}`. `paymentDeadline`(+`serverTime`)으로 남은 시간 표시 |
| `WON` | `PAID` | 값 있음 | "결제 완료" 표시. 결제 버튼 숨김 |
| `PAYMENT_EXPIRED` | `PAYMENT_EXPIRED` | `null` | "결제 기한 만료" 표시, 이어서 `GET /api/me/penalties` |
| `FORFEITED` | `CANCELED` | `null` | "낙찰 포기" 표시 |
| `NO_BIDS` / `LOST` / `BACKUP_WAITING` | `null`(본인 주문 없음) | `null` | 결제 버튼 없음. `BACKUP_WAITING`은 `backupOfferId`로 차순위 화면 진입 |

결제 버튼은 `result = WON && orderStatus = PAYMENT_PENDING`일 때만 표시한다. 기한 경과 여부는 서버
스케줄러가 `PAYMENT_EXPIRED`로 전이시키기 전까지 `PAYMENT_PENDING`으로 보일 수 있으므로,
`paymentDeadline` < `serverTime`이면 버튼을 비활성화하는 것을 권장한다(결제 API는 만료 주문에
`40910`을 반환한다).

#### 200 Ok — 낙찰

```json
{
  "success": true,
  "data": {
    "auctionId": 1,
    "result": "WON",
    "product": {
      "productId": 10,
      "name": "ASICS Novablast 6 Black",
      "subName": "Novablast 6",
      "imageUrl": "https://..."
    },
    "rank": 1,
    "finalPrice": 105000,
    "myLastBidAmount": 105000,
    "shippingFee": 3000,
    "totalAmount": 108000,
    "paymentDeadline": "2026-08-18T22:00:00+09:00",
    "serverTime": "2026-08-17T22:00:10+09:00",
    "orderId": 50,
    "backupOfferId": null,
    "backupEligible": false,
    "orderStatus": "PAYMENT_PENDING"
  },
  "error": null
}
```

#### 200 Ok — 패찰 / 차순위 후보

```json
{
  "success": true,
  "data": {
    "auctionId": 1,
    "result": "LOST",
    "product": {
      "productId": 10,
      "name": "ASICS Novablast 6 Black",
      "subName": "Novablast 6",
      "imageUrl": "https://..."
    },
    "rank": 2,
    "finalPrice": 105000,
    "myLastBidAmount": 100000,
    "shippingFee": null,
    "totalAmount": null,
    "paymentDeadline": null,
    "serverTime": "2026-08-17T22:00:10+09:00",
    "orderId": null,
    "backupOfferId": null,
    "backupEligible": true,
    "orderStatus": null
  },
  "error": null
}
```

#### 200 Ok — 차순위 제안 생성됨

```json
{
  "success": true,
  "data": {
    "auctionId": 1,
    "result": "BACKUP_WAITING",
    "product": {
      "productId": 10,
      "name": "ASICS Novablast 6 Black",
      "subName": "Novablast 6",
      "imageUrl": "https://..."
    },
    "rank": 2,
    "finalPrice": 105000,
    "myLastBidAmount": 100000,
    "serverTime": "2026-08-18T22:05:00+09:00",
    "orderId": null,
    "backupOfferId": 90,
    "backupEligible": true,
    "orderStatus": null
  },
  "error": null
}
```

#### 200 Ok — 결제 기한 만료

```json
{
  "success": true,
  "data": {
    "auctionId": 1,
    "result": "PAYMENT_EXPIRED",
    "product": {
      "productId": 10,
      "name": "ASICS Novablast 6 Black",
      "subName": "Novablast 6",
      "imageUrl": "https://..."
    },
    "rank": 1,
    "finalPrice": 105000,
    "myLastBidAmount": 105000,
    "shippingFee": null,
    "totalAmount": null,
    "paymentDeadline": null,
    "serverTime": "2026-08-18T22:01:00+09:00",
    "orderId": null,
    "backupOfferId": null,
    "backupEligible": false,
    "orderStatus": "PAYMENT_EXPIRED"
  },
  "error": null
}
```

결제 기한 만료에 따른 `noShowCount`, `bidRestrictedUntil`은 이 응답에 포함하지 않는다. 화면은 이어서 `GET /api/me/penalties`를 호출한다.

### Failure ❌

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | false | O |
| data | Object | API 관련 데이터(여기선 무조건 null) | O |
| error | Object | API 관련 에러 | O |
| — code | Int | 커스텀 코드 (분기처리용 / 오류 표 참고) | O |
| — message | String | 에러 메시지 (개발자 확인용 / 오류 표 참고) | O |

#### 404 Not Found

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40401,
    "message": "존재하지 않는 경매입니다."
  }
}
```

---

# 11. 낙찰 포기 O

```
POST /api/auctions/{auctionId}/award/forfeit
```

## API 상세 설명

낙찰자가 구매를 포기한다. 페널티 결과는 응답에 포함하지 않으며, 프론트는 이어서 `GET /api/me/penalties`를 호출한다.

이후 서버가 차순위 구매 제안을 생성할 수 있다.

**Order 전이**

포기 시점에 해당 주문이 `PAYMENT_PENDING`이면 **`CANCELED`로 전이**하고, 결제 기한 scheduler 대상에서 제외한다.
따라서 포기 이후 `PAYMENT_EXPIRED` 전이는 발생하지 않으며, 페널티는 `FORFEITED` 1건만 기록되고 차순위 BackupOffer도 포기 시점에 1회만 생성된다.

```
forfeit
→ AuctionResult(나) = FORFEITED
→ Order.status      = CANCELED   (scheduler 제외)
→ penalty FORFEITED 1건
→ BackupOffer 생성 (1회)
```

이미 `PAID`인 주문은 포기할 수 없다 → `409 / 40914 ALREADY_PAID`. v1은 환불을 구현하지 않는다.

## Request ✔️

### Path Variable (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| auctionId | Long | 경매 ID | O |

### Request Header (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | O |

```
Authorization: Bearer {accessToken}
```

### Request Parameter (0)

없음

### Request Body (0)

없음

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — auctionId | Long | 경매 ID | O |
| — result | String | FORFEITED 고정 | O |

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "auctionId": 1,
    "result": "FORFEITED"
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

#### 403 Forbidden

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40303,
    "message": "낙찰자가 아닙니다."
  }
}
```

#### 409 Conflict

발생 가능: `40910 PAYMENT_EXPIRED`(이미 만료 처리됨), `40914 ALREADY_PAID`(결제 완료 후 포기 시도)

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40914,
    "message": "이미 결제가 완료된 주문입니다."
  }
}
```

---

# 12. 결제 화면 조회 O

```
GET /api/orders/{orderId}
```

## API 상세 설명

결제 예정 금액과 기한 표시용이다. 실제 PG 결제 API는 구현하지 않는다.

최초 낙찰자와 차순위 수락자 모두 같은 주문 화면을 사용한다. 따라서 주문의 상품 금액은 원 경매의 `finalPrice`가 아니라 **`purchasePrice`**로 통일한다.

```
최초 낙찰자 주문   → purchasePrice = 원 경매 finalPrice
차순위 수락자 주문 → purchasePrice = 해당 차순위 구매 가능 금액
```

**Order status**

```
PAYMENT_PENDING
PAID
PAYMENT_EXPIRED
CANCELED
```

**상태 전이**

```
PAYMENT_PENDING → PAID             (POST /pay)
PAYMENT_PENDING → PAYMENT_EXPIRED  (scheduler, 기한 초과)
PAYMENT_PENDING → CANCELED         (낙찰 포기)
```

`PAID` / `PAYMENT_EXPIRED` / `CANCELED`는 terminal이다. scheduler는 `PAYMENT_PENDING`만 처리한다.

## Request ✔️

### Path Variable (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| orderId | Long | 주문 ID | O |

### Request Header (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | O |

```
Authorization: Bearer {accessToken}
```

### Request Parameter (0)

없음

### Request Body (0)

없음

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — orderId | Long | 주문 ID | O |
| — auctionId | Long | 경매 ID | O |
| — status | String | PAYMENT_PENDING / PAID / PAYMENT_EXPIRED / CANCELED | O |
| — product | Object | 상품 요약 | O |
| —— productId | Long | 상품 ID | O |
| —— name | String | 상품명 | O |
| —— subName | String | 모델명(§1 "상품 표시명 규칙") | O |
| —— imageUrl | String | 대표 이미지 | O |
| — purchasePrice | Long | 주문 기준 상품 금액 | O |
| — shippingFee | Long | 배송비 | O |
| — totalAmount | Long | 최종 결제 금액 | O |
| — paymentDeadline | String | 결제 기한 | O |
| — serverTime | String | 서버 기준 현재 시각 | O |
| — paidAt | String | 결제 완료 시각 (미결제면 null) | X |
| — orderType | String | 주문 유형 (#141): `WINNER`(최초 낙찰) / `BACKUP_ACCEPT`(차순위 수락). **`null` = 유형 미상** — 이 필드 도입 전에 만들어졌고 근거로 유형을 확정하지 못한 기존 주문 | X |

**`orderType` (#141).** 신규 주문은 항상 값이 있다 — 경매 종료 정산(`Order.createForWinner`)이면 `WINNER`,
차순위 제안 수락(§16, `Order.createForBackupAccept`)이면 `BACKUP_ACCEPT`. 주문 금액·결제·기한 규칙은 유형과
무관하게 위와 같다(유형은 표시 구분용).

기존 주문은 배포 후 보정 SQL(`backend/scripts/migrations/141-order-type-condition-description.sql`)로 근거가
있는 행만 채운다. 같은 (경매, 구매자)에 `ACCEPTED` 차순위 제안이 있으면 `BACKUP_ACCEPT`, 구매자가 그 경매의
최종 낙찰자이고 그 구매자에게 발행된 차순위 제안이 없으면 `WINNER`다. 나머지는 `null`로 남긴다(일괄 기본값 없음).
FE는 `null`이면 유형 문구를 표시하지 않는다.

#### 200 Ok — 결제 전

```json
{
  "success": true,
  "data": {
    "orderId": 50,
    "auctionId": 1,
    "status": "PAYMENT_PENDING",
    "product": {
      "productId": 10,
      "name": "ASICS Novablast 6 Black",
      "subName": "Novablast 6",
      "imageUrl": "https://..."
    },
    "purchasePrice": 105000,
    "shippingFee": 3000,
    "totalAmount": 108000,
    "paymentDeadline": "2026-08-18T22:00:00+09:00",
    "serverTime": "2026-08-18T20:12:00+09:00",
    "paidAt": null,
    "orderType": "WINNER"
  },
  "error": null
}
```

#### 200 Ok — 결제 완료

```json
{
  "success": true,
  "data": {
    "orderId": 50,
    "auctionId": 1,
    "status": "PAID",
    "product": {
      "productId": 10,
      "name": "ASICS Novablast 6 Black",
      "subName": "Novablast 6",
      "imageUrl": "https://..."
    },
    "purchasePrice": 105000,
    "shippingFee": 3000,
    "totalAmount": 108000,
    "paymentDeadline": "2026-08-18T22:00:00+09:00",
    "serverTime": "2026-08-18T20:14:00+09:00",
    "paidAt": "2026-08-18T20:13:00+09:00",
    "orderType": "WINNER"
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

#### 403 Forbidden

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40304,
    "message": "접근 권한이 없는 주문입니다."
  }
}
```

#### 404 Not Found

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40402,
    "message": "존재하지 않는 주문입니다."
  }
}
```

---

# 13. Mock 결제 O

```
POST /api/orders/{orderId}/pay
```

## API 상세 설명

실제 PG 연동, 결제 수단 선택, 환불, 웹훅은 **v1 범위에서 구현하지 않는다.** 서버가 실제로 변경하는 것은 Order의 결제 상태뿐이다.

```
Auction.status = ENDED   // 변경 없음
Order.status   = PAID    // 변경
```

Product에 별도의 `SOLD` 상태는 두지 않는다.

**동작**

```
PAYMENT_PENDING → PAID, paidAt 기록, 200
PAID (재호출)    → 상태 변경 없음, 기존 paidAt 반환, 200
PAYMENT_EXPIRED → 409 / 40910
CANCELED        → 409 / 40915  (낙찰 포기로 취소된 주문)
```

`Idempotency-Key`를 요구하지 않는다. 엔드포인트 자체가 멱등이다.

**개발/시연용 데이터**

`PAYMENT_EXPIRED` → 페널티 → 차순위 제안 흐름을 시연하기 위해 local/dev seed에 아래 주문을 미리 넣어둔다. 강제 만료용 production API는 만들지 않는다.

```
paymentDeadline = 현재시각 - 1분
status          = PAYMENT_PENDING
```

## Request ✔️

### Path Variable (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| orderId | Long | 주문 ID | O |

### Request Header (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | O |

```
Authorization: Bearer {accessToken}
```

### Request Parameter (0)

없음

### Request Body (0)

없음

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — orderId | Long | 주문 ID | O |
| — status | String | PAID 고정 | O |
| — paidAt | String | 결제 완료 시각 | O |

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "orderId": 50,
    "status": "PAID",
    "paidAt": "2026-08-18T20:13:00+09:00"
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

#### 403 Forbidden

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40304,
    "message": "접근 권한이 없는 주문입니다."
  }
}
```

#### 409 Conflict

발생 가능: `40910 PAYMENT_EXPIRED`, `40915 ORDER_CANCELED`

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40910,
    "message": "결제 기한이 만료되었습니다."
  }
}
```

---

# 14. 내 페널티 O

```
GET /api/me/penalties
```

## API 상세 설명

`noShowCount`, `bidRestricted`, `bidRestrictedUntil`의 single source of truth.

제재 기간의 값은 서버가 내려주는 **`bidRestrictedUntil`이 유일한 기준**이다.
회차별 제재 기간 정책은 서버 설정으로 관리하며 이 계약에 고정하지 않는다.
화면의 기간 표기는 `bidRestrictedUntil`(해제 시각)을 그대로 표기하거나, 최신 penalty의 `createdAt`과의 차로 계산해 표시한다. “N일간” 같은 고정 문구를 프론트에 하드코딩하지 않는다.

## Request ✔️

### Path Variable (0)

없음

### Request Header (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | O |

```
Authorization: Bearer {accessToken}
```

### Request Parameter (0)

없음

### Request Body (0)

없음

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — noShowCount | Int | 누적 노쇼 횟수 | O |
| — bidRestricted | Boolean | 현재 입찰 제한 여부 | O |
| — bidRestrictedUntil | String | 제한 해제 시각 (제한 없으면 null) | X |
| — serverTime | String | 서버 기준 현재 시각 | O |
| — penalties | Array<Object> | 페널티 이력 | O |
| —— penaltyId | Long | 페널티 ID | O |
| —— type | String | FORFEITED / PAYMENT_EXPIRED | O |
| —— auctionId | Long | 대상 경매 ID | O |
| —— createdAt | String | 발생 시각 | O |

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "noShowCount": 2,
    "bidRestricted": true,
    "bidRestrictedUntil": "2026-08-24T00:00:00+09:00",
    "serverTime": "2026-08-18T22:01:00+09:00",
    "penalties": [
      {
        "penaltyId": 3,
        "type": "PAYMENT_EXPIRED",
        "auctionId": 1,
        "createdAt": "2026-08-18T22:00:00+09:00"
      }
    ]
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

# 15. 차순위 구매 제안 조회 O

```
GET /api/backup-offers/{backupOfferId}
```

## API 상세 설명

`backupOfferId`는 `GET /api/auctions/{auctionId}/result`의 `backupOfferId`로 획득한다.

`deadline`은 제안 생성 시각 + 24시간이다. 프론트는 `serverTime` 기준으로 잔여 시간을 표시한다.

**Status**

```
WAITING
ACCEPTED
DECLINED
EXPIRED
```

## Request ✔️

### Path Variable (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| backupOfferId | Long | 차순위 제안 ID | O |

### Request Header (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | O |

```
Authorization: Bearer {accessToken}
```

### Request Parameter (0)

없음

### Request Body (0)

없음

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — backupOfferId | Long | 차순위 제안 ID | O |
| — auctionId | Long | 경매 ID | O |
| — status | String | WAITING / ACCEPTED / DECLINED / EXPIRED | O |
| — product | Object | 상품 요약 | O |
| —— productId | Long | 상품 ID | O |
| —— name | String | 상품명 | O |
| —— subName | String | 모델명(§1 "상품 표시명 규칙") | O |
| —— imageUrl | String | 대표 이미지 | O |
| — purchasePrice | Long | 구매 가능 금액 = 내 마지막 입찰가(`myLastBidAmount`) | O |
| — shippingFee | Long | 배송비 | O |
| — totalAmount | Long | 최종 결제 금액 | O |
| — deadline | String | 수락/거절 기한 | O |
| — serverTime | String | 서버 기준 현재 시각 | O |

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "backupOfferId": 90,
    "auctionId": 1,
    "status": "WAITING",
    "product": {
      "productId": 10,
      "name": "ASICS Novablast 6 Black",
      "subName": "Novablast 6",
      "imageUrl": "https://..."
    },
    "purchasePrice": 100000,
    "shippingFee": 3000,
    "totalAmount": 103000,
    "deadline": "2026-08-19T22:00:00+09:00",
    "serverTime": "2026-08-18T22:05:00+09:00"
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

#### 403 Forbidden

발생 가능: `40305 BACKUP_OFFER_ACCESS_DENIED`(#75, 본인 명의의 제안이 아님)

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40305,
    "message": "본인 명의의 차순위 제안이 아닙니다."
  }
}
```

#### 404 Not Found

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40403,
    "message": "존재하지 않는 차순위 제안입니다."
  }
}
```

---

# 16. 차순위 구매 수락 O

```
POST /api/backup-offers/{backupOfferId}/accept
```

## API 상세 설명

차순위 구매를 수락하고 주문을 생성한다.

`paymentDeadline`은 **수락 시각 + 24시간**이다. 원 경매의 `endsAt`, 제안의 `deadline`과 모두 무관하다.

아래 예시는 `2026-08-18T22:30`에 수락한 경우다.

## Request ✔️

### Path Variable (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| backupOfferId | Long | 차순위 제안 ID | O |

### Request Header (3)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | O |
| Idempotency-Key | String | UUID. 동일 논리 요청 retry 시 재사용 | O |

```
Authorization: Bearer {accessToken}
Idempotency-Key: {uuid}
```

### Request Parameter (0)

없음

### Request Body (0)

없음

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — backupOfferId | Long | 차순위 제안 ID | O |
| — status | String | ACCEPTED 고정 | O |
| — orderId | Long | 생성된 주문 ID | O |
| — totalAmount | Long | 최종 결제 금액 | O |
| — paymentDeadline | String | 결제 기한 (수락 시각 + 24h) | O |

#### 201 Created

```json
{
  "success": true,
  "data": {
    "backupOfferId": 90,
    "status": "ACCEPTED",
    "orderId": 55,
    "totalAmount": 103000,
    "paymentDeadline": "2026-08-19T22:30:00+09:00"
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

#### 403 Forbidden

발생 가능: `40305 BACKUP_OFFER_ACCESS_DENIED`(#75, 본인 명의의 제안이 아님)

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40305,
    "message": "본인 명의의 차순위 제안이 아닙니다."
  }
}
```

#### 409 Conflict

발생 가능: `40911 BACKUP_OFFER_EXPIRED`, `40912 BACKUP_OFFER_ALREADY_RESOLVED`, `40905 IDEMPOTENCY_PAYLOAD_MISMATCH`

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40911,
    "message": "차순위 구매 기한이 만료되었습니다."
  }
}
```

---

# 17. 차순위 구매 거절 O

```
POST /api/backup-offers/{backupOfferId}/decline
```

## API 상세 설명

차순위 구매를 거절한다. 이후 서버가 다음 순위에게 제안을 생성할 수 있다.

## Request ✔️

### Path Variable (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| backupOfferId | Long | 차순위 제안 ID | O |

### Request Header (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | O |

```
Authorization: Bearer {accessToken}
```

### Request Parameter (0)

없음

### Request Body (0)

없음

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — backupOfferId | Long | 차순위 제안 ID | O |
| — status | String | DECLINED 고정 | O |

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "backupOfferId": 90,
    "status": "DECLINED"
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

#### 403 Forbidden

발생 가능: `40305 BACKUP_OFFER_ACCESS_DENIED`(#75, 본인 명의의 제안이 아님)

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40305,
    "message": "본인 명의의 차순위 제안이 아닙니다."
  }
}
```

#### 409 Conflict

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40912,
    "message": "이미 처리된 제안입니다."
  }
}
```

---

# 18. 비슷한 상품 O

```
GET /api/auctions/{auctionId}/similar
```

## API 상세 설명

상품 상세 하단의 “비슷한 상품 보기” 목록.

비로그인 접근을 허용한다. Authorization이 있으면 `isLiked` 등 개인화 필드를 채우고, 없으면 중립값을 반환한다.

## Request ✔️

### Path Variable (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| auctionId | Long | 기준 경매 ID | O |

### Request Header (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | X |

```
Authorization: Bearer {accessToken}
```

### Request Parameter (0)

없음

### Request Body (0)

없음

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — items | Array<Object> | 비슷한 상품 목록 | O |
| —— productId | Long | 상품 ID | O |
| —— auctionId | Long | 경매 ID | O |
| —— brand | String | 브랜드 | O |
| —— name | String | 상품명 | O |
| —— thumbnailUrl | String | 썸네일 | O |
| —— price | Long | **호환용 기존 필드.** 항상 `currentPrice`와 같은 값이다. 새 화면은 `currentPrice`를 쓴다 | O |
| —— likeCount | Int | 관심 수 | O |
| —— isLiked | Boolean | 내 관심 등록 여부(비로그인이면 `false`) | O |
| —— status | String | 경매 상태. 후보가 `SCHEDULED`/`LIVE`로 제한되므로 둘 중 하나 (#141) | O |
| —— grade | String | 상품 등급. §1 `product.grade`와 같은 값(저장된 `conditionGrade` 그대로, 변환 없음) (#141) | X |
| —— startPrice | Long | 경매 시작가(`Auction.startPrice`). 판매자가 등록·재경매 때 정한 값이며, 시작 전 시작가 변경 API로 바뀔 수 있다 (#141) | O |
| —— currentPrice | Long | 경매 현재가(`Auction.currentPrice`). 입찰 전에는 `startPrice`와 같고, 입찰이 들어오면 최고 입찰 반영가로 오른다 — §1 `currentPrice`와 같은 값 (#141) | O |

선정 기준: 같은 브랜드의 다른 `SCHEDULED`/`LIVE` 경매, `endAt` 오름차순 → `id` 오름차순, 최대 4개.
`name`은 §1 "상품 표시명 규칙"과 같다. `grade`는 등록 데이터가 null이면 null일 수 있다(현재 등록 API는
`conditionGrade`를 필수로 받는다).

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "productId": 20,
        "auctionId": 30,
        "brand": "BAPE",
        "name": "BAPE Bape Sta Black",
        "thumbnailUrl": "https://...",
        "price": 234000,
        "likeCount": 556,
        "isLiked": false,
        "status": "LIVE",
        "grade": "A",
        "startPrice": 200000,
        "currentPrice": 234000
      }
    ]
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

#### 404 Not Found

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40401,
    "message": "존재하지 않는 경매입니다."
  }
}
```

---

# 19. 관심 상품 등록 O

```
POST /api/auctions/{auctionId}/likes
```

## API 상세 설명

관심 등록 후의 관심 수를 함께 반환한다. 프론트가 `+1 / -1`을 직접 계산하지 않는다.

## Request ✔️

### Path Variable (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| auctionId | Long | 경매 ID | O |

### Request Header (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | O |

```
Authorization: Bearer {accessToken}
```

### Request Parameter (0)

없음

### Request Body (0)

없음

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — liked | Boolean | true 고정 | O |
| — likeCount | Int | 처리 후 관심 수 | O |

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "liked": true,
    "likeCount": 557
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

#### 404 Not Found

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40401,
    "message": "존재하지 않는 경매입니다."
  }
}
```

---

# 20. 관심 상품 해제 O

```
DELETE /api/auctions/{auctionId}/likes
```

## API 상세 설명

관심 해제 후의 관심 수를 함께 반환한다.

## Request ✔️

### Path Variable (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| auctionId | Long | 경매 ID | O |

### Request Header (1)

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| Authorization | String | Bearer {accessToken} | O |

```
Authorization: Bearer {accessToken}
```

### Request Parameter (0)

없음

### Request Body (0)

없음

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — liked | Boolean | false 고정 | O |
| — likeCount | Int | 처리 후 관심 수 | O |

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "liked": false,
    "likeCount": 556
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

#### 404 Not Found

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40401,
    "message": "존재하지 않는 경매입니다."
  }
}
```

---

# 경매 관리 API (freeze 이후 추가)

아래 세 엔드포인트는 freeze(2026-08-20) 이후 구현에서 추가된 **판매자용 경매 관리 API**다(기존 20개
계약에는 없었다). 모두 로그인 필수이며 **해당 경매의 판매자 본인만** 호출할 수 있다(아니면 `403 40307
AUCTION_SELLER_MISMATCH`). Idempotency-Key는 요구하지 않는다.

## C-1. 경매 취소

```
POST /api/auctions/{auctionId}/cancel
```

`SCHEDULED`이고 아직 `startAt`에 도달하지 않은 경매만 취소할 수 있다. 취소 시 `status`는 `CANCELED`가
된다. 요청 본문 없음.

#### Response (200)

| 이름 | Type | Description |
| --- | --- | --- |
| — auctionId | Long | 경매 ID |
| — status | String | `CANCELED` 고정 |

```json
{ "success": true, "data": { "auctionId": 1, "status": "CANCELED" }, "error": null }
```

#### Failure

발생 가능: `40307 AUCTION_SELLER_MISMATCH`(판매자 아님), `40401 AUCTION_NOT_FOUND`,
`40921 AUCTION_CANCEL_WINDOW_CLOSED`(이미 시작했거나 `SCHEDULED`가 아님).

## C-2. 시작가 수정

```
PATCH /api/auctions/{auctionId}/start-price
```

`SCHEDULED`이고 **시작 1시간 전까지**만 시작가를 바꿀 수 있다.

#### Request Body (1)

| 이름 | Type | Description | Required | 검증 |
| --- | --- | --- | --- | --- |
| startPrice | Long | 새 시작가 | O | `@NotNull @Positive` (0보다 커야 함) |

```json
{ "startPrice": 60000 }
```

#### Response (200)

| 이름 | Type | Description |
| --- | --- | --- |
| — auctionId | Long | 경매 ID |
| — startPrice | Long | 변경된 시작가 |

```json
{ "success": true, "data": { "auctionId": 1, "startPrice": 60000 }, "error": null }
```

#### Failure

발생 가능: `40001 INVALID_REQUEST`(바디 검증 실패), `40307 AUCTION_SELLER_MISMATCH`,
`40401 AUCTION_NOT_FOUND`, `40922 START_PRICE_CHANGE_WINDOW_CLOSED`(시작 1시간 전 이후).

## C-3. 재경매 (relist)

```
POST /api/auctions/{previousAuctionId}/relist
```

이전 경매를 바탕으로 **같은 상품**의 새 경매를 연다. `bidIncrement`는 서버가 고정하므로 요청에 넣지
않는다. 성공 시 **201 Created**. 이전 경매에 **실제 입찰이 있었다면 재경매할 수 없고**(`40920`), 같은
상품에 이미 진행/예약 중인 경매가 있으면 안 되며(`40918`), 등록 횟수 한도(2회)를 넘을 수 없다(`40919`).

#### Request Body (3)

| 이름 | Type | Description | Required | 검증 |
| --- | --- | --- | --- | --- |
| startPrice | Long | 새 경매 시작가 | O | `@NotNull @Positive` |
| startAt | String | 시작 시각(ISO-8601 절대시각) | O | `@NotNull` |
| endAt | String | 종료 시각(ISO-8601 절대시각) | O | `@NotNull`, 서버 시간 정책 검증 |

```json
{ "startPrice": 50000, "startAt": "2026-09-01T20:00:00+09:00", "endAt": "2026-09-01T22:00:00+09:00" }
```

#### Response (201)

| 이름 | Type | Description |
| --- | --- | --- |
| — auctionId | Long | 새로 생성된 경매 ID |
| — previousAuctionId | Long | 직전 경매 ID |
| — productId | Long | 상품 ID |
| — startPrice | Long | 시작가 |
| — bidIncrement | Long | 서버가 고정한 입찰 단위 |
| — startAt | String | 시작 시각 |
| — endAt | String | 종료 시각 |
| — status | String | 새 경매 상태(`SCHEDULED`) |

```json
{
  "success": true,
  "data": {
    "auctionId": 42,
    "previousAuctionId": 1,
    "productId": 10,
    "startPrice": 50000,
    "bidIncrement": 5000,
    "startAt": "2026-09-01T20:00:00+09:00",
    "endAt": "2026-09-01T22:00:00+09:00",
    "status": "SCHEDULED"
  },
  "error": null
}
```

#### Failure

발생 가능: `40006 INVALID_AUCTION_TIME`(start/end 시각 유효성), `40307 AUCTION_SELLER_MISMATCH`,
`40401 AUCTION_NOT_FOUND`(이전 경매 없음), `40918 ACTIVE_AUCTION_ALREADY_EXISTS`,
`40919 AUCTION_REGISTRATION_LIMIT_EXCEEDED`(최대 2회), `40920 AUCTION_NOT_ELIGIBLE_FOR_REREGISTRATION`
(이전 경매에 실제 입찰이 있었음).

> 상품·첫 경매 동시 등록(`POST /api/products`)과 분석 세션 생성·취소(`POST /api/products/analyze`,
> `.../cancel`)는 경매가 아니라 **상품/분석 도메인**이라 `docs/api/spec.md`에서 관리한다.

---

# 21. 화면 ↔︎ API 대응

## 홈 · 상품 찾기 (#142)

| 화면 | 진입 API | 주요 사용 필드 |
| --- | --- | --- |
| 홈 "나에게 딱 맞는 상품" | `GET /api/recommendations/auctions?limit=4` (spec.md 추천 API) | `personalized`(문구 분기), `items[].auctionId`·`thumbnailUrl`·`brand`·`name`·`status`·`grade`·`startPrice`·`currentPrice`·`likeCount`·`isLiked` |
| 홈 "지금 인기 있는 경매" | `GET /api/auctions/popular` (§23, 기본 4개) | `items[]` 카드 필드 |
| 상품 찾기 기본 목록 | `GET /api/auctions?page=&size=` (§24) | `items[]` 카드 필드, `hasNext` |

관심 등록·해제는 기존 `POST·DELETE /api/auctions/{auctionId}/likes`(§19–20)를 그대로 쓴다. `GET /api/curations`는
더미 데이터라 홈 화면에 쓰지 않는다.

## phase01 · 경매 시작 전

| 화면 | 진입 API | 액션 API | 주요 사용 필드 |
| --- | --- | --- | --- |
| **1-1** AI 큐레이션 푸시 알림 | — | — | **v1 미구현.** 알림 도메인 자체는 #75에서 구현됐다(상세는 spec.md 「# 알림 —」 절) - 여기서 미구현인 것은 "AI 큐레이션" 콘텐츠와 실제 기기 푸시 전송(FCM/APNs) 뿐이다. §22 참고 |
| **1-2** 경매 상품 상세 | `GET /api/auctions/{auctionId}`<br>`GET /api/auctions/{auctionId}/similar` | `POST·DELETE /api/auctions/{auctionId}/likes` | `status=SCHEDULED`, `product.grade`, `startsAt`, `endsAt`, `startPrice`, `bidIncrement`, `aiEstimatedPrice`, `aiPriceReason`, `seller`, `likeCount` |
| **1-3** 자동 입찰 상한가 설정 바텀시트 | `GET /api/auctions/{auctionId}/auto-bid/recommendation` | `POST /api/auctions/{auctionId}/auto-bids` | `aiRecommendedCap`, `minCapAmount`, `bidIncrement` → 응답 `status=RESERVED` |
| **1-4** 자동 입찰 예약 완료 | `GET /api/auctions/{auctionId}`<br>`GET /api/auctions/{auctionId}/auto-bids/me` | — | `myState.autoBidStatus=RESERVED`, `autoBidCap`, `startsAt`, `serverTime` |
| **1-5** 자동 입찰 상한가 수정 바텀시트 | `GET /api/auctions/{auctionId}/auto-bids/me` | `PATCH /api/auctions/{auctionId}/auto-bids/me`<br>`DELETE /api/auctions/{auctionId}/auto-bids/me` | `minCapAmount`, `canModify`, `canCancel`. **RESERVED이므로** **`-`** **버튼 하향 가능** |

## phase02 · 경매 중

| 화면 | 진입 API | 액션 API | 주요 사용 필드 |
| --- | --- | --- | --- |
| **2-1** 실시간 경매 (최고가) | `GET /api/auctions/{auctionId}/live` (polling)<br>`GET /api/auctions/{auctionId}/bids` | `DELETE /api/auctions/{auctionId}/auto-bids/me` (입찰 중단) | `currentPrice`, `isMine=true`, `canBid=false`, `cannotBidReason=ALREADY_HIGHEST_BIDDER`, `minNextBidAmount`, `bidIncrement`, `myCap`, `endsAt`+`serverTime` |
| **2-2** 실시간 경매 (상한가 초과) | 동일 | `PATCH /api/auctions/{auctionId}/auto-bids/me`<br>`DELETE /api/auctions/{auctionId}/auto-bids/me` | `myAutoBidStatus=CAP_REACHED`, `myCap`, `minCapAmount`. **상향만 허용** |
| **2-3** 경매 상품 상세 (LIVE) | `GET /api/auctions/{auctionId}` | — | `status=LIVE`, `currentPrice`, `myState.canBid` |
| **2-4** 실시간 경매 (경매 참여X) | `GET /api/auctions/{auctionId}/live`<br>`GET /api/auctions/{auctionId}/bids` | — | `myAutoBidStatus=null`, `isMine=false` |
| **2-6** 직접 입찰 바텀시트 | `GET /api/auctions/{auctionId}/live` | `POST /api/auctions/{auctionId}/bids` | `minNextBidAmount`, `bidIncrement`. **자동입찰 사용 중이면 취소 경고 필수** → 응답 `autoBidCanceled` |

실시간 화면은 `/live`(가격·시간·내 상태·직접 입찰 가능 여부)와 `/bids`(입찰 목록)를 **함께 polling**한다.
‘자동 입찰 상한가 수정’·‘직접 입찰’ 버튼은 API를 직접 호출하지 않고 각각 상한가 수정 바텀시트(2-2 행)·직접 입찰 바텀시트(2-6 행)로 이동하며, API는 해당 행에 매핑되어 있다. ’입찰 중단’만 바텀시트 없이 confirm 후 `DELETE`를 직접 호출한다. 실시간 화면의 버튼 disable 판단은 `/live`의 `canBid` + `cannotBidReason`을 사용한다.

## phase03 · 경매 마감

| 화면 | 진입 API | 액션 API | 주요 사용 필드 |
| --- | --- | --- | --- |
| **3-1** 낙찰 성공 | `GET /api/auctions/{auctionId}/result` | `POST /api/auctions/{auctionId}/award/forfeit` | `result=WON`, `product`, `finalPrice`, `shippingFee`, `totalAmount`, `paymentDeadline`, `orderId` |
| **3-2** 낙찰 실패 / 차순위 대기 | `GET /api/auctions/{auctionId}/result` | — | `result=LOST` 또는 `BACKUP_WAITING`, `myLastBidAmount`, `backupEligible`, `backupOfferId` |
| **3-3** 경매 상품 상세 (마감) | `GET /api/auctions/{auctionId}`<br>`GET /api/auctions/{auctionId}/bids` | — | `status=ENDED`, `startPrice`, `finalPrice` |

## phase04 · 결제 및 차순위 이양

| 화면 | 진입 API | 액션 API | 주요 사용 필드 |
| --- | --- | --- | --- |
| **4-1/01** 결제 화면 | `GET /api/orders/{orderId}` | `POST /api/orders/{orderId}/pay` | `status=PAYMENT_PENDING`, `product.subName`, `purchasePrice`, `totalAmount`, `paymentDeadline`. **결제 수단 선택은 프론트 표시용 (서버 계약 없음)** |
| **4-1/02** 결제 완료 화면 | `GET /api/orders/{orderId}` | — | `status=PAID`, `paidAt` |
| **4-2/01** 결제 기한 만료 화면 | `GET /api/auctions/{auctionId}/result`<br>**+** `GET /api/me/penalties` | — | `result=PAYMENT_EXPIRED` / `noShowCount`, `bidRestrictedUntil`. **2회 호출로 구성** |
| **4-2/02** 차순위 구매 기회 | `GET /api/backup-offers/{backupOfferId}` | `POST /api/backup-offers/{backupOfferId}/accept`<br>`POST /api/backup-offers/{backupOfferId}/decline` | `product.subName`, `purchasePrice`, `totalAmount`, `deadline`+`serverTime` → accept 응답의 `orderId`로 결제 화면 이동 |

## 화면 전이 요약

```
[3-1 낙찰]  --orderId-->        [4-1/01 결제] --pay--> [4-1/02 완료]
     |
     +--forfeit-->              (서버가 BackupOffer 생성)

[결제 미이행]
     |
     +--scheduler PAYMENT_EXPIRED-->  [4-2/01 만료]
                                             |
                                  (차순위에게 BackupOffer 생성)
                                             |
[3-2 차순위 대기] --backupOfferId--> [4-2/02 제안] --accept--> [4-1/01 결제]
```

---

# 22. v1 범위 제외

```
알림(DB 저장 + REST 목록/읽음/안읽은수)  — #75에서 구현 완료(상세는 spec.md 「# 알림 —」 절). 대상 타입은
                                    AUCTION_WON / BACKUP_OFFER_CREATED / PAYMENT_EXPIRED
                                    3종뿐 — AUCTION_STARTED/PAYMENT_COMPLETED 등은 후속 스코프
푸시 / 디바이스 토큰 / FCM / APNs / SSE / WebSocket — 후속 스코프(REST 조회/polling만 지원)
관심 상품 목록 API               — #143에서 구현(GET /api/me/likes, spec.md 「# 마이페이지 API」)
내 참여 경매 목록 API             — #143에서 구현(GET /api/me/auctions, spec.md 「# 마이페이지 API」)
실제 PG 연동 / 결제수단 API      — 미구현
환불 / 웹훅                      — 미구현
배송 도메인                      — 미구현
이의 제기 · 문의하기             — 미구현
강제 만료 production API         — 미구현 (dev seed로 대체)
/live.recentBids                 — 미채택 (/bids로 분리 유지)
/result.noShowCount              — 미채택 (/api/me/penalties로 일원화)
/result.bidRestrictedUntil       — 미채택 (동일)
Product.SOLD 상태                — 불필요
```

---

# 23. 홈 인기 경매 O

```
GET /api/auctions/popular
```

## API 상세 설명

홈 화면 **"지금 인기 있는 경매"** 카드 목록(#142). 기본 4개.

- **인증: 선택.** 비로그인도 조회할 수 있다(`isLiked`는 모두 `false`). 로그인 사용자는 인증 정보
  (dev/prod `Authorization: Bearer`, local mock `X-User-Id`)로만 식별한다 — dev/prod에서 `X-User-Id` 헤더는 무시된다.
  유효하지 않은 토큰을 보내면 비로그인 허용 API여도 `401`(`40101`)이다.
- **조회 대상:** 경매 상태 `SCHEDULED`(예정)·`LIVE`(진행 중)만. `ENDED`·`CANCELED`는 제외한다.
- **인기 기준(정렬):** ① 그 경매에 저장된 입찰(`bids` 행) 수 내림차순 → ② 종료 시각(`endAt`) 오름차순 →
  ③ 경매 ID 오름차순. 입찰 수는 직접 입찰과 자동입찰 엔진이 넣은 입찰을 모두 센다. **관심 수(`likeCount`)는 기준이 아니다.**
  예정 경매는 입찰이 0건이라 입찰이 있는 진행 중 경매보다 뒤에 온다. 같은 기준을 추천 API의 Fallback이 "인기" 쪽에 쓴다.
- 대상 경매가 `limit`보다 적으면 **있는 만큼만** 반환한다(더미·중복으로 채우지 않음). 대상이 없으면 `items: []`.
- 종료 시각이 지났지만 종료 스케줄러가 아직 `ENDED`로 바꾸지 않은 짧은 구간의 경매는 `LIVE`로 포함될 수 있다.

## Request ✔️

### Query Parameter

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| limit | Int | 가져올 개수. 기본 `4`, 허용 `1`~`20` | X |

```
GET /api/auctions/popular
GET /api/auctions/popular?limit=4
Authorization: Bearer {accessToken}   (선택)
```

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — items | Array<Object> | 경매 카드 목록(인기순). 비어 있을 수 있다 | O |
| —— auctionId | Long | 경매 ID — 카드는 경매 단위로 식별한다 | O |
| —— productId | Long | 상품 ID | O |
| —— brand | String | 브랜드 | X |
| —— name | String | 상품 표시명. §1 "상품 표시명 규칙"(`brand + modelName + color`)과 같다 | O |
| —— thumbnailUrl | String | 대표 이미지(상품 첫 이미지)의 **24시간 서명 URL**. 응답마다 새로 서명되므로 저장하지 말고 그때그때 표시만 한다. 이미지가 없으면 `null` | X |
| —— status | String | 경매 상태 `SCHEDULED` / `LIVE` | O |
| —— grade | String | 상품 등급. 저장된 `conditionGrade` 그대로(변환 없음, `DS`/`S`/`UNKNOWN` 등도 올 수 있음) | X |
| —— startPrice | Long | 경매 시작가 | O |
| —— currentPrice | Long | 현재가 — 화면의 **"최고가"**. 입찰 전에는 `startPrice`와 같다. 자동입찰 상한가가 아니다 | O |
| —— likeCount | Int | 관심 수 | O |
| —— isLiked | Boolean | 로그인 사용자 본인의 관심 여부. 비로그인이면 `false` | O |

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "auctionId": 30,
        "productId": 20,
        "brand": "Nike",
        "name": "Nike Dunk Low Panda",
        "thumbnailUrl": "https://{bucket}.s3.ap-northeast-2.amazonaws.com/{key}?X-Amz-...",
        "status": "LIVE",
        "grade": "B",
        "startPrice": 100000,
        "currentPrice": 125000,
        "likeCount": 3,
        "isLiked": false
      }
    ]
  },
  "error": null
}
```

#### 200 Ok — 대상 없음

```json
{ "success": true, "data": { "items": [] }, "error": null }
```

### Failure ❌

| HTTP | code | 발생 조건 |
| --- | --- | --- |
| 400 | 40001 | `limit`이 1~20 범위 밖이거나 숫자가 아님 |
| 401 | 40101 | 유효하지 않은 토큰을 보냄 |

```json
{
  "success": false,
  "data": null,
  "error": { "code": 40001, "message": "limit은 1 이상 20 이하여야 합니다. 요청값: 0" }
}
```

---

# 24. 기본 경매 목록 O

```
GET /api/auctions
```

## API 상세 설명

상품 찾기 화면의 **기본 경매 목록**(#142). 경매 단위 카드를 페이지로 조회한다. 인기순·최신순 탭 전환과
검색·필터는 제공하지 않는다(기본 목록·기본 정렬만).

- **인증: 선택.** §23과 같다(비로그인 `isLiked = false`, 로그인 사용자는 인증 정보로만 식별).
- **조회 대상:** `SCHEDULED`·`LIVE` 경매만. `ENDED`·`CANCELED` 제외.
- **정렬:** 경매 생성 시각(`auctions.created_at` — 경매 행이 만들어진 시각. 상품 등록 시의 첫 경매와 재경매
  모두 각자 생성 시각을 가진다) 내림차순 → 같으면 경매 ID 내림차순.
- **페이지네이션:** 입찰 내역(§3)과 같은 `page`/`size` + 응답 `page`/`size`/`hasNext` 형식. 전체 개수는 주지 않는다.
  마지막 페이지를 넘긴 `page`는 오류가 아니라 빈 목록(`hasNext: false`)이다.
- 목록을 넘기는 사이 새 경매가 등록되면 다음 페이지에 앞 페이지 항목이 다시 보일 수 있다(offset 방식).
- 기존 `GET /api/products`(상품 단위 목록)는 그대로이며 이 API와 별개다.

## Request ✔️

### Query Parameter

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| page | Int | 페이지 번호(0부터). 기본 `0`, `0` 이상 | X |
| size | Int | 페이지 크기. 기본 `20`, 허용 `1`~`50` | X |

```
GET /api/auctions?page=0&size=20
Authorization: Bearer {accessToken}   (선택)
```

## Response ✔️

### Success ✅

#### Response Body

| 이름 | Type | Description | Required |
| --- | --- | --- | --- |
| success | Boolean | true | O |
| error | Object | null | O |
| data | Object | API 관련 데이터 | O |
| — items | Array<Object> | 경매 카드 목록. 카드 필드는 §23과 같다 | O |
| — page | Int | 요청한 페이지 번호 | O |
| — size | Int | 요청한 페이지 크기 | O |
| — hasNext | Boolean | 다음 페이지 존재 여부 | O |

#### 200 Ok

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "auctionId": 31,
        "productId": 21,
        "brand": "ASICS",
        "name": "ASICS Novablast 6 Black",
        "thumbnailUrl": "https://{bucket}.s3.ap-northeast-2.amazonaws.com/{key}?X-Amz-...",
        "status": "SCHEDULED",
        "grade": "A",
        "startPrice": 50000,
        "currentPrice": 50000,
        "likeCount": 0,
        "isLiked": false
      }
    ],
    "page": 0,
    "size": 20,
    "hasNext": false
  },
  "error": null
}
```

#### 200 Ok — 빈 목록

```json
{ "success": true, "data": { "items": [], "page": 0, "size": 20, "hasNext": false }, "error": null }
```

### Failure ❌

| HTTP | code | 발생 조건 |
| --- | --- | --- |
| 400 | 40001 | `page` < 0, `size`가 1~50 범위 밖, 또는 `page`/`size`가 숫자가 아님 |
| 401 | 40101 | 유효하지 않은 토큰을 보냄 |

```json
{
  "success": false,
  "data": null,
  "error": { "code": 40001, "message": "size는 1 이상 50 이하여야 합니다. 요청값: 51" }
}
```

---

# 인증 · 알림 API → docs/api/spec.md

Kakao 로그인 / Access Token 재발급 / 로그아웃, 알림 목록 / 읽음 처리 / 안 읽은 수 API의 상세
(요청·응답·필드·예시·오류)는 전체 API 명세인 [`docs/api/spec.md`](spec.md)가 **단독으로** 관리한다.
이 문서(auction-spec)는 경매·입찰·자동입찰·정산·주문·차순위 도메인만 상세화한다. 관련 오류 코드
(`40102`/`40103`/`40405`/`50201`)는 §0-A와 spec.md 오류표를 참고한다.

---
# 부록 A. 프론트 목업 수정 필요 항목

| 화면 | 문제 | 조치 |
| --- | --- | --- |
| **2-1** 실시간 경매 (최고가) | 현재 최고 입찰자에게 직접 입찰 버튼이 활성화될 여지가 있음 | `/live.canBid=false`, `cannotBidReason=ALREADY_HIGHEST_BIDDER` 기준으로 직접 입찰 버튼 비활성화 |
| **1-2** AI 가격 산정 근거 | 본문이 다른 상품 텍스트(최종 추천가 24,000원, 권장 범위 23,000~25,000원)인데 같은 화면의 AI 적정 시세는 100,000원, 시작가는 50,000원 | 본문을 해당 상품 기준으로 교체 |
| **3-1** 낙찰 성공 안내 문구 | “2026년 7월 14일 오후 9시까지 결제” — 경매일은 8월 17일 | `endsAt + 24h` = **2026년 8월 18일 오후 10시**로 수정 |
| **2-6** 직접 입찰 바텀시트 | 자동입찰 취소 경고 없음 | “직접 입찰하면 현재 자동입찰이 중단됩니다.” 문구 + 확인 단계 추가 |
| **4-1/01** 결제 화면 | 최초 낙찰자와 차순위 수락자가 같은 화면을 사용하지만 라벨이 “최종 낙찰가”로 고정됨 | 라벨을 **“상품 금액”**으로 변경. API는 `purchasePrice` 사용 |
| **4-1/02** 결제 완료 화면 | “마이페이지에서 배송 현황을 확인할 수 있어요” — 배송 도메인 없음 | 문구 수정 또는 마이페이지 링크 비활성화 |
| **4-2/01** 결제 기한 만료 화면 | 유일한 버튼이 “이의 제기·문의하기”인데 v1 미구현 → 이탈 경로 없음 | 버튼 교체 필요 |
| **4-2/02** 차순위 구매 기회 | 구매 가능 금액이 125,000원으로 표기되고 `deadline`을 “결제 기한”으로 표현함. 실제 정책상 차순위(rank 2)의 구매 금액은 100,000원이며 이 시점의 24시간은 제안 응답 기한임 | 구매 가능 100,000원 / 배송비 3,000원 / 최종 103,000원으로 수정. **“결제 기한” → “구매 결정 기한”**으로 변경 |
| **2-6** 직접 입찰 바텀시트 | 안내 문구 “설정한 금액은 실제 결제 금액이 아니에요.”는 자동입찰 시트 문구가 잘려 복사된 것. 직접 입찰은 낙찰 시 해당 금액이 그대로 결제 기준 | “낙찰 시 이 금액이 결제 금액이 됩니다” 류로 교체 |
| **4-2/01** 결제 기한 만료 화면 | “7일간 입찰 불가” 고정 문구와 “~7월 21일”이 §14 예시(`bidRestrictedUntil` = 8/24 00:00)와 불일치 | 기간·날짜를 API `bidRestrictedUntil` 기준 계산값으로 표기 (예시 데이터 기준 “~8월 24일”) |
| **3-2** 낙찰 실패 화면 | 유일한 버튼 “다른 상품 둘러보기”의 목적지(목록/홈)가 이 화면 세트와 계약 어디에도 없음 | 목적지 확정 후 §21에 경로 추가, 없으면 §22에 제외 명시 |
| **4-1/02** 결제 완료 화면 | “주문 상세 보기” 도착 화면(`status=PAID` 주문 화면) 목업 없음 — 4-1/01은 결제 버튼이 있는 PENDING 전용 레이아웃 | PAID 상태 레이아웃 정의 (결제 버튼 숨김, `paidAt` 표시) |
| 전체 | `result=FORFEITED` 사용자용 화면 없음 — 4-2/01 문구(“결제 기한이 만료되어”)는 PAYMENT_EXPIRED 전용 | 포기 경로를 시연에 넣으면 화면 추가, 아니면 §21에 미사용 명시 |
| 전 화면 | 마스킹이 `mma****` / `ham***` / `suy***`로 혼재 | **`*`** **4개 고정**으로 통일 |
| **2-1 / 2-2 / 2-4** | 종료 연장(`extensionCount`) 표시 없음 | 남은 시간이 늘어나는 경우 안내 필요. 미표시 시 `/live`의 `extensionCount`·`maxExtensions` 사용처 없음 |
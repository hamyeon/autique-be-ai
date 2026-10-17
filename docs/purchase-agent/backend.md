# Purchase Agent — 백엔드

구매 에이전트의 **현재 백엔드 구현**을 설명한다. 사용자가 자연어로 구매 목표를 등록하면, 서버가 주기적으로
후보 경매를 찾아 조건을 검증하고 사용자를 대신해 자동입찰에 참여한 뒤 결과를 관찰한다.

AI 파서·프롬프트·모델·평가 수치는 [`ai.md`](ai.md)에서 다루고 여기서 중복하지 않는다. 백엔드 전체
구조·다른 흐름은 [`docs/backend/overview.md`](../backend/overview.md).

> 이 문서는 현재 코드를 확인해 작성한 **현재 구현 안내**다. 구현 전 사전 조사(Day 0) 원문은 맨 아래
> 접힌 부록에 당시 커밋·시점과 함께 보존한다 — 그 안의 "미구현/미확정" 서술은 조사 당시 기준이므로 현재
> 설명과 섞어 읽지 않는다.

---

## 1. AutoBid와 Purchase Agent의 차이

- **AutoBid(자동입찰)** 는 사용자가 **특정 경매 하나**를 직접 고른 뒤 상한(`maxAmount`)을 걸어두는
  대리 입찰 설정이다. 경매 선택도, 상한 결정도 사용자가 한다.
- **Purchase Agent(구매 에이전트)** 는 그보다 한 단계 위다. 사용자는 **경매가 아니라 목표**(모델·최소
  등급·예산 등)를 등록하고, 서버가 **여러 경매를 스스로 탐색**해 조건에 맞는 매물에 **대신 AutoBid를
  생성**한다. 즉 Purchase Agent는 "탐색 + 참여"를 자동화하는 상위 기능이고, 실제 입찰 실행은 그 아래에서
  기존 AutoBid 메커니즘을 그대로 쓴다.

## 2. AI와 백엔드의 책임 경계

| | 역할 |
| --- | --- |
| **AI(해석·평가)** | 자연어 문장 → 목표 초안 파싱(`GoalParser`), "이 경매 매물이 목표에 맞나" 의미 판정(`ListingMatcher`) |
| **백엔드(상태·검증·실행)** | 목표 상태 전이, 후보 **하드 필터**(pre-filter), 입찰 직전 **서버 시세 재계산과 상한 제한**, 실제 AutoBid 생성, 결과 판정 |

핵심 원칙: **LLM이 낸 값이 사람 확인 없이 입찰 금액에 직접 닿는 경로는 없다.** 백엔드 검증기는 LLM 판정을
"맞다 → 아니다" 쪽으로만 뒤집고(`MatchResultValidator`: 하드 네거티브·모델/브랜드 불일치면 `matched`를
false로), 그 반대로 통과시키지 않는다. 목표 파싱 결과도 `GoalDraftValidator`가 카탈로그 모델 키·금액 범위·
사이즈 범위로 조여 신뢰도를 낮추거나 경고를 붙인다(예외는 던지지 않는다). AI 파서·Matcher의 상세·수치는
[`ai.md`](ai.md).

## 3. 주요 엔티티와 목표 상태 전이

**`PurchaseGoal`** (`purchasegoal/domain/PurchaseGoal.java`)의 주요 필드: `modelQuery`(필수 자연어/모델
질의), `modelKey`(가격 카탈로그 밖 모델이면 null), `minCondition`(최소 등급 `GoalCondition`),
`sizeKr`(있을 때만 하드 필터), `hardMaxAmount`(예산, 필수), `deadline`, `status`, `currentAuctionId`(참여
중인 경매, 참여 시에만 채워지는 참조값 — FK 아님).

**목표 상태**: `ACTIVE, ENGAGED, CANCEL_REQUESTED, FULFILLED, CANCELLED, EXPIRED`. (AI 초안은 저장하지
않으므로 DRAFT 상태가 없다.)

```
등록 ─► ACTIVE ──(후보 찾아 참여)──► ENGAGED ──(경매 종료, 낙찰)──► FULFILLED
          │  ▲                         │
          │  └──(졌는데 기한 남음, 재탐색)─┘
          │                            │
          │                       (졌고 기한 지남) ─► EXPIRED
          └──(사용자 취소)─► CANCELLED        ▲
                     ENGAGED에서 취소 ─► CANCEL_REQUESTED ─(종료 후 패배)─┘→ CANCELLED
          └──(기한 경과) ─► EXPIRED
```

### 등록·취소·만료 규칙
- **등록**: `POST /api/purchase-goals/parse`는 초안(`GoalDraft`)만 돌려주고 **DB에 쓰지 않는다**. 사용자가
  확인한 값을 `POST /api/purchase-goals`로 등록하면 `ACTIVE`로 저장된다(HTTP 201).
- **취소**: `ACTIVE`에서 취소하면 즉시 `CANCELLED`. 하지만 이미 참여 중(`ENGAGED`)이면 즉시 끊지 못하고
  `CANCEL_REQUESTED`가 된다(§8 "참여 중 취소" 참고).
- **만료**: `deadline`이 지난 `ACTIVE` 목표는 탐색 주기의 만료 단계에서 `EXPIRED`로 바뀐다.

## 4. 탐색 주기 (`PurchaseGoalScanScheduler`)

⚪ **기본 비활성화**다 — `purchase-agent.scan.enabled`(환경변수 `PURCHASE_AGENT_SCAN_ENABLED`) 기본값
`false`. 모든 프로필에서 꺼져 있고, 켜야 동작한다. 기본 5분 주기(`purchase-agent.scan.cron`)로 돌며 한 번의
`scan()`은 **세 단계를 이 순서로** 실행한다:

1. **만료 처리** — 기한이 지난 `ACTIVE` 목표를 `EXPIRED`로.
2. **결과 관찰** — 참여 중(`ENGAGED`/`CANCEL_REQUESTED`) 목표의 경매가 끝났는지 보고 승패를 판정(§8).
3. **탐색·참여** — `ACTIVE` 목표에 대해 후보를 찾아 평가하고 참여(§5~§7).

순서 이유: 만료를 먼저 해 지난 목표가 새로 참여하지 않게 하고, 결과 관찰을 탐색보다 먼저 해 **졌지만 기한이
남아 `ACTIVE`로 돌아온 목표가 같은 주기에 곧바로 재탐색**되게 한다.

## 5. 후보 필터 · 랭킹 · Matcher 결과 저장

**후보 탐색(`PurchaseGoalCandidateFinder`)** — `ACTIVE` + 기한이 남은 목표에 대해, 앞으로 24시간 내
`LIVE`/`SCHEDULED` 경매 중 다음 **하드 필터**를 모두 통과한 매물만 1차 통과시킨다:
- **모델**: `modelKey`가 있으면 별칭 표(`ModelAliases`)로 정규화해 동일 키만, 없으면 브랜드 일치.
- **최소 등급**: `minCondition.satisfiedBy(매물 등급)`.
- **사이즈**: 목표에 `sizeKr`가 있을 때만 동일 사이즈 하드 조건(없거나 다르면 제외).
- **예산**: `hardMaxAmount`가 그 경매의 최소 입찰가(`minNextBidAmount`)에 못 미치면 제외.
- 이미 사용자의 AutoBid가 걸린 경매는 제외(중복 참여 방지), 서버 시세 추정이 안 나오는(0 이하) 매물도 제외.

**랭킹(`PurchaseGoalCandidateRanker`)** — Matcher로 의미 판정을 받아 `matched`인 매물만 남기고, **서버
시세 대비 할인율**로 정렬한다. 정렬 키는 할인율 내림차순 → 종료 시각 오름차순 → semanticScore 내림차순 →
경매 id. 할인율은 `(서버 추정가 − 현재가) / 서버 추정가`로, **판매자 추천가는 쓰지 않는다**. 이 서비스는
AI 호출(Matcher)을 하므로 **DB 트랜잭션 없이** 돈다(락을 쥔 채 외부 호출을 기다리지 않기 위해).

**Matcher 결과 저장(`PurchaseGoalMatch`)** — `(goal, auction)` 쌍마다 판정 결과(`matched`/`semanticScore`/
`reason`/`listingModelKey`)를 저장해 재호출을 피한다(테이블 `purchase_goal_matches`, UNIQUE `(goal_id,
auction_id)`). 상태 enum이 있는 엔티티가 아니라 판정 캐시다.

## 6. 입찰 직전 시세 재계산과 상한 결정

참여 직전 `PurchaseGoalEngagementService.attemptEngage`가 **DB 트랜잭션 밖에서 서버 시세를 다시 계산**하고,
상한을 `min(hardMaxAmount, floor(최신시세 × CAP_RATIO))`로 정한다. `CAP_RATIO`는 코드 상수 `1.0`이다(외부
설정 키가 아니다). 랭킹 시점의 추정가가 아니라 **참여 직전 값**을 쓰는 이유는 그 사이 현재가/시세가 움직일
수 있기 때문이다. 서버가 다시 계산한 값만 상한에 들어가고, 판매자가 보낸 값은 쓰지 않는다.

## 7. AutoBid 연동과 목표 상태 변경의 트랜잭션

상한이 정해지면 `PurchaseGoalEngagementTransactionService.engage`가 **하나의 물리 트랜잭션**에서:
1. 목표를 `ACTIVE → ENGAGED`로 **조건부 전이**(정확히 1행에 반영돼야 함 — 동시 중복 참여 방지),
2. `autoBidCommandService.createAutoBid(auctionId, userId, cap, …, goalId)`로 **실제 AutoBid 생성**,
3. `PURCHASE_AGENT_ENGAGED` 알림 기록.

AutoBid 생성이 실패하면 **예외를 삼키지 않아** 목표 전이까지 함께 롤백된다 — "목표는 ENGAGED인데 입찰은
없는" 어긋난 상태를 만들지 않는다.

### 잠금·경쟁 처리와 충돌 방지
- `createAutoBid`는 경매를 **비관적 락**으로 잠그고 생성되므로, **수동 입찰이나 기존 AutoBid와의 충돌**은
  경매 도메인의 기존 규칙으로 처리된다(상한이 최소 입찰가에 못 미치면 거부, 같은 사용자의 기존 설정 처리
  등). Purchase Agent가 별도 입찰 규칙을 새로 만들지 않는다.
- 후보 탐색이 "이미 사용자의 AutoBid가 있는 경매"를 제외하고, 참여 전이가 조건부(1행 반영)라 **같은 목표가
  같은 경매에 중복 참여**하지 않는다.
- 시세 재계산은 락 밖(§6), 상태 전이+AutoBid 생성만 트랜잭션 안(위) — 락을 쥔 구간을 좁게 유지한다.

## 8. 결과 관찰 · 낙찰/패배 판정 · 재탐색 · 종료

`PurchaseGoalResultObservationService.observeIfDue`가 참여 중 목표의 경매가 `ENDED`/`CANCELED`가 됐을 때만
판정한다(아니면 대기). **낙찰 여부는 그 사용자의 주문 존재로 판정**한다(`orderRepository.findByAuctionIdAndBuyerId(auctionId, userId).isPresent()`).

- **낙찰(주문 존재)** → `FULFILLED`.
- **패배** → `CANCEL_REQUESTED`였으면 `CANCELLED`, 아니면 기한이 남았으면 `ACTIVE`로 되돌려 **재탐색**,
  기한이 지났으면 `EXPIRED`.

### FULFILLED는 "낙찰"이지 "결제 완료"가 아니다
`FULFILLED`는 **참여한 경매에서 그 사용자가 낙찰자가 됐다(주문이 생겼다)** 는 뜻이다. 결제가 끝났다는
의미가 **아니다** — 생성된 `Order`는 `PAYMENT_PENDING`에서 시작하고, 이후 결제·미결제 처리(차순위 이양·노쇼
패널티 등)는 **주문/패널티 도메인**이 맡는다([`docs/backend/overview.md`](../backend/overview.md) §4). 구매
에이전트의 역할은 "낙찰시키는 것"까지다.

### "참여 중 취소"의 의미
`ENGAGED` 상태(이미 어떤 경매에 AutoBid를 걸고 입찰 중)에서 사용자가 취소하면, 이미 들어간 입찰을 즉시
회수하지는 못하고 목표가 `CANCEL_REQUESTED`가 된다. 이 취소는 **그 경매가 끝난 뒤에만** 반영된다 — 그 사이
입찰이 유효해 **그대로 낙찰되면 `FULFILLED`**(관찰이 낙찰을 먼저 판정), **지면 재탐색하지 않고 `CANCELLED`**로
끝난다. 즉 "참여 중 취소"는 "다음부터 더 참여하지 말라"는 뜻이지 "이미 건 입찰을 무르라"가 아니다.

## 9. 설정 · 기본 활성화 · 실패 처리 · 검증 범위 · 한계

- **기본 활성화**: 탐색 스케줄러 기본 `false`(§4). 파서·Matcher provider 기본값은 각각 OpenAI
  (`PURCHASE_GOAL_PARSER`, `PURCHASE_LISTING_MATCHER` 기본 `openai`), 규칙 기반으로 교체 가능(`rule`).
- **실패 처리**: 파서는 OpenAI 실패 시 규칙 기반으로 **폴백**(신뢰도 하향 + 경고)한다. 반면 **Matcher는
  OpenAI 실패 시 폴백 없이 그 후보를 제외**한다 — 따라서 OpenAI 크레딧이 없으면 모든 후보가 제외돼 에이전트가
  아무 경매에도 참여하지 못한다. 충전 전까지는 `PURCHASE_LISTING_MATCHER=rule`로 두는 것이 안전하다.
- **검증 범위·한계**: 상한 비율은 코드 상수(`CAP_RATIO=1.0`)라 설정으로 바꿀 수 없다. 탐색 창은 24시간,
  차순위/결제는 주문 도메인 소관. AI 파서·Matcher의 측정 수치와 한계(예: 파서 테스트셋 자가검증, Matcher
  실측 정확도)는 [`ai.md`](ai.md)에 있다. 분석 큐·스케줄러 등 공통 한계는
  [`docs/backend/overview.md`](../backend/overview.md) §7.

---

<details>
<summary><b>부록 — Day 0 사전 조사 원문 (조사 당시 커밋 <code>17c9346</code>, 2026-09, 구현 전)</b></summary>

> 아래는 Purchase Agent 백엔드를 구현하기 **전**의 코드 조사 원문이다. 당시 "미구현/미확정"으로 적힌
> 항목 상당수는 이후 구현됐다(본문 §3~§8이 현재 상태). 역사적 기록으로 그대로 보존한다.


조사 기준 커밋: `17c9346` (branch: `feat/be-purchase-agent`)
범위: 코드 읽기 전용 조사. 구현 없음.


> 2026-10-01 기준: 2026-09-25 #109 머지로 아래 "Day 1을 막는 문제" 1~3은 해소됐다 - `PurchaseGoal` 엔티티,
> Matcher 결과 저장 `PurchaseGoalMatch`, 탐색 오케스트레이션 `PurchaseGoalScanScheduler`(→ CandidateFinder/Ranker/EngagementService).
> 4(LOST 상태 없음)는 `AutoBidSettingStatus`를 바꾸지 않고 풀었다 - 여전히 `RESERVED, ACTIVE, CAP_REACHED, CANCELED` 4종이고,
> `PurchaseGoalResultObservationService`가 경매 `ENDED` + `Order` 존재 여부(§1)로 승패를 판정해 Goal 상태
> (`FULFILLED` / `ACTIVE` 복귀 / `EXPIRED`)로 남긴다. 본문은 조사 당시(`17c9346`) 기록이다.

---

## 1. 경매 종료 상태 변경과 Order 생성이 같은 트랜잭션인지 / 승패 판정 근거

**결론**: 같은 물리 트랜잭션이다. `AuctionEndService.endIfDue()`가 `@Transactional`이고, 그 안에서
호출하는 `AuctionSettlementService.settle()`도 `@Transactional`(기본 REQUIRED)이라 하나의
트랜잭션으로 합류한다. `end()`가 실패하면 `settle()`(Order 생성 포함)까지 함께 롤백된다.

Agent가 승패를 판정할 근거는 **`Order` 존재 여부**다. `settle()`은 `auction.getCurrentWinner()`가
null이 아니면 `Order.createForWinner(...)`를 생성하고, `NotificationRecorder`로
`AUCTION_WON` 알림을 기록한다. 낙찰자가 없으면(`NO_BIDS`) Order를 만들지 않고
`Optional.empty()`를 반환한다. 즉 "정산 완료"는 `Auction.status == ENDED` +
(Order 존재 또는 winner == null 확인)으로 판단할 수 있다.

**근거 파일:줄**
- `backend/src/main/java/com/vintic/backend/auction/service/AuctionEndService.java:53-73`
- `backend/src/main/java/com/vintic/backend/order/service/AuctionSettlementService.java:47-81`

**구현 시 영향**
- Agent가 결과를 폴링할 때는 `Order.findByAuctionIdAndBuyerId(auctionId, agentUserId)` 존재 여부로
  승패를 판정하면 된다(정산 재실행에도 안전 - `existing.isPresent()`면 그대로 반환).
- `end()`~`settle()` 사이에 Agent 쪽 로직을 끼워 넣으려면 같은 트랜잭션에 편입시킬지,
  별도 이벤트/폴링으로 분리할지 결정이 필요하다(현재 코드에는 Agent 훅이 없음 — 미확정).

---

## 2. LIVE/SCHEDULED AutoBid 등록 진입점, 금액 검증, Goal 상태 변경과의 트랜잭션 결합 가능 여부

**결론**
- 진입점은 단일 메서드다: `AutoBidCommandService.createAutoBid(auctionId, userId, maxAmount[, idempotencyId])`.
  LIVE/SCHEDULED를 구분하는 별도 API는 없다 — 메서드 내부에서 `auction.getStatus()`로 분기한다.
  - `SCHEDULED`(코드상 auction 상태는 `RESERVED` 아님, `AuctionStatus.SCHEDULED`): `AutoBidSetting.reserve()`만 저장, `ProxyPriceEngine` 미호출.
  - `LIVE`: 저장 전에 `ProxyPriceEngine.resolve()`로 즉시 경쟁 가격을 계산해 반영.
- 금액 검증 규칙(등록 시): `maxAmount >= auction.getMinNextBidAmount()` 위반 시 `CapTooLowException`(40906).
  그 외 순서: 사용자 존재 → penalty(40903 계열) → 경매 종료/취소(`AuctionClosedException`) →
  마감시각 경과 → 판매자 본인 여부(`SellerCannotBidException`) → 기존 등록 중복(40908) → 금액.
  (update 시엔 ACTIVE/CAP_REACHED 상태면 `newMaxAmount > 기존값` 강제 — 40907.)
- **Goal 상태 변경과 한 트랜잭션으로 묶을 수 있는가**: 코드 구조상 가능하다. `createAutoBid`는
  순수 Spring `@Transactional`(REQUIRED)이고, 실제 호출 경로(`AutoBidService.createAutoBid` →
  `IdempotencyClaimService.claimAndExecute`)도 REQUIRED 트랜잭션 하나 안에서 idempotency claim
  insert + `command.apply(...)`(=createAutoBid 본체)를 실행한다. 외부 I/O(HTTP 등)가 이 경로에
  없으므로, Goal 상태 변경을 같은 서비스 메서드 호출 스택 안에 넣으면 자동으로 같은 트랜잭션에 묶인다.
  단, **`Goal` 영속 엔티티 자체가 현재 코드베이스에 존재하지 않는다** (`MatchGoal`은 DTO일 뿐이고,
  `ai/purchase` 패키지 주석에 "PurchaseGoal 엔티티는 백엔드 담당이 만든다(미구현)"라고 명시됨).

**근거 파일:줄**
- `backend/src/main/java/com/vintic/backend/autobid/service/AutoBidCommandService.java:82-186`(createAutoBid), `:192-295`(updateAutoBid)
- `backend/src/main/java/com/vintic/backend/autobid/service/AutoBidService.java:1-45`
- `backend/src/main/java/com/vintic/backend/bid/service/IdempotencyClaimService.java:49-74`
- `backend/src/main/java/com/vintic/backend/ai/purchase/match/MatchGoal.java:1-19` (엔티티 미구현 주석)

**구현 시 영향**
- Goal 엔티티/상태 설계가 Day 1 전제조건. 트랜잭션 결합 자체는 기술적으로 막히지 않는다.
- AutoBid 등록 락 순서는 "Auction FOR UPDATE → AutoBidSetting FOR UPDATE"로 고정돼 있음(주석 명시,
  `#45` 확정) — Goal 관련 락을 추가한다면 이 순서와 충돌하지 않게 배치해야 함.

---

## 3. AutoBidSetting이 취소·종료 후에도 남는지 / 참여 이력용 별도 테이블 필요 여부

**결론**
- **취소**: 남는다. `cancel()`은 row를 삭제하지 않고 `status=CANCELED, activeSlot=null`로 갱신한다.
  `activeSlot`이 CANCELED에서 null이 되는 이유는 UNIQUE 제약(`uk_auto_bid_setting_active_slot`,
  컬럼 `auction_id, user_id, active_slot`)이 "현재 유효 설정은 1건"만 강제하고, MySQL이 NULL을
  서로 다른 값으로 취급해 CANCELED 이력은 여러 건 누적 가능하게 설계돼 있다.
- **경매 종료**: `AuctionEndService`/`AuctionSettlementService`는 `AutoBidSetting`을 전혀
  건드리지 않는다(grep 결과 상태 전이 호출부는 `ProxyResolutionApplier`, `BidCommandService`,
  `AutoBidCommandService.cancelAutoBid` 뿐). 즉 경매가 ENDED가 돼도 패자의 `AutoBidSetting`은
  `ACTIVE`/`CAP_REACHED`/`RESERVED` 상태로 그대로 남고, "패배"를 나타내는 별도 상태(enum 값)는
  없다. `AutoBidSettingStatus`는 `RESERVED, ACTIVE, CAP_REACHED, CANCELED` 4종뿐.

**근거 파일:줄**
- `backend/src/main/java/com/vintic/backend/autobid/domain/AutoBidSetting.java:136-147`(cancel), `:26-30`(activeSlot 설계 주석)
- `backend/src/main/java/com/vintic/backend/autobid/domain/AutoBidSettingStatus.java:1-6`
- grep: 상태 전이 호출부 전수 조사 (`ProxyResolutionApplier.java:18-28`, `BidCommandService.java:231`, `AutoBidCommandService.java:304`) — 경매 종료 경로 없음

**구현 시 영향**
- row 자체는 삭제되지 않으므로 "몇 번 자동입찰에 참여했는지" 카운트는 `auto_bid_settings`를
  `userId`로 직접 집계하면 되고, 별도 이력 테이블이 필수는 아니다.
- 다만 "이겼는지/졌는지"는 이 테이블만으로 판단 불가 — 승리 여부는 `Order`(§1 참고) 또는
  `auction.currentWinner`와 대조해야 한다. Agent가 "낙찰 실패"를 구분해서 기록하려면 별도 필드나
  조인 쿼리가 필요함(**미확정** — 정책 결정 필요).

---

## 4. 경매 생성 후 매칭용 상품 정보 수정 가능 여부 / Matcher 결과 (goalId, auctionId) 재사용

**결론**
- `Product` 엔티티는 모든 필드가 `private`이고 **setter가 전혀 없다** — 생성자 1회 주입 후 불변.
- `ProductController`에는 `POST /calculate-price`, `POST /api/products`(생성), `GET /api/products`
  (목록)만 있고, **수정(PUT/PATCH) 엔드포인트가 존재하지 않는다**. 즉 현재 코드베이스 기준으로
  상품 등록 후 brand/model/colorway/sizeKr/conditionGrade/componentStatus를 바꿀 방법이 없다.
- `AuctionListing`(Matcher 입력 DTO)과 `new AuctionListing(...)` 생성 호출부를 `backend/src/main`
  전체에서 찾았으나 **호출 지점이 아직 없다** — 아직 어떤 서비스도 Auction/Product로부터
  `AuctionListing`을 만들어 Matcher를 실제로 호출하고 있지 않다(인터페이스만 존재).
  (조사 당시. #109 이후 `PurchaseGoalCandidateRanker.toListing()`이 만들어 호출하고, 결과는 `PurchaseGoalMatch`에 저장한다.)
- `ListingMatcher` 인터페이스 주석에 "결과는 (goal, auction) 단위로 저장해 재호출하지 않는다
  (백엔드 담당)"이라고 명시돼 있음 — 이는 AI 팀이 백엔드에 위임한 요구사항이지, 이미 구현된
  캐시/저장 로직이 아니다.

**근거 파일:줄**
- `backend/src/main/java/com/vintic/backend/product/domain/Product.java:36-52`(필드), `:92-154`(getter만 존재, setter 없음)
- `backend/src/main/java/com/vintic/backend/product/ProductController.java:1-55`(엔드포인트 전체)
- `backend/src/main/java/com/vintic/backend/ai/purchase/match/ListingMatcher.java:1-14`(저장 위임 주석)
- `backend/src/main/java/com/vintic/backend/ai/purchase/match/AuctionListing.java:1-18`

**구현 시 영향**
- 상품 정보는 현재 불변이므로 Matcher 결과를 `(goalId, auctionId)` 키로 캐시해도 "매칭 이후 상품이
  바뀌어 결과가 stale해지는" 위험은 코드상 없음(수정 API 자체가 없으므로).
- 단, Matcher 결과 저장 테이블/엔티티는 **미구현** — Day 1에서 새로 설계해야 함.

---

## 5. 알림 중복 방지 / DB 스키마 변경 방식 / AI 연결 시그니처

### 알림 중복 방지
**결론**: `Notification` 테이블의 `business_event_key`(`"{TYPE}:{resourceId}"`, 예:
`AUCTION_WON:55`) 컬럼에 DB UNIQUE 제약(`uk_notification_business_event_key`)을 걸어
최종 방어선으로 쓴다. 애플리케이션 레벨에서는 별도 pre-check 없이 소스 엔티티(Order 등)가
이미 "한 번만 생성/전이"를 보장하는 지점에서만 `NotificationRecorder.record()`를 호출한다.

**근거 파일:줄**
- `backend/src/main/java/com/vintic/backend/notification/domain/Notification.java:24-38`
- `backend/src/main/java/com/vintic/backend/notification/service/NotificationRecorder.java:38-45`

### DB 스키마 변경 방식
**결론**: Flyway/Liquibase 등 마이그레이션 도구가 레포에 없다(`backend/src/main/resources`에
migration 디렉터리 없음). `application-dev.yml`, `application-local.yml` 모두
`spring.jpa.hibernate.ddl-auto: update`로 설정돼 있어, JPA 엔티티(`@Entity`/`@Table`/`@Column`)
변경이 곧 스키마 변경이다. `application-prod.yml`의 ddl-auto 값은 이번 조사에서 열지 않음(**미확정**).

**근거 파일:줄**
- `backend/src/main/resources/application-dev.yml:11`
- `backend/src/main/resources/application-local.yml:11`

### AI 연결 시그니처 (내부 로직 미확인, 연결부만)

- `GoalDraft`(record, `ai/purchase/dto/GoalDraft.java:18-28`):
  `(modelQuery, brand, modelKey, minCondition: GoalCondition, hardMaxAmount: Long, sizeKr: Integer, freeTextConditions, confidence: double, warnings: List<String>)`.
  계약 문서 원 필드(`modelQuery/minCondition/hardMaxAmount/freeTextConditions/confidence`) 외
  `brand/modelKey/sizeKr/warnings`가 추가돼 있음.
- `GoalCondition`(enum, `ai/purchase/dto/GoalCondition.java:12-40`): `DS > S > A > B > C`,
  `satisfiedBy(GoalCondition listing): boolean`, `fromLabel(String): Optional<GoalCondition>`.
- `ListingMatcher`(interface, `ai/purchase/match/ListingMatcher.java:11-14`):
  `evaluate(MatchGoal goal, AuctionListing listing): MatchResult`.
  - `MatchGoal(modelKey, modelQuery, brand, freeTextConditions)` — minCondition/hardMaxAmount/sizeKr 제외(백엔드 pre-filter 담당).
  - `MatchResult(matched: boolean, semanticScore: double, reason: String, listingModelKey: String)`.
- `ModelAliases`(class, `ai/purchase/model/ModelAliases.java`): 주요 public 메서드 —
  `find(String text): Optional<Match>`, `byKey(String modelKey): Optional<ModelInfo>`,
  `isKnownKey(String modelKey): boolean`, `catalog(): List<ModelInfo>`, `aliasesOf(String modelKey): List<String>`,
  `static normalize(String value): String`.
- `PriceEstimateProvider`(interface, `ai/purchase/price/PriceEstimateProvider.java:15-18`):
  `estimate(PriceEstimateQuery query): Optional<PriceEstimate>`.
  - `PriceEstimateQuery(brand, model, colorway, sizeKr, conditionGrade, componentStatus)` +
    정적 팩토리 `of(Product product)` (`Auction.getProduct()`로 바로 생성 가능하다고 주석에 명시).
  - `PriceEstimate(estimatedPrice: int, lowerBound: int, upperBound: int, source: Source[USED_MARKET|KREAM], sampleCount: int, reason: String, computedAt: LocalDateTime)`.
  - 주석: "ENGAGE 직전에 서버가 다시 계산한다. `Product.recommendedPrice`를 읽지 않는다" — 판매자가
    부풀릴 수 있는 클라이언트 값이라 신뢰하지 않는다는 명시적 정책.

---

## 정책 초안(`CAP_RATIO=1.0`, SCHEDULED 24h, 순위 할인율→종료시각→semanticScore→auctionId) 과 AutoBid 금액 규칙 충돌 여부

**결론**: 코드 레벨에서 직접 충돌하는 지점은 발견되지 않았다. 단, AutoBid 등록/수정 시 다음
기존 규칙은 CAP_RATIO 계산 결과에 상관없이 항상 적용되므로 Agent가 계산한 금액이 이 규칙을
만족하지 못하면 등록 자체가 실패한다(이건 "충돌"이 아니라 지켜야 할 제약):
- 등록/수정 시 `amount >= auction.getMinNextBidAmount()` 미달이면 `CapTooLowException`
  (`AutoBidCommandService.java:118-123`, `:234-239`).
- 수정 시 기존 상태가 `ACTIVE`/`CAP_REACHED`면 신규 금액이 기존 `maxAmount`보다 커야 함
  (`CapNotIncreasedException`, `AutoBidCommandService.java:240-246`).
- `EffectiveCapCalculator`는 `maxAmount`를 `bidIncrement` 그리드에 맞춰 내림 처리할 뿐(§0.13),
  CAP_RATIO 정책과 독립적인 계산이라 충돌 없음(`EffectiveCapCalculator.java:14-26`).

---

## Day 1을 막는 문제

1. **`Goal`(구매 목표) 영속 엔티티가 코드베이스에 전혀 없다.** `MatchGoal`은 Matcher 입력용 DTO일
   뿐이고, 주석에도 "PurchaseGoal 엔티티는 백엔드 담당이 만든다(미구현)"이라고 명시돼 있다.
   상태 필드, 상태 전이 규칙 자체가 미정 — §2/§3 질문에 대한 답이 이 엔티티 설계에 종속된다.
2. **Matcher 결과 저장(캐시) 테이블이 없다.** `ListingMatcher` 계약이 "(goal, auction) 단위로
   저장해 재호출하지 않는다"를 백엔드에 요구하지만 대응 엔티티/repository가 없다.
3. **`AuctionListing`을 실제로 만들어 Matcher를 호출하는 오케스트레이션 코드가 없다.** 스캔
   서비스(SCHEDULED 24h 탐색, 랭킹 정렬 등)가 아직 존재하지 않아 §5 정책 초안을 어느 클래스에
   구현할지부터 설계가 필요하다.
4. **패배(낙찰 실패) 상태를 나타낼 방법이 없다.** `AutoBidSettingStatus`에 "LOST" 류 상태가 없고,
   경매 종료 시 `AutoBidSetting`을 건드리는 코드가 없어, Agent가 "이 경매는 졌다"를 판단하려면
   `Order`/`auction.currentWinner`와의 조인이 필요하다 — Day 1에서 설계 확정 필요.
5. `application-prod.yml`의 `ddl-auto` 값 및 실제 배포 스키마 변경 절차는 미확인(**미확정**) —
   운영 반영 전 별도 확인 필요.

</details>

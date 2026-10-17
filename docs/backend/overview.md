# Autique 백엔드 개요

처음 백엔드를 읽는 사람이 **현재 서비스가 어떻게 동작하는지**와 **왜 그렇게 설계했는지**를 한 곳에서
이해하고, 상세 설계·실험·배포 문서로 찾아가게 하는 문서다. 기능 흐름(§2~§5)은 "무엇이 일어나는지"를,
설계 결정(§6)은 "왜 그 방식을 골랐는지"를 설명한다 — 락·멱등성·종료 판정 같은 메커니즘은 흐름에서
짧게 짚고 근거는 §6에서 한 번만 모아 설명한다.

AI 트랙 전체 안내는 `docs/ai-system.md`가, 이 문서는 백엔드(경매·입찰·주문·구매 에이전트·분석
파이프라인의 서버 쪽)를 맡는다. 두 트랙은 상품 분석·가격 계산 지점에서 맞물린다.

**상태 표기**: ✅ 구현·운영 / ⚪ 구현됐으나 기본 비활성화(환경변수로 켬) / 🧪 실험에서만 검증(운영
코드 아님) / ⚠️ 실제 AWS 배포로는 아직 확인되지 않음. 이슈 번호·당시 작업 경과는 근거가 필요할 때만
남긴다.

---

## 1. 전체 구조와 도메인별 책임

중고 신발 **경매 서비스**의 서버다. 크게 네 가지를 책임진다.

1. **상품 등록 준비** — 판매자 사진을 AI로 분석해 상품 정보 초안을 만들고, 사용자가 확인한 값으로
   추천가를 계산한다(AI 호출은 AI 트랙, 서버는 세션 상태·검증·저장 담당).
2. **경매·입찰** — 경매 생명주기(예약 → 진행 → 종료), 수동 입찰, 자동입찰(Proxy Bidding), 종료 연장.
3. **정산·주문** — 낙찰자 주문 생성, 미결제 시 차순위 이양, 결제(Mock), 노쇼 패널티.
4. **구매 에이전트(Purchase Agent)** — 자연어 구매 목표를 등록하면 서버가 주기적으로 후보 경매를 찾아
   대신 자동입찰에 참여하고 결과를 관찰한다.

이 밖에 인증(카카오 로그인 + 자체 JWT), 개인화 추천, 알림, 큐레이션이 있다.

### 프로세스 토폴로지

서비스는 **같은 애플리케이션 이미지**를 두 역할로 띄운다. 어떤 역할인지는 활성 프로필
(`SPRING_PROFILES_ACTIVE`)로만 갈린다. 로컬 개발에서는 한 프로세스가 두 역할을 겸한다.

```
                 (공개 HTTPS)
사용자/프론트 ──► Caddy(80/443) ──► API 프로세스 (프로필 dev,api)
                                   · 웹 API 전체
                                   · 도메인 스케줄러(경매 시작/종료, 결제·차순위 만료, 사진 정리)
                                   · 분석 Vision 소비는 하지 않음
                                        │
                      ┌─────────────────┼───────────────────┐
                      ▼                 ▼                   ▼
                  MySQL 8.4         Redis Streams          S3
             (경매·입찰·주문·        (분석 작업 큐)      (상품/분석 이미지)
              세션·목표·벡터)             │
                      ▲                   ▼
                      └──────── Worker 프로세스 (프로필 dev,worker)
                                · Redis Streams 소비(XREADGROUP)·PEL 회수(XAUTOCLAIM)
                                · Vision 분석 → 결과 MySQL 저장 → XACK
                                · 외부 AI(Claude/OpenAI), S3 presign · 도메인 스케줄러는 전부 끔
```

- **API 프로세스**는 웹 트래픽과 도메인 스케줄러를, **Worker 프로세스**는 사진 분석(Vision)만 전담한다.
  Worker는 웹 트래픽을 받지 않고 공인 인터넷에 노출되지 않으며, 경매 스케줄러를 끄는 이유는 API와 중복
  실행을 막기 위해서다.
- **MySQL**이 경매·입찰·주문·세션·목표·벡터의 **진실의 원천**이다. **Redis Streams**는 분석 작업을
  API→Worker로 넘기는 **작업 큐**일 뿐 상태의 원천이 아니다.
- 프로필별 토글(스케줄러·소비자·워밍업·`ddl-auto` 등)과 API/Worker의 정확한 역할 차이는
  `docs/deployment/configuration.md`에 표로 정리돼 있다. AWS 시연 환경에서는 MySQL·Redis가 API EC2
  위 컨테이너로 뜨고 Worker는 API의 사설 IP로 접속한다(배포는 `docs/deployment/runbook.md`).

### 도메인 패키지 (책임 한 줄)

`backend/src/main/java/com/vintic/backend/` 아래:

| 패키지 | 책임 |
| --- | --- |
| `analyze` | 상품 분석 세션 생명주기(사진 업로드 → 큐 적재 → 결과 저장), Vision 작업 큐 생산/소비 |
| `ai` | LLM/벤더 연동 — `vision`(사진 분석), `purchase`(목표 파서·Matcher·시세 provider), `search`(임베딩), `prompt`, `observability` |
| `product` | 상품 등록, 규칙 기반 가격 계산 |
| `price` | KREAM·eBay 등 참조 시세 데이터 |
| `auction` | 경매 애그리거트와 생명주기(시작/종료 서비스·스케줄러), 가격 변경 감사 |
| `bid` | 수동 입찰, 멱등성(`IdempotencyClaimService`) |
| `autobid` | 자동입찰 설정과 Proxy 가격 엔진(`ProxyPriceEngine`) |
| `order` | 낙찰 후 주문과 정산(`AuctionSettlementService`), 결제 만료 |
| `backupoffer` | 차순위 제안(낙찰 포기·미결제 시) |
| `penalty` | 구매자 패널티와 입찰 제한 |
| `purchasegoal` | 구매 에이전트(목표·탐색 스케줄러·후보 평가·참여·결과 관찰) |
| `recommendation` | 벡터 기반 추천, 행동 로그 |
| `notification` | 앱 내 알림 |
| `auth` / `user` | 카카오 로그인·JWT, 사용자(노쇼 횟수·입찰 제한 보유) |
| `common` / `config` | 공통 응답·예외·유틸, Spring 설정(S3·보안·스케줄링·워밍업) |

---

## 2. 상품 사진 분석 → 사용자 확인·가격 계산 → 상품·첫 경매 등록

AI가 채운 값은 전부 **초안**이고, 사용자가 확인한 값으로만 가격을 계산하고 상품을 등록한다. 이 흐름은
하나의 분석 세션(`ProductAnalysisSession`, MySQL)을 중심으로 돈다.

**① 분석 요청 (API, 비동기)** — `POST /api/products/analyze`(로그인 필수). 세션을 만들고 사진을
S3에 올린 뒤(표시용 원본 + 분석용 768px 사본) **분석 작업을 Redis Streams에 적재(XADD)** 하고 바로
**202 Accepted**로 `analysisId`와 상태만 응답한다. Vision은 아직 돌지 않는다. 상태: `CREATED →
IMAGE_UPLOADED → QUEUED`. (`analyze/service/ProductAnalyzeService.java`,
`analyze/queue/AnalysisTaskProducer.java`)

**② Vision 분석 (Worker, 비동기)** — Worker가 스트림 메시지를 받아(XREADGROUP) 세션이 `QUEUED`인지
확인하고(중복 전달이면 버림) 세 단계(실루엣·라벨·상태) Vision을 호출한다. **근거 없는 값은 검증기가
비운다**(`VisionEvidenceValidator`: 근거 없는 브랜드/색상/사이즈 등은 null, 사이즈는 글자를 실제로 읽은
근거가 있어야 채움, 하나라도 비면 "사용자 확인 필요"를 켠다). 결과를 MySQL에 저장한다. 상태: `QUEUED →
VISION_PROCESSING → AWAITING_USER_CONFIRMATION`(성공) 또는 `VISION_FAILED`. 저장 성공 시에만 XACK하며,
작업 회수·재시도 메커니즘은 §6에서 다룬다. (`analyze/queue/AnalysisTaskConsumer.java`,
`ai/vision/agent/StagedVisionAnalysisService.java`, `ai/vision/agent/VisionEvidenceValidator.java`)

**③ 가격 계산 (API)** — `POST /api/products/calculate-price`(로그인 + `analysisId` 필수). 세션
소유자·상태를 확인하고 `PRICING_PROCESSING`으로 전이한 뒤, 사용자가 확인한 값으로 **DB 트랜잭션 밖에서**
규칙 기반 추천가를 계산해 세션에 저장한다(계산 중 취소되면 결과를 버린다). 상태: `AWAITING_USER_CONFIRMATION
→ PRICING_PROCESSING → COMPLETED`(또는 `PRICING_FAILED`).

**④ 상품·첫 경매 등록 (API)** — `POST /api/products`(로그인 + `analysisId` 필수). **한 트랜잭션**에서
세션을 잠가 소유자를 확인하고 `confirmRegistration()`으로 세션을 "등록됨"으로 봉인한 뒤, `Product`와
**첫 `Auction`을 함께 저장**한다. 경매 검증이 실패하면 세션 봉인과 상품 저장까지 전부 롤백된다. 상품
수정(PUT/PATCH) API는 **없다** — 상품 필드는 등록 시점 1회 확정이다.
(`product/service/ProductRegistrationService.java`)

> **세션 취소**: `POST /api/products/analyze/{taskId}/cancel`로 아직 등록되지 않은 세션만 `CANCELLED`로
> 만들 수 있다. 취소와 등록은 같은 행 잠금으로 직렬화돼 먼저 커밋한 쪽이 이긴다.
>
> **세션 상태 전체**: `CREATED, IMAGE_UPLOADED, QUEUED, VISION_PROCESSING, AWAITING_USER_CONFIRMATION,
> PRICING_PROCESSING, COMPLETED` + 실패(`IMAGE_UPLOAD_FAILED, QUEUE_FAILED, VISION_FAILED,
> PRICING_FAILED`) + `CANCELLED`.
>
> 분석 큐의 상세(ACK·회수·재시도·실패 스트림·지표)는 `docs/ai-async-analysis.md`.

---

## 3. 경매 시작 → 수동입찰 → 자동입찰

경매 상태는 `SCHEDULED, LIVE, ENDED, CANCELED` 넷뿐이다. **유찰은 별도 상태가 아니라** `ENDED` +
낙찰자 없음(`currentWinner == null`)으로 표현한다.

**① 시작: `SCHEDULED → LIVE`** — 스케줄러가 `AuctionStartService.startIfDue()`를 주기 호출한다(API
프로세스). 경매를 잠그고 시작 시각을 다시 확인한 뒤 `LIVE`로 전환하면서 **예약(RESERVED) 자동입찰을
일괄 정산**한다(새 입찰 규칙 없이 Proxy 엔진의 기존 계산을 그대로 적용). (`auction/service/AuctionStartService.java`)

**② 수동 입찰** — `POST /api/auctions/{id}/bids`(로그인 + `Idempotency-Key` 필수). 경매를 **비관적
락**으로 잠근 뒤(`findByIdForUpdate`) 패널티·마감·판매자 본인 여부를 검사하고, 도메인(`Auction.placeManualBid`)이
**최소 입찰가·입찰 단위 정렬**을 검증한다(미달 → 40904, 단위 불일치 → 40913). 같은 사용자의 자동입찰이
있으면 취소하고, 경쟁 자동입찰은 다시 계산한다. `Bid` 1건 생성 + `currentPrice`/`currentWinner` 갱신,
마감 1분 이내 입찰이면 **종료 연장**(+3분, 최대 3회). (`bid/service/BidCommandService.java`,
`auction/domain/Auction.java`)

**③ 자동입찰 (Proxy Bidding)** — `POST`/`PATCH /api/auctions/{id}/auto-bids...`(로그인 +
`Idempotency-Key` 필수). 상한(`maxAmount`)만 받아 저장하고 서버가 경쟁자 상한과 비교해 실제 입찰가를
계산한다. 경쟁 판정은 "다른 사람의 자동입찰 존재"가 아니라 **현재 최고가/낙찰자** 기준이고, 동일 상한
경쟁은 **먼저 등록된 쪽이 이긴다**(FIRST-IN-WINS). 수정은 상향만 허용(미달 → 40907). 설정 상태는
`RESERVED, ACTIVE, CAP_REACHED, CANCELED`이며 `CAP_REACHED`는 상한을 올리면 다시 `ACTIVE`가 될 수 있다
(종단 아님). 가격 결정 엔진 `ProxyPriceEngine`은 트리거(최초 전환 / 자동입찰 등록·수정 / 수동입찰 직후)에
따라 같은 pairwise 비교 로직을 돌린다 — 왜 그 구조인지는 §6. (`autobid/service/AutoBidCommandService.java`,
`autobid/proxy/ProxyPriceEngine.java`, `autobid/domain/EffectiveCapCalculator.java`)

---

## 4. 종료 → 정산 → 주문 → 미결제 시 차순위

낙찰 "결과"는 별도 테이블이 아니라 경매·주문·차순위 상태를 **조회마다 계산**한다(side-effect free).

**① 종료·정산: `LIVE → ENDED`** — 스케줄러가 `AuctionEndService.endIfDue()`를 주기 호출한다(API
프로세스). 경매를 잠그고 **그 시점의 최신 `endAt`**(연장 반영)을 다시 읽어 종료를 판정한 뒤, `ENDED`
전환 직후 **같은 트랜잭션**에서 `AuctionSettlementService.settle()`을 호출한다. 낙찰자가 있으면 주문
(`Order`, 상태 `PAYMENT_PENDING`, 결제 기한 24시간)을 만들고 낙찰 알림을 남기며, 낙찰자가 없으면 주문을
만들지 않는다(유찰). (`auction/service/AuctionEndService.java`, `order/service/AuctionSettlementService.java`)

**② 주문·결제·차순위** — 주문 상태는 `PAYMENT_PENDING, PAID, PAYMENT_EXPIRED, CANCELED`.

```
[PAYMENT_PENDING] ── POST /orders/{id}/pay ──► PAID (Mock 결제, 재호출 멱등)
                 └─ 결제 기한 초과(스케줄러) ──► PAYMENT_EXPIRED + 미결제 패널티 + 노쇼/입찰제한 갱신 + 다음 순위 제안
[낙찰자] ── POST /auctions/{id}/award/forfeit ──► 주문 CANCELED + 포기 패널티 + 차순위 제안(WAITING)
[차순위 제안] ── accept ──► 새 주문(본인 낙찰가 기준, 기한 24h) / decline·expire ──► 다음 순위로
```

- 차순위 제안 상태는 `WAITING, ACCEPTED, DECLINED, EXPIRED`이고 **2·3순위까지만** 후보다(그 이후는
  제안하지 않음 — `BackupCandidateSelector` 단일 선정 로직을 포기·거절·만료가 공유).
- 패널티는 포기(`FORFEITED`)와 미결제(`PAYMENT_EXPIRED`) 두 종류. **노쇼 횟수·입찰 제한은 미결제만**
  센다(포기는 입찰 제한을 늘리지 않는다). 입찰 제한 기간은 고정(기본 7일, 회차별 가중 없음).
- 결제는 Mock이라 실제 PG 연동은 없다(`Order.status`만 바꾸고 `Auction.status`는 건드리지 않음). 실제
  PG·환불·배송·강제 만료 production API는 v1 범위에서 제외했다.
- (`order/service/AuctionForfeitService.java`, `order/service/OrderExpirationService.java`,
  `backupoffer/service/BackupOfferCommandService.java`, `backupoffer/service/BackupCandidateSelector.java`)

---

## 5. Purchase Agent 개요

사용자가 "뉴발 990, A급 이상, 15만원 이하"처럼 등록하면 서버가 대신 경매를 찾아 자동입찰에 참여하는
기능이다. 역할이 둘로 갈린다.

- **AI(해석·평가)**: 자연어 → 목표 초안 파싱, "이 매물이 목표에 맞나" 의미 판정. (OpenAI 또는 규칙 기반)
- **백엔드(상태 관리·검증·입찰 실행)**: 목표 상태 전이, 후보 pre-filter(하드 조건), 입찰 직전 **서버
  시세 재계산과 상한 제한**, 실제 자동입찰 호출, 결과 판정. **LLM이 낸 값이 사람 확인 없이 입찰 금액에
  직접 닿는 경로는 없다** — 검증기는 LLM 판정을 "맞다"에서 "아니다" 쪽으로만 뒤집는다.

흐름 요약: 목표 등록(사용자 확인) → ⚪ 주기 탐색(기본 비활성화)으로 후보 pre-filter → Matcher + 서버
시세 할인율 랭킹 → 입찰 직전 시세 재계산 후 상한 `min(예산, 시세×비율)`로 자동입찰 참여 → 경매 종료 후
주문 존재로 승패 판정 → 재탐색/종료.

- **백엔드 상세(엔티티·상태 전이·탐색 순서·트랜잭션·잠금·결과 판정·한계)**: `docs/purchase-agent/backend.md`
- **AI 파트(파서·Matcher·시세 provider·평가 수치)**: `docs/purchase-agent/ai.md`

---

## 6. 핵심 설계 결정과 이유

서로 다른 네 가지 문제를 **각각 다른 장치**로 푼다. 하나가 다른 하나를 대체하지 않는다.

### 동시성 제어 — 비관적 락
"서로 다른 사용자가 같은 경매 row를 동시에 read-modify-write(현재가 확인 → 입찰가 검증 → 갱신)하는"
문제다. 경합 지점이 단일 MySQL 경매 row라, 권위 있는 읽기부터 직렬화하는
`SELECT ... FOR UPDATE`(`PESSIMISTIC_WRITE`)를 쓴다.
- 🧪 통제 실험(delay로 race window를 확대): 락 없음은 20회 중 3회 post-state invariant 위반, 비관적
  락은 0/20. 성능은 동일 로컬 workload에서 비관적 락이 더 느리지만(overall median 27→60ms), outcome
  mix가 달라 "순수 오버헤드"로 단정하지 않는다.
- **운영 코드가 쓰는 동시성 제어는 비관적 락 하나뿐**이다. 대안(JVM local lock, 단일 conditional
  UPDATE, SERIALIZABLE, Optimistic Lock + retry, Redisson 분산 락, Queue/Kafka)은 검토·일부 실험만
  했고 코드에 없다. `Auction`에 `@Version` 필드가 있으나 Optimistic 비교 실험용이고 운영 경로는 읽지
  않는다. Redisson을 들이지 않은 이유: 경합이 단일 MySQL row이고 모든 write가 이미 같은 트랜잭션 안에서
  직렬화되므로 별도 coordination 시스템이 불필요하다(다중 DB·트랜잭션 밖 자원을 묶을 요구가 생기면 재검토).
- 근거·대안 비교표·수치: [`concurrency/summary.md`](experiments/concurrency/summary.md#alternatives-considered-40)
  (Optimistic 재검토는 [§Experiment C](experiments/concurrency/summary.md#experiment-c--optimistic-lock--retry-74),
  [§Production Strategy Reconsideration](experiments/concurrency/summary.md#production-strategy-reconsideration-74)).
  단일 요청 correctness 스모크는 `docs/backend/experiments/manual-bid-smoke-test.md`, 부하 한계는
  `docs/backend/experiments/hot-auction-load/summary.md`(목적이 서로 다른 별도 검증들이다).

### 멱등성 — Idempotency-Key (동시성 제어와 다른 문제)
"같은 사용자가 같은 요청을 재시도로 두 번 보내는" 문제다. 동시성 제어가 "다른 사람들이 동시에 같은
row를 고쳤는가"라면 멱등성은 "같은 사람이 같은 요청을 두 번 보냈는가"로, 서로 대체할 수 없어 둘 다 쓴다.
입찰·자동입찰 생성/수정·차순위 수락 **4개 엔드포인트**가 `Idempotency-Key`를 요구하고,
`(user, operation_scope, key)` UNIQUE 제약 + 응답 스냅샷 재생으로 중복을 막는다. 포기·결제·차순위
거절·자동입찰 취소는 상태 멱등이라 키가 없다. (`bid/service/IdempotencyClaimService.java`)

### 트랜잭션 경계
함께 성립해야 하는 작업은 **한 물리 트랜잭션**으로 묶어 함께 롤백한다 — 종료·정산(주문 생성), 세션
봉인·상품/경매 생성, 목표 전이·자동입찰 생성. 반대로 **외부 호출/시세 계산은 DB 락 밖**에서 한다(락을
쥔 채 외부 응답을 기다리지 않기 위해 — 가격 계산, 구매 에이전트 시세 재계산). 세부 lock ordering·DB
invariant·알려진 gap은 `docs/api/auction-audit.md`의 Implementation Notes에 있다.

### 종료 판정과 파생 상태
종료 판정은 스케줄러가 후보를 고른 시각이 아니라 **경매를 다시 잠근 뒤 읽은 최신 종료 시각** 기준이라,
종료 연장으로 밀린 경매를 조기 종료하지 않는다(스케줄러는 후보 id만 넘기고 시각값은 넘기지 않는다).
낙찰 "결과"를 별도 테이블로 두지 않고 경매·주문·차순위 상태에서 조회마다 계산하는 이유는 상태 불일치를
원천 차단하기 위해서다. 경매 상세의 AI 추정가도 판매자가 보낸 값이 아니라 서버가 조회 시점에 재계산한다.

### 분석 작업 회수 — ACK·펜싱·재시도
"Worker가 작업 도중 죽거나 Vision이 실패하는" 문제다. 결과를 MySQL에 저장했을 때만 XACK하고, 미처리로
남은 작업은 회수 스케줄러가 XAUTOCLAIM으로 재처리한다. 재선점 시 **펜싱 토큰**으로 이전 시도의 뒤늦은
기록을 막고, 실패를 재시도 가치별로 분류해 상한까지만 재시도한 뒤 실패 스트림으로 보낸다. 상세·남은
구멍은 `docs/ai-async-analysis.md`.

### 경매 스케줄러의 운영 가정
경매 시작/종료 스케줄러는 **리더 선출 없이 단일 프로세스**를 전제한다. 비관적 락이 상태 중복 반영은
막지만(동시 invocation을 실제 MySQL로 검증), 인스턴스를 늘리면 같은 후보를 중복 조회하는 비효율이 남는다
— leader election은 범위 밖이다. API/Worker 중복 실행을 막으려고 스케줄러는 API 프로필에서만 켜고 Worker
프로필에서는 끈다(`docs/deployment/configuration.md`).

---

## 7. 현재 한계와 관련 문서

- ⚠️ **AWS 시연 환경은 실제 배포로 확인되지 않았다.** 배포 런북·스크립트는 로컬 검증까지만 됐고 실제
  자원 생성·배포·URL 검증은 수행되지 않았다("배포 후 확인 필요" 항목은 `docs/deployment/runbook.md`).
- ⚠️ **빈 DB 최초 배포는 현재 배포 스크립트만으로 완결되지 않는다.** 스키마 사전 게이트가 기존 스키마를
  전제하므로 최초 환경 준비는 별도 수동 단계가 필요하다 — 저장소 근거와 공백은
  `docs/deployment/runbook.md`의 "최초 스키마 준비" 참고.
- **단일 인스턴스 스케줄러 가정**(위 §6) — 다중 인스턴스에서 중복 조회 비효율.
- **구매 에이전트 탐색은 기본 비활성화**이고 Matcher 기본값이 OpenAI라, 크레딧 소진 시 참여가 전면
  중단된다(`docs/purchase-agent/backend.md`, `docs/purchase-agent/ai.md`).
- **분석 파이프라인 전체 배선은 실제 Redis/운영 DB로 완전히 재현되지 않았고**(단위·부분 통합까지), 남은
  구멍(Redis 영속화, 죽은 소비자 이름 누적 등)은 **모두 해결된 상태가 아니다** — `docs/ai-async-analysis.md`.
- 🧪 **부하·동시성 실험 수치는 실험 환경 값**이다. hot-auction 부하 실험은 운영 코드를 바꾸지 않고
  측정한 별도 브랜치 보고서이고(`docs/backend/experiments/hot-auction-load/summary.md`), 운영 latency로
  일반화하지 않는다.
- 사용자별 AI 호출(분석) 비용 상한이 아직 없다 — 로그인 사용자는 제한 없이 유료 Vision을 호출할 수 있다.

### 상세 문서 지도

| 문서 | 내용 |
| --- | --- |
| `docs/api/auction-spec.md` | 경매 도메인 **API 계약(FROZEN) 정본** |
| `docs/api/spec.md` | 프론트 연동 **전체 API** 명세 |
| `docs/api/auction-audit.md` | 경매 계약 vs 현재 구현 차이(해결 이력 포함), lock ordering·구현 노트 |
| `docs/ai-async-analysis.md` | 분석 비동기 파이프라인·장애 복구(ACK·회수·실패 스트림·지표) |
| `docs/ai-system.md` | AI 트랙 전체(크롤링 → Vision → 가격 → 추천 → 구매 에이전트) |
| `docs/purchase-agent/backend.md` | 구매 에이전트 **백엔드 상세**(엔티티·상태 전이·탐색·트랜잭션·결과 판정) |
| `docs/purchase-agent/ai.md` | 구매 에이전트 **AI 파트**(파서·Matcher·시세 provider·평가 수치) |
| `docs/backend/experiments/concurrency/summary.md` | 입찰 동시성 제어 실험(조건·수치·한계·raw) |
| `docs/backend/experiments/hot-auction-load/summary.md` | 인기 경매 부하 실험 보고서 |
| `docs/backend/experiments/manual-bid-smoke-test.md` | 수동 입찰 단일 요청 스모크 검증(당시 기록) |
| `docs/deployment/runbook.md` | 배포 절차(최초 구축·일반 배포·스키마 변경·롤백·복구·중지) |
| `docs/deployment/configuration.md` | 환경변수·프로필별 설정·포트·관리 엔드포인트 참조 |

> **과거 인프라 실험(별도 브랜치).** AWS 큐/네트워크 대안을 비교한 인프라 스프린트 기록은 현재 서비스가
> 아니라 **과거 실험**이며 `infra/experiment` 브랜치에 있다(해당 AWS 자원은 모두 삭제됨). 현재 서비스는
> 분석 작업 큐로 **Redis Streams**를, 진입점으로 **Caddy(EC2에서 직접 TLS 종료)**를 쓴다 — 실험의
> **SQS·Internal ALB·RDS·ElastiCache** 구성과 구분한다. 실험에서 발견한 Redis Streams 복구 gap과 현재
> main에 반영된 보완의 연결은 `docs/ai-async-analysis.md`에서 이어 읽을 수 있다(모든 gap이 해결된 것은
> 아니다). 실험 결론은 "Redis Streams 유지, SQS 미채택"이었다(`infra/experiment` 브랜치
> `docs/infra-sprint/queue-adoption-decision.md`).

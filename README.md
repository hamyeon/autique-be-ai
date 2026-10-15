# Autique BE·AI

Autique의 백엔드와 AI 레포입니다. 중고 신발 경매 서비스의 경매·입찰·주문 API와, 사진 한 번으로 상품 정보와 가격을 채워 주는 AI 분석을 함께 담고 있습니다.

- **backend/** — Spring Boot API 서버와 비동기 분석 Worker
- **crawler/** — 중고 시세 수집과 가격 계수 산출 (Python)
- **docs/** — API 계약, AI 시스템, 배포, 실험 기록
- **loadtest/** — k6 부하 테스트

## 주요 기능

| 기능 | 설명 |
| --- | --- |
| 경매·입찰 | 수동 입찰, 자동입찰(Proxy Bidding), 종료 연장, 낙찰·차순위 이양·주문 |
| AI 상품 분석 | 사진을 올리면 브랜드·모델·색상·사이즈·상태 등급을 초안으로 채웁니다 |
| AI 가격 계산 | 당근·후르츠패밀리·KREAM 실거래 시세와 상태·구성품 계수로 추천가를 계산합니다 |
| Purchase Agent | "뉴발 990 A급 15만원 이하" 같은 자연어 목표로 경매를 찾아 대신 입찰합니다 |
| 개인화 추천 | 상품 임베딩과 행동 로그(입찰·찜·조회)로 취향에 맞는 경매를 추천합니다 |

## 기술 스택

### Backend

| 기술 | 역할 |
| --- | --- |
| Java 17 | 백엔드 개발 언어 |
| Spring Boot 3.5 | REST API 및 비즈니스 로직 |
| MySQL 8.4 | 경매·입찰·주문 데이터 관리 |
| Redis / Redis Streams | 캐싱 및 비동기 메시지 큐 |

### Server & Infrastructure

| 기술 | 역할 |
| --- | --- |
| AWS EC2 | API 서버와 Worker 서버 분리 운영 (2대) |
| Docker | 컨테이너 기반 서비스 실행 |
| Amazon S3 | 상품 이미지 저장 |
| GitHub Actions | CI/CD 자동화 |
| Amazon ECR | Docker 이미지 저장 및 배포 |

### AI

| 기술 | 역할 |
| --- | --- |
| Claude Sonnet 5 | Vision 라벨 판독 (사이즈·스타일 코드, 최종 브랜드·모델명) |
| Claude Haiku 5.5 | Vision 실루엣(브랜드·모델·색상)·상태(등급·하자) 판정 |
| Anthropic Java SDK | Spring Boot에서 Claude Messages API 호출 |
| OpenAI GPT-4o-mini | Purchase Agent 자연어 Goal 파싱, 매물 적합도 Matcher |
| OpenAI text-embedding-3-small | 상품 임베딩(1536차원) 기반 개인화 추천 |
| Structured Outputs (JSON Schema) | 응답 형식 강제와 근거 없는 필드 null 처리로 환각 방지 |
| Micrometer | AI 호출 지연·토큰·실패 지표 수집 |

### Data Pipeline

| 기술 | 역할 |
| --- | --- |
| Python | 크롤링·시세 캘리브레이션 |
| Requests / BeautifulSoup / Playwright | 당근·후르츠패밀리·KREAM 시세 수집 (robots.txt 준수) |
| CSV 시세 테이블 | 모델 시세 65개·색상 83개·상태/구성품 계수 (실거래 6,173건 기반) |

## 아키텍처

### 비동기 서버 구조

API 서버는 분석 완료까지 기다리지 않고 바로 응답하며, Worker 서버가 Redis Streams로 작업을 받아 별도로 분석합니다.

```
API 요청 → Redis Streams 발행 → Worker 수신 → S3 이미지 조회 → AI 분석 → MySQL 결과 저장 → 메시지 ACK
```

### AI 분석 구조

사진 분석은 세 단계(실루엣 / 라벨 판독 / 상태 판정)를 **동시에** 실행합니다. 전체 시간이 세 단계의 합이 아니라 가장 느린 한 단계의 시간이 됩니다. 단계마다 모델을 나눠, 가벼운 판정은 Haiku로, 작은 글씨 판독은 Sonnet으로 처리합니다.

```
사진 업로드 → S3(768px 분석용 사본) → Redis Streams → Vision 3단계 동시 분석
  → 근거 검증 → 사용자 확인·수정 → 시세 기반 가격 계산
```

| 지표 (사진 3~6장, 실제 매물 14건) | 이전 (GPT-4o) | 현재 |
| --- | --- | --- |
| Vision 분석 시간 | 14.9초 | **4.7초** |
| 분석 1건 비용 | 약 $0.05 | **약 $0.018** |

AI가 채운 값은 모두 **초안**입니다. 근거(evidence)가 없는 값은 검증기가 비우고, 빈 값이나 경고가 있으면 사용자에게 확인을 요청합니다.

## 시작하기

### 사전 준비

- JDK 17
- Docker (로컬 MySQL·Redis 실행용)
- Python 3.11 이상 (크롤러를 돌릴 때만, 3.11에서 테스트 확인)

### 백엔드 실행

```bash
cd backend
cp .env.example .env
docker compose -f docker-compose.local.yml up -d   # MySQL, Redis

export LOCAL_DB_PASSWORD=localpw                 # .env의 MYSQL_ROOT_PASSWORD와 같은 값
export JWT_SECRET=<32자 이상 임의 문자열>
export OPENAI_API_KEY=<OpenAI 키>
export ANTHROPIC_API_KEY=<Anthropic 키>          # 없으면 기동은 되지만 Vision 분석이 실패합니다
./gradlew bootRun --args='--spring.profiles.active=local'
```

서버는 `http://localhost:8080`에서 뜹니다. API 문서는 `http://localhost:8080/swagger-ui/index.html`, 상태 확인은 `http://localhost:8080/actuator/health`에서 볼 수 있습니다.
S3 업로드를 쓰려면 `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `CLOUD_AWS_S3_BUCKET`도 넣어야 합니다. 전체 환경변수는 [docs/deployment-config.md](docs/deployment-config.md)에 정리되어 있습니다.

### 크롤러 실행

```bash
cd crawler
pip install -r requirements.txt pytest
python -m pytest tests
```

> 당근마켓 수집은 2026-09-17부터 중단된 상태입니다. 검색 페이지 구조가 바뀌었고 robots.txt가 AI 크롤러를 막아, 우회하지 않고 2026-09-04 수집분을 최종 데이터로 쓰고 있습니다.

## 스크립트

| 명령 | 설명 |
| --- | --- |
| `./gradlew test` | 단위·통합 테스트 (유료 API 호출·성능 실험 제외) |
| `./gradlew test -PincludeSlowTests` | 성능 실험·실제 AI 호출 테스트까지 포함 |
| `./gradlew build` | 테스트 후 빌드 |
| `scripts/aws/deploy.sh` | API·Worker EC2 배포 (GitHub Actions가 호출) |
| `scripts/aws/smoke-analyze.sh` | 배포 후 분석 API 스모크 테스트 |
| `loadtest/k6/run-stages.sh` | 인기 경매 동시 입찰 부하 테스트 |

## 폴더 구조

```
backend/src/main/java/com/vintic/backend/
├── ai/
│   ├── vision/            # 사진 분석 (3단계 Vision, Claude/OpenAI 클라이언트)
│   ├── purchase/          # Purchase Agent (Goal 파서, Matcher, 시세 provider)
│   ├── search/            # 임베딩, 청킹
│   ├── prompt/            # 프롬프트 템플릿 로더
│   └── observability/     # AI 호출 기록·지표
├── analyze/               # 분석 요청 API, Redis Streams 큐
├── price/                 # 가격 계산
├── recommendation/        # 개인화 추천
├── auction/ bid/ autobid/ # 경매, 수동 입찰, 자동입찰
├── backupoffer/ order/    # 차순위 이양, 주문
├── penalty/ purchasegoal/ # 낙찰 포기 패널티, 구매 목표
├── auth/ user/            # 인증(JWT, 카카오), 사용자
└── common/ config/        # 공통 응답·예외, 설정

backend/src/main/resources/
├── prompts/               # Vision·Agent 프롬프트
└── data/                  # 시세 CSV, 모델 별칭 표

crawler/
├── fruitsfamily/ kream/   # 사이트별 수집기
├── calibration/           # 정제·계수 산출 (별칭, 필터, 상태·색상·구성품 계수)
└── tests/
```

## 배포

`main`으로 PR을 열면 GitHub Actions가 테스트와 빌드를 실행합니다(`pr.yml`). 배포(`deploy.yml`)는 Docker 이미지를 Amazon ECR에 올린 뒤 같은 이미지를 API EC2와 Worker EC2에 각각 배포합니다. Actions에서 수동으로 실행하거나, 저장소 변수 `AUTO_DEPLOY=true`일 때 `main` push마다 자동으로 실행됩니다.

- API EC2: `dev,api` 프로필, 앞단에 Caddy가 HTTPS를 처리합니다
- Worker EC2: `dev,worker` 프로필, 분석 큐 소비와 Vision 호출을 맡습니다

자원 구성과 비밀값 설정은 [docs/aws-demo-deployment.md](docs/aws-demo-deployment.md)를 참고하세요.

## 문서

| 문서 | 내용 |
| --- | --- |
| [docs/auction-api-spec-final.md](docs/auction-api-spec-final.md) | 경매 API 최종 계약 |
| [docs/api-spec.md](docs/api-spec.md) | 전체 API 명세 |
| [docs/ai-system.md](docs/ai-system.md) | AI 시스템 전체 (크롤링 → Vision → 가격 → 추천 → Purchase Agent), 현재 상태와 실측 수치 |
| [docs/ai-vision-agent.md](docs/ai-vision-agent.md) | Vision 프롬프트 설계, 평가 하네스, 벤더 비교 |
| [docs/ai-pricing-agent.md](docs/ai-pricing-agent.md) | 가격 계산과 시세 측정 |
| [docs/ai-purchase-agent.md](docs/ai-purchase-agent.md) | Purchase Agent AI 파트 |
| [docs/ai-async-analysis.md](docs/ai-async-analysis.md) | Redis Streams 비동기 분석과 장애 복구 |
| [docs/deployment-config.md](docs/deployment-config.md) | 배포 환경변수, 포트 구성 |
| [docs/experiments/concurrency/summary.md](docs/experiments/concurrency/summary.md) | 입찰 동시성 제어 실험 결과 |

---

## 설계 기록

경매·입찰 쪽 주요 설계 결정과 그 근거입니다.

### Concurrency Experiments

수동 입찰(`Auction`) read-modify-write 경쟁을 no-lock/pessimistic-lock 등 동일 조건에서
비교하기 위한 실험. 프로토콜/환경/raw data/요약은 `docs/experiments/concurrency/`에 분리해
관리한다.

**문제**: 동시에 여러 사용자가 같은 `Auction` row를 read-modify-write(현재가 확인 → 입찰가
검증 → 갱신)하면 lost update/stale read가 발생할 수 있다.

**후보**: JVM local lock(synchronized/ReentrantLock), 단일 conditional UPDATE, SERIALIZABLE
isolation, Pessimistic Lock(`SELECT ... FOR UPDATE`), Optimistic Lock + retry, Redisson(분산
락), Queue/Kafka(event-driven serialize) — 전체 비교표는
[summary.md §Alternatives Considered](docs/experiments/concurrency/summary.md#alternatives-considered-40)
참고.

**실험** (delay로 race window를 확대한 통제 실험, #34/#35 correctness + #36 performance)
- No-lock (#34): 동일 frozen workload 20회 중 3회 post-state invariant violation 관찰.
- Pessimistic Lock (#35): 동일 frozen workload(독립변수는 `PESSIMISTIC_WRITE` 적용 여부
  하나만) 20회 중 0회 관찰.
- Performance(delay 없이 측정, #36-A): No-lock overall median 27.3ms/p95 38.4ms(attempt
  253.8/s) vs Pessimistic Lock median 60.2ms/p95 111.7ms(attempt 82.6/s). outcome mix가
  달라(No-lock은 DB 예외 위주, Pessimistic은 business rejection 위주) 이 차이를 "Pessimistic
  Lock의 순수 오버헤드"로 단정하지 않는다 — 상세는 summary 참고.

**선택**: Pessimistic Lock 채택. 단일 MySQL `Auction` row가 contention point이고 authoritative
read부터 직렬화가 필요한 구조라, correctness 실험에서 0/20 violation을 관찰한 이 방식을
택했다(latency 비용은 감수 — 위 performance 수치 참고). production write 경로가 실제로 쓰는
concurrency-control 방식은 **Pessimistic Lock(`SELECT ... FOR UPDATE`) 하나뿐**이다 — 아래
Redisson 등은 검토만 했고 코드에 없다. `Auction`에 `@Version` 필드가 있지만 #74 Optimistic
Lock + Retry 비교 실험용으로 추가된 것이고, production 경로는 이 필드를 읽거나 조건으로 쓰지
않는다(#74도 Optimistic 0/20 violation이었으나 conflict 표본 부족 등으로 Pessimistic 유지 —
[summary.md §Experiment C](docs/experiments/concurrency/summary.md#experiment-c--optimistic-lock--retry-74),
[§Production Strategy Reconsideration](docs/experiments/concurrency/summary.md#production-strategy-reconsideration-74)).

**Idempotency vs Lock — 서로 대체 관계가 아니다.**

```text
Idempotency        — 동일 user / 동일 logical request retry의 중복 처리 방지
                      (IdempotencyClaimService, UNIQUE(user_id, operation_scope, idempotency_key))
Concurrency control — 서로 다른 user/request가 동일 Auction row를 동시에 RMW하는 문제 해결
                      (Pessimistic Lock, findByIdForUpdate)
```

`IdempotencyClaimService`가 있다고 해서 pessimistic lock이 불필요해지지 않는다 - 전자는 "같은
사람이 같은 요청을 두 번 보냈는가", 후자는 "다른 사람들이 동시에 같은 row를 고쳤는가"를
다루는 별개의 문제라 이 프로젝트는 둘 다 쓴다(입찰/자동입찰 CREATE·UPDATE/BackupOffer
accept는 Idempotency-Key + Pessimistic Lock을 함께 쓰고, forfeit/decline은 Idempotency 없이
Pessimistic Lock만 쓴다 - Idempotency-Key가 FINAL contract §0.11이 지정한 4개 endpoint에만
필요하기 때문이다).

**Redisson(분산 락) 미도입 근거**: 검토는 했으나 **구현하지 않았다.** 이 프로젝트의 contention
point는 단일 MySQL row(`Auction`)이고, 모든 write 경로가 이미 같은 DB transaction 안에서
`SELECT ... FOR UPDATE`로 직렬화된다 - Redis라는 별도 coordination 시스템을 추가로 들일
이유가 없다. 다중 DB나 DB transaction 바깥 resource까지 묶는 distributed coordination
요구가 생기면 재검토 대상이다. (근거 전문:
[summary.md 대안 비교표](docs/experiments/concurrency/summary.md#alternatives-considered-40))

- 결과 요약: [docs/experiments/concurrency/summary.md](docs/experiments/concurrency/summary.md)
- 상세 프로토콜: [docs/experiments/concurrency/protocol.md](docs/experiments/concurrency/protocol.md)
- 실행 환경: [docs/experiments/concurrency/environment.md](docs/experiments/concurrency/environment.md)
- Raw data: [no-lock-correctness.csv](docs/experiments/concurrency/raw/no-lock-correctness.csv), [pessimistic-correctness.csv](docs/experiments/concurrency/raw/pessimistic-correctness.csv), [no-lock-performance.csv](docs/experiments/concurrency/raw/no-lock-performance.csv), [pessimistic-performance.csv](docs/experiments/concurrency/raw/pessimistic-performance.csv)

### Proxy Bidding 구조

자동입찰(AutoBid) 가격 결정 엔진(`autobid/proxy/ProxyPriceEngine`)은 두 트리거만 다룬다(#41
후속에서 실제 구현, 그 이전엔 정책 문서만 있는 stub이었다):

```text
1. AutoBid 등록/수정(LIVE) - resolveForAutoBidEntrant()
2. Manual Bid 성공 직후의 즉시 반격 - resolveAfterManualBid()
```

두 메서드는 같은 pairwise 비교 로직(effectiveCap 계산 + FIRST-IN WINS tie-break)을 공유한다.
경쟁자 판정은 "다른 사용자의 ACTIVE AutoBid" 존재 여부가 아니라 `Auction.currentWinner`/
`currentPrice`를 기준으로 한다. `SCHEDULED → LIVE` 전환 시점의 RESERVED 일괄 정산
(`ProxyTrigger.None`)은 `#73-1`에서 실제 production 호출부(`AuctionStartService`)가
생겼다 - 이 엔진을 그대로 호출할 뿐 새 계산식은 없다. 상세는 아래
`Auction Lifecycle Scheduler` 절 참고.

### Award → BackupOffer → Order 흐름

경매 종료 후 낙찰/포기/차순위 이양 흐름(#56). Result는 별도 테이블이 아니라 아래 네 도메인의
상태를 조합해 매 조회마다 계산한다(`AuctionResultQueryService`, side-effect free).

```text
[경매 종료] --settle()(#73-2: AuctionEndService가 LIVE→ENDED 직후 같은 트랜잭션에서 호출)--> [winner Order: PAYMENT_PENDING]
                                                                paymentDeadline = endsAt + 24h

[winner] --POST /award/forfeit--> Order: CANCELED
                                   + FORFEITED penalty 1건
                                   + BackupOffer(rank 2): WAITING, deadline = createdAt + 24h

[rank 2] --POST /backup-offers/{id}/accept--> BackupOffer: ACCEPTED
                                               + 새 Order(rank 2 본인): PAYMENT_PENDING
                                                 purchasePrice = offer.purchasePrice(원 낙찰가 X)
                                                 paymentDeadline = 수락 시각 + 24h
      --POST /backup-offers/{id}/decline--> BackupOffer: DECLINED
                                             + BackupOffer(rank 3): WAITING (있으면)

[rank 3] --accept/decline--> 위와 동일, 단 decline 시 추가 제안 없음(#56-0: rank 4는 후보 아님)

[PAYMENT_PENDING] --GET /orders/{id}--> 조회(§12 전체 shape)
                  --POST /orders/{id}/pay--> Order: PAID(Mock, 재호출은 상태 멱등 200)
                  --scheduler, deadline 초과--> Order: PAYMENT_EXPIRED
                                                 + PAYMENT_EXPIRED penalty 1건
                                                 + User.noShowCount++/bidRestrictedUntil 갱신
                                                 + 다음 순위 BackupOffer(있으면)
[WAITING BackupOffer] --scheduler, deadline 초과--> BackupOffer: EXPIRED
                                                     + 다음 순위 BackupOffer(있으면)
```

- **settlement 호출부**: `#73-2`에서 `AuctionEndService`가 `LIVE→ENDED` 전환 직후 같은
  트랜잭션 안에서 `AuctionSettlementService.settle()`을 호출하도록 연결했다(`settle()`
  내부 로직은 그대로 재사용, 복제하지 않음). `#57`의 두 scheduler(결제 기한 만료/차순위
  제안 만료)는 여전히 ENDED 이후 단계만 다룬다 - `#73`은 그 앞 단계(SCHEDULED→LIVE/
  LIVE→ENDED)를 담당해 lifecycle 전체가 이어졌다. 상세는 아래
  `Auction Lifecycle Scheduler` 절 참고.
- **rank 2/3까지만 후보다**: `BackupCandidateSelector`가 forfeit/decline/두 만료 scheduler
  전부가 공유하는 단일 선정 로직이다(#56, #57-2에서 재사용만 하고 새 순위 정책을 만들지
  않았다).
- **Order 조회/Mock 결제(§12/§13)와 결제 기한 만료 scheduler(#57-1/#57-2)**: `GET
  /orders/{id}`, `POST /orders/{id}/pay`, `OrderExpirationScheduler`. Mock 결제라
  실제 PG 연동은 없다 - `Order.status`만 바꾸고 `Auction.status`는 건드리지 않는다. 두
  scheduler(`payment.expiration`/`backup-offer.expiration`)는 기본 비활성이고(테스트
  간섭 방지), 실제 API가 뜨는 `dev` profile에서만 명시적으로 켜져 있다.
- **UserPenalty 완성(#57-2)**: `GET /me/penalties`가 `noShowCount`/`bidRestricted`/
  `bidRestrictedUntil`/이력의 single source of truth다. `noShowCount`는 `PAYMENT_EXPIRED`
  penalty만 세고 `FORFEITED`는 세지 않는다(사용자 확정 정책) - `bidRestrictedUntil`은 고정
  기간(설정값, 기본 7일)이며 회차별 escalating은 적용하지 않는다.
- **v1 제외 범위(계약 명시)**: 실제 PG 연동, 결제수단 선택 API, 환불/webhook, 배송, 강제
  만료용 production API는 FINAL contract §13이 v1 범위에 넣지 않은 항목이라 구현하지
  않았다.
- 상세 트랜잭션 순서(lock ordering)/DB invariant/알려진 gap(특히 FORFEITED의
  bidRestrictedUntil 미반영. accept/decline/조회의 candidate 본인 검증은 #75에서 추가됨 -
  40305 `BACKUP_OFFER_ACCESS_DENIED`):
  [`docs/api/auction-api-contract-gap.md`](docs/api/auction-api-contract-gap.md)의
  `#56-1`~`#57 Implementation Notes` 참고.

### Auction Lifecycle Scheduler

`#73`. `SCHEDULED → LIVE`/`LIVE → ENDED`를 시간 기반으로 자동 전환하는 production
scheduler. FINAL API contract는 변경 없음(새 endpoint 없음, 기존 20개 그대로).

```text
[SCHEDULED] --startAt 도달--> [LIVE] + RESERVED AutoBidSetting 일괄 정산(ProxyTrigger.None)
[LIVE]      --endAt 도달-----> [ENDED] + #56 AuctionSettlementService.settle() 호출
```

- **latest endsAt 기준**: 종료 판정은 스케줄러가 candidate를 고를 때 본 시각이 아니라,
  `AuctionEndService`가 Auction을 다시 잠근 뒤 읽은 "현재" `endAt`이다 - 종료 연장(`#43`
  `maybeExtend()`)으로 밀린 경매를 최초 예정 시각 기준으로 조기 종료하지 않는다. 스케줄러는
  candidate id만 넘기고 시각값 자체는 넘기지 않는다.
- **RESERVED 활성화**: 시작 시 `ProxyPriceEngine.resolve(trigger=ProxyTrigger.None)`을
  그대로 호출한다(`#42`가 미리 만들어 둔 계산, 새 scheduler 전용 bidding rule 없음) -
  유효한 cap은 ACTIVE, finalPrice에 못 미치면 CAP_REACHED, 동일 cap은 기존 FIRST-IN
  WINS(§0.12).
- **설정**: `auction.lifecycle.batch-size`(두 scheduler 공유) /
  `auction.lifecycle.start.cron`·`.enabled` / `auction.lifecycle.end.cron`·`.enabled`.
  base 기본값은 `false`(`#57-2`와 동일한 이유 - MySqlIT가 `local` profile을 빌려 쓰는데
  기본 활성화하면 간섭한다, `#58-3`에서 실측), `dev` profile에서 명시적으로 `true`(API EC2의
  `dev,api`도 그대로 물려받음). Worker EC2용 `worker` profile(`dev,worker`)은 API와 중복
  실행되지 않도록 start/end를 `false`로 고정한다(`application-worker.yml`).
- **운영 한계**: 리더 선출/분산 조정이 없는 단일 application scheduler 가정이다 - 여러
  인스턴스가 뜨면 각자 독립적으로 polling한다. MySQL `PESSIMISTIC_WRITE`가 실제 상태
  중복 반영은 막지만(동시 invocation을 실제 MySQL로 검증함), 인스턴스 수만큼 같은
  candidate를 중복 조회/lock 경합하는 비효율은 남는다 - leader election은 이번 범위 밖.
- **Result는 여전히 derived다**: 이번에도 별도 Result entity/row를 만들지 않았다 - `GET
  /result`는 Auction/Order 상태를 매 조회마다 계산하는 기존 구조(`#56`) 그대로다.
- 상세 구현/lock 순서/MySQL IT 목록:
  [`docs/api/auction-api-contract-gap.md`](docs/api/auction-api-contract-gap.md)의
  `#73 Implementation Notes` 참고.

### Auction API Contract

```text
Auction API contract frozen (#36-B)
- canonical contract 확정: docs/auction-api-spec-final.md
- 현재 구현과의 gap은 별도 audit 문서로 관리
- endpoint 20/20 구현(계약 완전 일치와는 별개) 및 deferred implementation gap(정렬 검증/닉네임 마스킹/
  오류 코드 매핑/40909 처리) 존재 — "계약 확정"과 "구현 완료"는 다른 것으로 취급한다
```

- canonical source of truth: [docs/auction-api-spec-final.md](docs/auction-api-spec-final.md)
- freeze 상태 / endpoint별 gap / deferred 항목: [docs/api/auction-api-contract-gap.md](docs/api/auction-api-contract-gap.md)
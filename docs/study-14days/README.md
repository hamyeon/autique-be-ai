# Autique 백엔드 14일 학습 자료

이 폴더는 Autique 백엔드 담당자가 **현재 코드**를 기준으로 2주 동안 공부하기 위한 자료다.
일반 백엔드 교과서가 아니라, 이 프로젝트를 설명하는 데 필요한 개념만 골라 실제 코드와 연결한다.

목표는 세 가지다.

1. 요청이 들어왔을 때 코드와 데이터(MySQL 행, Redis 메시지)가 어떻게 움직이는지 설명한다.
2. 핵심 설계가 어떤 문제를 막는지, 무엇을 막지 못하는지 안다.
3. 발표·인턴 면접의 꼬리 질문에 내 말로, 과장 없이 답한다.

---

## 1. 조사 기준

| 항목 | 값 |
| --- | --- |
| 조사일 | 2026-11-09 |
| 브랜치 | `main` (체크아웃 상태 그대로, 브랜치 전환·pull·reset 하지 않음) |
| 커밋 | `efeb09d345806b9f51789d8f8b2c19a370e10ad7` (`Merge pull request #153 from hamyeon/fix/notifications`) |
| 미커밋 변경 | 추적되지 않은 `terraform/` 폴더 하나. 안에는 과거 실험용 `tf-dev` 로컬 state가 있고 **리소스가 0개**다(이미 destroy됨). 애플리케이션 코드 변경은 없다. |
| 로컬 전용 자료 | `artifacts/`(과거 AWS 실험 증거 파일)는 `.git/info/exclude`로 Git에서 빠져 있다. 이 PC에만 있고 저장소에는 없다. `backend/src/main/resources/application-secret.yml`도 로컬 전용 파일이다(값은 읽지 않았고 자료에 넣지 않았다). |
| 실행한 것 | 코드·설정·문서·테스트 소스 읽기, `git log`/`git show`, GitHub Actions 실행 기록 조회(`gh run list/view`), 현재 소스의 `ProxyPriceEngine`을 저장소 밖 임시 폴더에서 컴파일해 jshell로 몇 가지 입력을 직접 실행(저장소 파일은 바꾸지 않음) |
| 실행한 테스트 (검수 때 추가, 2026-11-09) | 자료가 인용하는 기존 테스트 21개 클래스·109개를 로컬(Docker + Testcontainers MySQL + 로컬 Redis)에서 실행해 **모두 통과**. D1~D3 재현용 임시 통합 테스트를 1회 실행 후 삭제(결과는 [current-code-review §3](current-code-review.md#d-결함-후보--정책현재-동작확인-수준)). 목록과 조건은 [current-code-review §1](current-code-review.md#실제-실행-기록으로-확인된-것). **2026-11-11**: D1을 수정하고 회귀 테스트 `ManualBidOverUnalignedAutoBidCapMySqlIT`를 추가(미커밋), 관련 테스트 9개 클래스 로컬 통과 |
| 실행하지 않은 것 | 전체 테스트 스위트(로컬), 실험·성능 태그 테스트, 앱 기동 후 수동 호출, 외부 AI 호출, AWS 자원 접근. 자료에서 위 21개 클래스 **밖의** 테스트를 인용할 때는 "그렇게 검증하도록 작성돼 있다"는 뜻이다(CI 기록상 2026-11-05 전체 통과). |

### 기존 학습 자료

이전에 만든 공부 자료는 **찾지 못했다.** 저장소의 모든 브랜치 파일 이름, 저장소 안의 Markdown 본문,
홈 폴더의 학습 관련 폴더를 찾아봤지만 Autique 학습 자료로 보이는 파일이 없었다. 그래서 변경 이력을
추측하지 않고 현재 코드 기준으로 새로 썼다. 대신 저장소에 남아 있는 **설계 문서·실험 보고서**(`docs/`)와
현재 코드를 비교해 [`current-code-review.md`](current-code-review.md)에 정리했다.

---

## 2. 근거 표시 규칙

모든 날짜의 자료에서 아래 꼬리표를 쓴다. 면접에서 말할 때도 이 구분을 지키면 과장하지 않게 된다.

| 꼬리표 | 뜻 | 말할 때 표현 |
| --- | --- | --- |
| **[코드]** | 현재 커밋의 실행 코드·설정에서 직접 확인한 사실 | "코드상 ~한다" |
| **[테스트]** | 테스트 코드가 그 동작을 검증하도록 작성돼 있음. §1의 21개 클래스는 2026-11-09 로컬 실행으로 통과 확인, 그 밖은 CI 기록(2026-11-05)만 있음 | "~를 검증하는 통합 테스트가 있고 통과했다"(21개 클래스) / "~를 검증하는 테스트가 있다"(그 밖) |
| **[재현]** | 이번 검수에서 임시 테스트로 1회 재현하고 삭제한 결과(D1~D3). D1은 이후 수정되며 회귀 테스트가 생겼다 | "임시 재현 테스트로 실제 API 경로에서 확인했다. 회귀 테스트로 남기지는 않았다"(D2·D3) / "재현 시나리오를 회귀 테스트로 남기고 고쳤다"(D1) |
| **[기록]** | 과거 실험·로그로 확인된 결과. **당시 코드·환경 기준**이다 | "당시 실험 환경에서 ~가 측정됐다" |
| **[일반]** | 일반적인 기술 설명(Spring, MySQL, Redis 등) | — |
| **[해석]** | 설계 이유에 대한 해석. 구현자 의도가 기록에 없을 때 | "이 구조는 ~한 장점이 있다" |
| **[미확인]** | 코드나 기록으로 확인하지 못한 것 | "확인하지 못했다" |

**경로 표기**: 메인 코드는 `backend/src/main/java/com/vintic/backend/` 아래 경로만 적는다.
예) `bid/service/BidCommandService.java`. 테스트는 `backend/src/test/java/com/vintic/backend/` 아래 경로,
설정은 `backend/src/main/resources/` 아래 경로다.

---

## 3. 14일 일정표

하루 **필수 2시간 + 선택 심화 최대 1시간**. 선택 심화를 건너뛰어도 다음 날 필수 학습에 지장이 없다.

| Day | 주제 | 필수 | 선택 | 무게 |
| --- | --- | --- | --- | --- |
| [Day 1](day01.md) | 전체 구조와 핵심 사용자 시나리오 | 2h | 1h | 기초 |
| [Day 2](day02.md) | Spring 트랜잭션·프록시·JPA·MySQL 읽기 방식 | 2h | 1h | 기초(이후 모든 날의 전제) |
| [Day 3](day03.md) | 수동입찰 전체 흐름과 비관적 락 | 2h | 1h | **최우선** |
| [Day 4](day04.md) | 멱등성 키, 중복 요청, DB 제약이라는 최종 방어선 | 2h | 1h | **최우선** |
| [Day 5](day05.md) | 자동입찰 가격 엔진(ProxyPriceEngine) 손계산 | 2h | 1h | **최우선** |
| [Day 6](day06.md) | 자동입찰 동시성·락 순서·경매 시작 정산 | 2h | 1h | **최우선** |
| [Day 7](day07.md) | 중간 복습 + 입찰 동시성·부하 실험과 Tomcat·Hikari·DB 병목 | 2h | 1h | 복습 + **실험** |
| [Day 8](day08.md) | 경매 종료·정산·주문·차순위·패널티·재경매 | 2h | 1h | **최우선** |
| [Day 9](day09.md) | 구매 에이전트 ① 상태 머신·스케줄러·충돌 방지 | 2h | 1h | **최우선** |
| [Day 10](day10.md) | 가격 산정과 구매 에이전트 ② 탐색·순위·상한·AI 경계 | 2h | 1h | 최우선/중요 |
| [Day 11](day11.md) | 비동기 분석 ① 업로드→큐→Worker→결과, 취소 경쟁 | 2h | 1h | **최우선** |
| [Day 12](day12.md) | 비동기 분석 ② 실패·재시도·회수·펜싱·남은 한계 | 2h | 1h | **최우선** |
| [Day 13](day13.md) | 현재 배포·CI/CD, AWS 실험환경·장애 주입, 관측과 알람 | 2h | 1h | 중요 + **실험** |
| [Day 14](day14.md) | 전체 흐름 정리와 모의면접 | 2h | 1h | 복습 |
| [최종 복습](final-review.md) | 전체 흐름, 설계 판단과 한계, 최종 면접 질문, 돌아갈 날짜 | — | — | — |

**순서를 이렇게 정한 이유**

- Day 2의 트랜잭션·락·REPEATABLE READ 개념이 없으면 Day 3 이후의 "왜 이 조회만 잠그는가"를 설명할 수 없다.
- 수동입찰(Day 3) → 멱등성(Day 4) → 자동입찰 계산(Day 5) → 자동입찰 동시성(Day 6) 순으로, 앞날의
  코드를 다음 날이 재사용한다.
- 종료·정산(Day 8)을 알아야 구매 에이전트의 "승패 관찰"(Day 9)을 이해할 수 있다.
- 구매 에이전트의 시세는 상품 가격 산정 코드를 그대로 쓴다. 그래서 Day 10에서 두 주제를 묶는다.
- 비동기 분석(Day 11~12)은 앞 주제와 독립적이지만, Day 2의 트랜잭션 분리·프록시 개념을 많이 쓴다.
- 직접 수행한 실험은 그 실험이 검증한 주제 바로 뒤에 둔다. 입찰 동시성·부하 실험과 병목 위치는 입찰을 다 배운 Day 7(복습과 함께), AWS 실험환경의 장애 주입·관측은 배포와 분석
  파이프라인을 다 배운 Day 13이다. 실험마다 "방법 → 관찰 → 해석 → 한계" 순서로 정리돼 있다.
- 각 날의 시간표에는 읽기뿐 아니라 **IDE에서 코드 따라가기와 문제 풀이 시간**이 들어 있다. 처음 배우는 사람 기준이며, 표 아래에 "시간이 남으면 할 것"을 따로 적었다.

---

## 4. 핵심 파일 지도

자료를 읽다가 길을 잃으면 이 표로 돌아온다. 굵게 표시한 파일이 "반드시 읽을" 파일이다.

### 경매·입찰·자동입찰

| 파일 | 역할 | 나오는 날 |
| --- | --- | --- |
| **`auction/domain/Auction.java`** | 경매 상태·현재가·최고입찰자·연장 규칙 | 3, 5, 8 |
| `auction/repository/AuctionRepository.java` | `findByIdForUpdate`(비관적 락) | 3 |
| `bid/service/ManualBidService.java` | 수동입찰 진입점(트랜잭션 없음) | 3, 4 |
| **`bid/service/BidCommandService.java`** | 수동입찰 본체 | 3, 5, 6 |
| **`bid/service/IdempotencyClaimService.java`** | 멱등성 claim + 실행 + 스냅샷 | 4 |
| `bid/domain/Idempotency.java` | `(user_id, operation_scope, idempotency_key)` UNIQUE | 4 |
| **`autobid/proxy/ProxyPriceEngine.java`** | 자동입찰 가격 계산(순수 함수) | 5 |
| `autobid/domain/EffectiveCapCalculator.java` | 실효 상한 계산 | 5 |
| `autobid/domain/AutoBidSetting.java` | 자동입찰 설정 상태 머신 | 5, 6 |
| **`autobid/service/AutoBidCommandService.java`** | 자동입찰 등록·수정·취소 | 6 |
| `autobid/repository/AutoBidSettingRepository.java` | 잠금 조회와 그 이유 | 6 |
| `auction/service/AuctionStartService.java` | `SCHEDULED→LIVE` + 예약 자동입찰 정산 | 6 |

### 종료·주문·차순위

| 파일 | 역할 | 나오는 날 |
| --- | --- | --- |
| **`auction/service/AuctionEndService.java`** | `LIVE→ENDED` + 정산 호출 | 8 |
| **`order/service/AuctionSettlementService.java`** | 낙찰자 주문 생성 | 8 |
| `order/service/AuctionForfeitService.java` | 낙찰 포기 | 8 |
| `order/service/OrderExpirationService.java` | 결제 기한 만료 + 패널티 | 8 |
| `backupoffer/service/BackupOfferCommandService.java` | 차순위 수락·거절 | 8 |
| `backupoffer/service/BackupCandidateSelector.java` | 2·3순위 선정 | 8 |
| `auction/service/AuctionRelistService.java` | 재경매(상품당 총 2회) | 8 |

### 구매 에이전트

| 파일 | 역할 | 나오는 날 |
| --- | --- | --- |
| **`purchasegoal/service/PurchaseGoalScanScheduler.java`** | 5분 주기 3단계 스캔 | 9 |
| **`purchasegoal/repository/PurchaseGoalRepository.java`** | 조건부 UPDATE로 상태 전이 | 9 |
| `purchasegoal/service/PurchaseGoalEngagementTransactionService.java` | 참여 트랜잭션 | 9 |
| `purchasegoal/service/PurchaseGoalResultObservationService.java` | 승패 관찰 | 9 |
| `purchasegoal/service/AgentManagedAuctionGuard.java` | 사용자 수동 조작 차단 | 9 |
| `purchasegoal/service/PurchaseGoalCandidateFinder.java` | 하드 필터 | 10 |
| `purchasegoal/service/PurchaseGoalCandidateRanker.java` | Matcher + 순위 | 10 |
| `purchasegoal/service/PurchaseGoalCapPolicy.java` | `min(예산, 시세×2.0)` | 10 |
| `ai/purchase/price/PricingServiceEstimateProvider.java` | 에이전트용 시세 | 10 |
| `product/service/PriceCalculationService.java` | 추천가 계산(1·2·3순위 fallback) | 10 |

### 비동기 분석

| 파일 | 역할 | 나오는 날 |
| --- | --- | --- |
| **`analyze/service/ProductAnalyzeService.java`** | 업로드 → QUEUED 커밋 → XADD | 11 |
| **`analyze/domain/ProductAnalysisSession.java`** | 세션 상태 머신·펜싱 토큰 | 11, 12 |
| `analyze/service/AnalysisQueueCommitter.java` | `REQUIRES_NEW`로 QUEUED 먼저 커밋 | 11 |
| **`analyze/queue/AnalysisTaskConsumer.java`** | Worker: claim → Vision → 저장 → ACK | 11, 12 |
| `analyze/service/VisionAttemptCoordinator.java` | 짧은 트랜잭션으로 claim/complete | 11, 12 |
| **`analyze/queue/AnalysisStreamRecoveryScheduler.java`** | XPENDING + XCLAIM 회수 | 12 |
| `analyze/queue/RedisStreamConsumerConfig.java` | Consumer Group·구독·종료 대기 | 11, 12 |
| `analyze/queue/VisionFailureClassifier.java` | 재시도 가능/과부하 판정 | 12 |
| `product/service/ProductRegistrationService.java` | 세션 봉인 + 상품·첫 경매 생성 | 11 |

### 실험 기록

| 문서 | 내용 | 나오는 날 |
| --- | --- | --- |
| `docs/backend/experiments/concurrency/protocol.md`, `summary.md` | 입찰 동시성 실험(락 없음·비관적·낙관적) 방법과 결과 | 7 |
| `docs/backend/experiments/hot-auction-load/summary.md`, `loadtest/k6/` | 인기 경매 부하 실험, 병목 원인 좁히기 | 7 |
| `infra/experiment` 브랜치 `docs/infra-sprint/experiment-results.md` (`git show`로 읽기), 로컬 `artifacts/day7~15` | AWS 실험환경 장애 주입·관측·알람 | 13 |

### 설정·배포

| 파일 | 역할 | 나오는 날 |
| --- | --- | --- |
| **`application.yml`** | 기본값(스케줄러는 대부분 꺼짐) | 1, 13 |
| `application-dev.yml` / `application-api.yml` / `application-worker.yml` / `application-local.yml` | 프로필별 켜고 끄기 | 1, 13 |
| `backend/docker-compose.aws-api.yml`, `backend/docker-compose.aws-worker.yml` | AWS 시연 배포 구성 | 13 |
| `.github/workflows/pr.yml`, `.github/workflows/deploy.yml` | CI/CD | 13 |
| `scripts/aws/deploy.sh` | SSM 배포·헬스 확인·롤백 | 13 |

---

## 5. 읽는 순서와 사용법

1. 이 README의 근거 표시 규칙(§2)을 먼저 읽는다.
2. [`current-code-review.md`](current-code-review.md)를 30분 정도 훑는다. 전부 이해하려 하지 말고
   "기존 문서와 현재 코드가 어디서 다른가"만 본다. Day 7과 Day 14에서 다시 본다.
3. Day 1부터 순서대로 읽는다. 각 날은 같은 순서로 구성돼 있다:
   오늘의 목표 → 필수 학습 순서 → 핵심 개념과 구현 → 대표 시나리오 추적 → 직접 풀 문제(정답은 뒤에) →
   면접 질문 → 이해도 체크 → 선택 심화.
4. **코드는 IDE에서 함께 연다.** 자료에는 핵심 몇 줄만 발췌했다. "어디를 주목할지"를 자료가 알려주면,
   전체 메서드는 IDE에서 직접 따라간다.
5. 문제는 답을 보기 전에 종이에 먼저 푼다. 특히 Day 5의 가격 계산은 손으로 풀어야 남는다.
6. 마지막 15분 "말로 설명하기"는 소리 내서 한다. 막히는 지점이 곧 복습할 지점이다.

---

## 6. 이 자료가 하지 않는 것

- 모든 DTO 필드·모든 API를 외우게 하지 않는다. API 명세는 `docs/api/spec.md`, 경매 계약은
  `docs/api/auction-spec.md`가 정본이다.
- AI 모델 프롬프트·Vision 정확도 수치는 다루지 않는다(AI 트랙 문서 `docs/ai-*.md` 참고). 백엔드가
  AI 호출을 어디서, 어떤 트랜잭션 경계 밖에서 부르는지만 다룬다.
- 실험 수치를 운영 실적처럼 쓰지 않는다. 이 서비스에는 실제 사용자 운영 기록이 저장소에 없다.

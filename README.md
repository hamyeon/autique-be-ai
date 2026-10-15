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

## 기술적 의사결정

### Backend

#### 입찰 동시성 — 비관적 락 채택
같은 경매에 동시에 입찰하는 상황을 20회씩 재현했을 때, 락이 없으면 3회 데이터가 어긋났고 비관적 락(`SELECT ... FOR UPDATE`)은 0회였습니다. 응답 시간(중앙값 27ms → 60ms)은 늘어나지만 정합성을 우선했습니다. 경합 지점이 MySQL row 하나라 Redis 분산 락은 도입하지 않았습니다. → [실험 결과](docs/experiments/concurrency/summary.md)

#### 멱등성 키와 락을 함께 사용
같은 사람이 같은 요청을 다시 보내는 문제는 Idempotency-Key로, 여러 사람이 같은 경매를 동시에 고치는 문제는 락으로 막습니다. 서로 다른 문제라 하나로 대체하지 않았습니다. → [설계 기록](docs/backend-design.md)

#### 낙찰 결과는 저장하지 않고 매번 계산
경매·주문·차순위 제안 상태를 조합해 조회할 때마다 결과를 계산하므로, 결과 테이블과 실제 상태가 어긋날 일이 없습니다. 낙찰자가 포기하거나 결제 기한을 넘기면 2·3순위에게 차례로 구매 기회가 넘어갑니다. → [설계 기록](docs/backend-design.md)

#### 경매 종료는 최신 종료 시각 기준
종료 직전 입찰로 연장된 경매가 원래 시각에 끝나지 않도록, 스케줄러가 경매를 잠근 뒤 다시 읽은 종료 시각으로 판정합니다. Worker 서버에서는 스케줄러를 꺼서 중복 실행을 막습니다. → [설계 기록](docs/backend-design.md)

### AI

#### Vision 3단계 동시 실행과 단계별 모델 분리
실루엣·라벨·상태 판정을 동시에 실행하고, 가벼운 판정은 Haiku 5.5, 작은 글씨 판독은 Sonnet 5에 맡겼습니다. 등급·브랜드 정확도는 유지하면서 분석 시간은 14.9초 → 4.7초, 비용은 64% 줄었습니다. → [AI 시스템](docs/ai-system.md)

#### AI 결과는 초안, 근거 없는 값은 비움
모르는 값을 그럴듯하게 채우는 것보다 비워 두는 편이 낫다고 판단했습니다. 사진 근거가 없는 값은 검증기가 지우고, 빈 값이나 경고가 있으면 사용자에게 확인을 요청합니다. → [Vision 설계](docs/ai-vision-agent.md)

#### 시세는 국내 실거래 기준, 계수는 실측값
실거래 예측 오차가 KREAM 25%, eBay 155%로 나와 eBay는 참고용으로만 씁니다. 상태 계수(1,884건)와 구성품 계수(박스 없음 0.95 → 0.90)는 실거래 데이터로 쟀고, 표본이 부족한 값은 실측값 대신 기본값을 둡니다. → [가격 계산](docs/ai-pricing-agent.md)

#### LLM은 해석만, 입찰 금액은 서버가 결정
Purchase Agent에서 LLM이 해석한 조건은 사용자 확인을 거치고, 입찰 상한은 서버가 다시 계산한 시세로 제한합니다. Matcher는 잘못 사는 것보다 놓치는 쪽을 택해, 실제 매물 59건에서 정확도 97%, 조건에 맞지 않는 매물 통과 0건입니다. → [Purchase Agent](docs/ai-purchase-agent.md)

#### 크롤링이 막히면 우회하지 않음
당근마켓이 robots.txt로 AI 크롤러를 차단한 뒤에는 수집을 멈췄습니다. 대신 이미 모은 원본을 다시 분석해 시세 제공 모델을 45개에서 65개로 늘렸습니다. → [AI 시스템](docs/ai-system.md)

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
| [docs/backend-design.md](docs/backend-design.md) | 백엔드 설계 기록 전문 (동시성, 자동입찰, 낙찰·차순위, 경매 스케줄러) |
| [docs/ai-adr.md](docs/ai-adr.md) | AI 쪽 설계 결정 기록 |

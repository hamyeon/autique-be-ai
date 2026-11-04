# 한국어 상품명 (`nameKo`) API 명세

상품 정보 확인 화면부터 신발 이름을 **큰 글씨는 한국어, 작은 글씨는 영어**로 보여주기 위한 변경입니다.

```
아식스 노바블라스트 6 블랙 - 2E 와이드      ← 큰 글씨 (nameKo)
Asics Novablast 6 Black - 2E Wide          ← 작은 글씨 (brand + modelName + color)
```

- **새 API는 없습니다.** 기존 응답에 `nameKo` 필드가 추가되고, 경매 화면의 `product.name`/`subName` 값이 바뀝니다.
- 필드만 추가했으므로 지금 클라이언트는 그대로 동작합니다.
- 영문 값(`brand`, `modelName`, `color`)은 그대로입니다. 시세 매칭과 상품 등록에는 계속 영문 값을 씁니다.

### 화면별 표시

| 화면 | 상품명 표시 | 사용하는 API |
| --- | --- | --- |
| 상품 분석 (경매 플로우 2/6) | **영어만**, 브랜드·모델명·컬러 칸을 따로 (지금과 같음) | `GET /api/products/analyze/{taskId}` — `nameKo` 없음 |
| 상품 정보 확인 | **상품명 한 줄을 한국어 / 영어로** | `POST /api/products/calculate-price` — `nameKo` 추가 |
| 등록 이후 (상세·목록·경매) | 한국어 / 영어 | 아래 4~6번 |

`nameKo`는 브랜드·모델명·색상을 **합친 상품명 한 줄**입니다. 브랜드·모델명·색상을 각각 번역한 값은 없습니다.

---

## 1. 바뀌는 응답 한눈에 보기

| API | 바뀌는 점 | 프론트 작업 |
| --- | --- | --- |
| `GET /api/products/analyze/{taskId}` | **변경 없음** | 없음 (영어 칸 그대로) |
| `POST /api/products/calculate-price` | 응답에 `nameKo` 추가 | 상품 정보 확인 화면에 표시 |
| `POST /api/products` (상품 등록) | 응답에 `nameKo` 추가 | 필요하면 표시 |
| `GET /api/products` (상품 목록) | 항목에 `nameKo` 추가 | 필요하면 표시 |
| 경매 상세·경매 카드·경매 결과·주문·차순위 (`product.name` / `subName`, `name`) | 한국어명이 있으면 `name` = 한국어명, `subName` = 영문 전체명 | **없음** (값만 바뀜) |

---

## 2. `nameKo` 필드

| 항목 | 내용 |
| --- | --- |
| 타입 | `String` (nullable) |
| 값 | 브랜드·모델명·색상을 합친 한국어 상품명 한 줄. 예: `"나이키 덩크 로우 판다"` |
| 기준 값 | 요청으로 보낸 **확정** `brand`·`modelName`·`color` (판매자가 고친 값이면 고친 값 기준) |
| 표기 기준 | KREAM 상품명 표기. 크림 데이터에 있는 신발은 크림 이름을 그대로 쓰고, 없으면 AI가 크림 표기 규칙으로 옮깁니다 |
| `null`인 경우 | 이름을 만들지 못한 경우(AI 호출 실패 등), 이 기능 이전에 등록된 상품 |

### 표시 규칙 (프론트)

```
if (nameKo) {
  큰 글씨  = nameKo
  작은 글씨 = [brand, modelName, color] 중 값 있는 것을 공백으로 이은 영문
} else {
  큰 글씨  = 영문 (지금과 같음)
}
```

---

## 3. `POST /api/products/calculate-price` — 상품 정보 확인

**요청은 바뀌지 않습니다.** 응답에 `nameKo`가 추가됩니다. 요청의 확정 `brand`·`modelName`·`color`로 정한 이름입니다.

```json
{
  "success": true,
  "data": {
    "recommendedPrice": 40000,
    "baseMarketPrice": 40000,
    "...": "기존 필드 동일",
    "kreamMatches": [],
    "ebayMatches": [],
    "nameKo": "나이키 덩크 로우 판다"
  },
  "error": null
}
```

- 판매자가 분석 값을 **그대로 두면** 분석 직후 서버가 미리 만들어 둔 이름이 나옵니다(추가 지연 없음).
- 판매자가 브랜드·모델명·색상을 **고쳤으면** 이 요청에서 새로 만듭니다. 응답이 1~2초 늦어질 수 있습니다.
- 한국어명을 만들지 못해도 **가격 계산은 실패하지 않습니다.** `nameKo`만 `null`입니다.

---

## 4. `POST /api/products` — 상품 등록

**요청은 바뀌지 않습니다.** `nameKo`를 보낼 필요가 없고, 보내도 무시됩니다.
서버가 요청의 `brand`·`modelName`·`color`로 한국어명을 정해 저장하고, 응답에 담습니다.

```json
{
  "success": true,
  "data": {
    "id": 10,
    "brand": "Nike",
    "modelName": "Dunk Low",
    "nameKo": "나이키 덩크 로우 판다",
    "color": "Panda",
    "...": "기존 필드 동일"
  },
  "error": null
}
```

- 가격 계산 때와 같은 값으로 등록하면 그때 정한 이름을 바로 씁니다(추가 지연 없음).
- 한국어명을 만들지 못해도 **등록은 실패하지 않습니다.** `nameKo`만 `null`로 저장됩니다.

---

## 5. `GET /api/products` — 상품 목록

각 항목에 `nameKo`가 추가됩니다.

```json
{
  "id": 10,
  "thumbnailImageUrl": "https://...",
  "brand": "Nike",
  "modelName": "Dunk Low",
  "nameKo": "나이키 덩크 로우 판다",
  "conditionGrade": "B",
  "sellingPrice": 290000,
  "createdAt": "2026-11-03T20:00:00"
}
```

---

## 6. 경매 화면의 `product.name` / `subName`

경매 상세(`GET /api/auctions/{auctionId}`), 경매 카드(인기·목록), 경매 결과, 주문, 차순위 제안 응답의 상품명은
서버의 같은 규칙(`ProductDisplayName`)으로 만들어집니다. **필드는 그대로이고 값만 바뀝니다.**

| 상품 상태 | `name` (큰 글씨) | `subName` (작은 글씨) |
| --- | --- | --- |
| 한국어명 있음 | `"아식스 노바블라스트 6 블랙"` | `"Asics Novablast 6 Black"` (brand + modelName + color) |
| 한국어명 없음 (지금과 같음) | `"Asics Novablast 6 Black"` | `"Novablast 6"` (modelName) |

경매 카드처럼 `name`만 있는 응답은 한국어명이 있으면 한국어명이 내려갑니다.

---

## 7. 서버 동작 (참고)

한국어명은 신발(브랜드·모델명·색상 조합)마다 **한 번만** 정해 `korean_product_name` 테이블에 저장합니다.
같은 신발은 항상 같은 이름이 나오고, 두 번째부터는 비용과 지연이 없습니다.

```
1) 저장된 이름이 있으면 그대로 사용
2) 없으면 KREAM 데이터(data/kream_normalized.csv, 가격 계산과 같은 파일)에서 한국어 상품명 조회
3) 그래도 없으면 AI(Claude Haiku)로 KREAM 표기 규칙에 맞춰 번역
→ 2·3의 결과를 저장
```

| 시점 | 동작 | 사용자가 기다리는 시간 |
| --- | --- | --- |
| Vision 분석이 끝날 때 (Worker) | 분석 결과를 **완료로 바꾼 뒤** AI 인식값으로 1→2→3을 미리 돌려 저장 | **없음** (분석 화면은 바로 뜸) |
| 가격 계산 | 확정 값으로 1→2→3. 값을 안 고쳤으면 1에서 끝남 | 값을 고쳤을 때만 1~2초 (실측 중앙값 1.6초) |
| 상품 등록 | 확정 값으로 1→2→3. 보통 가격 계산 때 저장한 이름이 바로 나옴 | 보통 없음 |

- 번역 규칙: 색상은 KREAM 상품명에서 뽑은 색상 사전을 따르고, 모델 코드·숫자·약어(`LD-1000`, `SP`, `2E` 등)는 원문 그대로 둡니다.
- 설정: `naming.korean-name.translation-enabled=false`(`NAMING_KOREAN_NAME_TRANSLATION_ENABLED`)로 끄면 AI 번역 없이 저장된 이름·크림 이름만 씁니다.
- 프롬프트: `backend/src/main/resources/prompts/naming/korean-name-v1.md`

### 배포 시 주의

- `products.name_ko` 컬럼과 `korean_product_name` 테이블이 새로 생깁니다. API 서버(`ddl-auto: update`)가 먼저 떠서 스키마를 만든 뒤 Worker(`validate`)를 띄워야 합니다 — 기존 배포 순서와 같습니다.

-- #141: 주문 유형(orders.order_type)과 상품 상태 설명(products.condition_description) 추가.
--
-- 이 SQL은 운영 DB에 실행하지 않았다(파일로만 준비).
--
-- 로컬 검증(임시 MySQL 8.4 컨테이너): 현재 엔티티로 ddl-auto:create한 스키마에서 두 컬럼을 지워
-- 변경 전 스키마를 만든 뒤 -> ddl-auto:validate 실패(missing column [order_type]) 확인 -> 이 SQL 적용
-- -> ddl-auto:validate 통과. 같은 SQL 재실행도 오류 없음. #127/#135와 같은 이유로 둔다 - API/local/dev
-- 프로필은 ddl-auto:update로 두 컬럼을 자동 추가하지만, Worker 프로필은 ddl-auto:validate라
-- 기동 시점에 Product/Order 엔티티의 새 컬럼이 DB에 이미 있어야 한다(없으면 Worker 기동 실패).
--
-- 적용 순서
--   1. (배포 전) 이 스크립트의 1단계(컬럼 추가)를 운영 DB에 적용한다. 두 컬럼 모두 NULL 허용이라
--      기존 행이 있어도 실패하지 않고, 아직 구 버전 코드가 돌고 있어도 영향이 없다(구 코드는 이
--      컬럼을 모른다 - INSERT 시 NULL로 들어간다).
--   2. 새 코드(API, Worker)를 배포한다. 어느 쪽이 먼저 기동해도 컬럼이 이미 있으므로 validate를
--      통과한다.
--   3. (배포 후) 2단계(기존 주문 유형 보정)를 적용한다. 배포 전에 적용하면 1~2 사이 구 코드가
--      만든 주문이 NULL로 남으므로, 새 코드가 모든 신규 주문에 값을 채우기 시작한 뒤에 실행한다.
--      2단계는 order_type IS NULL인 행만 바꾸므로 여러 번 실행해도 결과가 같다.
--
-- 실행 전 전제: products/orders/backup_offers/auctions 테이블이 이미 있다(ddl-auto로 최초 생성된 상태).

-- ===== 1단계: 컬럼 추가 (재실행 안전) =====

DELIMITER $$

DROP PROCEDURE IF EXISTS _add_column_if_not_exists_141$$
CREATE PROCEDURE _add_column_if_not_exists_141(
    IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl VARCHAR(512)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl := CONCAT('ALTER TABLE ', p_table, ' ADD COLUMN ', p_ddl);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

-- Product.conditionDescription: @Column(name = "condition_description", length = 1000), nullable.
-- 기존 상품은 NULL로 남는다(경매 상세 product.conditionDescription = null). 분석 세션의
-- vision_result_json에서 소급해 채우지 않는다 - 기존 상품과 분석 세션을 잇는 컬럼이 없다.
CALL _add_column_if_not_exists_141('products', 'condition_description',
    'condition_description VARCHAR(1000) NULL');

-- Order.orderType: @Enumerated(STRING) + @Column(name = "order_type"), nullable. ddl-auto가 같은
-- 엔티티로 만드는 MySQL 네이티브 ENUM과 같은 정의로 둔다 - Hibernate는 값을 알파벳순으로 나열한다
-- (로컬 MySQL 8.4에서 ddl-auto:create 결과로 확인: enum('BACKUP_ACCEPT','WINNER')).
-- NOT NULL·DEFAULT를 두지 않는다 - 기존 행에 일괄 기본값을 넣지 않기 위해서다.
CALL _add_column_if_not_exists_141('orders', 'order_type',
    'order_type ENUM(''BACKUP_ACCEPT'',''WINNER'') NULL');

DROP PROCEDURE _add_column_if_not_exists_141;

-- ===== 2단계: 기존 주문 유형 보정 (새 코드 배포 후, 재실행 안전) =====

-- (a) 차순위 수락 주문: 같은 (경매, 구매자)에 ACCEPTED 차순위 제안이 있다. 차순위 수락은
--     BackupOfferCommandService.accept()가 offer.accept()와 Order.createForBackupAccept()를
--     한 트랜잭션에서 수행하는 유일한 경로이고, orders는 (auction_id, buyer_id)가 UNIQUE라
--     이 조인으로 정확히 한 주문이 대응된다.
UPDATE orders o
JOIN backup_offers b
  ON b.auction_id = o.auction_id
 AND b.candidate_id = o.buyer_id
 AND b.status = 'ACCEPTED'
SET o.order_type = 'BACKUP_ACCEPT'
WHERE o.order_type IS NULL;

-- (b) 최초 낙찰 주문: 구매자가 경매의 최종 낙찰자(auctions.current_winner_id - 종료 시점 값으로
--     고정되고 차순위 수락으로 바뀌지 않는다)이고, 그 구매자에게 발행된 차순위 제안이 하나도
--     없다. Order.createForWinner()는 AuctionSettlementService가 auction.currentWinner에게만
--     호출한다.
UPDATE orders o
JOIN auctions a ON a.id = o.auction_id
SET o.order_type = 'WINNER'
WHERE o.order_type IS NULL
  AND a.current_winner_id IS NOT NULL
  AND o.buyer_id = a.current_winner_id
  AND NOT EXISTS (
      SELECT 1 FROM backup_offers b
      WHERE b.auction_id = o.auction_id AND b.candidate_id = o.buyer_id
  );

-- 위 두 규칙에 해당하지 않는 주문은 NULL로 남긴다(API orderType = null, "유형 미상"). 남은 행 확인용:
-- SELECT id, auction_id, buyer_id, status, created_at FROM orders WHERE order_type IS NULL;

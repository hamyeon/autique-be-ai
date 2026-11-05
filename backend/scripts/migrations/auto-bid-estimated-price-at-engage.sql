-- Purchase Agent 참여 시점 시세 스냅샷(auto_bid_settings.estimated_price_at_engage) 추가.
--
-- 이 SQL은 운영 DB에 실행하지 않았다(파일로만 준비).
--
-- 왜 필요한가: API/local/dev 프로필은 ddl-auto:update로 컬럼을 자동 추가하지만, Worker 프로필은
-- ddl-auto:validate라 기동 시점에 AutoBidSetting 엔티티의 새 컬럼이 DB에 이미 있어야 한다(없으면
-- Worker 기동 실패 - #127/#135/#141과 같은 이유).
--
-- 적용 순서
--   1. (배포 전) 이 스크립트를 운영 DB에 적용한다. NULL 허용 컬럼이라 기존 행이 있어도 실패하지 않고,
--      아직 구 버전 코드가 돌고 있어도 영향이 없다(구 코드는 이 컬럼을 모른다 - INSERT 시 NULL).
--   2. 새 코드(API, Worker)를 배포한다. 어느 쪽이 먼저 기동해도 컬럼이 이미 있으므로 validate를 통과한다.
--
-- 기존 행은 보정하지 않는다. 참여 당시 시세는 어디에도 숫자로 남아 있지 않다(PURCHASE_AGENT_ENGAGED 알림
-- 문장에만 있다) - 현재 시세로 채우면 "참여 당시 값"이라는 의미가 깨지므로 NULL로 둔다
-- (API participations[].estimatedPriceAtEngage = null). autoBidCap은 기존 max_amount를 그대로 쓰므로
-- 기존 참여도 값이 나온다.
--
-- 실행 전 전제: auto_bid_settings 테이블이 이미 있다(ddl-auto로 최초 생성된 상태).

DELIMITER $$

DROP PROCEDURE IF EXISTS _add_column_if_not_exists_engage_snapshot$$
CREATE PROCEDURE _add_column_if_not_exists_engage_snapshot(
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

-- AutoBidSetting.estimatedPriceAtEngage: @Column(name = "estimated_price_at_engage", updatable = false),
-- Long, nullable. ddl-auto가 같은 엔티티로 만드는 정의(bigint NULL)와 같게 둔다.
CALL _add_column_if_not_exists_engage_snapshot('auto_bid_settings', 'estimated_price_at_engage',
    'estimated_price_at_engage BIGINT NULL');

DROP PROCEDURE _add_column_if_not_exists_engage_snapshot;

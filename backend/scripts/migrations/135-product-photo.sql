-- #135: 사진 선업로드 + 칸별 AI 판정 기능의 product_photo 테이블 생성.
--
-- #127(127-cancel-image-analysis.sql)과 달리 ENUM 값 추가가 아니라 완전히 새로운 테이블이라
-- ddl-auto:update(local/dev/api 프로필)는 이 테이블을 자동으로 만들 수 있다. 그런데도 이 SQL을
-- 별도로 두는 이유는 Worker 프로필이 ddl-auto:validate이기 때문이다(application-worker.yml) -
-- Worker는 기동 시점에 모든 @Entity(신규 ProductPhoto 포함)가 이미 스키마와 일치해야 하므로,
-- 이번 배포에서 API/Worker 중 어느 쪽이 먼저 기동하든 그 시점에 테이블이 이미 있어야 한다.
-- #127과 동일하게, API/Worker가 공유하는 운영 DB에 새 코드를 배포하기 전에 이 SQL을 먼저
-- 적용해 테이블을 미리 만들어 둔다.
--
-- 재실행해도 안전하다(CREATE TABLE IF NOT EXISTS) - 이미 존재하면 아무 것도 하지 않는다.
--
-- 로컬 검증 경로(이 작업에서 실제로 실행한 것): 이 SQL은 운영 DB에 직접 실행하지 않았다.
-- 동작 확인은 Hibernate ddl-auto:update가 같은 컬럼 정의로 테스트 DB(MySQL Testcontainers)에
-- 테이블을 자동 생성하는 것으로 간접 확인했다(PhotoUsageCommitterConcurrencyMySqlIT 등) -
-- 이 CREATE TABLE 문의 컬럼 정의가 ProductPhoto 엔티티의 @Column 선언과 정확히 일치하도록
-- 작성했다.

CREATE TABLE IF NOT EXISTS product_photo (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    slot VARCHAR(20) NOT NULL,
    original_url VARCHAR(1000) NOT NULL,
    analysis_url VARCHAR(1000) NOT NULL,
    match_status VARCHAR(20) NULL,
    detected_view VARCHAR(20) NULL,
    analysis_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 사용 처리(PhotoUsageCommitter.claimPhotos)와 정리 대상 스캔(PhotoCleanupService.
    -- findStaleUnusedPhotoIds)이 모두 analysis_id IS NULL로 미사용 사진을 찾는다.
    KEY idx_product_photo_owner_analysis (owner_id, analysis_id),
    KEY idx_product_photo_analysis_created (analysis_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

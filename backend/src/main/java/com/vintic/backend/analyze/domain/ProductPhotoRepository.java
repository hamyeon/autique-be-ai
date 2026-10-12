package com.vintic.backend.analyze.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ProductPhotoRepository extends JpaRepository<ProductPhoto, Long> {

    // photoIds 분석 요청의 "사용 처리"가 읽는 authoritative read. ProductAnalysisSessionRepository.
    // findByIdForUpdate와 동일한 원칙(#130) - PESSIMISTIC_WRITE로 요청에 포함된 모든 사진 행을
    // 한 번에 잠그고, PhotoUsageCommitter가 짧은 트랜잭션 안에서만 쓴다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductPhoto p where p.id in :ids")
    List<ProductPhoto> findAllByIdInForUpdate(@Param("ids") List<Long> ids);

    // PhotoCleanupService가 삭제 직전 "아직도 미사용·충분히 오래됨"을 재확인할 때 쓰는 단일 행 잠금.
    // 같은 잠금을 findAllByIdInForUpdate와 공유하므로, 삭제와 사용 처리가 동시에 같은 사진을
    // 노려도 먼저 커밋된 쪽이 이긴다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductPhoto p where p.id = :id")
    Optional<ProductPhoto> findByIdForUpdate(@Param("id") Long id);

    // 정리 대상 후보를 가볍게 훑기만 하는 읽기 전용 조회(잠금 없음) - 실제 삭제 가능 여부는
    // PhotoCleanupService가 photoId 하나씩 findByIdForUpdate로 다시 확인한다.
    @Query("select p.id from ProductPhoto p where p.analysisId is null and p.createdAt < :cutoff")
    List<Long> findUnusedOlderThanIds(@Param("cutoff") LocalDateTime cutoff);
}

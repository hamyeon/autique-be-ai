package com.vintic.backend.analyze.service;

import com.vintic.backend.analyze.domain.ProductPhoto;
import com.vintic.backend.analyze.domain.ProductPhotoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

// #135: 24시간 이상 분석에 쓰이지 않은 ProductPhoto를 정리한다. PhotoCleanupScheduler가
// 주기적으로 findStaleUnusedPhotoIds()로 후보를 가볍게 훑고, 각 photoId마다
// purgeIfStillUnused()를 호출한다.
@Service
@RequiredArgsConstructor
public class PhotoCleanupService {

    private static final Duration UNUSED_RETENTION = Duration.ofHours(24);

    private final ProductPhotoRepository photoRepository;
    private final S3UploaderService s3Service;

    public List<Long> findStaleUnusedPhotoIds() {
        return photoRepository.findUnusedOlderThanIds(LocalDateTime.now().minus(UNUSED_RETENTION));
    }

    // 삭제 직전 행 잠금(findByIdForUpdate) 아래서 "아직도 미사용·충분히 오래됨"을 다시 확인한다 -
    // findStaleUnusedPhotoIds()의 스캔과 이 호출 사이에 PhotoUsageCommitter.claimPhotos()가 같은
    // 잠금을 먼저 가져가 analysisId를 채웠다면 여기서 그걸 보고 조용히 건너뛴다. 분석에 쓰이는
    // 사진을 지우면 안 된다는 요구사항을 지키는 지점이 바로 이 재확인이다.
    //
    // S3 삭제까지 같은 트랜잭션(=같은 잠금) 안에서 끝낸다 - 잠금을 먼저 풀고 S3를 지우면 그
    // 사이 claimPhotos()가 끼어들어 "이제 막 분석에 쓰이기 시작한" 사진을 지울 수 있다. 호출
    // 빈도가 낮은 배치 작업이라 S3 DeleteObject 두 번(원본+분석용) 동안 잠그는 비용은 감수한다.
    //
    // S3 삭제가 실패하면 예외가 그대로 올라가 이 트랜잭션이 롤백된다 - DB 행이 그대로 남아
    // 다음 정리 주기의 후보 스캔에 다시 걸리므로, 별도의 재시도 상태 없이 "재시도 가능"이 된다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean purgeIfStillUnused(Long photoId) {
        ProductPhoto photo = photoRepository.findByIdForUpdate(photoId).orElse(null);
        if (photo == null) {
            return false;
        }
        if (photo.isUsed() || photo.getCreatedAt().isAfter(LocalDateTime.now().minus(UNUSED_RETENTION))) {
            return false;
        }

        s3Service.deleteObjects(List.of(photo.getOriginalUrl(), photo.getAnalysisUrl()));
        photoRepository.delete(photo);
        return true;
    }
}

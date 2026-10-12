package com.vintic.backend.analyze.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

// #135: 1시간마다 미사용 사진 정리를 돈다. AnalysisStreamRecoveryScheduler와 같은 원칙(인스턴스
// 마다 독립적으로 돈다, 리더 선출 없음) - 실제 삭제 가능 여부 판단과 잠금은 PhotoCleanupService
// 쪽(행 단위 PESSIMISTIC_WRITE)에 있어서, 이 스케줄러가 여러 인스턴스에서 동시에 돌거나 중복
// 실행되어도 같은 사진을 두 번 지우거나 사용 중인 사진을 지우는 일은 생기지 않는다.
//
// Worker 프로필에서는 photo.cleanup.enabled=false로 꺼둔다(application-worker.yml) - 다른
// 도메인 스케줄러(Auction/Payment/PurchaseGoal 등)와 같은 이유로, API 인스턴스 하나만 돌리면
// 충분하다.
@Component
@RequiredArgsConstructor
@Slf4j
public class PhotoCleanupScheduler {

    private final PhotoCleanupService cleanupService;

    @Value("${photo.cleanup.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${photo.cleanup.scan-interval-ms:3600000}")
    public void cleanupUnusedPhotos() {
        if (!enabled) {
            return;
        }

        List<Long> candidates;
        try {
            candidates = cleanupService.findStaleUnusedPhotoIds();
        } catch (RuntimeException e) {
            log.error("미사용 사진 후보 조회에 실패했습니다.", e);
            return;
        }

        int purged = 0;
        for (Long photoId : candidates) {
            try {
                if (cleanupService.purgeIfStillUnused(photoId)) {
                    purged++;
                }
            } catch (RuntimeException e) {
                // S3 삭제 실패 등 - 행이 그대로 남아 다음 주기에 다시 후보로 잡힌다.
                log.warn("미사용 사진 정리에 실패해 다음 주기로 미룹니다. photoId={}", photoId, e);
            }
        }

        if (purged > 0) {
            log.info("미사용 사진을 정리했습니다. purged={}, candidates={}", purged, candidates.size());
        }
    }
}

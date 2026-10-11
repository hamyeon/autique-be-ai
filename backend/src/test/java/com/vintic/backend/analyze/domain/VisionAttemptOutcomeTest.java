package com.vintic.backend.analyze.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// #130: fromGuardFailure()가 CREATED/IMAGE_UPLOADED를 ALREADY_FINALIZED로 잘못 분류해
// Worker가 QUEUED 커밋 전에 읽은 메시지를 "이미 종료됨"으로 오판하고 ACK해버리는 문제의
// 핵심 수정 지점이라 별도로 직접 검증한다.
class VisionAttemptOutcomeTest {

    @Test
    void CREATED와_IMAGE_UPLOADED는_NOT_YET_QUEUED로_분류된다() {
        assertThat(VisionAttemptOutcome.fromGuardFailure(AnalysisStatus.CREATED))
                .isEqualTo(VisionAttemptOutcome.NOT_YET_QUEUED);
        assertThat(VisionAttemptOutcome.fromGuardFailure(AnalysisStatus.IMAGE_UPLOADED))
                .isEqualTo(VisionAttemptOutcome.NOT_YET_QUEUED);
    }

    @Test
    void QUEUED와_VISION_PROCESSING은_OWNERSHIP_LOST로_분류된다() {
        assertThat(VisionAttemptOutcome.fromGuardFailure(AnalysisStatus.QUEUED))
                .isEqualTo(VisionAttemptOutcome.OWNERSHIP_LOST);
        assertThat(VisionAttemptOutcome.fromGuardFailure(AnalysisStatus.VISION_PROCESSING))
                .isEqualTo(VisionAttemptOutcome.OWNERSHIP_LOST);
    }

    @Test
    void 그_밖의_종료_상태는_ALREADY_FINALIZED로_분류된다() {
        for (AnalysisStatus status : new AnalysisStatus[]{
                AnalysisStatus.AWAITING_USER_CONFIRMATION, AnalysisStatus.PRICING_PROCESSING,
                AnalysisStatus.COMPLETED, AnalysisStatus.IMAGE_UPLOAD_FAILED, AnalysisStatus.QUEUE_FAILED,
                AnalysisStatus.VISION_FAILED, AnalysisStatus.PRICING_FAILED, AnalysisStatus.CANCELLED
        }) {
            assertThat(VisionAttemptOutcome.fromGuardFailure(status))
                    .as("status=%s", status)
                    .isEqualTo(VisionAttemptOutcome.ALREADY_FINALIZED);
        }
    }
}

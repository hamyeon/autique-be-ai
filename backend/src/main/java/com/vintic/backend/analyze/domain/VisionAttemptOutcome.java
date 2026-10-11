package com.vintic.backend.analyze.domain;

// Vision 처리 시도(claim/reclaim/complete/fail)의 결과. AnalysisTaskConsumer와 PEL 회수
// 스케줄러가 이 값으로 XACK 여부를 결정한다. InvalidAnalysisStatusException 하나로는 "이미
// 끝나서 ACK해도 되는 상황"과 "다른 시도가 소유권을 가져가서 ACK하면 안 되는 상황"을 구분할 수
// 없어 별도 타입으로 뺐다.
public enum VisionAttemptOutcome {

    // 이번 시도가 반영됨(결과/상태 변경이 한 트랜잭션에 커밋됨) - ACK 대상.
    COMMITTED,

    // #130: CREATED/IMAGE_UPLOADED, 즉 QUEUED 커밋이 아직 이 Worker에게 보이지 않는 상태로
    // 확인됨 - ProductAnalyzeService.submitForAnalysis()가 QUEUED를 커밋하기 전에 enqueue가
    // 먼저 보인 경우(정상 순서에서는 발생하지 않아야 하지만, 복제 지연 등으로 남을 수 있는
    // 창을 대비한 방어선)다. ALREADY_FINALIZED와 달리 "아직 끝나지 않았으니 다시 시도해야
    // 한다"는 뜻이라 ACK하지 않는다 - 메시지가 PEL에 남아 기존 회수 정책
    // (AnalysisStreamRecoveryScheduler)으로 다시 전달된다.
    NOT_YET_QUEUED,

    // 이미 이 단계를 벗어난 최종 상태로 확인됨(다른 시도가 이미 끝냈거나, DB 완료 후 ACK만
    // 실패해 재전달된 경우) - 재처리 없이 ACK만 한다.
    ALREADY_FINALIZED,

    // 다른 처리 시도(재선점)가 이미 이 세션의 소유권을 가져감 - ACK 금지, 재작업하지 않는다.
    OWNERSHIP_LOST;

    // claim/reclaim/complete/fail 시도가 InvalidAnalysisStatusException으로 막혔을 때, 시도
    // 직전에 읽은 상태를 근거로 세 결과를 구분한다. QUEUED/VISION_PROCESSING은 아직 이 단계
    // 안에 있다는 뜻이라 "다른 시도가 진행 중/가져감"(OWNERSHIP_LOST)으로 본다. CREATED/
    // IMAGE_UPLOADED는 Vision 단계에 아직 들어오지도 않은 상태라 "이미 종료"가 아니라 "아직
    // 준비 중"(NOT_YET_QUEUED)으로 구분한다 - 이 둘을 ALREADY_FINALIZED로 묶으면 QUEUED 커밋
    // 직전에 배달된 메시지를 "이미 끝남"으로 오판해 ACK해버려 작업이 영구히 유실된다(#130).
    // 그 밖의 상태(CANCELLED/COMPLETED/*_FAILED/AWAITING_USER_CONFIRMATION/PRICING_PROCESSING)는
    // 실제로 이 단계를 벗어났다는 뜻이라 ALREADY_FINALIZED로 본다.
    public static VisionAttemptOutcome fromGuardFailure(AnalysisStatus statusBeforeAttempt) {
        if (statusBeforeAttempt == AnalysisStatus.QUEUED || statusBeforeAttempt == AnalysisStatus.VISION_PROCESSING) {
            return OWNERSHIP_LOST;
        }
        if (statusBeforeAttempt == AnalysisStatus.CREATED || statusBeforeAttempt == AnalysisStatus.IMAGE_UPLOADED) {
            return NOT_YET_QUEUED;
        }
        return ALREADY_FINALIZED;
    }
}

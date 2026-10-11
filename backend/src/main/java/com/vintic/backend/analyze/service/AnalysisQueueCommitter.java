package com.vintic.backend.analyze.service;

import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.common.exception.AnalysisSessionNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// #130: IMAGE_UPLOADED -> QUEUED 전이를 Redis 발행과 분리된, 독립적으로 커밋되는 트랜잭션에서
// 수행한다. ProductAnalyzeService.submitForAnalysis()는 이 메서드가 반환한 뒤에만
// AnalysisTaskProducer.enqueue()를 호출하므로, Worker가 enqueue로 메시지를 받을 수 있는
// 시점에는 QUEUED 커밋이 이미 끝나 있음이 보장된다.
//
// REQUIRES_NEW로 분리하는 이유는 AnalysisFailureRecorder와 동일하다: submitForAnalysis()에는
// 지금 트랜잭션이 없어 각 save()가 이미 개별 커밋되지만, 같은 클래스 내부에 @Transactional
// 메서드를 추가해 self-invocation으로 호출하면 프록시가 적용되지 않는다 - 별도 빈으로 호출해야
// 트랜잭션이 실제로 걸리고, REQUIRES_NEW를 명시해 두면 이 서비스의 호출부가 나중에
// @Transactional을 갖게 되더라도(예: 상위 메서드 변경) 이 커밋이 그 트랜잭션에 묶이지 않고
// 항상 독립적으로 먼저 끝난다는 보장이 깨지지 않는다.
//
// REQUIRES_NEW는 "독립적으로 커밋된다"만 보장할 뿐, ProductAnalyzeService.cancel()과의 상호
// 배제는 보장하지 않는다 - 그래서 findByIdForUpdate(PESSIMISTIC_WRITE)로 cancel()이 잠그는
// 바로 그 행을 똑같이 잠근다(ProductAnalysisSessionRepository 참고). cancel()이 먼저
// 커밋됐으면 이 트랜잭션은 그 이후 상태(CANCELLED)를 읽고 markQueued()의 가드(IMAGE_UPLOADED
// 전제)에 막혀 예외를 던진다 - CANCELLED를 QUEUED로 되돌리지 않는다.
@Service
@RequiredArgsConstructor
public class AnalysisQueueCommitter {

    private final ProductAnalysisSessionRepository sessionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commitQueued(Long sessionId) {
        ProductAnalysisSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new AnalysisSessionNotFoundException(
                        "분석 세션을 찾을 수 없습니다. analysisId: " + sessionId
                ));
        session.markQueued();
        sessionRepository.save(session);
    }
}

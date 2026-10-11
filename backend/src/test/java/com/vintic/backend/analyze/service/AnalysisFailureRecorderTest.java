package com.vintic.backend.analyze.service;

import com.vintic.backend.analyze.domain.AnalysisStatus;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// @DataJpaTest는 기본적으로 각 테스트를 트랜잭션으로 감싸고 끝나면 롤백한다.
// 이 테스트는 그 반대 상황(REQUIRES_NEW로 기록한 실패 상태가 "상위" 트랜잭션의
// 롤백과 무관하게 실제로 커밋되어 남는지)을 검증해야 하므로, 클래스 레벨에서
// propagation을 NOT_SUPPORTED로 재정의해 기본 롤백 동작을 끈다.
@DataJpaTest
@Import(AnalysisFailureRecorder.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisFailureRecorderTest {

    @Autowired
    private ProductAnalysisSessionRepository sessionRepository;

    @Autowired
    private AnalysisFailureRecorder failureRecorder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 상위_트랜잭션이_롤백되어도_REQUIRES_NEW로_기록한_실패_상태는_커밋된_채로_남는다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markImageUploaded(java.util.List.of("https://example.com/a.jpg"));
        session.markQueued();
        session.claimVisionProcessing("test-token");
        ProductAnalysisSession saved = sessionRepository.save(session);
        Long sessionId = saved.getId();

        // 나중에 오케스트레이터 메서드에 @Transactional이 붙는 상황을 흉내낸 "상위 트랜잭션"
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> outerTransaction.executeWithoutResult(status -> {
            failureRecorder.recordVisionFailure(sessionId, "test-token", "OpenAI 호출 실패");
            throw new RuntimeException("상위 트랜잭션 강제 실패");
        }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("상위 트랜잭션 강제 실패");

        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.VISION_FAILED);
        assertThat(reloaded.getFailureMessage()).isEqualTo("OpenAI 호출 실패");
    }

    // #130: enqueue 예외는 "메시지가 발행되지 않았다"를 보장하지 않는다 - Worker가 이미
    // claim해 VISION_PROCESSING으로 넘어간 뒤에 뒤늦게 도착한 발행 실패 기록은 무시돼야
    // 하고(QUEUE_FAILED로 되돌리면 안 됨), claim 이전의 세션은 정상적으로 QUEUE_FAILED로 남아야 한다.
    @Test
    void 이미_QUEUED를_벗어난_세션은_발행_실패_기록이_덮어쓰지_않는다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markImageUploaded(java.util.List.of("https://example.com/a.jpg"));
        session.markQueued();
        session.claimVisionProcessing("test-token"); // Worker가 이미 가져간 상황을 흉내낸다.
        Long sessionId = sessionRepository.save(session).getId();

        failureRecorder.recordQueueingFailure(sessionId, "Redis 응답 타임아웃(실제로는 발행됐을 수 있음)");

        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.VISION_PROCESSING);
        assertThat(reloaded.getFailureStage()).isNull();
        assertThat(reloaded.getFailureMessage()).isNull();
    }

    @Test
    void QUEUED_상태의_세션은_발행_실패가_정상적으로_기록된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markImageUploaded(java.util.List.of("https://example.com/a.jpg"));
        session.markQueued();
        Long sessionId = sessionRepository.save(session).getId();

        failureRecorder.recordQueueingFailure(sessionId, "Redis 연결 실패");

        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.QUEUE_FAILED);
        assertThat(reloaded.getFailureMessage()).isEqualTo("Redis 연결 실패");
    }
}

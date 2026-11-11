package com.vintic.backend.config;

import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.auction.service.AuctionEndScheduler;
import com.vintic.backend.auction.service.AuctionEndService;
import com.vintic.backend.auction.service.AuctionStartScheduler;
import com.vintic.backend.auction.service.AuctionStartService;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalRepository;
import com.vintic.backend.purchasegoal.service.PurchaseGoalCandidateFinder;
import com.vintic.backend.purchasegoal.service.PurchaseGoalCandidateRanker;
import com.vintic.backend.purchasegoal.service.PurchaseGoalEngagementService;
import com.vintic.backend.purchasegoal.service.PurchaseGoalExpirationService;
import com.vintic.backend.purchasegoal.service.PurchaseGoalResultObservationService;
import com.vintic.backend.purchasegoal.service.PurchaseGoalScanScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// application.yml의 기본 cron 값이 실제 @Scheduled 등록에 반영됐는지 확인한다(상수 비교가 아니라
// Spring이 등록한 CronTask의 표현식을 본다). 도메인 의존성은 전부 mock - 스케줄러 3개와 설정만 띄운다.
// enabled 기본값이 false라 등록된 작업이 돌아도 아무 일도 하지 않는다.
@SpringBootTest(
        classes = {
                SchedulingConfig.class, ClockConfig.class,
                AuctionStartScheduler.class, AuctionEndScheduler.class, PurchaseGoalScanScheduler.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ImportAutoConfiguration(TaskSchedulingAutoConfiguration.class)
class SchedulingCronConfigTest {

    @MockitoBean
    private AuctionRepository auctionRepository;
    @MockitoBean
    private AuctionStartService auctionStartService;
    @MockitoBean
    private AuctionEndService auctionEndService;
    @MockitoBean
    private PurchaseGoalRepository purchaseGoalRepository;
    @MockitoBean
    private PurchaseGoalExpirationService expirationService;
    @MockitoBean
    private PurchaseGoalResultObservationService resultObservationService;
    @MockitoBean
    private PurchaseGoalCandidateFinder candidateFinder;
    @MockitoBean
    private PurchaseGoalCandidateRanker candidateRanker;
    @MockitoBean
    private PurchaseGoalEngagementService engagementService;

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @Test
    void 경매_시작_종료는_10초_구매_에이전트_스캔은_30초_cron으로_등록된다() {
        Map<String, String> cronByMethod = scheduledTaskHolder.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                // Spring 6.1+는 실행 결과 추적용 래퍼로 감싸 등록한다 - toString()이 "클래스.메서드"를 돌려준다.
                .collect(Collectors.toMap(task -> task.getRunnable().toString(), CronTask::getExpression));

        assertThat(cronByMethod)
                .containsEntry(AuctionStartScheduler.class.getName() + ".startDueAuctions", "*/10 * * * * *")
                .containsEntry(AuctionEndScheduler.class.getName() + ".endDueAuctions", "*/10 * * * * *")
                .containsEntry(PurchaseGoalScanScheduler.class.getName() + ".scan", "*/30 * * * * *");
    }
}

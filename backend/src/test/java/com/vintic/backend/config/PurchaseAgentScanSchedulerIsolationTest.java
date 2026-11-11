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
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

// 구매 에이전트 스캔이 오래 걸려도(AI 호출 지연을 흉내) 경매 시작/종료 스케줄러는 계속 돈다는 것과,
// 스캔이 겹쳐 실행되거나 밀린 실행이 한꺼번에 몰리지 않는다는 것을 실제 Spring 스케줄러로 확인한다.
// 주기를 1초로 줄여 빠르게 본다. 첫 단계(만료 처리) mock을 latch로 막아 스캔 하나가 스레드를 붙잡게 한다.
@SpringBootTest(
        classes = {
                SchedulingConfig.class, ClockConfig.class,
                AuctionStartScheduler.class, AuctionEndScheduler.class, PurchaseGoalScanScheduler.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "auction.lifecycle.start.enabled=true",
                "auction.lifecycle.start.cron=* * * * * *",
                "auction.lifecycle.end.enabled=true",
                "auction.lifecycle.end.cron=* * * * * *",
                "purchase-agent.scan.enabled=true",
                "purchase-agent.scan.cron=* * * * * *"
        }
)
@ImportAutoConfiguration(TaskSchedulingAutoConfiguration.class)
@DirtiesContext
class PurchaseAgentScanSchedulerIsolationTest {

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
    private ThreadPoolTaskScheduler taskScheduler;

    @Test
    void 에이전트_스캔이_막혀_있어도_경매_시작_종료는_실행되고_스캔은_겹치거나_몰리지_않는다() throws Exception {
        CountDownLatch scanEntered = new CountDownLatch(1);
        CountDownLatch releaseScan = new CountDownLatch(1);
        AtomicInteger scanCalls = new AtomicInteger();
        AtomicInteger runningScans = new AtomicInteger();
        AtomicInteger maxRunningScans = new AtomicInteger();
        Set<String> scanThreads = ConcurrentHashMap.newKeySet();
        given(expirationService.expireDueGoals()).willAnswer(invocation -> {
            int running = runningScans.incrementAndGet();
            maxRunningScans.accumulateAndGet(running, Math::max);
            scanCalls.incrementAndGet();
            scanThreads.add(Thread.currentThread().getName());
            try {
                scanEntered.countDown();
                releaseScan.await(30, TimeUnit.SECONDS); // 첫 스캔만 실제로 막힌다(이후 호출은 바로 통과)
                return 0;
            } finally {
                runningScans.decrementAndGet();
            }
        });

        Set<String> auctionThreads = ConcurrentHashMap.newKeySet();
        CountDownLatch startRunsWhileBlocked = new CountDownLatch(2);
        CountDownLatch endRunsWhileBlocked = new CountDownLatch(2);
        given(auctionRepository.findScheduledDueForStart(any(), any(), any())).willAnswer(invocation -> {
            auctionThreads.add(Thread.currentThread().getName());
            startRunsWhileBlocked.countDown();
            return List.of();
        });
        given(auctionRepository.findLiveDueForEnd(any(), any(), any())).willAnswer(invocation -> {
            auctionThreads.add(Thread.currentThread().getName());
            endRunsWhileBlocked.countDown();
            return List.of();
        });

        try {
            assertThat(scanEntered.await(10, TimeUnit.SECONDS)).isTrue();

            // 스캔이 스레드를 붙잡고 있는 동안에도 경매 시작/종료는 각각 여러 번 실행된다.
            assertThat(startRunsWhileBlocked.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(endRunsWhileBlocked.await(10, TimeUnit.SECONDS)).isTrue();

            // 막혀 있는 동안 지나간 cron 시각(1초마다)에도 스캔은 새로 시작되지 않는다(중첩 없음).
            assertThat(scanCalls.get()).isEqualTo(1);
        } finally {
            releaseScan.countDown();
        }

        // 밀린 시각만큼 몰아서 실행하지 않는다 - 풀린 직후 0.9초 안에는 많아야 다음 시각 1번만 돈다.
        int callsAtRelease = scanCalls.get();
        Thread.sleep(900);
        assertThat(scanCalls.get() - callsAtRelease).isLessThanOrEqualTo(1);

        assertThat(maxRunningScans.get()).isEqualTo(1);
        assertThat(scanThreads).allSatisfy(name -> assertThat(name).startsWith("purchase-agent-scan-"));
        // 다른 작업은 기존 기본 스케줄러(스레드 1개, spring.task.scheduling 기본값)를 그대로 쓴다.
        assertThat(auctionThreads).allSatisfy(name -> assertThat(name).startsWith("scheduling-"));
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
    }
}

package com.vintic.backend.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

// @Scheduled를 켠다.
//
// BackendApplication에 붙이지 않고 별도 설정으로 뺐다. 메인 클래스에 붙이면
// @SpringBootTest 계열이 전부 스케줄러를 끌고 들어와, 테스트 도중 배치가 돌 수 있다.
// 설정으로 두면 필요한 테스트만 골라 import 하거나 제외할 수 있다.
//
// 스케줄러 스레드: 모든 @Scheduled 작업은 기본 taskScheduler(스레드 1개, spring.task.scheduling.*)를
// 함께 쓴다. 구매 에이전트 스캔만 전용 스레드(purchaseAgentScanScheduler)로 뺐다 - 스캔은 후보마다
// AI(ListingMatcher)를 호출해 오래 걸릴 수 있는데, 같은 스레드를 쓰면 그동안 경매 시작/종료가 멈춘다.
//
// TaskScheduler 빈을 하나라도 직접 등록하면 Spring Boot가 기본 taskScheduler를 만들지 않는다.
// 그래서 기본 taskScheduler도 Boot가 쓰는 것과 같은 builder(spring.task.scheduling.* 반영)로 여기서
// 직접 만든다. @Scheduled에 scheduler를 지정하지 않은 작업은 "taskScheduler" 이름으로 이 빈을 쓴다.
@Configuration
@EnableScheduling
public class SchedulingConfig {

    public static final String PURCHASE_AGENT_SCAN_SCHEDULER = "purchaseAgentScanScheduler";

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    // 스레드 1개 - 스캔끼리 겹쳐 실행되지 않는다. cron 작업은 이전 실행이 끝난 뒤 다음 시각을 다시
    // 계산하므로, 스캔이 주기보다 오래 걸리면 지나간 시각은 건너뛰고 밀린 실행이 쌓이지 않는다.
    @Bean(name = PURCHASE_AGENT_SCAN_SCHEDULER)
    public ThreadPoolTaskScheduler purchaseAgentScanScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("purchase-agent-scan-");
        return scheduler;
    }
}

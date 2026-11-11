package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.bid.service.ManualBidService;
import com.vintic.backend.concurrency.support.RaceWindowDelay;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// 종료 연장 "최대 1회"가 동시 입찰에서도 지켜지는지 실제 MySQL에서 검증한다.
//
// 재현하려는 race: 남은 시간이 10초 이하인 경매에 여러 사용자가 동시에 입찰하면, 각 트랜잭션이
// 같은 (endAt, extensionCount=0)을 읽고 각자 maybeExtend()를 통과해 연장이 중복되거나 endAt이
// 서로 다른 값으로 덮어써질 수 있다. production은 Auction 행을 PESSIMISTIC_WRITE로 잠근 뒤
// 최신 endAt/extensionCount를 다시 읽고 판정하므로, 두 번째 이후 입찰은 이미 연장된 상태를 본다.
//
// 락 보유 구간에 짧은 지연을 넣어(ManualBidConcurrencyRaceIT와 같은 AuctionRepository 대리 빈
// 패턴) 나머지 요청이 실제로 같은 행 락 앞에 줄을 서게 만든다. 각 워커는 최소 입찰가 미달로
// 실패하면 최신 minNextBidAmount로 재시도해 결국 모두 한 번씩 성공한다 - 즉 "연장 구간 안에서
// 성공한 입찰 N건"을 동시에 만든 뒤 extensionCount가 1이고 endAt이 원래 값 + 20초인지 확인한다.
// 결과 불변식은 실행 순서와 무관하므로 결정적이다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class AuctionExtensionConcurrencyMySqlIT {

    private static final int WORKER_COUNT = 6;
    private static final int MAX_ATTEMPTS_PER_WORKER = 30;
    private static final long LOCK_HOLD_DELAY_MILLIS = 50;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
    }

    @TestConfiguration
    static class DelayConfig {

        @Bean
        RaceWindowDelay raceWindowDelay() {
            return new RaceWindowDelay();
        }

        @Bean
        @Primary
        AuctionRepository delayingAuctionRepository(
                AuctionRepository jpaAuctionRepository, RaceWindowDelay raceWindowDelay
        ) {
            AuctionRepository proxy = Mockito.mock(
                    AuctionRepository.class, AdditionalAnswers.delegatesTo(jpaAuctionRepository)
            );
            Mockito.doAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                Optional<Auction> result = jpaAuctionRepository.findByIdForUpdate(id);
                raceWindowDelay.applyIfTarget(id);
                return result;
            }).when(proxy).findByIdForUpdate(Mockito.anyLong());
            return proxy;
        }
    }

    @Autowired
    private ManualBidService manualBidService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private RaceWindowDelay raceWindowDelay;

    @Test
    void 종료_10초_이내_동시_입찰이_여러_건_성공해도_연장은_1회만_발생한다() throws Exception {
        User seller = userRepository.save(User.register(
                "ext-seller-" + UUID.randomUUID() + "@vintic.local", "seller", null
        ));
        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        ));
        // 실제 시계 기준 남은 시간 8초 - 생성 직후 바로 동시 입찰을 시작하므로 첫 성공 입찰은
        // 연장 구간(10초 이하, 마감 전) 안에서 처리된다.
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusMinutes(2), LocalDateTime.now().plusSeconds(8)
        );
        auction.start();
        Long auctionId = auctionRepository.save(auction).getId();
        LocalDateTime originalEndAt = auctionRepository.findById(auctionId).orElseThrow().getEndAt();

        List<Long> bidderIds = new ArrayList<>();
        for (int i = 0; i < WORKER_COUNT; i++) {
            bidderIds.add(userRepository.save(User.register(
                    "ext-bidder-" + i + "-" + UUID.randomUUID() + "@vintic.local", "bidder" + i, null
            )).getId());
        }

        raceWindowDelay.configure(auctionId, LOCK_HOLD_DELAY_MILLIS);
        raceWindowDelay.arm();
        CountDownLatch ready = new CountDownLatch(WORKER_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(WORKER_COUNT);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (Long bidderId : bidderIds) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_WORKER; attempt++) {
                        long amount = auctionRepository.findById(auctionId).orElseThrow().getMinNextBidAmount();
                        try {
                            manualBidService.placeBid(auctionId, bidderId, amount, UUID.randomUUID().toString());
                            return true;
                        } catch (RuntimeException e) {
                            // 다른 워커가 먼저 가격을 올려 최소 입찰가 미달 등으로 실패 - 최신 가격으로 재시도
                        }
                    }
                    return false;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successCount = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(60, TimeUnit.SECONDS)) {
                    successCount++;
                }
            }

            // 연장 구간 안에서 여러 건이 실제로 성공했어야 "중복 연장 race"를 검증한 의미가 있다.
            assertThat(successCount).isEqualTo(WORKER_COUNT);
        } finally {
            raceWindowDelay.disarm();
            executor.shutdownNow();
        }

        Auction reloaded = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(bidRepository.countByAuctionId(auctionId)).isEqualTo(WORKER_COUNT);
        assertThat(reloaded.getExtensionCount()).isEqualTo(1);
        assertThat(reloaded.getEndAt()).isEqualTo(originalEndAt.plusSeconds(20));
    }
}

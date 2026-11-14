package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.auction.service.AuctionEndService;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.domain.BackupOfferStatus;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.backupoffer.service.BackupOfferCommandService;
import com.vintic.backend.backupoffer.service.BackupOfferExpirationService;
import com.vintic.backend.backupoffer.service.BackupOfferService;
import com.vintic.backend.bid.service.BidCommandService;
import com.vintic.backend.common.exception.BackupOfferAlreadyResolvedException;
import com.vintic.backend.common.exception.BackupOfferExpiredException;
import com.vintic.backend.common.exception.PaymentExpiredException;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.order.service.AuctionForfeitService;
import com.vintic.backend.order.service.OrderExpirationService;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

// D2 후속: 차순위 체인의 두 처리(수락/거절/포기 vs 만료)가 같은 경매에서 경쟁할 때, 두 트랜잭션 모두
// 경매 잠금 "이전"의 일반 SELECT로 REPEATABLE READ read view를 먼저 만든 상태(= 상대 커밋 이전
// 스냅샷)에서 진행해도 중복 제안·주문이나 예상 밖 예외가 없는지 확인한다.
//
// 결정적 재현 방법: 테스트가 TransactionTemplate으로 Auction FOR UPDATE를 먼저 잡아 둔 뒤 두 작업을
// 동시에 시작하고, performance_schema.data_lock_waits에서 두 트랜잭션이 모두 잠금 대기에 들어간 것을
// 확인한 다음에 잠금을 푼다. 이 시점에 두 트랜잭션은 이미 잠금 이전 SELECT(findAuctionIdById /
// idempotency 조회)를 실행했으므로 read view가 상대의 커밋보다 앞선다. 누가 먼저 잠금을 얻는지는
// InnoDB에 맡기고, 두 순서 모두에서 성립해야 하는 결과만 검증한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class BackupOfferResolutionRaceMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("purchase-agent.scan.enabled", () -> "false");
        registry.add("auction.lifecycle.start.enabled", () -> "false");
        registry.add("auction.lifecycle.end.enabled", () -> "false");
        registry.add("payment.expiration.enabled", () -> "false");
        registry.add("backup-offer.expiration.enabled", () -> "false");
    }

    @Autowired private BidCommandService bidCommandService;
    @Autowired private AuctionEndService auctionEndService;
    @Autowired private AuctionForfeitService auctionForfeitService;
    @Autowired private OrderExpirationService orderExpirationService;
    @Autowired private BackupOfferService backupOfferService;
    @Autowired private BackupOfferCommandService backupOfferCommandService;
    @Autowired private BackupOfferExpirationService backupOfferExpirationService;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private BackupOfferRepository backupOfferRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private record Fixture(Auction auction, User a, User b, User c) {
    }

    private record Outcomes(Throwable first, Throwable second) {
    }

    private User persistUser(String name) {
        return userRepository.save(User.register(name + "-" + System.nanoTime() + "@vintic.local", name, null));
    }

    // 일반 A > B > C 경매를 실제 서비스로 종료·정산하고 A가 포기해 B에게 WAITING 제안이 있는 상태.
    private Fixture offerWaitingForB() {
        User seller = persistUser("seller");
        User a = persistUser("a");
        User b = persistUser("b");
        User c = persistUser("c");
        Product product = productRepository.save(new Product(
                seller, List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "", 290000, "", ""
        ));
        Auction auction = Auction.schedule(product, 100000L, 5000L, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        auction.start();
        auction = auctionRepository.save(auction);
        bidCommandService.placeManualBid(auction.getId(), c.getId(), 105000L);
        bidCommandService.placeManualBid(auction.getId(), b.getId(), 110000L);
        bidCommandService.placeManualBid(auction.getId(), a.getId(), 115000L);
        Auction live = auctionRepository.findById(auction.getId()).orElseThrow();
        ReflectionTestUtils.setField(live, "endAt", LocalDateTime.now().minusSeconds(1));
        auctionRepository.save(live);
        auctionEndService.endIfDue(auction.getId());
        auctionForfeitService.forfeit(auction.getId(), a.getId());
        return new Fixture(auction, a, b, c);
    }

    private BackupOffer offerOf(Fixture f, User candidate) {
        return backupOfferRepository.findByAuctionIdAndCandidateId(f.auction().getId(), candidate.getId()).orElseThrow();
    }

    private List<BackupOffer> offersOf(Fixture f) {
        return backupOfferRepository.findAll().stream().filter(o -> o.getAuction().getId().equals(f.auction().getId())).toList();
    }

    private List<Order> ordersOf(Fixture f) {
        return orderRepository.findAll().stream().filter(o -> o.getAuction().getId().equals(f.auction().getId())).toList();
    }

    private void forceOfferPastDue(BackupOffer offer) {
        ReflectionTestUtils.setField(offer, "deadline", LocalDateTime.now().minusMinutes(1));
        backupOfferRepository.save(offer);
    }

    private void forceOrderPastDue(Order order) {
        ReflectionTestUtils.setField(order, "paymentDeadline", LocalDateTime.now().minusMinutes(1));
        orderRepository.save(order);
    }

    private Outcomes raceBehindAuctionLock(Long auctionId, Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            Future<?> holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                auctionRepository.findByIdForUpdate(auctionId).orElseThrow();
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> f1 = executor.submit(first);
            Future<?> f2 = executor.submit(second);
            awaitLockWaiters(2);
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);

            return new Outcomes(outcome(f1), outcome(f2));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    // 잠금 대기 판정은 performance_schema.data_lock_waits의 "대기 중인 트랜잭션 수"로 한다.
    // information_schema.innodb_trx.trx_state는 같은 row를 두 번째로 기다리는 트랜잭션을 LOCK WAIT가
    // 아니라 RUNNING으로 보고하는 경우가 실측으로 확인돼(processlist/data_lock_waits로는 둘 다 auction
    // FOR UPDATE 대기) 판정 기준으로 쓰지 않는다. 이 조회들은 PROCESS 권한이 필요해 애플리케이션
    // 계정(test) 대신 컨테이너 root로 관찰 전용 커넥션을 따로 연다(Testcontainers는 root 비밀번호를
    // 같은 값으로 설정한다).
    private void awaitLockWaiters(int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        try (Connection observer = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             Statement statement = observer.createStatement()) {
            while (System.currentTimeMillis() < deadline) {
                try (ResultSet rs = statement.executeQuery(
                        "SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits")) {
                    rs.next();
                    if (rs.getInt(1) >= expected) {
                        return;
                    }
                }
                Thread.sleep(20);
            }
        }
        fail("두 트랜잭션이 경매 잠금 대기에 들어가지 않았습니다.");
    }

    private Throwable outcome(Future<?> future) throws Exception {
        try {
            future.get(30, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    @Test
    void 거절과_제안_만료가_경쟁해도_한쪽만_반영되고_다음_제안은_1건이다() throws Exception {
        Fixture f = offerWaitingForB();
        BackupOffer offerB = offerOf(f, f.b());
        forceOfferPastDue(offerB);

        Outcomes outcomes = raceBehindAuctionLock(
                f.auction().getId(),
                () -> backupOfferCommandService.decline(offerB.getId(), f.b().getId()),
                () -> {
                    backupOfferExpirationService.expireIfDue(offerB.getId());
                    return null;
                }
        );

        BackupOfferStatus finalStatus = offerOf(f, f.b()).getStatus();
        assertThat(finalStatus).isIn(BackupOfferStatus.DECLINED, BackupOfferStatus.EXPIRED);
        if (finalStatus == BackupOfferStatus.DECLINED) {
            assertThat(outcomes.first()).isNull();
        } else {
            assertThat(outcomes.first()).isInstanceOf(BackupOfferAlreadyResolvedException.class);
        }
        assertThat(outcomes.second()).isNull(); // 만료는 상태 재검증에서 조용히 종료된다.

        assertThat(offersOf(f)).extracting(o -> o.getCandidate().getId())
                .containsExactlyInAnyOrder(f.b().getId(), f.c().getId());
        assertThat(offerOf(f, f.c()).getStatus()).isEqualTo(BackupOfferStatus.WAITING);
        assertThat(ordersOf(f)).hasSize(1);
    }

    @Test
    void 기한이_지난_제안의_수락과_만료가_경쟁해도_주문은_생기지_않고_다음_제안은_1건이다() throws Exception {
        Fixture f = offerWaitingForB();
        BackupOffer offerB = offerOf(f, f.b());
        forceOfferPastDue(offerB);

        Outcomes outcomes = raceBehindAuctionLock(
                f.auction().getId(),
                () -> backupOfferService.accept(offerB.getId(), f.b().getId(), "race-" + System.nanoTime()),
                () -> {
                    backupOfferExpirationService.expireIfDue(offerB.getId());
                    return null;
                }
        );

        // 수락이 먼저면 기한 만료(40911)로 롤백, 나중이면 이미 처리됨(40912) - 둘 다 비즈니스 예외다.
        assertThat(outcomes.first()).isInstanceOfAny(BackupOfferExpiredException.class, BackupOfferAlreadyResolvedException.class);
        assertThat(outcomes.second()).isNull();

        assertThat(offerOf(f, f.b()).getStatus()).isEqualTo(BackupOfferStatus.EXPIRED);
        assertThat(orderRepository.findByAuctionIdAndBuyerId(f.auction().getId(), f.b().getId())).isEmpty();
        assertThat(offersOf(f)).extracting(o -> o.getCandidate().getId())
                .containsExactlyInAnyOrder(f.b().getId(), f.c().getId());
        assertThat(ordersOf(f)).hasSize(1);
    }

    @Test
    void 차순위_주문의_포기와_결제_만료가_경쟁해도_한쪽만_반영되고_다음_제안은_1건이다() throws Exception {
        Fixture f = offerWaitingForB();
        backupOfferCommandService.accept(offerOf(f, f.b()).getId(), f.b().getId());
        Order orderB = orderRepository.findByAuctionIdAndBuyerId(f.auction().getId(), f.b().getId()).orElseThrow();
        forceOrderPastDue(orderB);

        Outcomes outcomes = raceBehindAuctionLock(
                f.auction().getId(),
                () -> auctionForfeitService.forfeit(f.auction().getId(), f.b().getId()),
                () -> {
                    orderExpirationService.expireIfDue(orderB.getId());
                    return null;
                }
        );

        OrderStatus finalStatus = orderRepository.findById(orderB.getId()).orElseThrow().getStatus();
        assertThat(finalStatus).isIn(OrderStatus.CANCELED, OrderStatus.PAYMENT_EXPIRED);
        if (finalStatus == OrderStatus.CANCELED) {
            assertThat(outcomes.first()).isNull();
        } else {
            assertThat(outcomes.first()).isInstanceOf(PaymentExpiredException.class);
        }
        assertThat(outcomes.second()).isNull();

        assertThat(offersOf(f)).extracting(o -> o.getCandidate().getId())
                .containsExactlyInAnyOrder(f.b().getId(), f.c().getId());
        assertThat(offerOf(f, f.c()).getStatus()).isEqualTo(BackupOfferStatus.WAITING);
        assertThat(ordersOf(f)).hasSize(2);
    }
}

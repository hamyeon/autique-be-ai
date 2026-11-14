package com.vintic.backend.backupoffer;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.auction.service.AuctionEndService;
import com.vintic.backend.autobid.service.AutoBidCommandService;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.domain.BackupOfferStatus;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.backupoffer.service.BackupOfferCommandService;
import com.vintic.backend.backupoffer.service.BackupOfferExpirationService;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.bid.service.BidCommandService;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// D2 회귀: 실제 낙찰자(Auction.currentWinner - ProxyPriceEngine 동률 규칙 결과)와 차순위 순위표가
// 어긋나도, 그리고 거절/만료/수락 후 미결제가 이어져도 이미 구매 기회를 받은 사용자에게 다시 제안하지
// 않는지를 실제 MySQL + 실제 서비스 경로(AutoBid 등록, 수동입찰, 종료/정산, 포기, 수락/거절, 만료)로
// 확인한다. uk_order_auction_buyer / uk_backup_offer_auction_candidate 위반은 실제 DB에서만 드러나므로
// mock/H2가 아니라 Testcontainers MySQL을 쓴다.
@SpringBootTest
@ActiveProfiles("local")
@Testcontainers
class BackupCandidateSelectionMySqlIT {

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

    @Autowired private AutoBidCommandService autoBidCommandService;
    @Autowired private BidCommandService bidCommandService;
    @Autowired private AuctionEndService auctionEndService;
    @Autowired private AuctionForfeitService auctionForfeitService;
    @Autowired private OrderExpirationService orderExpirationService;
    @Autowired private BackupOfferCommandService backupOfferCommandService;
    @Autowired private BackupOfferExpirationService backupOfferExpirationService;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private BidRepository bidRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private BackupOfferRepository backupOfferRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private UserRepository userRepository;

    private User persistUser(String name) {
        return userRepository.save(User.register(name + "-" + System.nanoTime() + "@vintic.local", name, null));
    }

    private Auction persistLiveAuction() {
        User seller = persistUser("seller");
        Product product = productRepository.save(new Product(
                seller, List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "", 290000, "", ""
        ));
        Auction auction = Auction.schedule(product, 100000L, 5000L, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        auction.start();
        return auctionRepository.save(auction);
    }

    private void endAndSettle(Long auctionId) {
        Auction auction = auctionRepository.findById(auctionId).orElseThrow();
        ReflectionTestUtils.setField(auction, "endAt", LocalDateTime.now().minusSeconds(1));
        auctionRepository.save(auction);
        auctionEndService.endIfDue(auctionId);
    }

    private Order orderOf(Long auctionId, User buyer) {
        return orderRepository.findByAuctionIdAndBuyerId(auctionId, buyer.getId()).orElseThrow();
    }

    private BackupOffer offerOf(Long auctionId, User candidate) {
        return backupOfferRepository.findByAuctionIdAndCandidateId(auctionId, candidate.getId()).orElseThrow();
    }

    private List<BackupOffer> offersOf(Long auctionId) {
        return backupOfferRepository.findAll().stream().filter(o -> o.getAuction().getId().equals(auctionId)).toList();
    }

    private List<Order> ordersOf(Long auctionId) {
        return orderRepository.findAll().stream().filter(o -> o.getAuction().getId().equals(auctionId)).toList();
    }

    private void forcePaymentDeadlinePassed(Order order) {
        ReflectionTestUtils.setField(order, "paymentDeadline", LocalDateTime.now().minusMinutes(1));
        orderRepository.save(order);
    }

    private void forceOfferDeadlinePassed(BackupOffer offer) {
        ReflectionTestUtils.setField(offer, "deadline", LocalDateTime.now().minusMinutes(1));
        backupOfferRepository.save(offer);
    }

    // A 자동입찰(cap 130,000) 먼저 -> C 130,000 수동입찰 -> A가 같은 금액으로 반격해 승자 유지.
    // Bid는 C(MANUAL 130,000) -> A(AUTO 130,000) 순으로 저장된다.
    private Auction tieAuction(User a, User c) {
        Auction auction = persistLiveAuction();
        autoBidCommandService.createAutoBid(auction.getId(), a.getId(), 130000L);
        bidCommandService.placeManualBid(auction.getId(), c.getId(), 130000L);
        endAndSettle(auction.getId());
        return auction;
    }

    // 일반 A > B > C (전부 수동입찰). 낙찰자 A.
    private Auction plainAuction(User a, User b, User c) {
        Auction auction = persistLiveAuction();
        bidCommandService.placeManualBid(auction.getId(), c.getId(), 105000L);
        bidCommandService.placeManualBid(auction.getId(), b.getId(), 110000L);
        bidCommandService.placeManualBid(auction.getId(), a.getId(), 115000L);
        endAndSettle(auction.getId());
        return auction;
    }

    @Test
    void 동률_사례에서_실제_낙찰자는_A이고_순위표도_A가_1등이다() {
        User a = persistUser("a");
        User c = persistUser("c");
        Auction auction = tieAuction(a, c);

        Auction ended = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(ended.getCurrentWinner().getId()).isEqualTo(a.getId());
        assertThat(ended.getCurrentPrice()).isEqualTo(130000L);
        assertThat(orderOf(auction.getId(), a).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);

        List<Bid> ranked = bidRepository.findLatestBidPerUserOrderedByRank(auction.getId());
        assertThat(ranked).extracting(bid -> bid.getUser().getId()).containsExactly(a.getId(), c.getId());
        assertThat(ranked.get(0).getBidType()).isEqualTo(BidType.AUTO);
    }

    @Test
    void 동률_사례에서_A가_포기하면_C에게_제안되고_C가_수락하면_주문이_정상_생성된다() {
        User a = persistUser("a");
        User c = persistUser("c");
        Auction auction = tieAuction(a, c);

        auctionForfeitService.forfeit(auction.getId(), a.getId());

        List<BackupOffer> offers = offersOf(auction.getId());
        assertThat(offers).hasSize(1);
        BackupOffer offerToC = offers.get(0);
        assertThat(offerToC.getCandidate().getId()).isEqualTo(c.getId());
        // 구매 가격은 C의 실제 마지막 입찰가다(상한액이 아님).
        assertThat(offerToC.getPurchasePrice()).isEqualTo(130000L);

        backupOfferCommandService.accept(offerToC.getId(), c.getId());

        assertThat(orderOf(auction.getId(), c).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(orderOf(auction.getId(), c).getPurchasePrice()).isEqualTo(130000L);
        assertThat(orderOf(auction.getId(), a).getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(ordersOf(auction.getId())).hasSize(2);
    }

    @Test
    void 동률_사례에서_A의_결제기한이_만료되면_A가_아니라_C에게_제안된다() {
        User a = persistUser("a");
        User c = persistUser("c");
        Auction auction = tieAuction(a, c);
        Order orderA = orderOf(auction.getId(), a);
        forcePaymentDeadlinePassed(orderA);

        orderExpirationService.expireIfDue(orderA.getId());

        assertThat(orderOf(auction.getId(), a).getStatus()).isEqualTo(OrderStatus.PAYMENT_EXPIRED);
        List<BackupOffer> offers = offersOf(auction.getId());
        assertThat(offers).extracting(o -> o.getCandidate().getId()).containsExactly(c.getId());

        // 재실행해도 추가 제안 없음.
        orderExpirationService.expireIfDue(orderA.getId());
        assertThat(offersOf(auction.getId())).hasSize(1);
    }

    @Test
    void 일반_사례에서_B가_거절하면_C로_진행하고_C도_거절하면_종료된다() {
        User a = persistUser("a");
        User b = persistUser("b");
        User c = persistUser("c");
        Auction auction = plainAuction(a, b, c);

        auctionForfeitService.forfeit(auction.getId(), a.getId());
        backupOfferCommandService.decline(offerOf(auction.getId(), b).getId(), b.getId());

        assertThat(offerOf(auction.getId(), c).getStatus()).isEqualTo(BackupOfferStatus.WAITING);
        assertThat(offerOf(auction.getId(), c).getPurchasePrice()).isEqualTo(105000L);

        backupOfferCommandService.decline(offerOf(auction.getId(), c).getId(), c.getId());

        assertThat(offersOf(auction.getId())).extracting(o -> o.getCandidate().getId())
                .containsExactlyInAnyOrder(b.getId(), c.getId());
        assertThat(ordersOf(auction.getId())).hasSize(1);
    }

    @Test
    void 일반_사례에서_B의_제안이_만료되면_C로_진행한다() {
        User a = persistUser("a");
        User b = persistUser("b");
        User c = persistUser("c");
        Auction auction = plainAuction(a, b, c);

        auctionForfeitService.forfeit(auction.getId(), a.getId());
        BackupOffer offerToB = offerOf(auction.getId(), b);
        forceOfferDeadlinePassed(offerToB);

        backupOfferExpirationService.expireIfDue(offerToB.getId());

        assertThat(offerOf(auction.getId(), b).getStatus()).isEqualTo(BackupOfferStatus.EXPIRED);
        assertThat(offerOf(auction.getId(), c).getStatus()).isEqualTo(BackupOfferStatus.WAITING);
        assertThat(offersOf(auction.getId())).hasSize(2);

        // 스케줄러 재실행: 이미 EXPIRED라 아무것도 하지 않는다.
        backupOfferExpirationService.expireIfDue(offerToB.getId());
        assertThat(offersOf(auction.getId())).hasSize(2);
    }

    @Test
    void 일반_사례에서_B가_수락후_결제하지_않으면_A나_B가_아니라_C로_진행한다() {
        User a = persistUser("a");
        User b = persistUser("b");
        User c = persistUser("c");
        Auction auction = plainAuction(a, b, c);

        auctionForfeitService.forfeit(auction.getId(), a.getId());
        backupOfferCommandService.accept(offerOf(auction.getId(), b).getId(), b.getId());
        Order orderB = orderOf(auction.getId(), b);
        forcePaymentDeadlinePassed(orderB);

        orderExpirationService.expireIfDue(orderB.getId());

        assertThat(orderOf(auction.getId(), b).getStatus()).isEqualTo(OrderStatus.PAYMENT_EXPIRED);
        assertThat(offerOf(auction.getId(), c).getStatus()).isEqualTo(BackupOfferStatus.WAITING);
        assertThat(offersOf(auction.getId())).extracting(o -> o.getCandidate().getId())
                .containsExactlyInAnyOrder(b.getId(), c.getId());
    }

    @Test
    void 한_사용자가_여러번_입찰해도_구매_기회는_한번만_받는다() {
        User a = persistUser("a");
        User b = persistUser("b");
        User c = persistUser("c");
        Auction auction = persistLiveAuction();
        bidCommandService.placeManualBid(auction.getId(), b.getId(), 105000L);
        bidCommandService.placeManualBid(auction.getId(), c.getId(), 110000L);
        bidCommandService.placeManualBid(auction.getId(), b.getId(), 115000L);
        bidCommandService.placeManualBid(auction.getId(), c.getId(), 120000L);
        bidCommandService.placeManualBid(auction.getId(), a.getId(), 125000L);
        endAndSettle(auction.getId());

        auctionForfeitService.forfeit(auction.getId(), a.getId());
        // 1순위 차순위는 C(최신 120,000), 그 다음 B(최신 115,000).
        assertThat(offerOf(auction.getId(), c).getPurchasePrice()).isEqualTo(120000L);
        backupOfferCommandService.decline(offerOf(auction.getId(), c).getId(), c.getId());
        assertThat(offerOf(auction.getId(), b).getPurchasePrice()).isEqualTo(115000L);
        backupOfferCommandService.decline(offerOf(auction.getId(), b).getId(), b.getId());

        assertThat(offersOf(auction.getId())).extracting(o -> o.getCandidate().getId())
                .containsExactlyInAnyOrder(b.getId(), c.getId());
    }

    // 동률 쌍(C MANUAL 130,000 패 / A AUTO 130,000 승)이 낙찰자가 아닌 사용자들 사이에 남는 경우 -
    // 후보 풀 내부 순서가 제안 순서를 결정하므로 순위표의 동률 정렬이 후보 선정에도 영향을 준다.
    @Test
    void 낙찰자가_아닌_사용자끼리의_동률에서도_엔진이_이긴_쪽에게_먼저_제안한다() {
        User a = persistUser("a");
        User c = persistUser("c");
        User z = persistUser("z");
        Auction auction = persistLiveAuction();
        autoBidCommandService.createAutoBid(auction.getId(), a.getId(), 130000L);
        bidCommandService.placeManualBid(auction.getId(), c.getId(), 130000L);
        bidCommandService.placeManualBid(auction.getId(), z.getId(), 140000L);
        endAndSettle(auction.getId());

        assertThat(auctionRepository.findById(auction.getId()).orElseThrow().getCurrentWinner().getId()).isEqualTo(z.getId());
        assertThat(bidRepository.findLatestBidPerUserOrderedByRank(auction.getId()))
                .extracting(bid -> bid.getUser().getId())
                .containsExactly(z.getId(), a.getId(), c.getId());

        auctionForfeitService.forfeit(auction.getId(), z.getId());
        assertThat(offersOf(auction.getId())).extracting(o -> o.getCandidate().getId()).containsExactly(a.getId());
        assertThat(offerOf(auction.getId(), a).getPurchasePrice()).isEqualTo(130000L);

        backupOfferCommandService.decline(offerOf(auction.getId(), a).getId(), a.getId());
        assertThat(offerOf(auction.getId(), c).getStatus()).isEqualTo(BackupOfferStatus.WAITING);
        assertThat(offersOf(auction.getId())).hasSize(2);
    }

    @Test
    void 후보가_소진된_뒤_같은_처리를_재실행해도_중복_제안이나_주문이_생기지_않는다() {
        User a = persistUser("a");
        User c = persistUser("c");
        Auction auction = tieAuction(a, c);

        auctionForfeitService.forfeit(auction.getId(), a.getId());
        BackupOffer offerToC = offerOf(auction.getId(), c);
        backupOfferCommandService.accept(offerToC.getId(), c.getId());
        Order orderC = orderOf(auction.getId(), c);
        forcePaymentDeadlinePassed(orderC);

        // C가 결제하지 않음 -> 남은 후보 없음(A는 실제 낙찰자라 제외).
        orderExpirationService.expireIfDue(orderC.getId());
        assertThat(offersOf(auction.getId())).hasSize(1);

        // 재실행: forfeit(이미 CANCELED - idempotent), 주문 만료(이미 EXPIRED), 제안 만료(ACCEPTED라 대상 아님).
        auctionForfeitService.forfeit(auction.getId(), a.getId());
        orderExpirationService.expireIfDue(orderC.getId());
        orderExpirationService.expireIfDue(orderOf(auction.getId(), a).getId());
        backupOfferExpirationService.expireIfDue(offerToC.getId());

        assertThat(offersOf(auction.getId())).hasSize(1);
        assertThat(ordersOf(auction.getId())).hasSize(2);
    }
}

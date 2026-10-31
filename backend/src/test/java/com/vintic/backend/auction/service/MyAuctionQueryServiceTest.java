package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.dto.AuctionCardResponse;
import com.vintic.backend.auction.dto.AuctionListResponse;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.common.exception.InvalidPageRequestException;
import com.vintic.backend.common.util.S3UrlPresigner;
import com.vintic.backend.like.domain.AuctionLike;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

// #143: 마이페이지 "나의 경매"(내가 참여한 경매)와 관심 목록을 실제 JPA 쿼리로 확인한다.
@DataJpaTest
@Import({MyAuctionQueryService.class, AuctionCardAssembler.class})
class MyAuctionQueryServiceTest {

    @Autowired
    private MyAuctionQueryService service;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @MockitoBean
    private S3UrlPresigner s3UrlPresigner;

    private User me;
    private User other;
    private int productSeq;

    @BeforeEach
    void setUp() {
        lenient().when(s3UrlPresigner.presign(anyString(), any()))
                .thenAnswer(invocation -> "signed:" + invocation.getArgument(0));
        me = persistUser("me@vintic.local");
        other = persistUser("other@vintic.local");
    }

    private User persistUser(String email) {
        User user = User.register(email, email, null);
        entityManager.persist(user);
        return user;
    }

    private Product persistProduct(User seller) {
        productSeq++;
        Product product = new Product(
                seller, List.of("https://bucket.s3.ap-northeast-2.amazonaws.com/p" + productSeq + ".jpg"),
                "Nike", "Dunk Low " + productSeq, "Panda", 270, "A", "FULL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        );
        entityManager.persist(product);
        return product;
    }

    private Auction persistAuction(Product product, AuctionStatus status) {
        Auction auction = Auction.schedule(product, 10000L, 5000L,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2));
        switch (status) {
            case SCHEDULED -> {
            }
            case LIVE -> auction.start();
            case ENDED -> {
                auction.start();
                auction.end();
            }
            case CANCELED -> auction.cancel();
        }
        entityManager.persist(auction);
        return auction;
    }

    private Auction persistAuction(User seller, AuctionStatus status) {
        return persistAuction(persistProduct(seller), status);
    }

    private AuctionLike like(Auction auction, User user) {
        AuctionLike like = AuctionLike.create(auction, user);
        entityManager.persist(like);
        return like;
    }

    private void setCreatedAt(String table, Long id, LocalDateTime createdAt) {
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE " + table + " SET created_at = :t WHERE id = :id")
                .setParameter("t", createdAt).setParameter("id", id).executeUpdate();
    }

    private static List<Long> ids(AuctionListResponse response) {
        return response.items().stream().map(AuctionCardResponse::auctionId).toList();
    }

    // ===== 나의 경매(참여한 경매) =====

    // 실제 입찰 한 건(Auction 가격 갱신 + Bid 행). 직접 입찰과 같은 경로다.
    private void bid(Auction auction, User bidder, long amount) {
        auction.placeManualBid(bidder, amount);
        entityManager.persist(Bid.place(auction, bidder, amount, BidType.MANUAL));
    }

    private AutoBidSetting reserveAutoBid(Auction auction, User user) {
        AutoBidSetting setting = AutoBidSetting.reserve(auction, user, 100000L);
        entityManager.persist(setting);
        return setting;
    }

    @Test
    void 현재_목록은_내가_입찰했거나_유효한_자동입찰이_있는_예정_진행중_경매만이다() {
        Auction liveWithMyBid = persistAuction(other, AuctionStatus.LIVE);
        bid(liveWithMyBid, me, 15000L);
        Auction scheduledWithReservation = persistAuction(other, AuctionStatus.SCHEDULED);
        reserveAutoBid(scheduledWithReservation, me);
        Auction liveWithActiveAutoBid = persistAuction(other, AuctionStatus.LIVE);
        reserveAutoBid(liveWithActiveAutoBid, me).activate();
        Auction liveWithAgentAutoBid = persistAuction(other, AuctionStatus.LIVE);
        AutoBidSetting agentSetting = AutoBidSetting.reserve(liveWithAgentAutoBid, me, 120000L, 77L);
        agentSetting.activate();
        entityManager.persist(agentSetting);
        // 제외: 취소한 자동입찰만 있음 / 다른 사용자만 입찰 / 내가 판매자일 뿐
        Auction canceledAutoBidOnly = persistAuction(other, AuctionStatus.LIVE);
        reserveAutoBid(canceledAutoBidOnly, me).cancel();
        Auction othersBidOnly = persistAuction(other, AuctionStatus.LIVE);
        bid(othersBidOnly, persistUser("third@vintic.local"), 15000L);
        persistAuction(me, AuctionStatus.LIVE);
        persistAuction(me, AuctionStatus.SCHEDULED);
        entityManager.flush();
        entityManager.clear();

        AuctionListResponse response = service.getMyParticipatedAuctions(me.getId(), "current", 0, 20);

        assertThat(ids(response)).containsExactlyInAnyOrder(
                liveWithMyBid.getId(), scheduledWithReservation.getId(),
                liveWithActiveAutoBid.getId(), liveWithAgentAutoBid.getId());
    }

    @Test
    void 과거_목록은_낙찰_패찰_종료_경매와_예약해_둔_취소_경매를_포함한다() {
        User rival = persistUser("rival@vintic.local");
        Auction won = persistAuction(other, AuctionStatus.LIVE);
        bid(won, rival, 15000L);
        bid(won, me, 20000L);
        won.end();
        Auction lost = persistAuction(other, AuctionStatus.LIVE);
        bid(lost, me, 15000L);
        bid(lost, rival, 20000L);
        lost.end();
        // 예약만 해 둔 경매가 판매자 취소됨 - 설정은 CANCELED로 바뀌지 않으므로 참여 이력으로 남는다
        Auction canceledAuction = persistAuction(other, AuctionStatus.SCHEDULED);
        reserveAutoBid(canceledAuction, me);
        canceledAuction.cancel();
        // 제외: 자동입찰을 걸었다가 입찰 없이 중단한 뒤 종료된 경매
        Auction withdrawn = persistAuction(other, AuctionStatus.LIVE);
        reserveAutoBid(withdrawn, me).cancel();
        withdrawn.end();
        // 제외: 내가 판매한 종료 경매
        persistAuction(me, AuctionStatus.ENDED);
        entityManager.flush();
        entityManager.clear();

        AuctionListResponse past = service.getMyParticipatedAuctions(me.getId(), "past", 0, 20);
        AuctionListResponse current = service.getMyParticipatedAuctions(me.getId(), "current", 0, 20);

        assertThat(ids(past)).containsExactlyInAnyOrder(won.getId(), lost.getId(), canceledAuction.getId());
        assertThat(past.items()).extracting(AuctionCardResponse::status)
                .containsExactlyInAnyOrder(AuctionStatus.ENDED, AuctionStatus.ENDED, AuctionStatus.CANCELED);
        assertThat(current.items()).isEmpty();
    }

    @Test
    void 자동입찰을_취소한_뒤에도_실제_입찰이_있었다면_참여로_본다() {
        Auction auction = persistAuction(other, AuctionStatus.LIVE);
        AutoBidSetting setting = reserveAutoBid(auction, me);
        setting.activate();
        entityManager.persist(Bid.place(auction, me, 15000L, BidType.AUTO));
        auction.applyProxyResult(me, 15000L);
        setting.cancel();
        entityManager.flush();
        entityManager.clear();

        assertThat(ids(service.getMyParticipatedAuctions(me.getId(), "current", 0, 20)))
                .containsExactly(auction.getId());
    }

    @Test
    void 여러_번_입찰하고_수동_자동을_함께_써도_경매당_한_번이며_페이지와_hasNext가_정확하다() {
        User rival = persistUser("rival@vintic.local");
        LocalDateTime base = LocalDateTime.now().minusDays(1).withNano(0);
        List<Auction> auctions = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Auction auction = persistAuction(other, AuctionStatus.LIVE);
            bid(auction, me, 15000L);
            bid(auction, rival, 20000L);
            bid(auction, me, 25000L);
            entityManager.persist(Bid.place(auction, me, 35000L, BidType.AUTO));
            auction.applyProxyResult(me, 35000L);
            AutoBidSetting setting = reserveAutoBid(auction, me);
            setting.activate();
            auctions.add(auction);
        }
        for (int i = 0; i < 3; i++) {
            setCreatedAt("auctions", auctions.get(i).getId(), base.plusHours(i));
        }
        entityManager.clear();

        AuctionListResponse first = service.getMyParticipatedAuctions(me.getId(), "current", 0, 2);
        AuctionListResponse second = service.getMyParticipatedAuctions(me.getId(), "current", 1, 2);
        AuctionListResponse beyond = service.getMyParticipatedAuctions(me.getId(), "current", 2, 2);

        assertThat(ids(first)).containsExactly(auctions.get(2).getId(), auctions.get(1).getId());
        assertThat(first.items()).hasSize(2);
        assertThat(first.hasNext()).isTrue();
        assertThat(ids(second)).containsExactly(auctions.get(0).getId());
        assertThat(second.hasNext()).isFalse();
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.hasNext()).isFalse();
    }

    @Test
    void 참여한_경매는_생성_시각_최신순이고_같으면_경매_ID_내림차순이다() {
        LocalDateTime base = LocalDateTime.now().minusDays(1).withNano(0);
        Auction oldest = persistAuction(other, AuctionStatus.LIVE);
        Auction tieLow = persistAuction(other, AuctionStatus.SCHEDULED);
        Auction tieHigh = persistAuction(other, AuctionStatus.SCHEDULED);
        Auction newest = persistAuction(other, AuctionStatus.LIVE);
        bid(oldest, me, 15000L);
        reserveAutoBid(tieLow, me);
        reserveAutoBid(tieHigh, me);
        bid(newest, me, 15000L);
        setCreatedAt("auctions", oldest.getId(), base);
        setCreatedAt("auctions", tieLow.getId(), base.plusHours(1));
        setCreatedAt("auctions", tieHigh.getId(), base.plusHours(1));
        setCreatedAt("auctions", newest.getId(), base.plusHours(2));
        entityManager.clear();

        assertThat(ids(service.getMyParticipatedAuctions(me.getId(), "current", 0, 20)))
                .containsExactly(newest.getId(), tieHigh.getId(), tieLow.getId(), oldest.getId());
    }

    @Test
    void 종료_경매_카드는_종료_시점_최고가와_내_관심_여부를_보여준다() {
        User rival = persistUser("rival@vintic.local");
        Auction lost = persistAuction(other, AuctionStatus.LIVE);
        bid(lost, me, 15000L);
        bid(lost, rival, 25000L);
        lost.end();
        like(lost, me);
        like(lost, rival);
        entityManager.flush();
        entityManager.clear();

        AuctionCardResponse card = service.getMyParticipatedAuctions(me.getId(), "past", 0, 20).items().get(0);

        assertThat(card.status()).isEqualTo(AuctionStatus.ENDED);
        assertThat(card.startPrice()).isEqualTo(10000L);
        // 경매의 현재가(종료 시점 최고가)이지 내 입찰가가 아니다
        assertThat(card.currentPrice()).isEqualTo(25000L);
        assertThat(card.likeCount()).isEqualTo(2);
        assertThat(card.isLiked()).isTrue();
        assertThat(card.thumbnailUrl()).startsWith("signed:");
    }

    @Test
    void 참여한_경매가_없으면_빈_목록이다() {
        persistAuction(me, AuctionStatus.LIVE);
        entityManager.flush();
        entityManager.clear();

        AuctionListResponse response = service.getMyParticipatedAuctions(me.getId(), "current", 0, 20);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void 나의_경매_state와_page_size가_잘못되면_예외다() {
        assertThatThrownBy(() -> service.getMyParticipatedAuctions(me.getId(), "ended", 0, 20))
                .isInstanceOf(InvalidPageRequestException.class);
        assertThatThrownBy(() -> service.getMyParticipatedAuctions(me.getId(), null, 0, 20))
                .isInstanceOf(InvalidPageRequestException.class);
        assertThatThrownBy(() -> service.getMyParticipatedAuctions(me.getId(), "current", -1, 20))
                .isInstanceOf(InvalidPageRequestException.class);
        assertThatThrownBy(() -> service.getMyParticipatedAuctions(me.getId(), "current", 0, 51))
                .isInstanceOf(InvalidPageRequestException.class);
    }

    // ===== 관심 목록 =====

    @Test
    void 관심_목록은_내_관심만_나오고_종료_취소_경매도_포함하며_isLiked는_모두_true다() {
        Auction live = persistAuction(other, AuctionStatus.LIVE);
        Auction ended = persistAuction(other, AuctionStatus.ENDED);
        Auction canceled = persistAuction(other, AuctionStatus.CANCELED);
        Auction onlyOtherLikes = persistAuction(other, AuctionStatus.LIVE);
        like(live, me);
        like(ended, me);
        like(canceled, me);
        like(live, other);
        like(onlyOtherLikes, other);
        entityManager.flush();
        entityManager.clear();

        AuctionListResponse response = service.getMyLikedAuctions(me.getId(), 0, 20);

        assertThat(ids(response)).containsExactlyInAnyOrder(live.getId(), ended.getId(), canceled.getId());
        assertThat(response.items()).allMatch(AuctionCardResponse::isLiked);
        assertThat(response.items()).extracting(AuctionCardResponse::status)
                .containsExactlyInAnyOrder(AuctionStatus.LIVE, AuctionStatus.ENDED, AuctionStatus.CANCELED);
        // likeCount는 전체 사용자 기준이다(live는 나와 other 두 명)
        assertThat(response.items().stream().filter(c -> c.auctionId().equals(live.getId())).findFirst().orElseThrow()
                .likeCount()).isEqualTo(2);
    }

    @Test
    void 관심_목록은_관심_등록_시각_최신순이고_같으면_관심_ID_내림차순이며_페이지로_나뉜다() {
        LocalDateTime base = LocalDateTime.now().minusDays(1).withNano(0);
        AuctionLike oldest = like(persistAuction(other, AuctionStatus.LIVE), me);
        AuctionLike tieLow = like(persistAuction(other, AuctionStatus.ENDED), me);
        AuctionLike tieHigh = like(persistAuction(other, AuctionStatus.SCHEDULED), me);
        AuctionLike newest = like(persistAuction(other, AuctionStatus.LIVE), me);
        setCreatedAt("auction_likes", oldest.getId(), base);
        setCreatedAt("auction_likes", tieLow.getId(), base.plusHours(1));
        setCreatedAt("auction_likes", tieHigh.getId(), base.plusHours(1));
        setCreatedAt("auction_likes", newest.getId(), base.plusHours(2));
        entityManager.clear();

        AuctionListResponse first = service.getMyLikedAuctions(me.getId(), 0, 2);
        AuctionListResponse second = service.getMyLikedAuctions(me.getId(), 1, 2);
        AuctionListResponse beyond = service.getMyLikedAuctions(me.getId(), 2, 2);

        assertThat(ids(first)).containsExactly(newest.getAuction().getId(), tieHigh.getAuction().getId());
        assertThat(first.hasNext()).isTrue();
        assertThat(ids(second)).containsExactly(tieLow.getAuction().getId(), oldest.getAuction().getId());
        assertThat(second.hasNext()).isFalse();
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.hasNext()).isFalse();
    }

    @Test
    void 관심이_없으면_빈_목록이고_잘못된_page_size는_예외다() {
        assertThat(service.getMyLikedAuctions(me.getId(), 0, 20).items()).isEmpty();
        assertThatThrownBy(() -> service.getMyLikedAuctions(me.getId(), -1, 20))
                .isInstanceOf(InvalidPageRequestException.class);
        assertThatThrownBy(() -> service.getMyLikedAuctions(me.getId(), 0, 0))
                .isInstanceOf(InvalidPageRequestException.class);
    }

    @Test
    void 관심_목록이_길어져도_쿼리_수는_늘지_않는다() {
        for (int i = 0; i < 2; i++) {
            like(persistAuction(other, AuctionStatus.LIVE), me);
        }
        entityManager.flush();
        entityManager.clear();
        long two = countQueries(() -> service.getMyLikedAuctions(me.getId(), 0, 20));

        for (int i = 0; i < 6; i++) {
            like(persistAuction(other, AuctionStatus.ENDED), me);
        }
        entityManager.flush();
        entityManager.clear();
        long eight = countQueries(() -> service.getMyLikedAuctions(me.getId(), 0, 20));

        assertThat(eight).isEqualTo(two);
    }

    private long countQueries(Runnable action) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        action.run();
        long count = statistics.getPrepareStatementCount();
        entityManager.clear();
        return count;
    }
}

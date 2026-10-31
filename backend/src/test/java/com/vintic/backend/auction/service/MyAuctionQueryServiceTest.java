package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.dto.AuctionCardResponse;
import com.vintic.backend.auction.dto.AuctionListResponse;
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

// #143: 마이페이지 "나의 경매"(판매 등록한 경매)와 관심 목록을 실제 JPA 쿼리로 확인한다.
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

    // ===== 나의 경매(판매 등록) =====

    @Test
    void 나의_경매는_내가_판매자인_경매만_current_past로_나눠_조회한다() {
        Auction myScheduled = persistAuction(me, AuctionStatus.SCHEDULED);
        Auction myLive = persistAuction(me, AuctionStatus.LIVE);
        Auction myEnded = persistAuction(me, AuctionStatus.ENDED);
        Auction myCanceled = persistAuction(me, AuctionStatus.CANCELED);
        persistAuction(other, AuctionStatus.LIVE);
        persistAuction(other, AuctionStatus.ENDED);
        entityManager.flush();
        entityManager.clear();

        assertThat(ids(service.getMySellingAuctions(me.getId(), "current", 0, 20)))
                .containsExactlyInAnyOrder(myScheduled.getId(), myLive.getId());
        assertThat(ids(service.getMySellingAuctions(me.getId(), "past", 0, 20)))
                .containsExactlyInAnyOrder(myEnded.getId(), myCanceled.getId());
    }

    @Test
    void 재경매한_상품은_경매마다_따로_나온다() {
        Product product = persistProduct(me);
        Auction first = persistAuction(product, AuctionStatus.ENDED);
        Auction relisted = persistAuction(product, AuctionStatus.SCHEDULED);
        entityManager.flush();
        entityManager.clear();

        AuctionListResponse current = service.getMySellingAuctions(me.getId(), "current", 0, 20);
        AuctionListResponse past = service.getMySellingAuctions(me.getId(), "past", 0, 20);

        assertThat(ids(current)).containsExactly(relisted.getId());
        assertThat(ids(past)).containsExactly(first.getId());
        assertThat(current.items().get(0).productId()).isEqualTo(past.items().get(0).productId());
    }

    @Test
    void 나의_경매는_생성_시각_최신순이고_같으면_경매_ID_내림차순이며_페이지로_나뉜다() {
        LocalDateTime base = LocalDateTime.now().minusDays(1).withNano(0);
        Auction oldest = persistAuction(me, AuctionStatus.LIVE);
        Auction tieLow = persistAuction(me, AuctionStatus.SCHEDULED);
        Auction tieHigh = persistAuction(me, AuctionStatus.SCHEDULED);
        Auction newest = persistAuction(me, AuctionStatus.LIVE);
        setCreatedAt("auctions", oldest.getId(), base);
        setCreatedAt("auctions", tieLow.getId(), base.plusHours(1));
        setCreatedAt("auctions", tieHigh.getId(), base.plusHours(1));
        setCreatedAt("auctions", newest.getId(), base.plusHours(2));
        entityManager.clear();

        AuctionListResponse first = service.getMySellingAuctions(me.getId(), "current", 0, 3);
        AuctionListResponse second = service.getMySellingAuctions(me.getId(), "current", 1, 3);
        AuctionListResponse beyond = service.getMySellingAuctions(me.getId(), "current", 5, 3);

        assertThat(ids(first)).containsExactly(newest.getId(), tieHigh.getId(), tieLow.getId());
        assertThat(first.hasNext()).isTrue();
        assertThat(ids(second)).containsExactly(oldest.getId());
        assertThat(second.hasNext()).isFalse();
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.hasNext()).isFalse();
    }

    @Test
    void 종료_경매_카드의_currentPrice는_종료_시점_최고가이고_입찰이_없으면_시작가다() {
        User bidder = persistUser("bidder@vintic.local");
        Auction sold = Auction.schedule(persistProduct(me), 10000L, 5000L,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2));
        sold.start();
        entityManager.persist(sold);
        sold.placeManualBid(bidder, 25000L);
        entityManager.persist(Bid.place(sold, bidder, 25000L, BidType.MANUAL));
        sold.end();
        Auction unsold = persistAuction(me, AuctionStatus.ENDED);
        like(sold, other);
        entityManager.flush();
        entityManager.clear();

        List<AuctionCardResponse> cards = service.getMySellingAuctions(me.getId(), "past", 0, 20).items();
        AuctionCardResponse soldCard = cards.stream().filter(c -> c.auctionId().equals(sold.getId())).findFirst().orElseThrow();
        AuctionCardResponse unsoldCard = cards.stream().filter(c -> c.auctionId().equals(unsold.getId())).findFirst().orElseThrow();

        assertThat(soldCard.status()).isEqualTo(AuctionStatus.ENDED);
        assertThat(soldCard.startPrice()).isEqualTo(10000L);
        assertThat(soldCard.currentPrice()).isEqualTo(25000L);
        assertThat(soldCard.likeCount()).isEqualTo(1);
        // 판매자 본인은 이 경매에 관심을 누르지 않았다 - 다른 사용자의 관심이 isLiked로 섞이지 않는다
        assertThat(soldCard.isLiked()).isFalse();
        assertThat(soldCard.thumbnailUrl()).startsWith("signed:");
        assertThat(unsoldCard.currentPrice()).isEqualTo(unsoldCard.startPrice());
    }

    @Test
    void 판매한_경매가_없으면_빈_목록이다() {
        AuctionListResponse response = service.getMySellingAuctions(me.getId(), "past", 0, 20);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void 나의_경매_state와_page_size가_잘못되면_예외다() {
        assertThatThrownBy(() -> service.getMySellingAuctions(me.getId(), "ended", 0, 20))
                .isInstanceOf(InvalidPageRequestException.class);
        assertThatThrownBy(() -> service.getMySellingAuctions(me.getId(), null, 0, 20))
                .isInstanceOf(InvalidPageRequestException.class);
        assertThatThrownBy(() -> service.getMySellingAuctions(me.getId(), "current", -1, 20))
                .isInstanceOf(InvalidPageRequestException.class);
        assertThatThrownBy(() -> service.getMySellingAuctions(me.getId(), "current", 0, 51))
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

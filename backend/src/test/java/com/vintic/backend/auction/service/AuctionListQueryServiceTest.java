package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.dto.AuctionCardResponse;
import com.vintic.backend.auction.dto.AuctionListResponse;
import com.vintic.backend.auction.dto.PopularAuctionsResponse;
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

// #142: 인기 경매·기본 경매 목록의 조회 조건·정렬·페이지·카드 필드를 실제 JPA 쿼리로 확인한다.
// (MySQL에서의 쿼리 동작은 JwtAuthorizationWiringMySqlIT가 같은 API를 호출해 확인한다.)
@DataJpaTest
@Import({AuctionListQueryService.class, AuctionCardAssembler.class})
class AuctionListQueryServiceTest {

    @Autowired
    private AuctionListQueryService service;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    // 버킷을 public-read로 열지 않아 응답 직전에 서명한다. 서명 자체는 S3UrlPresignerTest가 본다 -
    // 여기서는 "signed:" 접두어로 서명 단계를 거쳤는지만 확인한다.
    @MockitoBean
    private S3UrlPresigner s3UrlPresigner;

    private User seller;
    private int productSeq;

    @BeforeEach
    void setUp() {
        lenient().when(s3UrlPresigner.presign(anyString(), any()))
                .thenAnswer(invocation -> "signed:" + invocation.getArgument(0));
        seller = persistUser("seller@vintic.local");
    }

    private User persistUser(String email) {
        User user = User.register(email, email, null);
        entityManager.persist(user);
        return user;
    }

    // 상품당 활성 경매는 1개라(uk_auction_product_active_slot) 경매마다 상품을 새로 만든다.
    private Product persistProduct(String grade, List<String> imageUrls) {
        productSeq++;
        Product product = new Product(
                seller, imageUrls, "Nike", "Dunk Low " + productSeq, "Panda", 270, grade, "FULL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        );
        entityManager.persist(product);
        return product;
    }

    private Auction persistAuction(AuctionStatus status) {
        return persistAuction(status, LocalDateTime.now().plusHours(2));
    }

    private Auction persistAuction(AuctionStatus status, LocalDateTime endAt) {
        Product product = persistProduct("A", List.of("https://bucket.s3.ap-northeast-2.amazonaws.com/p" + (productSeq + 1) + ".jpg"));
        Auction auction = Auction.schedule(product, 10000L, 5000L, endAt.minusHours(1), endAt);
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

    private void bid(Auction auction, User bidder, long amount) {
        auction.placeManualBid(bidder, amount);
        entityManager.persist(Bid.place(auction, bidder, amount, BidType.MANUAL));
    }

    private void like(Auction auction, User user) {
        entityManager.persist(AuctionLike.create(auction, user));
    }

    private void setCreatedAt(Auction auction, LocalDateTime createdAt) {
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE auctions SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", auction.getId())
                .executeUpdate();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static List<Long> ids(List<AuctionCardResponse> cards) {
        return cards.stream().map(AuctionCardResponse::auctionId).toList();
    }

    // ===== 카드 필드 =====

    @Test
    void 카드_필드는_실제_경매_상품_관심_데이터와_일치한다() {
        User bidder = persistUser("bidder@vintic.local");
        User fan1 = persistUser("fan1@vintic.local");
        User fan2 = persistUser("fan2@vintic.local");
        Product product = persistProduct("DS", List.of(
                "https://bucket.s3.ap-northeast-2.amazonaws.com/first.jpg",
                "https://bucket.s3.ap-northeast-2.amazonaws.com/second.jpg"));
        Auction auction = Auction.schedule(product, 10000L, 5000L,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2));
        auction.start();
        entityManager.persist(auction);
        bid(auction, bidder, 25000L);
        like(auction, fan1);
        like(auction, fan2);
        flushAndClear();

        AuctionCardResponse card = service.getList(0, 20, fan1.getId()).items().get(0);

        assertThat(card.auctionId()).isEqualTo(auction.getId());
        assertThat(card.productId()).isEqualTo(product.getId());
        assertThat(card.brand()).isEqualTo("Nike");
        assertThat(card.name()).isEqualTo("Nike Dunk Low " + productSeq + " Panda");
        assertThat(card.thumbnailUrl()).isEqualTo("signed:https://bucket.s3.ap-northeast-2.amazonaws.com/first.jpg");
        assertThat(card.status()).isEqualTo(AuctionStatus.LIVE);
        assertThat(card.grade()).isEqualTo("DS");
        assertThat(card.startPrice()).isEqualTo(10000L);
        assertThat(card.currentPrice()).isEqualTo(25000L);
        assertThat(card.likeCount()).isEqualTo(2);
        assertThat(card.isLiked()).isTrue();
    }

    @Test
    void 이미지가_없는_상품은_thumbnailUrl이_null이다() {
        Product product = persistProduct("B", List.of());
        Auction auction = Auction.schedule(product, 10000L, 5000L,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2));
        entityManager.persist(auction);
        flushAndClear();

        AuctionCardResponse card = service.getList(0, 20, null).items().get(0);

        assertThat(card.thumbnailUrl()).isNull();
        assertThat(card.status()).isEqualTo(AuctionStatus.SCHEDULED);
        assertThat(card.currentPrice()).isEqualTo(card.startPrice());
        assertThat(card.likeCount()).isZero();
    }

    @Test
    void 비로그인은_isLiked가_false이고_다른_사용자의_관심_여부가_섞이지_않는다() {
        User me = persistUser("me@vintic.local");
        User other = persistUser("other@vintic.local");
        Auction likedByOther = persistAuction(AuctionStatus.LIVE);
        Auction likedByMe = persistAuction(AuctionStatus.LIVE);
        like(likedByOther, other);
        like(likedByMe, me);
        flushAndClear();

        List<AuctionCardResponse> anonymous = service.getList(0, 20, null).items();
        List<AuctionCardResponse> mine = service.getList(0, 20, me.getId()).items();

        assertThat(anonymous).allMatch(card -> !card.isLiked());
        assertThat(anonymous).allMatch(card -> card.likeCount() == 1);
        assertThat(mine).filteredOn(AuctionCardResponse::isLiked).extracting(AuctionCardResponse::auctionId)
                .containsExactly(likedByMe.getId());
    }

    // ===== GET /api/auctions/popular =====

    @Test
    void 인기_경매는_입찰_수_내림차순이고_관심_수는_기준이_아니다() {
        User b1 = persistUser("b1@vintic.local");
        User b2 = persistUser("b2@vintic.local");
        User fan = persistUser("fan@vintic.local");
        Auction oneBid = persistAuction(AuctionStatus.LIVE);
        Auction threeBids = persistAuction(AuctionStatus.LIVE);
        Auction noBidManyLikes = persistAuction(AuctionStatus.LIVE);
        bid(oneBid, b1, 15000L);
        bid(threeBids, b1, 15000L);
        bid(threeBids, b2, 20000L);
        bid(threeBids, b1, 25000L);
        like(noBidManyLikes, fan);
        like(noBidManyLikes, b1);
        like(noBidManyLikes, b2);
        flushAndClear();

        PopularAuctionsResponse response = service.getPopular(4, null);

        assertThat(ids(response.items())).containsExactly(threeBids.getId(), oneBid.getId(), noBidManyLikes.getId());
    }

    @Test
    void 인기_경매는_입찰_수가_같으면_종료_임박순_그래도_같으면_경매_ID_오름차순이다() {
        LocalDateTime sameEnd = LocalDateTime.now().plusHours(3).withNano(0);
        Auction laterEnd = persistAuction(AuctionStatus.LIVE, sameEnd.plusHours(1));
        Auction sameEndFirst = persistAuction(AuctionStatus.LIVE, sameEnd);
        Auction sameEndSecond = persistAuction(AuctionStatus.LIVE, sameEnd);
        flushAndClear();

        PopularAuctionsResponse response = service.getPopular(4, null);

        assertThat(ids(response.items()))
                .containsExactly(sameEndFirst.getId(), sameEndSecond.getId(), laterEnd.getId());
    }

    @Test
    void 인기_경매는_예정_진행중만_포함하고_종료_취소는_제외한다() {
        User bidder = persistUser("bidder@vintic.local");
        Auction scheduled = persistAuction(AuctionStatus.SCHEDULED);
        Auction live = persistAuction(AuctionStatus.LIVE);
        Auction endedWithBids = persistAuction(AuctionStatus.LIVE);
        bid(endedWithBids, bidder, 15000L);
        endedWithBids.end();
        persistAuction(AuctionStatus.CANCELED);
        flushAndClear();

        PopularAuctionsResponse response = service.getPopular(4, null);

        assertThat(ids(response.items())).containsExactlyInAnyOrder(scheduled.getId(), live.getId());
    }

    @Test
    void 인기_경매는_최대_limit개이고_부족하면_있는_만큼만_중복_없이_반환한다() {
        for (int i = 0; i < 5; i++) {
            persistAuction(AuctionStatus.LIVE);
        }
        flushAndClear();

        assertThat(service.getPopular(4, null).items()).hasSize(4);
        assertThat(ids(service.getPopular(20, null).items())).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    void 인기_경매_대상이_없으면_빈_목록이다() {
        persistAuction(AuctionStatus.CANCELED);
        flushAndClear();

        assertThat(service.getPopular(4, null).items()).isEmpty();
    }

    @Test
    void 인기_경매_limit이_범위를_벗어나면_예외다() {
        assertThatThrownBy(() -> service.getPopular(0, null)).isInstanceOf(InvalidPageRequestException.class);
        assertThatThrownBy(() -> service.getPopular(21, null)).isInstanceOf(InvalidPageRequestException.class);
    }

    // ===== GET /api/auctions =====

    @Test
    void 기본_목록은_예정_진행중만_포함하고_종료_취소는_제외한다() {
        Auction scheduled = persistAuction(AuctionStatus.SCHEDULED);
        Auction live = persistAuction(AuctionStatus.LIVE);
        persistAuction(AuctionStatus.ENDED);
        persistAuction(AuctionStatus.CANCELED);
        flushAndClear();

        AuctionListResponse response = service.getList(0, 20, null);

        assertThat(ids(response.items())).containsExactlyInAnyOrder(scheduled.getId(), live.getId());
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void 기본_목록은_생성_시각_내림차순이고_같으면_경매_ID_내림차순이다() {
        LocalDateTime base = LocalDateTime.now().minusDays(1).withNano(0);
        Auction oldest = persistAuction(AuctionStatus.LIVE);
        Auction newest = persistAuction(AuctionStatus.LIVE);
        Auction tieLowId = persistAuction(AuctionStatus.SCHEDULED);
        Auction tieHighId = persistAuction(AuctionStatus.SCHEDULED);
        setCreatedAt(oldest, base);
        setCreatedAt(newest, base.plusHours(3));
        setCreatedAt(tieLowId, base.plusHours(1));
        setCreatedAt(tieHighId, base.plusHours(1));
        entityManager.clear();

        AuctionListResponse response = service.getList(0, 20, null);

        assertThat(ids(response.items()))
                .containsExactly(newest.getId(), tieHighId.getId(), tieLowId.getId(), oldest.getId());
    }

    @Test
    void 기본_목록은_page_size로_나뉘고_hasNext로_다음_페이지를_알린다() {
        LocalDateTime base = LocalDateTime.now().minusDays(1).withNano(0);
        List<Auction> auctions = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Auction auction = persistAuction(AuctionStatus.LIVE);
            auctions.add(auction);
        }
        for (int i = 0; i < 5; i++) {
            setCreatedAt(auctions.get(i), base.plusMinutes(i));
        }
        entityManager.clear();

        AuctionListResponse first = service.getList(0, 2, null);
        AuctionListResponse second = service.getList(1, 2, null);
        AuctionListResponse last = service.getList(2, 2, null);
        AuctionListResponse beyond = service.getList(3, 2, null);

        assertThat(ids(first.items())).containsExactly(auctions.get(4).getId(), auctions.get(3).getId());
        assertThat(first.hasNext()).isTrue();
        assertThat(first.page()).isZero();
        assertThat(first.size()).isEqualTo(2);
        assertThat(ids(second.items())).containsExactly(auctions.get(2).getId(), auctions.get(1).getId());
        assertThat(second.hasNext()).isTrue();
        assertThat(ids(last.items())).containsExactly(auctions.get(0).getId());
        assertThat(last.hasNext()).isFalse();
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.hasNext()).isFalse();
    }

    @Test
    void 대상_경매가_없으면_빈_목록을_정상_반환한다() {
        AuctionListResponse response = service.getList(0, 20, null);

        assertThat(response.items()).isEmpty();
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void 기본_목록_page_size가_범위를_벗어나면_예외다() {
        assertThatThrownBy(() -> service.getList(-1, 20, null)).isInstanceOf(InvalidPageRequestException.class);
        assertThatThrownBy(() -> service.getList(0, 0, null)).isInstanceOf(InvalidPageRequestException.class);
        assertThatThrownBy(() -> service.getList(0, 51, null)).isInstanceOf(InvalidPageRequestException.class);
    }

    // ===== 쿼리 수 =====

    @Test
    void 카드_수가_늘어도_목록_조회의_쿼리_수는_늘지_않는다() {
        User fan = persistUser("fan@vintic.local");
        for (int i = 0; i < 2; i++) {
            like(persistAuction(AuctionStatus.LIVE), fan);
        }
        flushAndClear();
        long twoCards = countQueries(() -> service.getList(0, 20, fan.getId()));

        for (int i = 0; i < 6; i++) {
            like(persistAuction(AuctionStatus.LIVE), fan);
        }
        flushAndClear();
        long eightCards = countQueries(() -> service.getList(0, 20, fan.getId()));

        assertThat(eightCards).isEqualTo(twoCards);
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

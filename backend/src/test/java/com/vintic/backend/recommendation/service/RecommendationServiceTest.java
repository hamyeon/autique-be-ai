package com.vintic.backend.recommendation.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.dto.AuctionCardResponse;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.auction.service.AuctionCardAssembler;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.recommendation.domain.ProductVector;
import com.vintic.backend.recommendation.dto.RecommendationResponse;
import com.vintic.backend.recommendation.repository.ProductVectorRepository;
import com.vintic.backend.recommendation.repository.UserActivityLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecommendationServiceTest {

    @Mock
    private UserActivityLogRepository activityLogRepository;

    @Mock
    private UserVectorService userVectorService;

    @Mock
    private ProductVectorRepository productVectorRepository;

    @Mock
    private AuctionRepository auctionRepository;

    // Fallback 생성은 FallbackRecommendationProviderTest가 검증한다.
    // 여기서는 개인화/Fallback 분기만 본다.
    @Mock
    private FallbackRecommendationProvider fallbackProvider;

    // #142: 카드 필드 채우기. 카드 변환 자체는 AuctionListQueryServiceTest(실제 JPA)가 검증한다.
    @Mock
    private AuctionCardAssembler auctionCardAssembler;

    // #142: 추천 마지막 단계는 경매를 id로 다시 읽어 SCHEDULED/LIVE만 남긴다. 기존 분기 테스트가 그대로
    // 의미를 갖도록, auction(...)으로 만든 목 경매를 "DB"처럼 등록해 두고 다시 읽기·카드 변환이 그 목을
    // 쓰게 한다. 같은 id로 다시 만들면 최신 목(= 다시 읽은 시점의 상태)으로 바뀐다.
    private final Map<Long, Auction> stored = new HashMap<>();

    @BeforeEach
    void reloadFromStoredAuctions() {
        when(auctionRepository.findAllWithProductByIdIn(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return ids.stream().map(stored::get).filter(Objects::nonNull).toList();
        });
        when(auctionCardAssembler.toCards(anyList(), any())).thenAnswer(invocation -> {
            List<Auction> auctions = invocation.getArgument(0);
            return auctions.stream().map(a -> card(a.getId(), 100_000L, false)).toList();
        });
    }

    private RecommendationService newService() {
        return new RecommendationService(activityLogRepository, userVectorService,
                productVectorRepository, auctionRepository, fallbackProvider, auctionCardAssembler);
    }

    private static final float[] NIKE_LIKE = {1.0f, 0.0f};
    private static final float[] ADIDAS_LIKE = {0.0f, 1.0f};

    private Auction auction(Long auctionId, Long productId) {
        return auction(auctionId, productId, LocalDateTime.now().plusHours(1));
    }

    private Auction auction(Long auctionId, Long productId, LocalDateTime endAt) {
        return auction(auctionId, productId, endAt, AuctionStatus.LIVE);
    }

    private Auction auction(Long auctionId, Long productId, AuctionStatus status) {
        return auction(auctionId, productId, LocalDateTime.now().plusHours(1), status);
    }

    private Auction auction(Long auctionId, Long productId, LocalDateTime endAt, AuctionStatus status) {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        Auction auction = mock(Auction.class);
        when(auction.getId()).thenReturn(auctionId);
        when(auction.getProduct()).thenReturn(product);
        when(auction.getEndAt()).thenReturn(endAt);
        when(auction.getStatus()).thenReturn(status);
        stored.put(auctionId, auction);
        return auction;
    }

    private RecommendationResponse fallbackWithIds(long... auctionIds) {
        List<RecommendationResponse.RecommendedAuction> items = new java.util.ArrayList<>();
        for (long id : auctionIds) {
            items.add(RecommendationResponse.RecommendedAuction.withoutCard(
                    id, id * 10, "Nike", "Model", "Color", 270, 100_000L, null, null));
        }
        return new RecommendationResponse(false, "Fallback", items);
    }

    // Fallback 내용 검증은 FallbackRecommendationProviderTest의 몫이다.
    //
    // Auction 목을 받지 않고 id만 받는다. 목의 getter를 when(...) 안에서 부르면
    // Mockito가 스터빙 중인 것으로 오해해 UnfinishedStubbingException을 던진다.
    private RecommendationResponse fallbackWith(long auctionId, long productId) {
        return new RecommendationResponse(false, "Fallback",
                List.of(RecommendationResponse.RecommendedAuction.withoutCard(
                        auctionId, productId, "Nike", "Model", "Color", 270, 100_000L, null, null)));
    }

    @Test
    void 행동이_3건_미만이면_개인화하지_않는다() {
        // 1~2건은 우연일 수 있어 취향이라 보기 어렵다
        Auction a10 = auction(10L, 100L);
        when(activityLogRepository.countByUserId(1L)).thenReturn(2L);
        when(fallbackProvider.recommend(anyInt())).thenReturn(fallbackWith(10L, 100L));

        RecommendationResponse response = newService().recommend(1L, 10);

        assertThat(response.personalized()).isFalse();
        verify(userVectorService, never()).buildUserVector(anyLong());
    }

    @Test
    void 행동이_3건_이상이면_취향_유사도로_정렬한다() {
        Auction a10 = auction(10L, 100L);
        Auction a11 = auction(11L, 200L);
        when(activityLogRepository.countByUserId(1L)).thenReturn(3L);
        when(userVectorService.buildUserVector(1L)).thenReturn(Optional.of(NIKE_LIKE));
        when(auctionRepository.findOpenAuctions(anyList())).thenReturn(List.of(a10, a11));
        when(productVectorRepository.findAllById(anyList())).thenReturn(List.of(
                ProductVector.of(100L, ADIDAS_LIKE, "adidas"),
                ProductVector.of(200L, NIKE_LIKE, "nike")));

        RecommendationResponse response = newService().recommend(1L, 10);

        assertThat(response.personalized()).isTrue();
        // 취향(NIKE_LIKE)과 같은 방향인 상품 200이 먼저 와야 한다
        assertThat(response.items().get(0).productId()).isEqualTo(200L);
        assertThat(response.items().get(0).similarity()).isGreaterThan(response.items().get(1).similarity());
    }

    @Test
    void 비로그인이면_Fallback을_준다() {
        Auction a10 = auction(10L, 100L);
        when(fallbackProvider.recommend(anyInt())).thenReturn(fallbackWith(10L, 100L));

        RecommendationResponse response = newService().recommend(null, 10);

        assertThat(response.personalized()).isFalse();
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).similarity()).isNull();
    }

    @Test
    void 취향은_있지만_추천할_경매가_없으면_Fallback으로_넘어간다() {
        // 벡터가 아직 안 만들어진 상품만 있는 경우 - 빈 목록을 주면 화면이 깨진다
        Auction a10 = auction(10L, 100L);
        Auction a10Fallback = auction(10L, 100L);
        when(activityLogRepository.countByUserId(1L)).thenReturn(5L);
        when(userVectorService.buildUserVector(1L)).thenReturn(Optional.of(NIKE_LIKE));
        when(auctionRepository.findOpenAuctions(anyList())).thenReturn(List.of(a10));
        when(productVectorRepository.findAllById(anyList())).thenReturn(List.of());
        when(fallbackProvider.recommend(anyInt())).thenReturn(fallbackWith(10L, 100L));

        RecommendationResponse response = newService().recommend(1L, 10);

        assertThat(response.personalized()).isFalse();
        assertThat(response.items()).isNotEmpty();
    }

    @Test
    void 벡터_없는_경매도_버리지_않고_유사도_순_뒤에_붙인다() {
        // 유사도를 못 매기는 것과 보여줄 수 없는 것은 다르다. 버리면 같은 경매가
        // Fallback 사용자에게는 보이고 개인화 사용자에게는 안 보이는 역전이 생긴다.
        Auction adidasAuction = auction(10L, 100L);
        Auction nikeAuction = auction(11L, 200L);
        Auction vectorless = auction(12L, 300L);
        when(activityLogRepository.countByUserId(1L)).thenReturn(3L);
        when(userVectorService.buildUserVector(1L)).thenReturn(Optional.of(NIKE_LIKE));
        when(auctionRepository.findOpenAuctions(anyList()))
                .thenReturn(List.of(adidasAuction, nikeAuction, vectorless));
        when(productVectorRepository.findAllById(anyList())).thenReturn(List.of(
                ProductVector.of(100L, ADIDAS_LIKE, "adidas"),
                ProductVector.of(200L, NIKE_LIKE, "nike")));

        RecommendationResponse response = newService().recommend(1L, 10);

        assertThat(response.personalized()).isTrue();
        assertThat(response.items()).extracting(RecommendationResponse.RecommendedAuction::auctionId)
                .containsExactly(11L, 10L, 12L);
        // 순위를 못 매긴 항목은 similarity를 지어내지 않는다 - 측정하지 않은 숫자를
        // 측정한 것처럼 내보내면 안 된다
        assertThat(response.items().get(2).similarity()).isNull();
    }

    @Test
    void 벡터_없는_경매끼리는_마감_임박_순으로_붙는다() {
        Auction ranked = auction(10L, 100L);
        Auction endsLater = auction(11L, 300L, LocalDateTime.now().plusHours(5));
        Auction endsSoon = auction(12L, 400L, LocalDateTime.now().plusHours(1));
        when(activityLogRepository.countByUserId(1L)).thenReturn(3L);
        when(userVectorService.buildUserVector(1L)).thenReturn(Optional.of(NIKE_LIKE));
        when(auctionRepository.findOpenAuctions(anyList()))
                .thenReturn(List.of(ranked, endsLater, endsSoon));
        when(productVectorRepository.findAllById(anyList())).thenReturn(List.of(
                ProductVector.of(100L, NIKE_LIKE, "nike")));

        RecommendationResponse response = newService().recommend(1L, 10);

        // 순위 없는 구간에서는 지금 참여할 수 있는 것부터 보여준다
        assertThat(response.items()).extracting(RecommendationResponse.RecommendedAuction::auctionId)
                .containsExactly(10L, 12L, 11L);
    }

    @Test
    void limit은_순위_있는_것과_없는_것을_합쳐_적용한다() {
        Auction ranked = auction(10L, 100L);
        Auction vectorless1 = auction(11L, 300L, LocalDateTime.now().plusHours(1));
        Auction vectorless2 = auction(12L, 400L, LocalDateTime.now().plusHours(2));
        when(activityLogRepository.countByUserId(1L)).thenReturn(3L);
        when(userVectorService.buildUserVector(1L)).thenReturn(Optional.of(NIKE_LIKE));
        when(auctionRepository.findOpenAuctions(anyList()))
                .thenReturn(List.of(ranked, vectorless1, vectorless2));
        when(productVectorRepository.findAllById(anyList())).thenReturn(List.of(
                ProductVector.of(100L, NIKE_LIKE, "nike")));

        RecommendationResponse response = newService().recommend(1L, 2);

        assertThat(response.items()).extracting(RecommendationResponse.RecommendedAuction::auctionId)
                .containsExactly(10L, 11L);
    }

    @Test
    void limit을_Fallback에_그대로_넘긴다() {
        // 개수를 실제로 잘라내는 건 FallbackRecommendationProviderTest가 검증한다.
        // 여기서 볼 것은 요청받은 limit이 손실 없이 전달되는지다.
        when(fallbackProvider.recommend(anyInt())).thenReturn(fallbackWith(10L, 100L));

        newService().recommend(null, 7);

        verify(fallbackProvider).recommend(7);
    }

    // ===== #142 카드 필드 =====

    private static AuctionCardResponse card(long auctionId, long currentPrice, boolean isLiked) {
        return new AuctionCardResponse(auctionId, 100L, "Nike", "Nike Model Color", "signed:a.jpg",
                AuctionStatus.LIVE, "A", 50_000L, currentPrice, 7, isLiked);
    }

    @Test
    void Fallback_결과에_조회_시점의_카드_필드와_요청자의_isLiked를_채운다() {
        Auction a10 = auction(10L, 100L);
        when(fallbackProvider.recommend(anyInt())).thenReturn(fallbackWith(10L, 100L));
        when(auctionRepository.findAllWithProductByIdIn(List.of(10L))).thenReturn(List.of(a10));
        when(auctionCardAssembler.toCards(List.of(a10), 1L)).thenReturn(List.of(card(10L, 120_000L, true)));

        RecommendationResponse response = newService().recommend(1L, 4);

        RecommendationResponse.RecommendedAuction item = response.items().get(0);
        assertThat(response.personalized()).isFalse();
        assertThat(item.auctionId()).isEqualTo(10L);
        assertThat(item.name()).isEqualTo("Nike Model Color");
        assertThat(item.thumbnailUrl()).isEqualTo("signed:a.jpg");
        assertThat(item.status()).isEqualTo(AuctionStatus.LIVE);
        assertThat(item.grade()).isEqualTo("A");
        assertThat(item.startPrice()).isEqualTo(50_000L);
        // 캐시된 Fallback 값(100,000)이 아니라 조회 시점 값으로 맞춘다
        assertThat(item.currentPrice()).isEqualTo(120_000L);
        assertThat(item.likeCount()).isEqualTo(7);
        assertThat(item.isLiked()).isTrue();
        // 기존 필드는 그대로
        assertThat(item.brand()).isEqualTo("Nike");
        assertThat(item.model()).isEqualTo("Model");
        assertThat(item.similarity()).isNull();
    }

    @Test
    void 비로그인은_null_userId로_카드를_만든다() {
        Auction a10 = auction(10L, 100L);
        when(fallbackProvider.recommend(anyInt())).thenReturn(fallbackWith(10L, 100L));
        when(auctionRepository.findAllWithProductByIdIn(List.of(10L))).thenReturn(List.of(a10));
        when(auctionCardAssembler.toCards(List.of(a10), null)).thenReturn(List.of(card(10L, 100_000L, false)));

        RecommendationResponse response = newService().recommend(null, 4);

        assertThat(response.items().get(0).isLiked()).isFalse();
        verify(auctionCardAssembler).toCards(List.of(a10), null);
    }

    @Test
    void 카드_필드를_채워도_개인화_순서와_유사도는_바뀌지_않는다() {
        Auction a10 = auction(10L, 100L);
        Auction a11 = auction(11L, 200L);
        when(activityLogRepository.countByUserId(1L)).thenReturn(3L);
        when(userVectorService.buildUserVector(1L)).thenReturn(Optional.of(NIKE_LIKE));
        when(auctionRepository.findOpenAuctions(anyList())).thenReturn(List.of(a10, a11));
        when(productVectorRepository.findAllById(anyList())).thenReturn(List.of(
                ProductVector.of(100L, ADIDAS_LIKE, "adidas"),
                ProductVector.of(200L, NIKE_LIKE, "nike")));
        // DB가 id 순으로 돌려줘도 응답은 추천 순서(11 -> 10)를 지켜야 한다
        when(auctionRepository.findAllWithProductByIdIn(List.of(11L, 10L))).thenReturn(List.of(a10, a11));
        when(auctionCardAssembler.toCards(List.of(a11, a10), 1L))
                .thenReturn(List.of(card(11L, 1L, false), card(10L, 2L, true)));

        RecommendationResponse response = newService().recommend(1L, 4);

        assertThat(response.personalized()).isTrue();
        assertThat(response.items()).extracting(RecommendationResponse.RecommendedAuction::auctionId)
                .containsExactly(11L, 10L);
        assertThat(response.items().get(0).similarity()).isGreaterThan(response.items().get(1).similarity());
        assertThat(response.items()).extracting(RecommendationResponse.RecommendedAuction::isLiked)
                .containsExactly(false, true);
    }

    @Test
    void 다시_읽지_못한_경매는_상태를_확인할_수_없어_제외한다() {
        // stored에 없는 경매 - 정상 흐름에선 없지만 상태를 확인할 수 없으므로 내보내지 않는다
        when(fallbackProvider.recommend(anyInt())).thenReturn(fallbackWithIds(10L));

        RecommendationResponse response = newService().recommend(null, 4);

        assertThat(response.items()).isEmpty();
        assertThat(response.personalized()).isFalse();
    }

    // ===== #142 최종 상태 필터 =====

    @Test
    void 캐시된_Fallback에서_이후_종료_취소된_경매는_빼고_남은_순서를_유지한다() {
        auction(10L, 100L, AuctionStatus.LIVE);
        auction(11L, 110L, AuctionStatus.ENDED);
        auction(12L, 120L, AuctionStatus.SCHEDULED);
        auction(13L, 130L, AuctionStatus.CANCELED);
        auction(14L, 140L, AuctionStatus.LIVE);
        RecommendationResponse cached = fallbackWithIds(10L, 11L, 12L, 13L, 14L);
        when(fallbackProvider.recommend(anyInt())).thenReturn(cached);

        RecommendationResponse response = newService().recommend(null, 5);

        assertThat(response.personalized()).isFalse();
        assertThat(response.reason()).isEqualTo("Fallback");
        // 빠진 자리를 다른 경매로 채우지 않는다
        assertThat(response.items()).extracting(RecommendationResponse.RecommendedAuction::auctionId)
                .containsExactly(10L, 12L, 14L);
        assertThat(response.items()).extracting(RecommendationResponse.RecommendedAuction::similarity)
                .containsOnlyNulls();
    }

    @Test
    void 추천_대상이_모두_종료_취소되면_정상_빈_목록이다() {
        auction(10L, 100L, AuctionStatus.ENDED);
        auction(11L, 110L, AuctionStatus.CANCELED);
        when(fallbackProvider.recommend(anyInt())).thenReturn(fallbackWithIds(10L, 11L));

        RecommendationResponse response = newService().recommend(null, 4);

        assertThat(response.items()).isEmpty();
        assertThat(response.personalized()).isFalse();
        verify(auctionCardAssembler).toCards(List.of(), null);
    }

    @Test
    void 개인화_경로도_같은_최종_상태_필터를_거치고_순서와_유사도는_유지된다() {
        Auction a10 = auction(10L, 100L);
        Auction a11 = auction(11L, 200L);
        Auction a12 = auction(12L, 300L);
        when(activityLogRepository.countByUserId(1L)).thenReturn(3L);
        when(userVectorService.buildUserVector(1L)).thenReturn(Optional.of(NIKE_LIKE));
        when(auctionRepository.findOpenAuctions(anyList())).thenReturn(List.of(a10, a11, a12));
        when(productVectorRepository.findAllById(anyList())).thenReturn(List.of(
                ProductVector.of(100L, ADIDAS_LIKE, "adidas"),
                ProductVector.of(200L, NIKE_LIKE, "nike"),
                ProductVector.of(300L, new float[]{0.7f, 0.7f}, "mixed")));
        // 후보 조회 뒤, 다시 읽는 시점에 300번 상품의 경매(12)가 종료됐다
        auction(12L, 300L, AuctionStatus.ENDED);

        RecommendationResponse response = newService().recommend(1L, 10);

        assertThat(response.personalized()).isTrue();
        assertThat(response.items()).extracting(RecommendationResponse.RecommendedAuction::auctionId)
                .containsExactly(11L, 10L);
        assertThat(response.items().get(0).similarity()).isGreaterThan(response.items().get(1).similarity());
    }

    @Test
    void isLiked는_요청자별로_계산되고_공유_캐시_값에는_들어가지_않는다() {
        auction(10L, 100L, AuctionStatus.LIVE);
        auction(11L, 110L, AuctionStatus.LIVE);
        RecommendationResponse cached = fallbackWithIds(10L, 11L);
        when(fallbackProvider.recommend(anyInt())).thenReturn(cached);
        // 사용자 1은 10번만 관심 등록, 사용자 2는 없음
        when(auctionCardAssembler.toCards(anyList(), any())).thenAnswer(invocation -> {
            List<Auction> auctions = invocation.getArgument(0);
            Long viewer = invocation.getArgument(1);
            return auctions.stream()
                    .map(a -> card(a.getId(), 100_000L, Long.valueOf(1L).equals(viewer) && a.getId() == 10L))
                    .toList();
        });

        RecommendationResponse forUser1 = newService().recommend(1L, 4);
        RecommendationResponse forUser2 = newService().recommend(2L, 4);
        RecommendationResponse forAnonymous = newService().recommend(null, 4);

        assertThat(forUser1.items()).extracting(RecommendationResponse.RecommendedAuction::isLiked)
                .containsExactly(true, false);
        assertThat(forUser2.items()).extracting(RecommendationResponse.RecommendedAuction::isLiked)
                .containsExactly(false, false);
        assertThat(forAnonymous.items()).extracting(RecommendationResponse.RecommendedAuction::isLiked)
                .containsExactly(false, false);
        // 공유되는 Fallback 결과 객체는 바뀌지 않는다(카드 필드·isLiked는 응답 객체에만 있다)
        assertThat(cached.items()).allMatch(item -> !item.isLiked() && item.name() == null);
    }

    @Test
    void 추천_결과가_비면_카드_조회를_하지_않는다() {
        when(fallbackProvider.recommend(anyInt()))
                .thenReturn(new RecommendationResponse(false, "Fallback", List.of()));

        RecommendationResponse response = newService().recommend(null, 4);

        assertThat(response.items()).isEmpty();
        verify(auctionRepository, never()).findAllWithProductByIdIn(anyList());
    }
}

package com.vintic.backend.product.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.vision.dto.ConditionGrade;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.common.exception.AnalysisSessionNotFoundException;
import com.vintic.backend.common.exception.InvalidAnalysisStatusException;
import com.vintic.backend.common.exception.InvalidAuctionTimeException;
import com.vintic.backend.common.exception.InvalidImageException;
import com.vintic.backend.common.exception.UserNotFoundException;
import com.vintic.backend.common.util.BidIncrementPolicy;
import com.vintic.backend.common.util.S3UrlPresigner;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.dto.CreateProductRequest;
import com.vintic.backend.product.dto.ProductResponse;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.recommendation.service.ProductVectorService;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductRegistrationServiceTest {

    // 2026-08-18 20:00:00 KST 고정.
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-18T11:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final OffsetDateTime NOW_KST = OffsetDateTime.now(FIXED_CLOCK);

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UserRepository userRepository;

    // 등록 시 추천용 벡터를 만든다. 벡터 생성 자체는 ProductVectorServiceTest가 검증하므로
    // 여기서는 등록 흐름을 막지 않는지만 본다.
    @Mock
    private ProductVectorService productVectorService;

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private ProductAnalysisSessionRepository sessionRepository;

    @Mock
    private S3UrlPresigner s3UrlPresigner;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ProductRegistrationService sut;

    private final CreateProductRequest request = new CreateProductRequest(
            1L,
            List.of("https://example.com/a.jpg", "https://example.com/b.jpg", "https://example.com/c.jpg"),
            "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
            300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명", null,
            10000L, NOW_KST.plusHours(2), NOW_KST.plusHours(3)
    );

    private void initSut() {
        // presign 자체는 이 테스트의 관심사가 아니다 - 입력 URL을 그대로 돌려줘 등록 흐름
        // 검증에 영향을 주지 않는다(S3UrlPresignerTest가 presign 동작을 검증한다).
        lenient().when(s3UrlPresigner.presign(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        sut = new ProductRegistrationService(
                productRepository, userRepository, productVectorService, auctionRepository, sessionRepository,
                FIXED_CLOCK, s3UrlPresigner, objectMapper
        );
    }

    // 분석 세션에 서버가 저장해 둔 이미지(서명 없는 공개 형식 URL). multipart/photoIds 분석 모두
    // 세션에는 이 형식의 원본 URL만 남는다(ProductAnalyzeService.commitImagesAndEnqueue).
    private static final List<String> SESSION_IMAGES = List.of(
            "https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/side.jpg",
            "https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/front.jpg",
            "https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/outsole.jpg"
    );

    // #127: 등록에 쓸 수 있는(아직 취소/등록 확정되지 않은) 분석 세션을 흉내낸다.
    // #141: 세션 이미지가 등록 이미지의 출처가 되므로 실제 분석 세션처럼 3장을 둔다.
    private ProductAnalysisSession registrableSession() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markImageUploaded(SESSION_IMAGES);
        session.markQueued();
        return session;
    }

    private CreateProductRequest requestWithImages(List<String> imageUrls) {
        return requestWith(imageUrls, request.sellerDescription(), request.conditionDescription());
    }

    private CreateProductRequest requestWith(List<String> imageUrls, String sellerDescription, String conditionDescription) {
        return new CreateProductRequest(
                request.analysisId(),
                imageUrls, request.brand(), request.modelName(), request.color(), request.size(),
                request.conditionGrade(), request.componentStatus(), request.recommendedPrice(),
                request.baseMarketPrice(), request.priceRange(), request.sellingPrice(), request.reason(),
                sellerDescription, conditionDescription,
                request.auctionStartPrice(), request.auctionStartAt(), request.auctionEndAt()
        );
    }

    // #141: Vision 분석까지 끝난(AWAITING_USER_CONFIRMATION) 세션. 상태 설명 기본값의 출처다.
    private ProductAnalysisSession analyzedSession(String visionConditionDescription) throws Exception {
        ProductAnalysisSession session = registrableSession();
        session.claimVisionProcessing("token");
        session.completeVision("token", objectMapper.writeValueAsString(new VisionAnalysisResult(
                "Nike", "Dunk Low", "Panda", 270, visionConditionDescription, ConditionGrade.B, true, 0.9, false,
                List.of(), List.of(), List.of(), List.of()
        )));
        return session;
    }

    private Product savedProduct() {
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        return captor.getValue();
    }

    // ===== #141 상품 이미지 출처 =====

    @Test
    void imageUrls를_생략해도_분석_세션의_이미지로_등록된다() {
        initSut();
        stubHappyPath();

        ProductResponse response = sut.createProduct(requestWithImages(null), 1L);

        assertThat(savedProduct().getImageUrls()).containsExactlyElementsOf(SESSION_IMAGES);
        assertThat(response.imageUrls()).containsExactlyElementsOf(SESSION_IMAGES);
    }

    @Test
    void 다른_imageUrls를_보내도_분석_세션의_이미지가_저장된다() {
        initSut();
        stubHappyPath();

        sut.createProduct(requestWithImages(List.of(
                "https://evil.example.com/1.jpg", "https://evil.example.com/2.jpg", "https://evil.example.com/3.jpg"
        )), 1L);

        assertThat(savedProduct().getImageUrls()).containsExactlyElementsOf(SESSION_IMAGES);
    }

    @Test
    void 분석_조회의_서명_URL을_보내도_만료되는_서명_URL은_저장되지_않는다() {
        initSut();
        stubHappyPath();
        List<String> presigned = SESSION_IMAGES.stream()
                .map(url -> url + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Expires=21600&X-Amz-Signature=abc")
                .toList();

        sut.createProduct(requestWithImages(presigned), 1L);

        assertThat(savedProduct().getImageUrls())
                .containsExactlyElementsOf(SESSION_IMAGES)
                .noneMatch(url -> url.contains("X-Amz-"));
    }

    @Test
    void 이미지가_없는_세션이면_요청_imageUrls가_있어도_등록이_거절된다() {
        // #141: 서명을 뗀 우리 버킷 형식 URL이라도 소유권을 확인할 수 없으므로 대체 경로로 쓰지 않는다.
        initSut();
        User seller = User.register("seller@vintic.local", "seller", null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(seller));
        when(sessionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ProductAnalysisSession.create(1L)));
        List<String> presigned = SESSION_IMAGES.stream().map(url -> url + "?X-Amz-Signature=abc").toList();

        assertThatThrownBy(() -> sut.createProduct(requestWithImages(presigned), 1L))
                .isInstanceOf(InvalidImageException.class);

        verify(productRepository, never()).save(any());
        verify(auctionRepository, never()).save(any());
    }

    @Test
    void 이미지가_없는_세션에_외부_URL을_보내도_등록이_거절된다() {
        initSut();
        User seller = User.register("seller@vintic.local", "seller", null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(seller));
        when(sessionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ProductAnalysisSession.create(1L)));

        assertThatThrownBy(() -> sut.createProduct(request, 1L))
                .isInstanceOf(InvalidImageException.class);

        verify(productRepository, never()).save(any());
    }

    @Test
    void 이미지_업로드에_실패한_세션은_등록이_거절된다() {
        initSut();
        User seller = User.register("seller@vintic.local", "seller", null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(seller));
        ProductAnalysisSession failed = ProductAnalysisSession.create(1L);
        failed.failImageUpload("S3 오류");
        when(sessionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(failed));

        assertThatThrownBy(() -> sut.createProduct(request, 1L))
                .isInstanceOf(InvalidImageException.class);

        verify(productRepository, never()).save(any());
    }

    // ===== #141 상태 설명 =====

    @Test
    void 상태_설명을_보내면_수정값이_저장되고_판매자_설명과_섞이지_않는다() throws Exception {
        initSut();
        stubHappyPathWith(analyzedSession("AI가 본 상태입니다."));

        sut.createProduct(requestWith(null, "판매자 설명", "판매자가 고친 상태 설명"), 1L);

        Product saved = savedProduct();
        assertThat(saved.getConditionDescription()).isEqualTo("판매자가 고친 상태 설명");
        assertThat(saved.getDescription()).isEqualTo("판매자 설명");
    }

    @Test
    void 상태_설명을_생략하면_분석_세션의_상태_설명이_저장된다() throws Exception {
        initSut();
        ProductAnalysisSession session = analyzedSession("AI가 본 상태입니다.");
        String visionJsonBefore = session.getVisionResultJson();
        stubHappyPathWith(session);

        sut.createProduct(requestWith(null, "판매자 설명", null), 1L);

        Product saved = savedProduct();
        assertThat(saved.getConditionDescription()).isEqualTo("AI가 본 상태입니다.");
        assertThat(saved.getDescription()).isEqualTo("판매자 설명");
        // 분석 원본 JSON은 바뀌지 않는다.
        assertThat(session.getVisionResultJson()).isEqualTo(visionJsonBefore);
    }

    @Test
    void 상태_설명이_공백뿐이면_분석_세션의_상태_설명이_저장된다() throws Exception {
        initSut();
        stubHappyPathWith(analyzedSession("AI가 본 상태입니다."));

        sut.createProduct(requestWith(null, null, "   "), 1L);

        assertThat(savedProduct().getConditionDescription()).isEqualTo("AI가 본 상태입니다.");
    }

    @Test
    void 상태_설명을_생략하고_분석_결과도_없으면_null로_저장된다() {
        initSut();
        stubHappyPath();

        sut.createProduct(requestWith(null, "판매자 설명", null), 1L);

        Product saved = savedProduct();
        assertThat(saved.getConditionDescription()).isNull();
        assertThat(saved.getDescription()).isEqualTo("판매자 설명");
    }

    @Test
    void 판매자_설명을_생략해도_상태_설명이_판매자_설명으로_들어가지_않는다() throws Exception {
        initSut();
        stubHappyPathWith(analyzedSession("AI가 본 상태입니다."));

        sut.createProduct(requestWith(null, null, null), 1L);

        Product saved = savedProduct();
        assertThat(saved.getDescription()).isNull();
        assertThat(saved.getConditionDescription()).isEqualTo("AI가 본 상태입니다.");
    }

    @Test
    void 세션에도_요청에도_이미지가_없으면_등록이_거절되고_상품이_저장되지_않는다() {
        initSut();
        User seller = User.register("seller@vintic.local", "seller", null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(seller));
        when(sessionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ProductAnalysisSession.create(1L)));

        assertThatThrownBy(() -> sut.createProduct(requestWithImages(null), 1L))
                .isInstanceOf(InvalidImageException.class);

        verify(productRepository, never()).save(any());
        verify(auctionRepository, never()).save(any());
    }

    @Test
    void 세션_이미지가_3장_미만이면_요청_imageUrls로_보충되지_않고_거절된다() {
        initSut();
        User seller = User.register("seller@vintic.local", "seller", null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(seller));
        ProductAnalysisSession twoImages = ProductAnalysisSession.create(1L);
        twoImages.markImageUploaded(SESSION_IMAGES.subList(0, 2));
        when(sessionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(twoImages));

        assertThatThrownBy(() -> sut.createProduct(request, 1L))
                .isInstanceOf(InvalidImageException.class);

        verify(productRepository, never()).save(any());
    }

    private void stubHappyPath() {
        stubHappyPathWith(registrableSession());
    }

    private void stubHappyPathWith(ProductAnalysisSession session) {
        User seller = User.register("seller@vintic.local", "seller", null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(seller));
        when(sessionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(session));
        when(productRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(auctionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void 존재하는_유저ID로_상품을_등록하면_seller로_설정된다() {
        initSut();
        stubHappyPath();

        ProductResponse response = sut.createProduct(request, 1L);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        assertThat(captor.getValue().getSeller().getEmail()).isEqualTo("seller@vintic.local");
        assertThat(response.brand()).isEqualTo("Nike");
    }

    @Test
    void 첫_경매가_상품과_함께_SCHEDULED로_생성되고_시작가는_판매희망가와_별개로_반영된다() {
        initSut();
        stubHappyPath();

        ProductResponse response = sut.createProduct(request, 1L);

        ArgumentCaptor<Auction> captor = ArgumentCaptor.forClass(Auction.class);
        verify(auctionRepository).save(captor.capture());
        Auction savedAuction = captor.getValue();

        assertThat(response.auctionStatus()).isEqualTo("SCHEDULED");
        assertThat(savedAuction.getStartPrice()).isEqualTo(10000L);
        assertThat(savedAuction.getStartPrice()).isNotEqualTo(request.sellingPrice().longValue());
        assertThat(response.sellingPrice()).isEqualTo(290000);
    }

    @Test
    void 경매별_입찰단위는_요청과_무관하게_고정값이_적용된다() {
        initSut();
        stubHappyPath();

        ProductResponse response = sut.createProduct(request, 1L);

        assertThat(response.bidIncrement()).isEqualTo(BidIncrementPolicy.DEFAULT_BID_INCREMENT);
    }

    @Test
    void 경매_진행시간이_1시간_미만이면_상품_저장까지_포함해_전체가_실패한다() {
        initSut();
        User seller = User.register("seller@vintic.local", "seller", null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(seller));
        when(sessionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(registrableSession()));
        when(productRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CreateProductRequest invalid = new CreateProductRequest(
                request.analysisId(),
                request.imageUrls(), request.brand(), request.modelName(), request.color(), request.size(),
                request.conditionGrade(), request.componentStatus(), request.recommendedPrice(),
                request.baseMarketPrice(), request.priceRange(), request.sellingPrice(), request.reason(),
                request.sellerDescription(), request.conditionDescription(),
                request.auctionStartPrice(), NOW_KST.plusHours(1), NOW_KST.plusHours(1).plusMinutes(59).plusSeconds(59)
        );

        assertThatThrownBy(() -> sut.createProduct(invalid, 1L))
                .isInstanceOf(InvalidAuctionTimeException.class);

        verify(auctionRepository, never()).save(any());
    }

    @Test
    void 존재하지_않는_유저ID로_상품을_등록하면_예외가_발생한다() {
        initSut();
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.createProduct(request, 999L))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void 상품을_등록하면_추천용_벡터를_만든다() {
        initSut();
        stubHappyPath();

        sut.createProduct(request, 1L);

        verify(productVectorService).refresh(any());
    }

    @Test
    void 분석_세션이_없으면_상품_등록이_거절되고_상품이_저장되지_않는다() {
        initSut();
        User seller = User.register("seller@vintic.local", "seller", null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(seller));
        when(sessionRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.createProduct(request, 1L))
                .isInstanceOf(AnalysisSessionNotFoundException.class);

        verify(productRepository, never()).save(any());
    }

    @Test
    void 타인의_분석_세션으로_상품_등록을_시도하면_존재하지_않는_것과_동일하게_거절된다() {
        initSut();
        User seller = User.register("seller@vintic.local", "seller", null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(seller));
        // analysisId 1L은 2L이 소유한 세션이다.
        when(sessionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ProductAnalysisSession.create(2L)));

        assertThatThrownBy(() -> sut.createProduct(request, 1L))
                .isInstanceOf(AnalysisSessionNotFoundException.class);

        verify(productRepository, never()).save(any());
    }

    @Test
    void 취소된_분석_세션으로는_상품_등록이_거절된다() {
        initSut();
        User seller = User.register("seller@vintic.local", "seller", null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(seller));
        ProductAnalysisSession cancelled = registrableSession();
        cancelled.cancel();
        when(sessionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> sut.createProduct(request, 1L))
                .isInstanceOf(InvalidAnalysisStatusException.class);

        verify(productRepository, never()).save(any());
    }

    @Test
    void 이미_다른_상품_등록에_사용된_분석_세션으로는_다시_등록할_수_없다() {
        initSut();
        User seller = User.register("seller@vintic.local", "seller", null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(seller));
        ProductAnalysisSession alreadyUsed = registrableSession();
        alreadyUsed.confirmRegistration();
        when(sessionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(alreadyUsed));

        assertThatThrownBy(() -> sut.createProduct(request, 1L))
                .isInstanceOf(InvalidAnalysisStatusException.class);

        verify(productRepository, never()).save(any());
    }
}

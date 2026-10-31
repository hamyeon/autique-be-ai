package com.vintic.backend.product.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.auction.service.AuctionSchedulePolicy;
import com.vintic.backend.common.exception.AnalysisSessionNotFoundException;
import com.vintic.backend.common.exception.InvalidImageException;
import com.vintic.backend.common.exception.UserNotFoundException;
import com.vintic.backend.common.util.BidIncrementPolicy;
import com.vintic.backend.common.util.S3UrlPresigner;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.dto.CreateProductRequest;
import com.vintic.backend.product.dto.ProductListResponse;
import com.vintic.backend.product.dto.ProductResponse;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.recommendation.service.ProductVectorService;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

// 상품 등록과 첫 경매 등록은 한 번의 제출로 함께 처리된다(기획 확정 - 두 단계로 분리하지
// 않는다). Product 저장과 Auction.schedule() 저장이 같은 @Transactional 경계 안에 있으므로,
// 시간 검증 실패 등으로 Auction 쪽이 예외를 던지면 이미 실행된 Product insert도 함께
// 롤백된다(별도 보상 로직 없이 트랜잭션 경계만으로 원자성을 보장 - ProductAuctionRegistration
// AtomicityMySqlIT로 실제 MySQL에서 확인).
//
// 이 경로로 만들어지는 Auction은 이 상품의 "총 2회" 등록 횟수 중 1회를 차지한다 - 남은 1회는
// AuctionRelistService(POST /api/auctions/{previousAuctionId}/relist, 재경매 전용)가 처리한다.
// 첫 경매를 만드는 외부 경로는 이 클래스(createProduct())뿐이다 - productId 기반의 별도 경매
// 생성 API는 존재하지 않는다. 신규 Product는 동시에 참조할 다른 트랜잭션이 있을 수 없어(아직
// 커밋 전) 재경매 쪽과 달리 Product row lock이 필요 없다.
@Service
public class ProductRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(ProductRegistrationService.class);
    private static final int MIN_IMAGE_COUNT = 3;
    private static final int MAX_IMAGE_COUNT = 4;

    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final ProductVectorService productVectorService;
    private final AuctionRepository auctionRepository;
    private final ProductAnalysisSessionRepository sessionRepository;
    private final Clock clock;
    private final S3UrlPresigner s3UrlPresigner;
    private final ObjectMapper objectMapper;

    public ProductRegistrationService(
            ProductRepository productRepository,
            UserRepository userRepository,
            ProductVectorService productVectorService,
            AuctionRepository auctionRepository,
            ProductAnalysisSessionRepository sessionRepository,
            Clock clock,
            S3UrlPresigner s3UrlPresigner,
            ObjectMapper objectMapper
    ) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.productVectorService = productVectorService;
        this.auctionRepository = auctionRepository;
        this.sessionRepository = sessionRepository;
        this.clock = clock;
        this.s3UrlPresigner = s3UrlPresigner;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProductResponse createProduct(CreateProductRequest request, Long sellerId) {
        User seller = userRepository.findById(sellerId)
                .orElseThrow(() -> new UserNotFoundException("존재하지 않는 사용자입니다: " + sellerId));

        // #127: 세션을 잠근(findByIdForUpdate) 채로 소유권·취소 여부·중복 등록 여부를 확인하고
        // "등록에 확정 사용됨"으로 표시한다 - 이 메서드 전체가 하나의 트랜잭션이므로, 아래에서
        // AuctionSchedulePolicy 검증 등으로 등록이 실패하면 이 확정 표시도 함께 롤백된다(세션은
        // 여전히 취소/재사용 가능한 상태로 남는다). 같은 행 잠금을 ProductAnalyzeService.cancel()도
        // 쓰므로, 취소와 등록 중 먼저 커밋되는 쪽이 그대로 확정된다.
        ProductAnalysisSession session = sessionRepository.findByIdForUpdate(request.analysisId())
                .orElseThrow(() -> new AnalysisSessionNotFoundException(
                        "분석 세션을 찾을 수 없습니다. analysisId: " + request.analysisId()
                ));
        if (!session.isOwnedBy(sellerId)) {
            throw new AnalysisSessionNotFoundException(
                    "분석 세션을 찾을 수 없습니다. analysisId: " + request.analysisId()
            );
        }
        session.confirmRegistration();
        sessionRepository.save(session);

        Product product = new Product(
                seller,
                resolveImageUrls(session),
                request.brand(),
                request.modelName(),
                request.color(),
                request.size(),
                request.conditionGrade(),
                request.componentStatus(),
                request.recommendedPrice(),
                request.baseMarketPrice(),
                request.priceRange(),
                request.sellingPrice(),
                request.reason(),
                request.sellerDescription(),
                resolveConditionDescription(session, request.conditionDescription())
        );

        Product savedProduct = productRepository.save(product);
        // 추천용 벡터를 만들어 둔다. 임베딩 호출이 실패해도 refresh가 삼키므로 등록은 성공한다 -
        // 추천 품질을 위한 부가 작업이 상품 등록을 막으면 안 된다.
        productVectorService.refresh(savedProduct);

        // auctionStartPrice는 sellingPrice(Product.finalPrice)와 무관한 별개 값이다 - 자동
        // 매핑하지 않고 요청값을 그대로 쓴다.
        AuctionSchedulePolicy.validate(request.auctionStartAt(), request.auctionEndAt(), clock);
        LocalDateTime startAt = TimePolicy.fromApiTime(request.auctionStartAt());
        LocalDateTime endAt = TimePolicy.fromApiTime(request.auctionEndAt());
        Auction auction = Auction.schedule(
                savedProduct, request.auctionStartPrice(), BidIncrementPolicy.DEFAULT_BID_INCREMENT, startAt, endAt
        );
        Auction savedAuction = auctionRepository.save(auction);

        return ProductResponse.from(savedProduct, savedAuction, s3UrlPresigner);
    }

    // #141: 상품 이미지는 분석 세션에 서버가 저장해 둔 원본 URL로만 정한다. 세션 이미지는
    // multipart 분석(S3UploaderService 업로드 결과)과 photoIds 분석(ProductPhoto.originalUrl,
    // PhotoUsageCommitter가 소유권·미사용을 확인한 사진) 모두 서버가 직접 채운 서명 없는 URL이다.
    // 요청 imageUrls는 어떤 경우에도 쓰지 않는다 - 클라이언트가 보낸 URL은 서명을 떼어도 그
    // 객체가 이 판매자의 사진인지 확인할 방법이 없기 때문이다. 두 분석 경로 모두 큐 적재 전에
    // markImageUploaded로 세션 이미지를 저장하므로, 세션 이미지가 비어 있는 것은 이미지 업로드
    // 전(CREATED)이거나 업로드에 실패한(IMAGE_UPLOAD_FAILED) 세션뿐이다 - 둘 다 등록을 거절한다.
    private List<String> resolveImageUrls(ProductAnalysisSession session) {
        List<String> imageUrls = session.getImageUrls();
        if (imageUrls.size() < MIN_IMAGE_COUNT || imageUrls.size() > MAX_IMAGE_COUNT) {
            throw new InvalidImageException(
                    "분석 세션에 저장된 상품 이미지는 최소 3개, 최대 4개여야 합니다. 현재 이미지 수: " + imageUrls.size()
            );
        }
        return imageUrls;
    }

    // #141: 판매자가 수정한 상태 설명이 있으면 그 값을, 없으면(null/공백) 분석 세션의 Vision
    // conditionDescription을 저장한다. 세션의 vision_result_json은 읽기만 하고 바꾸지 않는다.
    // 분석 결과가 아직 없거나 읽지 못하면 null로 저장한다 - 상태 설명은 등록 필수값이 아니다.
    private String resolveConditionDescription(ProductAnalysisSession session, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        String visionResultJson = session.getVisionResultJson();
        if (visionResultJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(visionResultJson, VisionAnalysisResult.class).conditionDescription();
        } catch (JsonProcessingException e) {
            log.warn("분석 결과를 읽지 못해 상태 설명 없이 등록합니다. analysisId={}", session.getId(), e);
            return null;
        }
    }

    @Transactional(readOnly = true)
    public List<ProductListResponse> getProducts() {
        return productRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(product -> ProductListResponse.from(product, s3UrlPresigner))
                .toList();
    }
}

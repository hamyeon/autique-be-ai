package com.vintic.backend.product;

import com.vintic.backend.analyze.domain.PhotoMatchStatus;
import com.vintic.backend.analyze.domain.PhotoSlot;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.domain.ProductPhoto;
import com.vintic.backend.analyze.domain.ProductPhotoRepository;
import com.vintic.backend.analyze.service.PhotoUsageCommitter;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.product.dto.ProductResponse;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 상품 등록과 첫 경매 등록은 같은 트랜잭션에 있다(ProductRegistrationService.createProduct()).
// 경매 쪽 검증(진행시간 최소 1시간)이 Product insert 이후에 실패하면 이미 실행된 Product insert도
// 함께 롤백되는지를 실제 MySQL(Testcontainers)로 확인한다 - Mockito 단위테스트는 실제 트랜잭션
// 커밋/롤백을 검증할 수 없어(ProductRegistrationServiceTest는 "auction 저장이 호출되지 않는지"만
// 확인) 이 원자성 자체는 여기서만 신뢰성 있게 검증된다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class ProductAuctionRegistrationAtomicityMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    // shippingFee 매핑을 철회하기 전, 이 브랜치에서 이미 한 번 ddl-auto:update로 products 테이블에
    // shipping_fee 컬럼이 생성된 적이 있다(dev RDS도 같은 상태일 수 있다). @BeforeAll static
    // 메서드는 Spring context(Hibernate ddl-auto)가 뜨기 전에 실행되므로(AutoBidSettingSchemaMigrationIT와
    // 동일한 근거), 여기서 raw JDBC로 "이미 shipping_fee 컬럼이 남아있는 products 테이블"을
    // 최소 컬럼(PK만)으로 미리 만들어두면, 이후 Hibernate가 나머지 컬럼을 추가하는 ddl-auto:update가
    // 그 컬럼을 지우거나 실패하지 않고 그대로 두는지를 있는 그대로 관찰할 수 있다.
    @BeforeAll
    static void seedLegacyShippingFeeColumn() throws Exception {
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE products (
                        id BIGINT NOT NULL AUTO_INCREMENT,
                        shipping_fee INT NULL,
                        PRIMARY KEY (id)
                    )
                    """);
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private ProductAnalysisSessionRepository sessionRepository;

    @Autowired
    private ProductPhotoRepository photoRepository;

    @Autowired
    private PhotoUsageCommitter photoUsageCommitter;

    // #127: CreateProductRequest.analysisId가 필수가 됐고, 세션은 한 번 등록에 쓰이면
    // (confirmRegistration()) 재사용할 수 없다 - requestEntity()를 호출할 때마다(테스트마다)
    // 그 userId 소유의 새 세션을 만든다.
    // #141: 등록 이미지는 세션 이미지로만 정하므로, 기존 multipart(URL 기반) 분석처럼 서버가
    // 업로드한 원본 URL을 세션에 기록해 둔 세션을 만든다(ProductAnalyzeService.commitImagesAndEnqueue의
    // markImageUploaded). 요청 imageUrls는 기존 클라이언트처럼 계속 보내지만 쓰이지 않는다.
    private static final List<String> MULTIPART_SESSION_IMAGES = List.of(
            "https://bucket.s3.ap-northeast-2.amazonaws.com/u1.jpg",
            "https://bucket.s3.ap-northeast-2.amazonaws.com/u2.jpg",
            "https://bucket.s3.ap-northeast-2.amazonaws.com/u3.jpg"
    );

    private Long multipartSession(Long userId) {
        ProductAnalysisSession session = ProductAnalysisSession.create(userId);
        session.markImageUploaded(MULTIPART_SESSION_IMAGES);
        return sessionRepository.save(session).getId();
    }

    private HttpEntity<Map<String, Object>> requestEntity(Long userId, OffsetDateTime startAt, OffsetDateTime endAt) {
        Long analysisId = multipartSession(userId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", String.valueOf(userId));
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("analysisId", analysisId);
        body.put("imageUrls", List.of("https://example.com/a.jpg", "https://example.com/b.jpg", "https://example.com/c.jpg"));
        body.put("brand", "Nike");
        body.put("modelName", "Dunk Low Atomicity Marker");
        body.put("color", "Panda");
        body.put("size", 270);
        body.put("conditionGrade", "B");
        body.put("componentStatus", "PARTIAL");
        body.put("recommendedPrice", 300000);
        body.put("baseMarketPrice", 350000);
        body.put("priceRange", "285,000원 ~ 315,000원");
        body.put("sellingPrice", 290000);
        body.put("reason", "사유");
        body.put("sellerDescription", "설명");
        body.put("auctionStartPrice", 10000);
        body.put("auctionStartAt", startAt.toString());
        body.put("auctionEndAt", endAt.toString());
        return new HttpEntity<>(body, headers);
    }

    @Test
    void 경매_진행시간이_1시간_미만이면_이미_저장된_상품도_롤백된다() {
        User seller = userRepository.save(User.register(
                "seller-" + System.identityHashCode(new Object()) + "@vintic.local", "seller", null
        ));
        long productCountBefore = productRepository.count();
        long auctionCountBefore = auctionRepository.count();

        OffsetDateTime startAt = OffsetDateTime.now().plusHours(1);
        OffsetDateTime endAt = startAt.plusMinutes(30); // 30분 < 1시간, 실패 유도

        ResponseEntity<ApiResponse<ProductResponse>> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                requestEntity(seller.getId(), startAt, endAt),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error().code()).isEqualTo(40006);

        // Product insert가 먼저 실행되고 그 이후(경매 시간 검증)에 실패했음에도, 같은 트랜잭션
        // 경계 안이라 Product도 함께 롤백돼 DB에 남지 않아야 한다.
        assertThat(productRepository.count()).isEqualTo(productCountBefore);
        assertThat(auctionRepository.count()).isEqualTo(auctionCountBefore);
        assertThat(productRepository.findAllByOrderByCreatedAtDesc().stream()
                .noneMatch(p -> "Dunk Low Atomicity Marker".equals(p.getModel()))).isTrue();
    }

    @Test
    void 정상_요청이면_상품과_경매가_함께_저장된다() {
        User seller = userRepository.save(User.register(
                "seller-" + System.identityHashCode(new Object()) + "@vintic.local", "seller", null
        ));

        OffsetDateTime startAt = OffsetDateTime.now().plusHours(2);
        OffsetDateTime endAt = startAt.plusHours(2);

        ResponseEntity<ApiResponse<ProductResponse>> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                requestEntity(seller.getId(), startAt, endAt),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ProductResponse body = response.getBody().data();
        assertThat(body.auctionStatus()).isEqualTo("SCHEDULED");
        assertThat(body.auctionId()).isNotNull();
        assertThat(body.bidIncrement()).isEqualTo(5000L);
    }

    // ===== #141 사진 ID 경로의 상품 이미지 출처 =====

    // ProductAnalyzeService.submitForAnalysisByPhotoIds()가 큐 적재 전까지 하는 일 - 사진 사용 처리
    // (소유권·미사용·필수 slot 확인) 후 세션에 그 사진들의 originalUrl을 기록 - 을 그대로 재현한다.
    // Redis 큐 적재·Vision은 이 테스트의 관심사가 아니다.
    private Long sessionWithClaimedPhotos(Long userId, List<String> originalUrls) {
        ProductAnalysisSession session = sessionRepository.save(ProductAnalysisSession.create(userId));
        List<PhotoSlot> slots = List.of(PhotoSlot.SIDE, PhotoSlot.FRONT, PhotoSlot.OUTSOLE);
        List<Long> photoIds = new java.util.ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            photoIds.add(photoRepository.save(ProductPhoto.create(
                    userId, slots.get(i), originalUrls.get(i), originalUrls.get(i), PhotoMatchStatus.MATCH, "side"
            )).getId());
        }
        List<String> claimed = photoUsageCommitter.claimPhotos(photoIds, userId, session.getId()).stream()
                .sorted(java.util.Comparator.comparing(ProductPhoto::getSlot))
                .map(ProductPhoto::getOriginalUrl)
                .toList();
        session.markImageUploaded(claimed);
        return sessionRepository.save(session).getId();
    }

    private List<String> storedImageUrls(Long productId) throws Exception {
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT image_url FROM product_image_urls WHERE product_id = " + productId)) {
            List<String> urls = new java.util.ArrayList<>();
            while (resultSet.next()) {
                urls.add(resultSet.getString("image_url"));
            }
            return urls;
        }
    }

    private HttpEntity<Map<String, Object>> photoIdRequest(Long userId, Long analysisId, List<String> imageUrls) {
        HttpEntity<Map<String, Object>> base = requestEntity(userId, OffsetDateTime.now().plusHours(2), OffsetDateTime.now().plusHours(4));
        Map<String, Object> body = new java.util.HashMap<>(base.getBody());
        body.put("analysisId", analysisId);
        if (imageUrls == null) {
            body.remove("imageUrls");
        } else {
            body.put("imageUrls", imageUrls);
        }
        return new HttpEntity<>(body, base.getHeaders());
    }

    @Test
    void 사진_ID_방식은_imageUrls_없이_등록되고_세션에_연결된_사진_원본이_저장된다() throws Exception {
        User seller = userRepository.save(User.register(
                "seller-" + System.identityHashCode(new Object()) + "@vintic.local", "seller", null
        ));
        List<String> originals = List.of(
                "https://photos.example.com/side.jpg", "https://photos.example.com/front.jpg", "https://photos.example.com/outsole.jpg"
        );
        Long analysisId = sessionWithClaimedPhotos(seller.getId(), originals);

        ResponseEntity<ApiResponse<ProductResponse>> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST, photoIdRequest(seller.getId(), analysisId, null),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(storedImageUrls(response.getBody().data().id())).containsExactlyInAnyOrderElementsOf(originals);
    }

    @Test
    void 사진_ID_방식에서_다른_imageUrls를_보내도_세션에_연결된_사진이_저장된다() throws Exception {
        User seller = userRepository.save(User.register(
                "seller-" + System.identityHashCode(new Object()) + "@vintic.local", "seller", null
        ));
        List<String> originals = List.of(
                "https://photos.example.com/s2.jpg", "https://photos.example.com/f2.jpg", "https://photos.example.com/o2.jpg"
        );
        Long analysisId = sessionWithClaimedPhotos(seller.getId(), originals);

        ResponseEntity<ApiResponse<ProductResponse>> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                photoIdRequest(seller.getId(), analysisId, List.of(
                        "https://other.example.com/1.jpg", "https://other.example.com/2.jpg", "https://other.example.com/3.jpg"
                )),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(storedImageUrls(response.getBody().data().id())).containsExactlyInAnyOrderElementsOf(originals);
    }

    @Test
    void 다른_사용자의_분석_세션으로는_등록할_수_없고_아무것도_저장되지_않는다() {
        User owner = userRepository.save(User.register(
                "owner-" + System.identityHashCode(new Object()) + "@vintic.local", "owner", null
        ));
        User other = userRepository.save(User.register(
                "other-" + System.identityHashCode(new Object()) + "@vintic.local", "other", null
        ));
        Long analysisId = sessionWithClaimedPhotos(owner.getId(), List.of(
                "https://photos.example.com/s3.jpg", "https://photos.example.com/f3.jpg", "https://photos.example.com/o3.jpg"
        ));
        long productCountBefore = productRepository.count();

        ResponseEntity<ApiResponse<ProductResponse>> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST, photoIdRequest(other.getId(), analysisId, null),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error().code()).isEqualTo(40408);
        assertThat(productRepository.count()).isEqualTo(productCountBefore);
        assertThat(sessionRepository.findById(analysisId).orElseThrow().getRegisteredAt()).isNull();
    }

    @Test
    void 같은_분석_세션으로_두_번째_등록은_400이고_상품이_추가되지_않는다() {
        User seller = userRepository.save(User.register(
                "seller-" + System.identityHashCode(new Object()) + "@vintic.local", "seller", null
        ));
        Long analysisId = sessionWithClaimedPhotos(seller.getId(), List.of(
                "https://photos.example.com/s4.jpg", "https://photos.example.com/f4.jpg", "https://photos.example.com/o4.jpg"
        ));
        restTemplate.exchange("/api/products", HttpMethod.POST, photoIdRequest(seller.getId(), analysisId, null),
                new ParameterizedTypeReference<ApiResponse<ProductResponse>>() {
                });
        long productCountAfterFirst = productRepository.count();

        ResponseEntity<ApiResponse<ProductResponse>> second = restTemplate.exchange(
                "/api/products", HttpMethod.POST, photoIdRequest(seller.getId(), analysisId, null),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(second.getBody().error().code()).isEqualTo(40003);
        assertThat(productRepository.count()).isEqualTo(productCountAfterFirst);
    }

    @Test
    void 이미지_없는_세션에_imageUrls도_없으면_400_40002이고_세션_확정도_롤백된다() {
        User seller = userRepository.save(User.register(
                "seller-" + System.identityHashCode(new Object()) + "@vintic.local", "seller", null
        ));
        Long analysisId = sessionRepository.save(ProductAnalysisSession.create(seller.getId())).getId();

        ResponseEntity<ApiResponse<ProductResponse>> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST, photoIdRequest(seller.getId(), analysisId, null),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error().code()).isEqualTo(40002);
        assertThat(sessionRepository.findById(analysisId).orElseThrow().getRegisteredAt()).isNull();
    }

    // ===== #141 URL 기반(multipart) 분석과 이미지 없는 세션 =====

    @Test
    void URL_기반_분석_세션은_imageUrls_없이_세션_이미지로_등록되고_상태_설명이_따로_저장된다() throws Exception {
        User seller = userRepository.save(User.register(
                "seller-" + System.identityHashCode(new Object()) + "@vintic.local", "seller", null
        ));
        Long analysisId = multipartSession(seller.getId());
        HttpEntity<Map<String, Object>> base = photoIdRequest(seller.getId(), analysisId, null);
        Map<String, Object> body = new java.util.HashMap<>(base.getBody());
        body.put("sellerDescription", "판매자 설명");
        body.put("conditionDescription", "판매자가 고친 상태 설명");

        ResponseEntity<ApiResponse<ProductResponse>> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST, new HttpEntity<>(body, base.getHeaders()),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long productId = response.getBody().data().id();
        assertThat(storedImageUrls(productId)).containsExactlyInAnyOrderElementsOf(MULTIPART_SESSION_IMAGES);
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT description, condition_description FROM products WHERE id = " + productId)) {
            resultSet.next();
            assertThat(resultSet.getString("description")).isEqualTo("판매자 설명");
            assertThat(resultSet.getString("condition_description")).isEqualTo("판매자가 고친 상태 설명");
        }
    }

    @Test
    void 이미지_없는_세션에_imageUrls를_보내도_400_40002이고_아무것도_저장되지_않는다() throws Exception {
        User seller = userRepository.save(User.register(
                "seller-" + System.identityHashCode(new Object()) + "@vintic.local", "seller", null
        ));
        Long analysisId = sessionRepository.save(ProductAnalysisSession.create(seller.getId())).getId();
        long productCountBefore = productRepository.count();
        long auctionCountBefore = auctionRepository.count();

        ResponseEntity<ApiResponse<ProductResponse>> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                photoIdRequest(seller.getId(), analysisId, List.of(
                        "https://bucket.s3.ap-northeast-2.amazonaws.com/other-user-1.jpg?X-Amz-Signature=a",
                        "https://bucket.s3.ap-northeast-2.amazonaws.com/other-user-2.jpg?X-Amz-Signature=b",
                        "https://bucket.s3.ap-northeast-2.amazonaws.com/other-user-3.jpg?X-Amz-Signature=c"
                )),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error().code()).isEqualTo(40002);
        assertThat(productRepository.count()).isEqualTo(productCountBefore);
        assertThat(auctionRepository.count()).isEqualTo(auctionCountBefore);
        assertThat(sessionRepository.findById(analysisId).orElseThrow().getRegisteredAt()).isNull();
    }

    // 매핑을 지운 뒤에도(Product.java에 shippingFee 필드 없음) 이미 shipping_fee 컬럼이 남아있는
    // products 테이블(@BeforeAll seedLegacyShippingFeeColumn())에 실제로 INSERT가 정상 동작하는지
    // 확인한다 - Hibernate는 매핑되지 않은 컬럼을 INSERT 문에 아예 포함하지 않으므로, nullable한
    // 잔여 컬럼은 값 없이 그대로 NULL로 남고 등록 자체는 막히지 않아야 한다.
    @Test
    void 매핑을_지워도_기존_DB에_남아있는_shipping_fee_컬럼과_무관하게_상품_등록이_정상_동작한다() throws Exception {
        User seller = userRepository.save(User.register(
                "seller-" + System.identityHashCode(new Object()) + "@vintic.local", "seller", null
        ));
        long productCountBefore = productRepository.count();

        OffsetDateTime startAt = OffsetDateTime.now().plusHours(2);
        OffsetDateTime endAt = startAt.plusHours(2);

        ResponseEntity<ApiResponse<ProductResponse>> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                requestEntity(seller.getId(), startAt, endAt),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(productRepository.count()).isEqualTo(productCountBefore + 1);

        // ddl-auto:update가 매핑되지 않은 기존 컬럼을 지우지 않고 그대로 두는지 확인한다 -
        // 이 컬럼이 남아있어야 "매핑만 삭제해도 기존 DB에서 안전하다"는 주장이 실제로 검증된다.
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT COUNT(*) AS cnt
                     FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'products' AND COLUMN_NAME = 'shipping_fee'
                     """)) {
            resultSet.next();
            assertThat(resultSet.getInt("cnt")).isEqualTo(1);
        }
    }
}

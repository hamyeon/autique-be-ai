package com.vintic.backend.concurrency;

import com.vintic.backend.analyze.domain.AnalysisStatus;
import com.vintic.backend.analyze.domain.PhotoMatchStatus;
import com.vintic.backend.analyze.domain.PhotoSlot;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.domain.ProductPhoto;
import com.vintic.backend.analyze.domain.ProductPhotoRepository;
import com.vintic.backend.analyze.dto.AnalyzeAcceptedResponse;
import com.vintic.backend.analyze.service.PhotoCleanupService;
import com.vintic.backend.analyze.service.PhotoUsageCommitter;
import com.vintic.backend.analyze.service.ProductAnalyzeService;
import com.vintic.backend.common.exception.InvalidPhotoSelectionException;
import com.vintic.backend.common.exception.PhotoAlreadyUsedException;
import com.vintic.backend.common.exception.PhotoNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// #135: 사진 선업로드 + photoIds 분석 요청의 동시성 보호를 실제 MySQL(행 잠금)에서 검증한다.
// AnalysisCancellationRedisMySqlIT와 같은 원칙으로 실제 Redis(Testcontainers)도 함께 띄워
// ProductAnalyzeService.submitForAnalysisByPhotoIds()가 실제로 enqueue까지 끝내는지 확인한다 -
// 단, Worker 쪽 소비는 이 테스트의 관심사가 아니므로 analysis.stream.consumer.enabled=false로
// 앱 자신의 백그라운드 소비자는 꺼 둔다(AnalysisCancellationRedisMySqlIT와 동일).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class PhotoUsageConcurrencyMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("LOCAL_REDIS_HOST", redis::getHost);
        registry.add("LOCAL_REDIS_PORT", () -> redis.getMappedPort(6379));
        registry.add("analysis.stream.consumer.enabled", () -> "false");
    }

    @Autowired
    private ProductPhotoRepository photoRepository;

    @Autowired
    private ProductAnalysisSessionRepository sessionRepository;

    @Autowired
    private ProductAnalyzeService productAnalyzeService;

    @Autowired
    private PhotoUsageCommitter photoUsageCommitter;

    @Autowired
    private PhotoCleanupService cleanupService;

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;

    private Long savePhoto(Long ownerId, PhotoSlot slot) {
        ProductPhoto photo = ProductPhoto.create(ownerId, slot,
                "https://bucket/" + slot + "-" + System.nanoTime() + ".jpg",
                "https://bucket/analysis/" + slot + "-" + System.nanoTime() + ".jpg",
                PhotoMatchStatus.MATCH, slot.name());
        return photoRepository.save(photo).getId();
    }

    private List<Long> requiredSlotPhotoIds(Long ownerId) {
        return List.of(savePhoto(ownerId, PhotoSlot.SIDE), savePhoto(ownerId, PhotoSlot.FRONT),
                savePhoto(ownerId, PhotoSlot.OUTSOLE));
    }

    @Test
    void photoIds_분석_요청은_실제_QUEUED_커밋과_Redis_발행까지_끝낸다() {
        List<Long> photoIds = requiredSlotPhotoIds(USER_ID);

        AnalyzeAcceptedResponse response = productAnalyzeService.submitForAnalysisByPhotoIds(photoIds, USER_ID);

        assertThat(response.status()).isEqualTo("QUEUED");
        ProductAnalysisSession session = sessionRepository.findById(response.analysisId()).orElseThrow();
        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.QUEUED);

        List<ProductPhoto> used = photoRepository.findAllById(photoIds);
        assertThat(used).allMatch(ProductPhoto::isUsed);
        assertThat(used).allMatch(p -> p.getAnalysisId().equals(response.analysisId()));
    }

    // 실제 PESSIMISTIC_WRITE 잠금(findAllByIdInForUpdate)이 같은 사진을 가리키는 두 번째 요청을
    // 막는지 확인한다 - 순차 호출로도 "먼저 커밋된 쪽이 analysisId를 채우고, 나중 요청은 그
    // 커밋된 결과를 본다"는 인과관계는 그대로 검증된다(실제 스레드 경합 없이도 Mockito 단위
    // 테스트와 달리 실제 커밋/조회를 거친다는 점이 다르다).
    @Test
    void 같은_사진으로_두_번_분석을_요청하면_두_번째는_거절되고_첫_분석은_영향받지_않는다() {
        List<Long> photoIds = requiredSlotPhotoIds(USER_ID);

        AnalyzeAcceptedResponse first = productAnalyzeService.submitForAnalysisByPhotoIds(photoIds, USER_ID);

        assertThatThrownBy(() -> productAnalyzeService.submitForAnalysisByPhotoIds(photoIds, USER_ID))
                .isInstanceOf(PhotoAlreadyUsedException.class);

        List<ProductPhoto> photos = photoRepository.findAllById(photoIds);
        assertThat(photos).allMatch(p -> p.getAnalysisId().equals(first.analysisId()));
        ProductAnalysisSession firstSession = sessionRepository.findById(first.analysisId()).orElseThrow();
        assertThat(firstSession.getStatus()).isEqualTo(AnalysisStatus.QUEUED);
    }

    @Test
    void 타인_소유_사진으로_요청하면_PhotoNotFoundException을_던지고_사진은_미사용으로_남는다() {
        List<Long> photoIds = requiredSlotPhotoIds(USER_ID);

        assertThatThrownBy(() -> photoUsageCommitter.claimPhotos(photoIds, OTHER_USER_ID, 999L))
                .isInstanceOf(PhotoNotFoundException.class);

        assertThat(photoRepository.findAllById(photoIds)).noneMatch(ProductPhoto::isUsed);
    }

    @Test
    void 필수_slot이_빠지면_InvalidPhotoSelectionException을_던지고_사진은_부분적으로도_사용처리되지_않는다() {
        // OUTSOLE 없이 SIDE/FRONT만 - REQUIRES_NEW 트랜잭션 하나가 전부 롤백되는지 확인한다
        // (검증 실패 전에 일부만 markUsed()가 저장되는 일이 없어야 한다).
        List<Long> photoIds = List.of(savePhoto(USER_ID, PhotoSlot.SIDE), savePhoto(USER_ID, PhotoSlot.FRONT));

        assertThatThrownBy(() -> photoUsageCommitter.claimPhotos(photoIds, USER_ID, 999L))
                .isInstanceOf(InvalidPhotoSelectionException.class);

        assertThat(photoRepository.findAllById(photoIds)).noneMatch(ProductPhoto::isUsed);
    }

    // #135: 분석에 사용된 사진은 정리 대상에서 빠져야 한다 - claimPhotos()와 PhotoCleanupService가
    // 같은 행 잠금(findByIdForUpdate)을 공유하므로, 먼저 커밋된 "사용 처리"가 그 뒤의 정리
    // 스캔/재확인 양쪽 모두에서 보인다. S3 삭제까지는 실행하지 않는다(실제 AWS 호출이 필요한
    // 구간은 PhotoCleanupServiceTest에서 Mock으로 이미 검증했다) - 여기서는 "삭제 대상에서
    // 제외되는지"까지만 실제 MySQL로 확인한다.
    @Test
    void 분석에_사용된_오래된_사진은_정리_스캔_대상에서_제외되고_재확인에서도_삭제되지_않는다() {
        Long photoId = savePhoto(USER_ID, PhotoSlot.OUTSOLE);
        ProductPhoto stale = photoRepository.findById(photoId).orElseThrow();
        org.springframework.test.util.ReflectionTestUtils.setField(stale, "createdAt", LocalDateTime.now().minusHours(25));
        photoRepository.save(stale);

        // 아직 미사용 - 스캔 후보에 들어간다.
        assertThat(cleanupService.findStaleUnusedPhotoIds()).contains(photoId);

        photoUsageCommitter.claimPhotos(List.of(photoId,
                savePhoto(USER_ID, PhotoSlot.SIDE), savePhoto(USER_ID, PhotoSlot.FRONT)), USER_ID, 999L);

        // 사용 처리된 뒤에는 스캔 후보에서 빠지고, 재확인(purgeIfStillUnused)도 false를 반환한다.
        assertThat(cleanupService.findStaleUnusedPhotoIds()).doesNotContain(photoId);
        assertThat(cleanupService.purgeIfStillUnused(photoId)).isFalse();
        assertThat(photoRepository.findById(photoId)).isPresent();
    }
}

package com.vintic.backend.analyze.service;

import com.vintic.backend.analyze.domain.PhotoMatchStatus;
import com.vintic.backend.analyze.domain.PhotoSlot;
import com.vintic.backend.analyze.domain.ProductPhoto;
import com.vintic.backend.analyze.domain.ProductPhotoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhotoCleanupServiceTest {

    @Mock
    private ProductPhotoRepository photoRepository;

    @Mock
    private S3UploaderService s3Service;

    private PhotoCleanupService service;

    private ProductPhoto photoCreatedAt(LocalDateTime createdAt) {
        ProductPhoto photo = ProductPhoto.create(1L, PhotoSlot.SIDE, "https://bucket/a.jpg",
                "https://bucket/analysis/a.jpg", PhotoMatchStatus.MATCH, "SIDE");
        ReflectionTestUtils.setField(photo, "createdAt", createdAt);
        return photo;
    }

    @Test
    void 미사용_사진이_24시간_이상_지나면_S3와_DB에서_모두_삭제한다() {
        service = new PhotoCleanupService(photoRepository, s3Service);
        ProductPhoto stale = photoCreatedAt(LocalDateTime.now().minusHours(25));
        when(photoRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(stale));

        boolean purged = service.purgeIfStillUnused(1L);

        assertThat(purged).isTrue();
        verify(s3Service).deleteObjects(List.of(stale.getOriginalUrl(), stale.getAnalysisUrl()));
        verify(photoRepository).delete(stale);
    }

    @Test
    void 미사용_사진이라도_24시간이_안_지났으면_삭제하지_않는다() {
        service = new PhotoCleanupService(photoRepository, s3Service);
        ProductPhoto fresh = photoCreatedAt(LocalDateTime.now().minusHours(1));
        when(photoRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(fresh));

        boolean purged = service.purgeIfStillUnused(1L);

        assertThat(purged).isFalse();
        verify(s3Service, never()).deleteObjects(any());
        verify(photoRepository, never()).delete(any());
    }

    // #135: findStaleUnusedPhotoIds()의 스캔과 이 호출 사이에 PhotoUsageCommitter.claimPhotos()가
    // 먼저 사용 처리를 끝냈다면(같은 행 잠금을 먼저 가져감) analysisId가 채워져 있으므로 건너뛴다 -
    // 분석에 쓰이는 사진을 지우면 안 된다는 요구사항의 핵심 재확인 지점.
    @Test
    void 그_사이_분석에_사용된_사진은_삭제하지_않는다() {
        service = new PhotoCleanupService(photoRepository, s3Service);
        ProductPhoto staleButUsed = photoCreatedAt(LocalDateTime.now().minusHours(25));
        staleButUsed.markUsed(999L);
        when(photoRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(staleButUsed));

        boolean purged = service.purgeIfStillUnused(1L);

        assertThat(purged).isFalse();
        verify(s3Service, never()).deleteObjects(any());
        verify(photoRepository, never()).delete(any());
    }

    @Test
    void 이미_삭제된_사진이면_조용히_넘어간다() {
        service = new PhotoCleanupService(photoRepository, s3Service);
        when(photoRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        boolean purged = service.purgeIfStillUnused(1L);

        assertThat(purged).isFalse();
        verify(s3Service, never()).deleteObjects(any());
    }

    @Test
    void S3_삭제가_실패하면_예외가_그대로_전파되어_DB_삭제까지_함께_롤백된다() {
        service = new PhotoCleanupService(photoRepository, s3Service);
        ProductPhoto stale = photoCreatedAt(LocalDateTime.now().minusHours(25));
        when(photoRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(stale));
        org.mockito.Mockito.doThrow(new RuntimeException("S3 삭제 실패"))
                .when(s3Service).deleteObjects(any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.purgeIfStillUnused(1L))
                .isInstanceOf(RuntimeException.class);

        // S3 삭제가 먼저 실패했으므로 DB 삭제는 호출되지 않아야 한다(다음 주기 재시도 대상으로 남음).
        verify(photoRepository, never()).delete(any());
    }
}

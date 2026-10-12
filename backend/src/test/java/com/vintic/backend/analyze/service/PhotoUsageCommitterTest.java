package com.vintic.backend.analyze.service;

import com.vintic.backend.analyze.domain.PhotoMatchStatus;
import com.vintic.backend.analyze.domain.PhotoSlot;
import com.vintic.backend.analyze.domain.ProductPhoto;
import com.vintic.backend.analyze.domain.ProductPhotoRepository;
import com.vintic.backend.common.exception.InvalidPhotoSelectionException;
import com.vintic.backend.common.exception.PhotoAlreadyUsedException;
import com.vintic.backend.common.exception.PhotoNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhotoUsageCommitterTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long ANALYSIS_ID = 100L;

    @Mock
    private ProductPhotoRepository photoRepository;

    private PhotoUsageCommitter committer;

    private ProductPhoto photo(Long ownerId, PhotoSlot slot) {
        return ProductPhoto.create(ownerId, slot, "https://bucket/" + slot + ".jpg",
                "https://bucket/analysis/" + slot + ".jpg", PhotoMatchStatus.MATCH, slot.name());
    }

    @Test
    void 필수_slot이_모두_있으면_사용_처리하고_analysisId를_채운다() {
        committer = new PhotoUsageCommitter(photoRepository);
        List<ProductPhoto> photos = List.of(
                photo(USER_ID, PhotoSlot.SIDE), photo(USER_ID, PhotoSlot.FRONT), photo(USER_ID, PhotoSlot.OUTSOLE));
        when(photoRepository.findAllByIdInForUpdate(List.of(1L, 2L, 3L))).thenReturn(photos);
        when(photoRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ProductPhoto> result = committer.claimPhotos(List.of(1L, 2L, 3L), USER_ID, ANALYSIS_ID);

        assertThat(result).allMatch(ProductPhoto::isUsed);
        assertThat(result).allMatch(p -> p.getAnalysisId().equals(ANALYSIS_ID));
    }

    @Test
    void 존재하지_않는_photoId가_있으면_PhotoNotFoundException을_던진다() {
        committer = new PhotoUsageCommitter(photoRepository);
        when(photoRepository.findAllByIdInForUpdate(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(photo(USER_ID, PhotoSlot.SIDE), photo(USER_ID, PhotoSlot.FRONT))); // 3L 누락

        assertThatThrownBy(() -> committer.claimPhotos(List.of(1L, 2L, 3L), USER_ID, ANALYSIS_ID))
                .isInstanceOf(PhotoNotFoundException.class);
    }

    @Test
    void 타인_소유_사진이_있으면_존재하지_않는_것과_동일하게_PhotoNotFoundException을_던진다() {
        committer = new PhotoUsageCommitter(photoRepository);
        when(photoRepository.findAllByIdInForUpdate(List.of(1L, 2L)))
                .thenReturn(List.of(photo(USER_ID, PhotoSlot.SIDE), photo(OTHER_USER_ID, PhotoSlot.FRONT)));

        assertThatThrownBy(() -> committer.claimPhotos(List.of(1L, 2L), USER_ID, ANALYSIS_ID))
                .isInstanceOf(PhotoNotFoundException.class);
    }

    @Test
    void 이미_사용된_사진이_있으면_PhotoAlreadyUsedException을_던진다() {
        committer = new PhotoUsageCommitter(photoRepository);
        ProductPhoto used = photo(USER_ID, PhotoSlot.SIDE);
        used.markUsed(999L);
        when(photoRepository.findAllByIdInForUpdate(List.of(1L, 2L)))
                .thenReturn(List.of(used, photo(USER_ID, PhotoSlot.FRONT)));

        assertThatThrownBy(() -> committer.claimPhotos(List.of(1L, 2L), USER_ID, ANALYSIS_ID))
                .isInstanceOf(PhotoAlreadyUsedException.class);
    }

    @Test
    void 같은_slot이_중복되면_InvalidPhotoSelectionException을_던진다() {
        committer = new PhotoUsageCommitter(photoRepository);
        when(photoRepository.findAllByIdInForUpdate(List.of(1L, 2L)))
                .thenReturn(List.of(photo(USER_ID, PhotoSlot.SIDE), photo(USER_ID, PhotoSlot.SIDE)));

        assertThatThrownBy(() -> committer.claimPhotos(List.of(1L, 2L), USER_ID, ANALYSIS_ID))
                .isInstanceOf(InvalidPhotoSelectionException.class);
    }

    @Test
    void 필수_slot이_누락되면_InvalidPhotoSelectionException을_던진다() {
        committer = new PhotoUsageCommitter(photoRepository);
        // OUTSOLE 누락
        when(photoRepository.findAllByIdInForUpdate(List.of(1L, 2L)))
                .thenReturn(List.of(photo(USER_ID, PhotoSlot.SIDE), photo(USER_ID, PhotoSlot.FRONT)));

        assertThatThrownBy(() -> committer.claimPhotos(List.of(1L, 2L), USER_ID, ANALYSIS_ID))
                .isInstanceOf(InvalidPhotoSelectionException.class);
    }

    @Test
    void DEFECT_사진을_선택적으로_포함해도_정상_처리된다() {
        committer = new PhotoUsageCommitter(photoRepository);
        List<ProductPhoto> photos = List.of(
                photo(USER_ID, PhotoSlot.SIDE), photo(USER_ID, PhotoSlot.FRONT),
                photo(USER_ID, PhotoSlot.OUTSOLE), photo(USER_ID, PhotoSlot.DEFECT));
        when(photoRepository.findAllByIdInForUpdate(List.of(1L, 2L, 3L, 4L))).thenReturn(photos);
        when(photoRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ProductPhoto> result = committer.claimPhotos(List.of(1L, 2L, 3L, 4L), USER_ID, ANALYSIS_ID);

        assertThat(result).hasSize(4);
    }
}

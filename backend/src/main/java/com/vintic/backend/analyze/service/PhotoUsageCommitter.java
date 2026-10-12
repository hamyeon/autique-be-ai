package com.vintic.backend.analyze.service;

import com.vintic.backend.analyze.domain.PhotoSlot;
import com.vintic.backend.analyze.domain.ProductPhoto;
import com.vintic.backend.analyze.domain.ProductPhotoRepository;
import com.vintic.backend.common.exception.InvalidPhotoSelectionException;
import com.vintic.backend.common.exception.PhotoAlreadyUsedException;
import com.vintic.backend.common.exception.PhotoNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

// #135: photoIds 분석 요청이 기존에 올려둔 사진을 "사용 처리"하는 지점. AnalysisQueueCommitter와
// 똑같은 원칙(#130)을 따른다 - 별도 빈으로 분리해야 @Transactional 프록시가 실제로 걸리고
// (self-invocation 함정), REQUIRES_NEW로 분리해 두면 호출부(ProductAnalyzeService)가 나중에
// @Transactional을 갖게 되어도 이 커밋이 그 트랜잭션에 묶이지 않는다.
//
// findAllByIdInForUpdate(PESSIMISTIC_WRITE)로 요청에 포함된 사진 행을 모두 잠근 채로 소유권·
// 중복 slot·필수 slot·미사용 여부를 확인하고 analysisId를 채운다 - PhotoCleanupService가 삭제
// 직전 쓰는 findByIdForUpdate와 같은 잠금을 공유하므로, 사용 처리와 정리 중 먼저 커밋되는 쪽이
// 이긴다(둘 다 "짧게 잠그고 외부 I/O 없이 끝낸다"는 원칙을 지킨다).
@Service
@RequiredArgsConstructor
public class PhotoUsageCommitter {

    private static final Set<PhotoSlot> REQUIRED_SLOTS = Set.of(PhotoSlot.SIDE, PhotoSlot.FRONT, PhotoSlot.OUTSOLE);

    private final ProductPhotoRepository photoRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ProductPhoto> claimPhotos(List<Long> photoIds, Long userId, Long analysisId) {
        List<ProductPhoto> photos = photoRepository.findAllByIdInForUpdate(photoIds);
        if (photos.size() != photoIds.size()) {
            throw new PhotoNotFoundException("존재하지 않는 사진이 포함되어 있습니다.");
        }

        Set<PhotoSlot> slots = EnumSet.noneOf(PhotoSlot.class);
        for (ProductPhoto photo : photos) {
            // 타인 소유 사진의 존재 여부가 드러나지 않도록 "없음"과 같은 예외로 응답한다(404).
            if (!photo.isOwnedBy(userId)) {
                throw new PhotoNotFoundException("존재하지 않는 사진이 포함되어 있습니다.");
            }
            if (photo.isUsed()) {
                throw new PhotoAlreadyUsedException("이미 다른 분석에 사용된 사진이 포함되어 있습니다.");
            }
            if (!slots.add(photo.getSlot())) {
                throw new InvalidPhotoSelectionException("같은 칸(slot)의 사진이 중복으로 포함되어 있습니다.");
            }
        }
        if (!slots.containsAll(REQUIRED_SLOTS)) {
            throw new InvalidPhotoSelectionException("측면(SIDE)/앞면(FRONT)/밑창(OUTSOLE) 사진이 모두 필요합니다.");
        }

        for (ProductPhoto photo : photos) {
            photo.markUsed(analysisId);
        }
        return photoRepository.saveAll(photos);
    }
}

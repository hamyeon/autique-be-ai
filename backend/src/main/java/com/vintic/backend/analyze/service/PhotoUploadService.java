package com.vintic.backend.analyze.service;

import com.vintic.backend.ai.vision.view.PhotoView;
import com.vintic.backend.ai.vision.view.PhotoViewCheck;
import com.vintic.backend.ai.vision.view.PhotoViewClassifier;
import com.vintic.backend.analyze.domain.PhotoMatchStatus;
import com.vintic.backend.analyze.domain.PhotoSlot;
import com.vintic.backend.analyze.domain.ProductPhoto;
import com.vintic.backend.analyze.domain.ProductPhotoRepository;
import com.vintic.backend.analyze.dto.PhotoUploadResponse;
import com.vintic.backend.common.exception.InvalidImageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

// #135: 사진 한 장을 칸(slot)에 넣는 즉시 S3에 올리고 촬영 면을 판정한다("사진 선업로드").
// S3 업로드는 기존 S3UploaderService.uploadImage()를 그대로 쓰고, 판정도 기존
// PhotoViewClassifier/PhotoViewCheck를 그대로 호출한다 - 이 서비스가 하는 새 일은 둘을 엮고
// DEFECT 칸을 건너뛰고 판정 실패를 UNKNOWN으로 접는 것뿐이다.
@Service
@RequiredArgsConstructor
@Slf4j
public class PhotoUploadService {

    private final S3UploaderService s3Service;
    private final PhotoViewClassifier classifier;
    private final ProductPhotoRepository photoRepository;

    public PhotoUploadResponse upload(MultipartFile image, PhotoSlot slot, Long userId) {
        if (image == null || image.isEmpty()) {
            throw new InvalidImageException("이미지 파일이 존재하지 않습니다.");
        }

        // S3 업로드와 DB 저장은 그대로 전파한다 - AI 판정 실패만 UNKNOWN으로 접는다(요구사항).
        S3UploaderService.UploadedImage uploaded = s3Service.uploadImage(image);

        PhotoMatchStatus matchStatus = null;
        PhotoView detectedView = null;
        String message = null;

        if (slot.isJudged()) {
            PhotoView expected = slot.toExpectedView();
            try {
                PhotoViewClassifier.Result result = classifier.classify(image.getBytes());
                detectedView = result.view();
                boolean matched = PhotoViewCheck.matches(expected, detectedView);
                matchStatus = matched ? PhotoMatchStatus.MATCH : PhotoMatchStatus.MISMATCH;
                message = matched ? null : guidanceMessage(slot, detectedView);
            } catch (RuntimeException | IOException e) {
                // 판정 호출 구간(AI API 오류, 타임아웃, 응답 파싱 실패, 이미지 재읽기 실패 등)만
                // UNKNOWN으로 접는다 - 업로드 결과는 그대로 유지한다. 실제 오류는 PhotoViewClassifier
                // 내부에서 이미 AiCallLogger로 기록되고(관측용), 여기서는 요청 맥락만 추가로 남긴다.
                log.warn("사진 면 판정에 실패해 UNKNOWN으로 처리합니다. slot={}, userId={}", slot, userId, e);
                matchStatus = PhotoMatchStatus.UNKNOWN;
                detectedView = null;
                message = null;
            }
        }

        ProductPhoto photo = ProductPhoto.create(
                userId, slot, uploaded.originalUrl(), uploaded.analysisUrl(),
                matchStatus, detectedView == null ? null : detectedView.name());
        photoRepository.save(photo);

        return new PhotoUploadResponse(
                photo.getId(),
                slot.name(),
                matchStatus == null ? null : matchStatus.name(),
                detectedView == null ? null : detectedView.name(),
                message
        );
    }

    // 안내 문구는 PhotoViewCheck가 이미 가려낸 "불일치"에만 붙는다 - 판정 기준(어떤 view가
    // 어떤 칸을 통과하는지)은 중복 구현하지 않고 PhotoViewCheck.matches() 결과만 본다.
    private String guidanceMessage(PhotoSlot slot, PhotoView detectedView) {
        if (detectedView == PhotoView.OTHER) {
            return "신발이 잘 보이지 않아요. 신발 전체가 나오게 찍어주세요.";
        }
        if (slot == PhotoSlot.OUTSOLE) {
            return "밑창이 보이게 신발을 뒤집어 찍어주세요.";
        }
        // slot이 SIDE/FRONT인데 PhotoViewCheck가 불일치로 판정했다면(OTHER는 위에서 처리했으므로)
        // 남는 경우는 OUTSOLE 사진을 올린 것뿐이다.
        return "밑창 사진이에요. 신발 윗부분이 보이게 찍어주세요.";
    }
}

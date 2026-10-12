package com.vintic.backend.analyze.service;

import com.vintic.backend.ai.vision.view.PhotoView;
import com.vintic.backend.ai.vision.view.PhotoViewClassifier;
import com.vintic.backend.analyze.domain.PhotoMatchStatus;
import com.vintic.backend.analyze.domain.PhotoSlot;
import com.vintic.backend.analyze.domain.ProductPhoto;
import com.vintic.backend.analyze.domain.ProductPhotoRepository;
import com.vintic.backend.analyze.dto.PhotoUploadResponse;
import com.vintic.backend.common.exception.AiApiException;
import com.vintic.backend.common.exception.InvalidImageException;
import com.vintic.backend.common.exception.S3UploadException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhotoUploadServiceTest {

    private static final Long USER_ID = 42L;

    @Mock
    private S3UploaderService s3Service;

    @Mock
    private PhotoViewClassifier classifier;

    @Mock
    private ProductPhotoRepository photoRepository;

    private PhotoUploadService service;

    private MultipartFile image() {
        return new MockMultipartFile("image", "shoe.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    private void stubUpload() {
        when(s3Service.uploadImage(any())).thenReturn(
                new S3UploaderService.UploadedImage("https://bucket/shoe.jpg", "https://bucket/analysis/shoe.jpg"));
    }

    private void newServiceInstance() {
        service = new PhotoUploadService(s3Service, classifier, photoRepository);
    }

    @Test
    void OUTSOLE_칸에_밑창_사진이면_MATCH와_photoId를_반환한다() {
        newServiceInstance();
        stubUpload();
        when(classifier.classify(any())).thenReturn(new PhotoViewClassifier.Result(PhotoView.OUTSOLE, "outsole", 1200));
        when(photoRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PhotoUploadResponse response = service.upload(image(), PhotoSlot.OUTSOLE, USER_ID);

        assertThat(response.check()).isEqualTo("MATCH");
        assertThat(response.detectedView()).isEqualTo("OUTSOLE");
        assertThat(response.message()).isNull();
        assertThat(response.slot()).isEqualTo("OUTSOLE");

        ArgumentCaptor<ProductPhoto> captor = ArgumentCaptor.forClass(ProductPhoto.class);
        verify(photoRepository).save(captor.capture());
        assertThat(captor.getValue().getOwnerId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getMatchStatus()).isEqualTo(PhotoMatchStatus.MATCH);
    }

    @Test
    void OUTSOLE_칸에_옆면_사진이면_MISMATCH와_뒤집어_찍으라는_안내문구를_반환한다() {
        newServiceInstance();
        stubUpload();
        when(classifier.classify(any())).thenReturn(new PhotoViewClassifier.Result(PhotoView.SIDE, "side", 1200));
        when(photoRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PhotoUploadResponse response = service.upload(image(), PhotoSlot.OUTSOLE, USER_ID);

        assertThat(response.check()).isEqualTo("MISMATCH");
        assertThat(response.message()).isEqualTo("밑창이 보이게 신발을 뒤집어 찍어주세요.");
    }

    @Test
    void SIDE_칸에_밑창_사진이면_MISMATCH와_윗부분을_찍으라는_안내문구를_반환한다() {
        newServiceInstance();
        stubUpload();
        when(classifier.classify(any())).thenReturn(new PhotoViewClassifier.Result(PhotoView.OUTSOLE, "outsole", 1200));
        when(photoRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PhotoUploadResponse response = service.upload(image(), PhotoSlot.SIDE, USER_ID);

        assertThat(response.check()).isEqualTo("MISMATCH");
        assertThat(response.message()).isEqualTo("밑창 사진이에요. 신발 윗부분이 보이게 찍어주세요.");
    }

    @Test
    void 신발이_잘_안_보이면_OTHER_판정이고_전체가_나오게_찍으라는_안내문구를_반환한다() {
        newServiceInstance();
        stubUpload();
        when(classifier.classify(any())).thenReturn(new PhotoViewClassifier.Result(PhotoView.OTHER, "unclear", 1200));
        when(photoRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PhotoUploadResponse response = service.upload(image(), PhotoSlot.FRONT, USER_ID);

        assertThat(response.check()).isEqualTo("MISMATCH");
        assertThat(response.detectedView()).isEqualTo("OTHER");
        assertThat(response.message()).isEqualTo("신발이 잘 보이지 않아요. 신발 전체가 나오게 찍어주세요.");
    }

    @Test
    void DEFECT_칸은_판정을_생략하고_check와_detectedView가_null이다() {
        newServiceInstance();
        stubUpload();
        when(photoRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PhotoUploadResponse response = service.upload(image(), PhotoSlot.DEFECT, USER_ID);

        assertThat(response.check()).isNull();
        assertThat(response.detectedView()).isNull();
        assertThat(response.message()).isNull();
        verify(classifier, never()).classify(any());
    }

    @Test
    void AI_판정_호출이_실패해도_업로드는_성공하고_UNKNOWN을_반환한다() {
        newServiceInstance();
        stubUpload();
        when(classifier.classify(any())).thenThrow(new AiApiException("Claude 호출 실패"));
        when(photoRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PhotoUploadResponse response = service.upload(image(), PhotoSlot.FRONT, USER_ID);

        assertThat(response.check()).isEqualTo("UNKNOWN");
        assertThat(response.detectedView()).isNull();
        assertThat(response.message()).isNull();
        verify(photoRepository).save(any());
    }

    @Test
    void S3_업로드_실패는_UNKNOWN으로_감싸지_않고_그대로_전파되며_DB_저장도_호출되지_않는다() {
        newServiceInstance();
        when(s3Service.uploadImage(any())).thenThrow(new S3UploadException("S3 이미지 업로드 중 문제가 발생했습니다."));

        assertThatThrownBy(() -> service.upload(image(), PhotoSlot.FRONT, USER_ID))
                .isInstanceOf(S3UploadException.class);

        verify(classifier, never()).classify(any());
        verify(photoRepository, never()).save(any());
    }

    @Test
    void DB_저장_실패는_UNKNOWN으로_감싸지_않고_그대로_전파된다() {
        newServiceInstance();
        stubUpload();
        when(classifier.classify(any())).thenReturn(new PhotoViewClassifier.Result(PhotoView.FRONT, "front", 1200));
        when(photoRepository.save(any())).thenThrow(new RuntimeException("DB 저장 실패"));

        assertThatThrownBy(() -> service.upload(image(), PhotoSlot.FRONT, USER_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB 저장 실패");
    }

    @Test
    void 빈_이미지는_InvalidImageException을_던진다() {
        newServiceInstance();
        MultipartFile empty = new MockMultipartFile("image", "empty.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> service.upload(empty, PhotoSlot.FRONT, USER_ID))
                .isInstanceOf(InvalidImageException.class);

        verify(s3Service, never()).uploadImage(any());
    }
}

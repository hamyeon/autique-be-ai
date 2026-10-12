package com.vintic.backend.common.exception;

// 존재하지 않는 photoId, 또는 타인 소유의 photoId. AnalysisSessionNotFoundException과 같은
// 이유로 둘을 구분하지 않는다 - "그 photoId는 있는데 내 것이 아니다"와 "애초에 없다"를
// 응답에서 구분할 수 없게 해 타인 사진의 존재 여부가 드러나지 않게 한다.
public class PhotoNotFoundException extends RuntimeException {
    public PhotoNotFoundException(String message) {
        super(message);
    }
}

package com.vintic.backend.common.exception;

// 이미 다른 분석(analysisId != null)에 사용된 photoId로 다시 분석을 요청함.
public class PhotoAlreadyUsedException extends RuntimeException {
    public PhotoAlreadyUsedException(String message) {
        super(message);
    }
}

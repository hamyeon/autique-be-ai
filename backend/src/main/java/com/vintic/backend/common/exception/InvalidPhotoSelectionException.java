package com.vintic.backend.common.exception;

// photoIds 요청 자체의 구성이 잘못됨 - 중복 photoId, 중복 slot, 필수 slot(SIDE/FRONT/OUTSOLE) 누락 등.
public class InvalidPhotoSelectionException extends RuntimeException {
    public InvalidPhotoSelectionException(String message) {
        super(message);
    }
}

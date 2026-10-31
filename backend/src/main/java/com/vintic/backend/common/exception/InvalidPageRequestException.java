package com.vintic.backend.common.exception;

// #142: 목록 조회의 page/size/limit 값이 허용 범위를 벗어남. 400 / 40001(요청값 검증 실패)로 응답한다.
public class InvalidPageRequestException extends RuntimeException {
    public InvalidPageRequestException(String message) {
        super(message);
    }
}

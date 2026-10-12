package com.vintic.backend.analyze.dto;

// POST /api/products/photos의 응답. check/detectedView/message는 DEFECT 칸(판정 생략)이거나
// 판정 자체에 실패(UNKNOWN)하면 일부가 null일 수 있다 - MISMATCH/UNKNOWN도 photoId는 그대로
// 반환되며, 이후 photoIds 분석 요청을 막지 않는다.
public record PhotoUploadResponse(
        Long photoId,
        String slot,
        String check,
        String detectedView,
        String message
) {
}

package com.vintic.backend.analyze.dto;

import java.util.List;

// POST /api/products/analyze (JSON)의 요청 본문. 기존 multipart 요청(images)과는 consumes로
// 구분되는 같은 경로의 또 다른 진입점이다.
public record AnalyzeByPhotoIdsRequest(
        List<Long> photoIds
) {
}

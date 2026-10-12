package com.vintic.backend.analyze.domain;

// #135: 사진 선업로드 화면에 보여줄 안내용 판정 결과. 분석 요청을 막지 않는다 - MISMATCH/UNKNOWN도
// photoIds 분석 요청에 그대로 쓸 수 있다(PhotoUsageCommitter 참고).
public enum PhotoMatchStatus {
    MATCH,
    MISMATCH,
    UNKNOWN
}

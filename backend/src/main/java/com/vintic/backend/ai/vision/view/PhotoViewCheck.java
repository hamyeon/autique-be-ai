package com.vintic.backend.ai.vision.view;

import java.util.Set;

// 업로드 칸(기대한 면)과 판정 결과가 맞는지 정한다.
//
// 앞면·측면 칸은 신발 윗부분이 보이면 통과시킨다(side/front/top/back). 실제 매물 사진은 앞면을 정면에서 찍는 경우가
// 드물고 위에서 내려다보거나 3/4 각도로 찍는다(2026-10-12 직접 받은 사진 12장 중 정면 앞면 0장). 분석에 필요한 건
// 정확한 각도가 아니라 "윗부분이 보이는 사진"이다.
// 밑창 칸은 밑창만 통과시킨다. 밑창이 빠지면 상태 판정의 핵심 근거(밑창 마모)가 사라지므로 그쪽만 엄격하게 본다.
public final class PhotoViewCheck {

    private static final Set<PhotoView> UPPER_VIEWS = Set.of(PhotoView.SIDE, PhotoView.FRONT, PhotoView.TOP, PhotoView.BACK);

    private PhotoViewCheck() {
    }

    public static boolean matches(PhotoView expected, PhotoView actual) {
        if (UPPER_VIEWS.contains(expected)) {
            return UPPER_VIEWS.contains(actual);
        }
        return expected == actual;
    }
}

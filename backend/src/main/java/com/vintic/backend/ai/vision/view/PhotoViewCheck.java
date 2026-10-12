package com.vintic.backend.ai.vision.view;

import java.util.Set;

// 업로드 칸(기대한 면)과 판정 결과가 맞는지 정한다.
//
// 밑창 칸만 엄격하게 본다. 측면·앞면은 3/4 각도 사진이 흔해 모델도 사람도 헷갈리므로, 둘이 서로 바뀐 것은
// 통과시킨다 - 분석에 필요한 건 "신발 윗부분이 보이는 사진"이지 정확한 각도가 아니다. 밑창이 빠지면 상태 판정의
// 핵심 근거(밑창 마모)가 사라지므로 그쪽만 확실히 잡는다. 하네스 결과를 보고 측면·앞면도 엄격하게 볼지 정한다.
public final class PhotoViewCheck {

    private static final Set<PhotoView> UPPER_VIEWS = Set.of(PhotoView.SIDE, PhotoView.FRONT);

    private PhotoViewCheck() {
    }

    public static boolean matches(PhotoView expected, PhotoView actual) {
        if (expected == PhotoView.SIDE || expected == PhotoView.FRONT) {
            return UPPER_VIEWS.contains(actual);
        }
        return expected == actual;
    }
}

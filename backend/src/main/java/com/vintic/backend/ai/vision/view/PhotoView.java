package com.vintic.backend.ai.vision.view;

import java.util.Arrays;

// 사진 한 장이 신발의 어느 면을 찍은 것인지. prompts/vision/photo-view-v1.schema.json의 enum과 1:1로 대응한다.
//
// 앱이 받는 칸은 측면·앞면·밑창 셋이지만 판정 값은 더 잘게 둔다. "앞면 칸에 뒤꿈치 사진"처럼 무엇으로 잘못
// 넣었는지 알아야 하네스에서 오판 유형을 보고 안내 문구도 고를 수 있다.
public enum PhotoView {
    SIDE("side"),
    FRONT("front"),
    BACK("back"),
    TOP("top"),
    OUTSOLE("outsole"),
    OTHER("other");

    private final String value;

    PhotoView(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    // 스키마 밖의 값이 오면(Structured Outputs라 보통 없다) OTHER로 둔다 - 판정 실패를 사진 문제로 몰지 않도록
    // 호출부는 OTHER를 "확인 못 함"으로 다룬다.
    public static PhotoView from(String value) {
        return Arrays.stream(values())
                .filter(view -> view.value.equalsIgnoreCase(value))
                .findFirst()
                .orElse(OTHER);
    }
}

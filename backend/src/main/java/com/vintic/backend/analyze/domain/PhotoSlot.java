package com.vintic.backend.analyze.domain;

import com.vintic.backend.ai.vision.view.PhotoView;

// #135: 사진 선업로드 화면의 칸. PhotoView(AI가 실제로 판정하는 값)와는 별개 개념이다 - DEFECT
// 칸은 판정 생략 대상이라 PhotoView에 대응값이 없고, PhotoView.OTHER/TOP/BACK처럼 칸에는 없지만
// 판정 결과로만 나오는 값도 있다. toExpectedView()가 이 둘을 잇는다.
public enum PhotoSlot {
    SIDE,
    FRONT,
    OUTSOLE,
    DEFECT;

    // DEFECT는 판정 생략 대상이라 기대 PhotoView가 없다 - 호출부(PhotoUploadService)가 먼저
    // isJudged()로 걸러내고 이 메서드를 부른다.
    public PhotoView toExpectedView() {
        return switch (this) {
            case SIDE -> PhotoView.SIDE;
            case FRONT -> PhotoView.FRONT;
            case OUTSOLE -> PhotoView.OUTSOLE;
            case DEFECT -> throw new IllegalStateException("DEFECT 칸은 판정 대상이 아닙니다.");
        };
    }

    public boolean isJudged() {
        return this != DEFECT;
    }
}

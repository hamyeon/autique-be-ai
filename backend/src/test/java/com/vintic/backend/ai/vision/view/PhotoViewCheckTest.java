package com.vintic.backend.ai.vision.view;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoViewCheckTest {

    @Test
    void 밑창_칸은_밑창만_통과한다() {
        assertThat(PhotoViewCheck.matches(PhotoView.OUTSOLE, PhotoView.OUTSOLE)).isTrue();
        assertThat(PhotoViewCheck.matches(PhotoView.OUTSOLE, PhotoView.SIDE)).isFalse();
        assertThat(PhotoViewCheck.matches(PhotoView.OUTSOLE, PhotoView.OTHER)).isFalse();
    }

    @Test
    void 측면과_앞면은_서로_바뀌어도_통과한다() {
        assertThat(PhotoViewCheck.matches(PhotoView.SIDE, PhotoView.FRONT)).isTrue();
        assertThat(PhotoViewCheck.matches(PhotoView.FRONT, PhotoView.SIDE)).isTrue();
    }

    @Test
    void 측면_칸에_밑창이나_다른_사진은_통과하지_않는다() {
        assertThat(PhotoViewCheck.matches(PhotoView.SIDE, PhotoView.OUTSOLE)).isFalse();
        assertThat(PhotoViewCheck.matches(PhotoView.FRONT, PhotoView.OTHER)).isFalse();
        assertThat(PhotoViewCheck.matches(PhotoView.FRONT, PhotoView.TOP)).isFalse();
    }
}

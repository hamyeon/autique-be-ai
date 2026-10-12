package com.vintic.backend.ai.vision.view;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoViewCheckTest {

    @Test
    void 밑창_칸은_밑창만_통과한다() {
        assertThat(PhotoViewCheck.matches(PhotoView.OUTSOLE, PhotoView.OUTSOLE)).isTrue();
        assertThat(PhotoViewCheck.matches(PhotoView.OUTSOLE, PhotoView.SIDE)).isFalse();
        assertThat(PhotoViewCheck.matches(PhotoView.OUTSOLE, PhotoView.TOP)).isFalse();
        assertThat(PhotoViewCheck.matches(PhotoView.OUTSOLE, PhotoView.OTHER)).isFalse();
    }

    @Test
    void 앞면_측면_칸은_신발_윗부분이_보이면_통과한다() {
        assertThat(PhotoViewCheck.matches(PhotoView.SIDE, PhotoView.FRONT)).isTrue();
        assertThat(PhotoViewCheck.matches(PhotoView.FRONT, PhotoView.SIDE)).isTrue();
        assertThat(PhotoViewCheck.matches(PhotoView.FRONT, PhotoView.TOP)).isTrue();
        assertThat(PhotoViewCheck.matches(PhotoView.SIDE, PhotoView.BACK)).isTrue();
    }

    @Test
    void 앞면_측면_칸에_밑창이나_신발이_아닌_사진은_통과하지_않는다() {
        assertThat(PhotoViewCheck.matches(PhotoView.SIDE, PhotoView.OUTSOLE)).isFalse();
        assertThat(PhotoViewCheck.matches(PhotoView.FRONT, PhotoView.OTHER)).isFalse();
    }
}

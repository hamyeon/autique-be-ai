package com.vintic.backend.purchasegoal.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceRatioTest {

    @Test
    void 배수는_소수_첫째_자리까지_쓴다() {
        assertThat(PriceRatio.format(30000, 20000)).isEqualTo("1.5");
        assertThat(PriceRatio.format(40000, 20000)).isEqualTo("2");
        assertThat(PriceRatio.format(10000, 30000)).isEqualTo("0.3");
        assertThat(PriceRatio.format(10000, 0)).isEqualTo("?");
    }

    @Test
    void 시세보다_비싸면_몇_배인지_알린다() {
        assertThat(PriceRatio.engagementNote(30000, 20000, 40000))
                .isEqualTo("다음 입찰가 30,000원은 AI 시세(20,000원)의 1.5배입니다. 최대 40,000원(시세의 2배)까지 자동으로 입찰합니다.");
    }

    @Test
    void 시세보다_싸면_몇_퍼센트_저렴한지_알린다() {
        assertThat(PriceRatio.engagementNote(18000, 20000, 40000))
                .startsWith("다음 입찰가 18,000원은 AI 시세(20,000원)보다 10% 저렴합니다.");
    }

    @Test
    void 상한_배율은_설정값을_따르고_예산을_넘지_않는다() {
        PurchaseGoalCapPolicy policy = new PurchaseGoalCapPolicy(2.0);

        assertThat(policy.cap(100000L, 20000)).isEqualTo(40000L);  // 시세 × 2
        assertThat(policy.cap(30000L, 20000)).isEqualTo(30000L);   // 예산이 더 낮음
        assertThat(new PurchaseGoalCapPolicy(1.0).cap(100000L, 20000)).isEqualTo(20000L);
        assertThatThrownBy(() -> new PurchaseGoalCapPolicy(0)).isInstanceOf(IllegalArgumentException.class);
    }
}

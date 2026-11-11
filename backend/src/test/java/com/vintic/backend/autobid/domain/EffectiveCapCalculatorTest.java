package com.vintic.backend.autobid.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// §0.13 effectiveCap 공식(ProxyPriceEngine과 AutoBidSetting.getEffectiveCap()이 공유하는 유일한
// 계산 지점)을 독립적으로 고정한다. floor 동작 자체를 이 계산식 밖(engine/entity)에서 검증하면
// 매번 currentPrice/bidIncrement 조합을 다시 세팅해야 해서 결합도가 높아진다 - 여기서 순수하게
// 공식만 고정해둔다.
class EffectiveCapCalculatorTest {

    @Test
    void maxAmount가_이미_배수로_정렬돼_있으면_effectiveCap은_maxAmount와_같다() {
        long effectiveCap = EffectiveCapCalculator.calculate(120000L, 105000L, 5000L);

        assertThat(effectiveCap).isEqualTo(120000L);
    }

    @Test
    void maxAmount가_배수가_아니면_도달_가능한_마지막_grid_지점으로_내림한다() {
        // (121000-105000)/5000 = 3.2 -> floor 3 -> 105000 + 15000 = 120000
        long effectiveCap = EffectiveCapCalculator.calculate(121000L, 105000L, 5000L);

        assertThat(effectiveCap).isEqualTo(120000L);
    }

    @Test
    void maxAmount가_currentPrice_bidIncrement_바로_다음_한_단계보다_작으면_currentPrice_그대로다() {
        // (109999-105000)/5000 = 0.9999 -> floor 0 -> 한 단계도 못 오른다
        long effectiveCap = EffectiveCapCalculator.calculate(109999L, 105000L, 5000L);

        assertThat(effectiveCap).isEqualTo(105000L);
    }

    @Test
    void maxAmount가_currentPrice와_같으면_effectiveCap도_currentPrice와_같다() {
        long effectiveCap = EffectiveCapCalculator.calculate(105000L, 105000L, 5000L);

        assertThat(effectiveCap).isEqualTo(105000L);
    }

    @Test
    void effectiveCap은_maxAmount를_절대_초과하지_않는다() {
        long[] maxAmounts = {105001L, 109999L, 121000L, 999999L, 105000L, 200000L};
        for (long maxAmount : maxAmounts) {
            long effectiveCap = EffectiveCapCalculator.calculate(maxAmount, 105000L, 5000L);
            assertThat(effectiveCap).isLessThanOrEqualTo(maxAmount);
        }
    }

    @Test
    void effectiveCap은_currentPrice_bidIncrement_배수_그리드_위에만_존재한다() {
        long[] maxAmounts = {105001L, 109999L, 121000L, 999999L};
        for (long maxAmount : maxAmounts) {
            long effectiveCap = EffectiveCapCalculator.calculate(maxAmount, 105000L, 5000L);
            assertThat((effectiveCap - 105000L) % 5000L).isZero();
        }
    }

    // D1 회귀: maxAmount < currentPrice이고 차이가 bidIncrement 배수가 아니면, 0 쪽 버림('/')은
    // maxAmount보다 높은 지점을 만들어 예외가 났다. 내림(floorDiv)이면 maxAmount 아래 grid 지점이다.
    @Test
    void maxAmount가_currentPrice보다_낮고_차이가_배수가_아니면_maxAmount_아래_grid_지점으로_내림한다() {
        // (132000-135000)/5000 = -0.6 -> floor -1 -> 130000
        assertThat(EffectiveCapCalculator.calculate(132000L, 135000L, 5000L)).isEqualTo(130000L);
        // (132000-140000)/5000 = -1.6 -> floor -2 -> 130000
        assertThat(EffectiveCapCalculator.calculate(132000L, 140000L, 5000L)).isEqualTo(130000L);
    }

    @Test
    void maxAmount가_currentPrice보다_낮고_차이가_배수면_maxAmount_그대로다() {
        assertThat(EffectiveCapCalculator.calculate(130000L, 140000L, 5000L)).isEqualTo(130000L);
    }

    @Test
    void maxAmount가_currentPrice보다_낮아도_maxAmount를_넘지_않고_grid_위에_있다() {
        long[] maxAmounts = {100001L, 104999L, 131999L, 132000L, 134999L};
        for (long maxAmount : maxAmounts) {
            long effectiveCap = EffectiveCapCalculator.calculate(maxAmount, 135000L, 5000L);
            assertThat(effectiveCap).isLessThanOrEqualTo(maxAmount);
            assertThat(effectiveCap).isGreaterThan(maxAmount - 5000L);
            assertThat(Math.floorMod(effectiveCap - 135000L, 5000L)).isZero();
        }
    }

    @Test
    void bidIncrement이_1이어도_정상_계산된다() {
        long effectiveCap = EffectiveCapCalculator.calculate(100005L, 100000L, 1L);

        assertThat(effectiveCap).isEqualTo(100005L);
    }
}

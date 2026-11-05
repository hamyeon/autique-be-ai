package com.vintic.backend.product.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 used_market_prices.csv를 읽어 검증한다. 이 파일은 크롤링 산출물이라
// 재생성 시 값이 바뀔 수 있으므로, 구체적 가격이 아니라 구조적 성질만 확인한다.
class UsedMarketPriceProviderTest {

    private final UsedMarketPriceProvider provider = new UsedMarketPriceProvider();

    @Test
    void 브랜드와_모델이_맞으면_중고_시세를_찾는다() {
        Optional<UsedMarketPriceProvider.UsedMarketPrice> found =
                provider.find("Nike", "Dunk Low");

        assertThat(found).isPresent();
        assertThat(found.get().listingCount()).isGreaterThanOrEqualTo(10);
        assertThat(found.get().medianPrice()).isPositive();
        // 분포가 뒤집혀 있으면 산출이 잘못된 것이다
        assertThat(found.get().q1Price()).isLessThanOrEqualTo(found.get().medianPrice());
        assertThat(found.get().q3Price()).isGreaterThanOrEqualTo(found.get().medianPrice());
    }

    @Test
    void 모델_표기가_흔들려도_찾는다() {
        // Vision이 "Jordan 1 Retro High"처럼 길게 답해도 jordan1 시세에 붙어야 한다
        assertThat(provider.find("Nike", "Jordan 1 Retro High")).isPresent();
        assertThat(provider.find("NIKE", "AIR FORCE 1")).isPresent();
    }

    @Test
    void 브랜드가_다르면_모델이_같아도_매칭하지_않는다() {
        // "574" 같은 숫자 모델이 다른 브랜드 요청에 붙으면 안 된다
        assertThat(provider.find("New Balance", "574")).isPresent();
        assertThat(provider.find("Adidas", "574")).isEmpty();
    }

    @Test
    void 모델_키와_글자가_안_겹치는_표기는_별칭_표로_찾는다() {
        // Vision이 "All Star"라고 답하면 chucktaylor와 글자가 안 겹쳐 포함 검사로는 못 찾는다.
        // 별칭 표로 키를 접어 찾아야 한다. 한글 모델명도 같은 경로로 붙는다.
        assertThat(provider.find("Converse", "All Star")).map(UsedMarketPriceProvider.UsedMarketPrice::modelNorm)
                .contains("chucktaylor");
        assertThat(provider.find("Converse", "올스타 하이")).map(UsedMarketPriceProvider.UsedMarketPrice::modelNorm)
                .contains("chucktaylor");
        assertThat(provider.find("Nike", "덩크 로우")).map(UsedMarketPriceProvider.UsedMarketPrice::modelNorm)
                .contains("dunklow");
    }

    @Test
    void 골든구스_슈퍼스타는_아디다스_슈퍼스타_시세에_붙지_않는다() {
        assertThat(provider.find("Golden Goose", "Superstar")).map(UsedMarketPriceProvider.UsedMarketPrice::modelNorm)
                .contains("goldengoose");
        assertThat(provider.find("Adidas", "Superstar")).map(UsedMarketPriceProvider.UsedMarketPrice::modelNorm)
                .contains("superstar");
    }

    @Test
    void 더_구체적인_모델이_행_순서보다_먼저다() {
        // 포함 검사만 하면 표본 많은 행이 먼저 걸려 "Jordan 11"이 jordan1에 붙었다
        assertThat(provider.find("Nike", "Air Jordan 11 Retro")).map(UsedMarketPriceProvider.UsedMarketPrice::modelNorm)
                .contains("jordan11");
        assertThat(provider.find("Converse", "Chuck Taylor All Star 70")).map(UsedMarketPriceProvider.UsedMarketPrice::modelNorm)
                .contains("chuck70");
        assertThat(provider.find("Nike", "SB Dunk Low Pro")).map(UsedMarketPriceProvider.UsedMarketPrice::modelNorm)
                .contains("dunksb");
        assertThat(provider.find("Nike", "Air Jordan 1 Retro High OG")).map(UsedMarketPriceProvider.UsedMarketPrice::modelNorm)
                .contains("jordan1");
    }

    @Test
    void 브랜드_표기도_표준_표기로_접는다() {
        // Vision이 브랜드를 "Jordan"으로 답해도 나이키 시세에 붙어야 한다
        assertThat(provider.find("Jordan", "Air Jordan 4")).map(UsedMarketPriceProvider.UsedMarketPrice::modelNorm)
                .contains("jordan4");
    }

    @Test
    void 브랜드_전체_시세를_찾는다() {
        var converse = provider.findBrand("Converse");

        assertThat(converse).isPresent();
        assertThat(converse.get().listingCount()).isGreaterThanOrEqualTo(30);
        assertThat(converse.get().q1Price()).isLessThanOrEqualTo(converse.get().medianPrice());
        assertThat(converse.get().q3Price()).isGreaterThanOrEqualTo(converse.get().medianPrice());
        assertThat(provider.findBrand("컨버스")).isPresent();
        assertThat(provider.findBrand("없는브랜드")).isEmpty();
        assertThat(provider.findBrand(null)).isEmpty();
    }

    @Test
    void 별칭_경로에서도_브랜드가_다르면_매칭하지_않는다() {
        // 별칭 표는 "올스타"를 컨버스로 아는데, 요청 브랜드가 나이키면 붙이면 안 된다
        assertThat(provider.find("Nike", "All Star")).isEmpty();
    }

    @Test
    void 별칭으로_찾은_모델도_색상_버킷을_찾는다() {
        // 모델 시세를 별칭으로 찾았으면 색상 버킷도 같은 키로 찾아야 한다
        assertThat(provider.findColor("Converse", "All Star", "Black")).map(UsedMarketPriceProvider.ColorPrice::modelNorm)
                .contains("chucktaylor");
    }

    @Test
    void 시세에_없는_모델이면_empty를_돌려준다() {
        // 호출부는 이 empty를 보고 기존 KREAM/eBay 방식으로 폴백한다
        assertThat(provider.find("Nike", "존재하지 않는 모델")).isEmpty();
    }

    @Test
    void 입력이_비어도_안전하다() {
        assertThat(provider.find(null, "Dunk Low")).isEmpty();
        assertThat(provider.find("Nike", null)).isEmpty();
        assertThat(provider.find("  ", "  ")).isEmpty();
    }

    @Test
    void 색상_표기가_달라도_같은_색상_버킷을_찾는다() {
        // 조던1 grey 버킷(실측 21건)에 "Wolf Gray"도 "회색"도 붙어야 한다 (#93)
        var wolfGray = provider.findColor("Nike", "Jordan 1", "Wolf Gray");
        var korean = provider.findColor("Nike", "Jordan 1", "회색");

        assertThat(wolfGray).isPresent();
        assertThat(korean).isPresent();
        assertThat(wolfGray.get().colorFamily()).isEqualTo("grey");
        assertThat(wolfGray.get().medianPrice()).isEqualTo(korean.get().medianPrice());
        assertThat(wolfGray.get().listingCount()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void 색상_버킷이_없으면_empty로_모델_시세_폴백을_유도한다() {
        // 색상을 판독 못 하거나 표본이 없는 색이면 empty - 호출부는 모델 시세를 쓴다
        assertThat(provider.findColor("Nike", "Jordan 1", "Panda")).isEmpty();
        assertThat(provider.findColor("Nike", "Jordan 1", null)).isEmpty();
        assertThat(provider.findColor("Adidas", "Jordan 1", "grey")).isEmpty();
    }
}

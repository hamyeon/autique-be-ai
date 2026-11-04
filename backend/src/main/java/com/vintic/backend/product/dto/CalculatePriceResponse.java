package com.vintic.backend.product.dto;

import java.util.List;

public record CalculatePriceResponse(
        int recommendedPrice,
        int baseMarketPrice,
        int kreamAveragePrice,
        int ebayAveragePrice,
        int minRecommendedPrice,
        int maxRecommendedPrice,
        String priceRange,
        String reason,
        List<MatchedMarketPrice> kreamMatches,
        List<MatchedMarketPrice> ebayMatches,
        // 한국어 상품명(KoreanProductNameResolver). 요청의 확정 brand/modelName/color를 합친 한 줄이다
        // (예: "나이키 덩크 로우 판다"). 상품 정보 확인 화면이 영문명과 함께 보여준다. 만들지 못했으면 null.
        String nameKo
) {
    // 한국어 상품명 없이 만드는 생성자. 가격 계산 자체(PriceCalculationService)는 이름을 모른다 -
    // 이름은 ProductPricingService가 붙인다.
    public CalculatePriceResponse(int recommendedPrice, int baseMarketPrice, int kreamAveragePrice, int ebayAveragePrice,
                                  int minRecommendedPrice, int maxRecommendedPrice, String priceRange, String reason,
                                  List<MatchedMarketPrice> kreamMatches, List<MatchedMarketPrice> ebayMatches) {
        this(recommendedPrice, baseMarketPrice, kreamAveragePrice, ebayAveragePrice, minRecommendedPrice,
                maxRecommendedPrice, priceRange, reason, kreamMatches, ebayMatches, null);
    }

    public record MatchedMarketPrice(
            String source,
            String brand,
            String modelName,
            String color,
            Integer size,
            String conditionGrade,
            String componentStatus,
            int price,
            String url
    ) {
    }
}
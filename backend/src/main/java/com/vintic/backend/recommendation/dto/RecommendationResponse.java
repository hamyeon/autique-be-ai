package com.vintic.backend.recommendation.dto;

import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.dto.AuctionCardResponse;

import java.util.List;

// 추천 결과.
//
// personalized가 false면 취향 데이터가 부족해 Fallback(마감 임박 + 인기)으로 채운 결과다.
// 프론트가 "회원님을 위한 추천" 대신 "지금 인기 있는 경매" 같은 문구를 쓸 수 있도록
// 이 사실을 숨기지 않고 내려준다.
public record RecommendationResponse(
        boolean personalized,
        String reason,
        List<RecommendedAuction> items
) {

    // #142: 홈 카드에 필요한 필드(name~isLiked)를 뒤에 덧붙였다. 기존 필드는 그대로다.
    // 추천 생성 단계(개인화·Fallback)는 카드 필드를 비워 두고, RecommendationService가 응답 직전에
    // withCard()로 채운다 - Fallback 결과는 모든 사용자가 공유하는 캐시라 isLiked 같은 사용자별 값을
    // 넣으면 안 되기 때문이다.
    public record RecommendedAuction(
            Long auctionId,
            Long productId,
            String brand,
            String model,
            String colorway,
            Integer sizeKr,
            Long currentPrice,
            String endAt,
            // 취향 벡터와의 코사인 유사도. Fallback일 때는 null이다.
            Double similarity,
            String name,
            String thumbnailUrl,
            AuctionStatus status,
            String grade,
            Long startPrice,
            int likeCount,
            boolean isLiked
    ) {

        // 카드 필드가 비어 있는 추천 항목. 개인화·Fallback 생성 단계에서 쓴다.
        public static RecommendedAuction withoutCard(
                Long auctionId, Long productId, String brand, String model, String colorway,
                Integer sizeKr, Long currentPrice, String endAt, Double similarity
        ) {
            return new RecommendedAuction(auctionId, productId, brand, model, colorway, sizeKr, currentPrice,
                    endAt, similarity, null, null, null, null, null, 0, false);
        }

        // 조회 시점의 경매·관심 정보로 카드 필드를 채운다. currentPrice도 카드 값(조회 시점)으로 맞춰
        // 같은 카드 안의 startPrice/currentPrice/status가 서로 다른 시점 값이 되지 않게 한다.
        public RecommendedAuction withCard(AuctionCardResponse card) {
            return new RecommendedAuction(auctionId, productId, brand, model, colorway, sizeKr, card.currentPrice(),
                    endAt, similarity, card.name(), card.thumbnailUrl(), card.status(), card.grade(),
                    card.startPrice(), card.likeCount(), card.isLiked());
        }
    }
}

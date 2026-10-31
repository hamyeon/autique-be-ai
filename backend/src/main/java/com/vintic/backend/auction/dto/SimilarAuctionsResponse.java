package com.vintic.backend.auction.dto;

import com.vintic.backend.auction.domain.AuctionStatus;

import java.util.List;

// FINAL contract §18 shape. #141: 카드에 경매 상태(status)와 상품 등급(grade)을 추가했다 - 이름은
// 경매 상세(AuctionDetailResponse.status / product.grade)와 맞췄고, grade는 Product.conditionGrade를
// 변환 없이 그대로 내보낸다.
public record SimilarAuctionsResponse(
        List<Item> items
) {
    public record Item(
            Long productId,
            Long auctionId,
            String brand,
            String name,
            String thumbnailUrl,
            Long price,
            int likeCount,
            boolean isLiked,
            AuctionStatus status,
            String grade,
            // #141: 기존 price(= currentPrice)는 호환을 위해 그대로 두고 두 값을 이름 그대로 함께 낸다.
            Long startPrice,
            Long currentPrice
    ) {
    }
}

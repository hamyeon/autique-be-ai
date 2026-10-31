package com.vintic.backend.auction.dto;

import com.vintic.backend.auction.domain.AuctionStatus;

// #142: 홈 인기 경매·기본 경매 목록의 카드 한 장. 상품이 아니라 경매 단위로 식별한다(auctionId).
// 필드 이름은 비슷한 상품(SimilarAuctionsResponse.Item)과 경매 상세(AuctionDetailResponse)에 맞췄다.
public record AuctionCardResponse(
        Long auctionId,
        Long productId,
        String brand,
        // ProductDisplayName.name - 경매 상세 product.name과 같은 규칙
        String name,
        // 대표 이미지(상품 첫 이미지)의 24시간 서명 URL. 이미지가 없으면 null
        String thumbnailUrl,
        AuctionStatus status,
        // Product.conditionGrade 그대로(변환 없음)
        String grade,
        Long startPrice,
        // 화면의 "최고가". 자동입찰 상한가가 아니다
        Long currentPrice,
        int likeCount,
        // 비로그인이면 항상 false
        boolean isLiked
) {
}

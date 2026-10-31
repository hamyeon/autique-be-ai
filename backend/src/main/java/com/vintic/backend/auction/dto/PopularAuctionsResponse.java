package com.vintic.backend.auction.dto;

import java.util.List;

// #142: GET /api/auctions/popular 응답. 비슷한 상품(SimilarAuctionsResponse)처럼 items 하나만 둔다.
public record PopularAuctionsResponse(
        List<AuctionCardResponse> items
) {
}

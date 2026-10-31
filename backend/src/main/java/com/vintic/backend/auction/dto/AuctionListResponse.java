package com.vintic.backend.auction.dto;

import java.util.List;

// #142: GET /api/auctions 응답. 페이지 정보는 입찰 내역(BidHistoryResponse)과 같은 형태(page/size/hasNext)다.
// 전체 개수(totalElements)는 내지 않는다 - count 쿼리를 따로 돌리지 않는다.
public record AuctionListResponse(
        List<AuctionCardResponse> items,
        int page,
        int size,
        boolean hasNext
) {
}

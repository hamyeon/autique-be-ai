package com.vintic.backend.purchasegoal.dto;

import com.vintic.backend.auction.domain.AuctionStatus;

// Goal 상세 화면의 참여 이력 한 건. auctionId는 경매 상세로 이동할 ID다.
// status: SCHEDULED(참여 예정 - AutoBid는 등록됐지만 경매 시작 전) | LIVE(진행 중) |
//         WON(낙찰) | LOST(패배 또는 경매 취소).
// #143: auctionStatus(경매의 현재 상태 그대로)를 뒤에 추가했다 - status=LOST가 패배(ENDED)인지
// 경매 취소(CANCELED)인지 이 값으로 구분한다. 기존 status의 의미는 바꾸지 않았다.
public record PurchaseGoalParticipationResponse(
        Long auctionId,
        String status,
        AuctionStatus auctionStatus
) {
}

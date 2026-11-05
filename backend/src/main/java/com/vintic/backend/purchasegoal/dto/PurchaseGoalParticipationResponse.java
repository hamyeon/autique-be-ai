package com.vintic.backend.purchasegoal.dto;

import com.vintic.backend.auction.domain.AuctionStatus;

// Goal 상세 화면의 참여 이력 한 건. auctionId는 경매 상세로 이동할 ID다.
// status: SCHEDULED(참여 예정 - AutoBid는 등록됐지만 경매 시작 전) | LIVE(진행 중) |
//         WON(낙찰) | LOST(패배 또는 경매 취소).
// #143: auctionStatus(경매의 현재 상태 그대로)를 뒤에 추가했다 - status=LOST가 패배(ENDED)인지
// 경매 취소(CANCELED)인지 이 값으로 구분한다. 기존 status의 의미는 바꾸지 않았다.
// estimatedPriceAtEngage/autoBidCap: 이 참여의 AutoBid를 등록할 때 저장한 값 그대로다(조회 시점에 재계산하지
// 않는다). autoBidCap은 AutoBidSetting.maxAmount, 즉 실제로 등록한 자동입찰 상한이다. estimatedPriceAtEngage는
// 그 상한 계산에 쓴 서버 시세이며, 이 필드 도입 전에 참여한 기록은 당시 값을 복원할 수 없어 null이다.
public record PurchaseGoalParticipationResponse(
        Long auctionId,
        String status,
        AuctionStatus auctionStatus,
        Long estimatedPriceAtEngage,
        Long autoBidCap
) {
}

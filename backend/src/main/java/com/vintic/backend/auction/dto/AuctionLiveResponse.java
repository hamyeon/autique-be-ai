package com.vintic.backend.auction.dto;

import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.domain.CannotBidReason;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;

import java.time.OffsetDateTime;

// extensionCount/maxExtensions(FINAL contract §0.13/§9)는 Auction.extensionCount/MAX_EXTENSIONS를
// 그대로 반영한다. 시간 필드(bidRestrictedUntil/endsAt/serverTime)는 공통 시간 정책(TimePolicy,
// Asia/Seoul 고정)에 따라 OffsetDateTime으로 +09:00 절대시각을 낸다 - 서비스가 Clock/TimePolicy로
// 변환해 채운다.
public record AuctionLiveResponse(
        Long auctionId,
        AuctionStatus status,
        Long currentPrice,
        Long minNextBidAmount,
        Long bidIncrement,
        String highestBidderMasked,
        boolean isMine,
        boolean canBid,
        CannotBidReason cannotBidReason,
        OffsetDateTime bidRestrictedUntil,
        OffsetDateTime endsAt,
        OffsetDateTime serverTime,
        int extensionCount,
        int maxExtensions,
        AutoBidSettingStatus myAutoBidStatus,
        Long myCap,
        Long minCapAmount,
        // #141: 조회자 본인이 이 경매에 실제로 남긴 마지막 Bid 금액(수동 입찰이든 자동입찰 엔진이
        // 대신 넣은 입찰이든 Bid 행으로 저장된 것). Bid가 없으면 null - 자동입찰 설정(myCap)만
        // 있고 아직 Bid가 만들어지지 않았으면 null이다. isMine(현재 최고 입찰자 여부)과 별개다.
        Long myLastBidAmount
) {
}

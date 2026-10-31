package com.vintic.backend.auction.dto;

import com.vintic.backend.auction.domain.AuctionResult;
import com.vintic.backend.order.domain.OrderStatus;

import java.time.OffsetDateTime;

// FINAL contract §10 shape. rank/finalPrice/myLastBidAmount/shippingFee/totalAmount/
// paymentDeadline/orderId/backupOfferId는 계약상 optional(X) - result에 따라 null이다.
// backupOfferId는 result==BACKUP_WAITING일 때만 값을 갖는다(AuctionResultQueryService 참고).
// #141: orderStatus는 조회자 본인의 이 경매 주문 상태다(주문이 없으면 null). result와 달리
// PAYMENT_PENDING/PAID를 구분하므로 FE가 결제 버튼과 결제 완료 표시를 고를 수 있다.
public record AuctionResultResponse(
        Long auctionId,
        AuctionResult result,
        Product product,
        Integer rank,
        Long finalPrice,
        Long myLastBidAmount,
        Long shippingFee,
        Long totalAmount,
        OffsetDateTime paymentDeadline,
        OffsetDateTime serverTime,
        Long orderId,
        Long backupOfferId,
        boolean backupEligible,
        OrderStatus orderStatus
) {
    public record Product(
            Long productId,
            String name,
            String subName,
            String imageUrl
    ) {
    }
}

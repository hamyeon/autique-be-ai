package com.vintic.backend.order.dto;

import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.domain.OrderType;

import java.time.OffsetDateTime;

// FINAL contract §12 전체 shape. paidAt은 계약상 optional(X) - 미결제면 null이다.
public record OrderResponse(
        Long orderId,
        Long auctionId,
        OrderStatus status,
        Product product,
        Long purchasePrice,
        Long shippingFee,
        Long totalAmount,
        OffsetDateTime paymentDeadline,
        OffsetDateTime serverTime,
        OffsetDateTime paidAt,
        // #141: WINNER(최초 낙찰) / BACKUP_ACCEPT(차순위 수락). 이 필드 도입 전 주문 중 유형을
        // 식별하지 못한 행은 null이다(신규 주문은 항상 값이 있다).
        OrderType orderType
) {
    public record Product(
            Long productId,
            String name,
            String subName,
            String imageUrl
    ) {
    }
}

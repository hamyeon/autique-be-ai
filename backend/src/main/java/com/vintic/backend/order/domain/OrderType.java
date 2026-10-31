package com.vintic.backend.order.domain;

// #141: 주문이 어떤 흐름으로 만들어졌는지. Order.createForWinner -> WINNER(최초 낙찰),
// Order.createForBackupAccept -> BACKUP_ACCEPT(차순위 제안 수락). 이 값이 생기기 전에 만들어진
// 주문 중 근거로 식별하지 못한 행은 null로 남는다(scripts/migrations/141-order-type-condition-description.sql).
public enum OrderType {
    WINNER,
    BACKUP_ACCEPT
}

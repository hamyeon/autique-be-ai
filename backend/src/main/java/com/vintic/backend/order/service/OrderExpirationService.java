package com.vintic.backend.order.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.backupoffer.service.BackupCandidateSelector;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.notification.domain.NotificationType;
import com.vintic.backend.notification.service.NotificationRecorder;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.penalty.domain.Penalty;
import com.vintic.backend.penalty.domain.PenaltyType;
import com.vintic.backend.penalty.repository.PenaltyRepository;
import com.vintic.backend.penalty.service.BidRestrictionPolicy;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// FINAL contract §12/§0.10, #57-2. lock 순서: orderId -> auctionId 식별용 non-locking 조회 ->
// Auction FOR UPDATE -> Order FOR UPDATE(재조회, authoritative) -> validation -> state
// transition -> penalty(+User FOR UPDATE) -> 다음 BackupOffer 생성 -> commit. Auction을 먼저
// 잠그는 원칙은 AuctionForfeitService/BackupOfferCommandService와 동일하다 - forfeit/accept/
// decline이 같은 auction에 대해 동시에 들어와도 이 트랜잭션과 항상 직렬화된다. User는 항상
// Order 다음에만 잠근다(Auction -> Order -> User 고정) - 서로 다른 auction의 만료 처리가 같은
// buyer를 동시에 건드려도 각자 자신의 Auction/Order를 먼저 확보해야만 User를 요청하므로 순환
// 대기가 생기지 않는다(UserRepository.findByIdForUpdate 참고).
//
// PAID/CANCELED는 이 서비스에 도달해도 아무 일도 하지 않는다 - expireIfDue()가 락 이후 상태를
// 재확인해 PAYMENT_PENDING이 아니면 조용히 반환한다(스케줄러가 non-locking으로 고른 후보가
// stale할 수 있다는 전제, BackupOfferCommandService의 lockOfferViaAuctionFirst와 동일한 이유).
@Service
public class OrderExpirationService {

    private final AuctionRepository auctionRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final PenaltyRepository penaltyRepository;
    private final BackupOfferRepository backupOfferRepository;
    private final BidRepository bidRepository;
    private final BidRestrictionPolicy bidRestrictionPolicy;
    private final Clock clock;
    private final NotificationRecorder notificationRecorder;

    public OrderExpirationService(
            AuctionRepository auctionRepository,
            OrderRepository orderRepository,
            UserRepository userRepository,
            PenaltyRepository penaltyRepository,
            BackupOfferRepository backupOfferRepository,
            BidRepository bidRepository,
            BidRestrictionPolicy bidRestrictionPolicy,
            Clock clock,
            NotificationRecorder notificationRecorder
    ) {
        this.auctionRepository = auctionRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.penaltyRepository = penaltyRepository;
        this.backupOfferRepository = backupOfferRepository;
        this.bidRepository = bidRepository;
        this.bidRestrictionPolicy = bidRestrictionPolicy;
        this.clock = clock;
        this.notificationRecorder = notificationRecorder;
    }

    @Transactional
    public void expireIfDue(Long orderId) {
        Long auctionId = orderRepository.findAuctionIdById(orderId).orElse(null);
        if (auctionId == null) {
            return;
        }

        Auction auction = auctionRepository.findByIdForUpdate(auctionId).orElse(null);
        if (auction == null) {
            return;
        }

        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (order.getStatus() != OrderStatus.PAYMENT_PENDING || !order.getPaymentDeadline().isBefore(now)) {
            // 스케줄러가 non-locking으로 고른 후보가 이 락 사이에 이미 결제/포기됐거나, 아직
            // 기한 전이다 - 조용히 넘어간다(재실행 시 중복 처리 없음의 핵심 방어선).
            return;
        }

        order.expire();

        User buyer = order.getBuyer();
        // #75: 위 guard를 통과해 실제 PAYMENT_PENDING -> PAYMENT_EXPIRED 전이가 일어난 경우에만
        // 기록한다 - 재실행 시에는 guard가 먼저 걸려(status != PAYMENT_PENDING) 이 지점에 도달하지 않는다.
        notificationRecorder.record(buyer, NotificationType.PAYMENT_EXPIRED, auctionId, order.getId());

        if (!penaltyRepository.existsByAuction_IdAndUser_IdAndType(auctionId, buyer.getId(), PenaltyType.PAYMENT_EXPIRED)) {
            User lockedBuyer = userRepository.findByIdForUpdate(buyer.getId()).orElseThrow();
            penaltyRepository.save(Penalty.paymentExpired(lockedBuyer, auction));
            lockedBuyer.recordPaymentExpiredPenalty(bidRestrictionPolicy.restrictedUntil(now));
        }

        createNextBackupOfferIfCandidateExists(auction, buyer);
    }

    // D2: 만료된 Order의 buyer가 실제 낙찰자든 차순위 수락자든, 순위 숫자 대신 BackupCandidateSelector
    // 의 이력 기반 규칙(실제 낙찰자 제외 + 이미 제안받은 사용자 제외 + 처리된 사람 뒤로만 진행)으로
    // 다음 후보를 고른다. 후보가 소진되면 empty라 체인이 끝난다.
    private void createNextBackupOfferIfCandidateExists(Auction auction, User expiredBuyer) {
        List<Bid> ranked = bidRepository.findLatestBidPerUserOrderedByRank(auction.getId());
        Set<Long> offeredUserIds = new HashSet<>(backupOfferRepository.findCandidateIdsByAuctionId(auction.getId()));
        Long winnerUserId = auction.getCurrentWinner() == null ? null : auction.getCurrentWinner().getId();

        BackupCandidateSelector.next(ranked, winnerUserId, expiredBuyer.getId(), offeredUserIds).ifPresent(candidateBid -> {
            User candidate = candidateBid.getUser();
            // uk_backup_offer_auction_candidate가 최종 방어선이다.
            BackupOffer saved = backupOfferRepository.save(BackupOffer.create(auction, candidate, candidateBid.getAmount()));
            // #75: 신규 BackupOffer가 실제 저장된 경우에만 기록한다(네 번째 BackupOffer 생성 경로 - 위
            // PAYMENT_EXPIRED 기록과는 별개의 이벤트다).
            notificationRecorder.record(candidate, NotificationType.BACKUP_OFFER_CREATED, auction.getId(), saved.getId());
        });
    }
}

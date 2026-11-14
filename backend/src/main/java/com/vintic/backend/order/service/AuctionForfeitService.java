package com.vintic.backend.order.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionResult;
import com.vintic.backend.auction.dto.AuctionForfeitResponse;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.backupoffer.service.BackupCandidateSelector;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.AlreadyPaidException;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.exception.NotAwardeeException;
import com.vintic.backend.common.exception.PaymentExpiredException;
import com.vintic.backend.notification.domain.NotificationType;
import com.vintic.backend.notification.service.NotificationRecorder;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.penalty.domain.Penalty;
import com.vintic.backend.penalty.domain.PenaltyType;
import com.vintic.backend.penalty.repository.PenaltyRepository;
import com.vintic.backend.user.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// FINAL contract §11. lock 순서: Auction FOR UPDATE -> Order FOR UPDATE -> validation/state
// transition -> penalty / BackupOffer -> commit(#56-0 확정, 사용자 지시). 두 락 모두 이
// 메서드의 첫/두 번째 statement라 #46 follow-up이 발견한 "사전 non-locking read가 REPEATABLE
// READ snapshot을 고정시키는" 문제 클래스가 애초에 발생하지 않는다(AuctionSettlementService와
// 동일한 근거).
@Service
public class AuctionForfeitService {

    private final AuctionRepository auctionRepository;
    private final OrderRepository orderRepository;
    private final PenaltyRepository penaltyRepository;
    private final BackupOfferRepository backupOfferRepository;
    private final BidRepository bidRepository;
    private final NotificationRecorder notificationRecorder;

    public AuctionForfeitService(
            AuctionRepository auctionRepository,
            OrderRepository orderRepository,
            PenaltyRepository penaltyRepository,
            BackupOfferRepository backupOfferRepository,
            BidRepository bidRepository,
            NotificationRecorder notificationRecorder
    ) {
        this.auctionRepository = auctionRepository;
        this.orderRepository = orderRepository;
        this.penaltyRepository = penaltyRepository;
        this.backupOfferRepository = backupOfferRepository;
        this.bidRepository = bidRepository;
        this.notificationRecorder = notificationRecorder;
    }

    @Transactional
    public AuctionForfeitResponse forfeit(Long auctionId, Long userId) {
        Auction auction = auctionRepository.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));

        // NOT_AWARDEE(40303): 이 auction에 대해 이 사용자 명의의 Order가 아예 없다 - 낙찰자가
        // 아니었거나(한 번도 이긴 적 없음), 아직 settlement가 실행되지 않은 경우(#56-1의 알려진
        // lifecycle-integration gap) 둘 다 이 코드로 수렴한다. 계약이 이 둘을 구분하지 않는다.
        Order order = orderRepository.findByAuctionIdAndBuyerIdForUpdate(auctionId, userId)
                .orElseThrow(() -> new NotAwardeeException(
                        "낙찰자가 아닙니다. auctionId: " + auctionId + ", userId: " + userId
                ));

        switch (order.getStatus()) {
            case PAID -> throw new AlreadyPaidException(
                    "이미 결제가 완료된 주문입니다. orderId: " + order.getId()
            );
            case PAYMENT_EXPIRED -> throw new PaymentExpiredException(
                    "결제 기한이 만료되었습니다. orderId: " + order.getId()
            );
            case CANCELED -> {
                // 사용자 확정 정책: 이미 forfeit으로 CANCELED된 주문에 대한 재호출은 새 에러를
                // 만들지 않고 state-idempotent 200으로 흡수한다 - 이 프로젝트엔 CANCELED로
                // 이어지는 경로가 forfeit뿐이라(Order.cancel() 호출부가 이 클래스 하나) 여기
                // 도달했다는 것 자체가 "이미 forfeit 처리됨"과 동치다. penalty/BackupOffer를
                // 다시 만들지 않고 그대로 성공 응답만 반환한다.
                return new AuctionForfeitResponse(auctionId, AuctionResult.FORFEITED);
            }
            case PAYMENT_PENDING -> {
                // 아래에서 실제로 처리한다.
            }
        }

        order.cancel();

        User loser = order.getBuyer();
        // uk_penalty_auction_user_type이 최종 방어선이지만, Auction/Order 락이 이미 이 트랜잭션을
        // 직렬화하므로 이 사전 존재 확인은 정상 경로에서 사실상 항상 false다.
        if (!penaltyRepository.existsByAuction_IdAndUser_IdAndType(auctionId, userId, PenaltyType.FORFEITED)) {
            penaltyRepository.save(Penalty.forfeited(loser, auction));
        }

        createBackupOfferIfCandidateExists(auction, loser);

        return new AuctionForfeitResponse(auctionId, AuctionResult.FORFEITED);
    }

    // D2: "포기자 == 순위표 1등"을 가정하지 않는다. 실제 낙찰자(Auction.currentWinner)를 후보 풀에서
    // 빼고, 이미 제안을 받은 사용자를 건너뛴다(BackupCandidateSelector 주석). 포기자가 차순위 수락자인
    // 경우에도 같은 규칙으로 그 사람 다음 후보로 진행한다.
    private void createBackupOfferIfCandidateExists(Auction auction, User forfeitedBuyer) {
        List<Bid> ranked = bidRepository.findLatestBidPerUserOrderedByRank(auction.getId());
        Set<Long> offeredUserIds = new HashSet<>(backupOfferRepository.findCandidateIdsByAuctionId(auction.getId()));
        Long winnerUserId = auction.getCurrentWinner() == null ? null : auction.getCurrentWinner().getId();
        BackupCandidateSelector.next(ranked, winnerUserId, forfeitedBuyer.getId(), offeredUserIds).ifPresent(candidateBid -> {
            User candidate = candidateBid.getUser();
            // uk_backup_offer_auction_candidate가 최종 방어선이다 - offeredUserIds로 이미 걸렀으므로
            // 정상 경로에선 위반되지 않는다.
            BackupOffer saved = backupOfferRepository.save(BackupOffer.create(auction, candidate, candidateBid.getAmount()));
            // #75: 신규 BackupOffer가 실제 저장된 경우에만 기록한다(위 dedup return에는 도달하지 않음).
            notificationRecorder.record(candidate, NotificationType.BACKUP_OFFER_CREATED, auction.getId(), saved.getId());
        });
    }
}

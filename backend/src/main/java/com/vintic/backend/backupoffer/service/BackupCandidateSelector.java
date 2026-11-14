package com.vintic.backend.backupoffer.service;

import com.vintic.backend.bid.domain.Bid;

import java.util.List;
import java.util.Optional;
import java.util.Set;

// #56-0 확정: 차순위 후보는 "실제 낙찰자를 제외한 상위 2명"(기존 표현 rank 2, 3)까지만이다.
// forfeit / decline / BackupOffer 만료 / Order 결제 만료 네 경로가 이 로직을 공유한다.
//
// D2 수정: 이전에는 "최초 낙찰자 == ranked.get(0)"을 가정하고 고정 offset(afterRank + 1)으로
// 다음 후보를 골랐다. 하지만 실제 낙찰자는 Auction.currentWinner(ProxyPriceEngine의 동률 규칙 결과)
// 이고, 순위표(Bid 정렬)와 어긋나면 낙찰자 본인에게 다시 제안이 가 uk_order_auction_buyer 위반(500)이
// 났다. 이제 순위 숫자를 쓰지 않고 다음 세 가지로 후보를 정한다:
//   1) 후보 풀 = ranked에서 실제 낙찰자를 뺀 뒤 앞에서부터 MAX_BACKUP_CANDIDATES명.
//   2) 방금 처리된(포기/거절/만료) 사용자가 풀 안에 있으면 그 사람 "뒤"에서만 찾는다 - 이미 지나간
//      후보에게 되돌아가지 않는다. 낙찰자 본인이 처리된 경우는 풀 처음부터 찾는다.
//   3) 이미 이 경매에서 BackupOffer를 받은 사용자(상태 무관 - WAITING/ACCEPTED/DECLINED/EXPIRED
//      모두 구매 기회를 이미 받은 것)는 건너뛴다.
// ranked는 BidRepository.findLatestBidPerUserOrderedByRank() 결과라 이미 사용자당 1행이다.
public final class BackupCandidateSelector {

    public static final int MAX_BACKUP_CANDIDATES = 2;

    private BackupCandidateSelector() {
    }

    // winnerUserId: Auction.currentWinner의 id(settlement가 Order를 만든 바로 그 사용자).
    // resolvedUserId: 이번 트랜잭션에서 구매 기회를 잃은 사용자(낙찰자 또는 차순위 후보).
    // offeredUserIds: 이 경매에서 이미 BackupOffer가 생성된 적 있는 candidate id 전체.
    public static Optional<Bid> next(List<Bid> ranked, Long winnerUserId, Long resolvedUserId, Set<Long> offeredUserIds) {
        List<Bid> pool = ranked.stream()
                .filter(bid -> !bid.getUser().getId().equals(winnerUserId))
                .limit(MAX_BACKUP_CANDIDATES)
                .toList();

        int start = 0;
        if (!resolvedUserId.equals(winnerUserId)) {
            int resolvedIndex = indexOf(pool, resolvedUserId);
            if (resolvedIndex < 0) {
                // 처리된 사용자가 후보 풀 밖이다 - 정상 경로에선 도달하지 않는다(BackupOffer는 풀
                // 안의 사용자에게만 생성된다). 어디서부터 이어갈지 알 수 없으므로 새 제안을 만들지 않는다.
                return Optional.empty();
            }
            start = resolvedIndex + 1;
        }

        for (int i = start; i < pool.size(); i++) {
            Bid candidate = pool.get(i);
            if (!offeredUserIds.contains(candidate.getUser().getId())) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static int indexOf(List<Bid> pool, Long userId) {
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i).getUser().getId().equals(userId)) {
                return i;
            }
        }
        return -1;
    }
}

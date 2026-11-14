package com.vintic.backend.backupoffer.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

// D2: 순위 숫자(offset) 대신 "실제 낙찰자 제외 + 이미 제안받은 사용자 제외 + 처리된 사람 뒤로만 진행"
// 규칙을 검증한다. 기존 rank1->rank2 / rank2->rank3 / rank3->없음 / 입찰자 부족 케이스는 같은 의미로
// 유지했고, 낙찰자가 순위표 1등이 아닌 경우를 추가했다.
class BackupCandidateSelectorTest {

    private final AtomicLong nextUserId = new AtomicLong(1);

    private final User seller = User.register("seller@vintic.local", "seller", null);
    private final Product product = new Product(
            seller,
            List.of("https://example.com/a.jpg"),
            "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
            300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
    );
    private final Auction auction = Auction.schedule(
            product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
    );

    private Bid bidFor(String email, long amount) {
        User bidder = User.register(email, email, null);
        ReflectionTestUtils.setField(bidder, "id", nextUserId.getAndIncrement());
        return Bid.place(auction, bidder, amount, BidType.MANUAL);
    }

    private static Long id(Bid bid) {
        return bid.getUser().getId();
    }

    @Test
    void 낙찰자가_포기하면_낙찰자를_제외한_첫_후보다() {
        Bid a = bidFor("a@vintic.local", 30000L);
        Bid b = bidFor("b@vintic.local", 20000L);
        List<Bid> ranked = List.of(a, b);

        Optional<Bid> next = BackupCandidateSelector.next(ranked, id(a), id(a), Set.of());

        assertThat(next).contains(b);
    }

    @Test
    void 첫_후보가_처리되면_두_번째_후보다() {
        Bid a = bidFor("a@vintic.local", 30000L);
        Bid b = bidFor("b@vintic.local", 20000L);
        Bid c = bidFor("c@vintic.local", 10000L);
        List<Bid> ranked = List.of(a, b, c);

        Optional<Bid> next = BackupCandidateSelector.next(ranked, id(a), id(b), Set.of(id(b)));

        assertThat(next).contains(c);
    }

    @Test
    void 두_번째_후보가_처리되면_후보가_없다() {
        Bid a = bidFor("a@vintic.local", 30000L);
        Bid b = bidFor("b@vintic.local", 20000L);
        Bid c = bidFor("c@vintic.local", 10000L);
        Bid d = bidFor("d@vintic.local", 5000L);
        List<Bid> ranked = List.of(a, b, c, d);

        // d는 낙찰자 제외 상위 2명 밖이라 후보가 아니다(기존 rank 4 이하 제외 정책).
        assertThat(BackupCandidateSelector.next(ranked, id(a), id(c), Set.of(id(b), id(c)))).isEmpty();
    }

    @Test
    void 입찰자_자체가_부족하면_후보가_없다() {
        Bid a = bidFor("a@vintic.local", 30000L);

        assertThat(BackupCandidateSelector.next(List.of(a), id(a), id(a), Set.of())).isEmpty();
    }

    @Test
    void 낙찰자가_순위표_1등이_아니어도_낙찰자에게_다시_제안하지_않는다() {
        // 동률에서 순위표는 C가 앞, 실제 낙찰자는 A인 상황.
        Bid c = bidFor("c@vintic.local", 130000L);
        Bid a = bidFor("a@vintic.local", 130000L);
        Bid b = bidFor("b@vintic.local", 120000L);
        List<Bid> ranked = List.of(c, a, b);

        assertThat(BackupCandidateSelector.next(ranked, id(a), id(a), Set.of())).contains(c);
        assertThat(BackupCandidateSelector.next(ranked, id(a), id(c), Set.of(id(c)))).contains(b);
        assertThat(BackupCandidateSelector.next(ranked, id(a), id(b), Set.of(id(c), id(b)))).isEmpty();
    }

    @Test
    void 이미_제안받은_사용자는_건너뛴다() {
        Bid a = bidFor("a@vintic.local", 30000L);
        Bid b = bidFor("b@vintic.local", 20000L);
        Bid c = bidFor("c@vintic.local", 10000L);
        List<Bid> ranked = List.of(a, b, c);

        // 낙찰자 재처리(예: 비정상 이력으로 B가 이미 제안받음)여도 B에게 다시 가지 않는다.
        assertThat(BackupCandidateSelector.next(ranked, id(a), id(a), Set.of(id(b)))).contains(c);
        assertThat(BackupCandidateSelector.next(ranked, id(a), id(a), Set.of(id(b), id(c)))).isEmpty();
    }

    @Test
    void 처리된_사용자보다_앞선_후보에게는_되돌아가지_않는다() {
        Bid a = bidFor("a@vintic.local", 30000L);
        Bid b = bidFor("b@vintic.local", 20000L);
        Bid c = bidFor("c@vintic.local", 10000L);
        List<Bid> ranked = List.of(a, b, c);

        // C만 제안받았던 이력에서 C가 처리돼도 B로 되돌아가지 않는다.
        assertThat(BackupCandidateSelector.next(ranked, id(a), id(c), Set.of(id(c)))).isEmpty();
    }

    @Test
    void 처리된_사용자가_후보_풀_밖이면_새_제안을_만들지_않는다() {
        Bid a = bidFor("a@vintic.local", 30000L);
        Bid b = bidFor("b@vintic.local", 20000L);
        List<Bid> ranked = List.of(a, b);

        assertThat(BackupCandidateSelector.next(ranked, id(a), 9999L, Set.of())).isEmpty();
    }
}

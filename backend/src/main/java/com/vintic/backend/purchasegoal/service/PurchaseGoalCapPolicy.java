package com.vintic.backend.purchasegoal.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 에이전트가 한 경매에 쓸 수 있는 최고 금액(cap) 규칙. cap = min(예산, 서버 시세 × capRatio).
//
// 후보 순위(PurchaseGoalCandidateRanker)와 참여 직전 재확인(PurchaseGoalEngagementService)이 같은
// 식을 따로 들고 있으면 한쪽만 바뀌어 "순위에서는 통과, 참여에서는 탈락"이 생긴다 - 그래서 한 곳에 둔다.
//
// capRatio 기본값 2.0: 서버 시세는 중고 호가의 중앙값이라 비슷한 매물의 절반은 원래 그보다 비싸다.
// 1.0이면 예산을 넉넉히 잡아도 참여할 매물이 거의 없어서 시세의 2배까지 허용한다. 예산은 여전히
// 절대 상한이고, 후보 순위는 시세 대비 할인율 순이라 싼 매물부터 고른다. 사용자에게는 참여 알림에서
// "다음 입찰가가 시세의 몇 배인지"를 알려 비싸게 사는 경우를 숨기지 않는다.
@Component
public class PurchaseGoalCapPolicy {

    private final double capRatio;

    public PurchaseGoalCapPolicy(@Value("${purchase-agent.cap-ratio:2.0}") double capRatio) {
        if (capRatio <= 0) {
            throw new IllegalArgumentException("purchase-agent.cap-ratio는 0보다 커야 합니다: " + capRatio);
        }
        this.capRatio = capRatio;
    }

    public long cap(long hardMaxAmount, int estimatedPrice) {
        long capByEstimate = (long) Math.floor(estimatedPrice * capRatio);
        return Math.min(hardMaxAmount, capByEstimate);
    }

    public double capRatio() {
        return capRatio;
    }
}

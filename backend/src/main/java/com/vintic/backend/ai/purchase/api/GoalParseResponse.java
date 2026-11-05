package com.vintic.backend.ai.purchase.api;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.ai.purchase.dto.GoalDraft;

import java.time.OffsetDateTime;
import java.util.List;

// POST /api/purchase-goals/parse 응답. GoalDraft(파서 출력)의 필드를 이름·순서 그대로 내리고 capRatio만 덧붙인다 -
// 기존 응답을 읽던 쪽은 그대로 동작한다.
//
// capRatio는 AI가 만든 값이 아니다. 등록 전 확인 화면에서 "AI 시세의 최대 N배까지 입찰"을 보여 주려고, 이 응답을
// 조립하는 컨트롤러가 PurchaseGoalCapPolicy.capRatio()(현재 적용 중인 purchase-agent.cap-ratio 설정값)를 넣는다.
// 그래서 GoalDraft·파서·LLM 스키마에는 이 필드가 없다. 목표 등록·상세 응답의 capRatio와 같은 값이다.
public record GoalParseResponse(
        String modelQuery,
        String brand,
        String modelKey,
        GoalCondition minCondition,
        Long hardMaxAmount,
        Integer sizeKr,
        OffsetDateTime deadline,
        String freeTextConditions,
        double confidence,
        List<String> warnings,
        double capRatio
) {

    public static GoalParseResponse from(GoalDraft draft, double capRatio) {
        return new GoalParseResponse(
                draft.modelQuery(),
                draft.brand(),
                draft.modelKey(),
                draft.minCondition(),
                draft.hardMaxAmount(),
                draft.sizeKr(),
                draft.deadline(),
                draft.freeTextConditions(),
                draft.confidence(),
                draft.warnings(),
                capRatio
        );
    }
}

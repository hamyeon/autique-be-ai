package com.vintic.backend.ai.purchase.parser;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.ai.purchase.dto.GoalDraft;
import com.vintic.backend.ai.purchase.model.ModelAliases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// LLM 출력이 서버 검증을 거치지 않고 나가는 길이 없음을 고정한다.
class GoalDraftValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    private final GoalDraftValidator validator = new GoalDraftValidator(new ModelAliases());

    @Test
    void 카탈로그_키면_brand와_modelQuery는_LLM_값이_아니라_카탈로그_값을_쓴다() {
        GoalDraft draft = validator.validate(new LlmGoalDraft(
                "nb990", "NB 990v6 (LLM이 쓴 이름)", "뉴발란스", "A", 150_000L, 270, null, null, 0.9), TODAY);

        assertThat(draft.modelKey()).isEqualTo("nb990");
        assertThat(draft.modelQuery()).isEqualTo("New Balance 990");
        assertThat(draft.brand()).isEqualTo("New Balance");
        assertThat(draft.minCondition()).isEqualTo(GoalCondition.A);
        assertThat(draft.confidence()).isEqualTo(0.9);
    }

    @Test
    void 카탈로그_밖_키는_비우고_시세_없음_경고를_붙이며_확신도를_누른다() {
        GoalDraft draft = validator.validate(new LlmGoalDraft(
                "yeezy350", "Adidas Yeezy 350", "Adidas", "DS", 300_000L, 270, null, "제브라", 0.95), TODAY);

        assertThat(draft.modelKey()).isNull();
        assertThat(draft.modelQuery()).isEqualTo("Adidas Yeezy 350");
        assertThat(draft.brand()).isEqualTo("Adidas");
        assertThat(draft.warnings()).contains(GoalDraftWarnings.MODEL_NOT_IN_CATALOG);
        assertThat(draft.confidence()).isLessThanOrEqualTo(0.6);
    }

    @Test
    void 비정상_금액과_사이즈는_비운다() {
        GoalDraft draft = validator.validate(new LlmGoalDraft(
                "sambaog", null, null, null, -5L, 999, null, null, 0.8), TODAY);

        assertThat(draft.hardMaxAmount()).isNull();
        assertThat(draft.sizeKr()).isNull();
        assertThat(draft.warnings()).contains(GoalDraftWarnings.BUDGET_OUT_OF_RANGE, GoalDraftWarnings.SIZE_MISSING);
    }

    @Test
    void 모르는_등급_표기와_모르는_브랜드는_비운다() {
        GoalDraft draft = validator.validate(new LlmGoalDraft(
                null, null, "Zara", "MINT", 500_000L, null, null, null, 0.7), TODAY);

        assertThat(draft.minCondition()).isNull();
        assertThat(draft.brand()).isNull();
        assertThat(draft.warnings()).contains(GoalDraftWarnings.CONDITION_MISSING);
    }

    @Test
    void 확신도는_0과_1_사이로_잘린다() {
        assertThat(validator.validate(new LlmGoalDraft("nb990", null, null, null, 100_000L, 270, null, null, 7.0), TODAY)
                .confidence()).isEqualTo(1.0);
        assertThat(validator.validate(new LlmGoalDraft("nb990", null, null, null, 100_000L, 270, null, null, -1.0), TODAY)
                .confidence()).isEqualTo(0.0);
    }

    @Test
    void 기한_날짜는_그날_23시59분_서울시간으로_채운다() {
        GoalDraft draft = validator.validate(new LlmGoalDraft(
                "nb990", null, null, "A", 150_000L, 270, "2026-11-12", null, 0.9), TODAY);

        assertThat(draft.deadline()).isEqualTo(OffsetDateTime.parse("2026-11-12T23:59:00+09:00"));
        assertThat(draft.warnings())
                .doesNotContain(GoalDraftWarnings.DEADLINE_MISSING, GoalDraftWarnings.DEADLINE_OUT_OF_RANGE);
    }

    @Test
    void 오늘_날짜도_기한으로_받는다() {
        GoalDraft draft = validator.validate(new LlmGoalDraft(
                "nb990", null, null, null, 150_000L, 270, "2026-11-05", null, 0.9), TODAY);

        assertThat(draft.deadline()).isEqualTo(OffsetDateTime.parse("2026-11-05T23:59:00+09:00"));
    }

    @Test
    void 기한이_없으면_null이고_다시_묻도록_경고를_붙인다() {
        GoalDraft draft = validator.validate(new LlmGoalDraft(
                "nb990", null, null, null, 150_000L, 270, null, null, 0.9), TODAY);

        assertThat(draft.deadline()).isNull();
        assertThat(draft.warnings()).contains(GoalDraftWarnings.DEADLINE_MISSING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-04", "2027-11-06", "2025-11-12", "11월 12일", "2026-02-30", "2026/11/12"})
    void 지난_날짜_1년_넘는_날짜_형식이_틀린_값은_비운다(String deadlineDate) {
        GoalDraft draft = validator.validate(new LlmGoalDraft(
                "nb990", null, null, null, 150_000L, 270, deadlineDate, null, 0.9), TODAY);

        assertThat(draft.deadline()).isNull();
        assertThat(draft.warnings()).contains(GoalDraftWarnings.DEADLINE_OUT_OF_RANGE)
                .doesNotContain(GoalDraftWarnings.DEADLINE_MISSING);
    }
}

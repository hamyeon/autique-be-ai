package com.vintic.backend.ai.purchase.parser;

import com.vintic.backend.config.ClockConfig;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

// 파서가 뽑은 "마감 날짜"를 GoalDraft.deadline으로 바꾸는 규칙. 규칙 파서와 LLM 검증기가 같이 쓴다.
//
// 파서는 날짜만 낸다("11/20까지", "3일 안에"). 시각은 그날 23:59(Asia/Seoul)로 서버가 채운다 -
// LLM에게 시각·오프셋까지 만들게 하면 틀릴 자리만 늘어난다.
//
// 범위: 오늘 ~ 한 달 뒤 같은 날(11/5면 12/5, "한 달 안에"가 딱 경계). 31일 고정이 아니라 달력 기준이다.
// 지난 날짜·날짜로 성립하지 않는 값은 해석 오류, 한 달 넘게 뒤는 상한 초과로 각각 다른 경고를 붙인다 -
// "연말까지"라고 쓴 사용자에게는 "못 알아봤다"가 아니라 "최대 한 달"이라고 알려야 한다.
// 등록 API는 "현재 이후"만 검사하므로 이 상한은 파싱 초안에만 적용된다.
final class GoalDeadlines {

    static final int MAX_MONTHS_AHEAD = 1;
    static final LocalTime END_OF_DAY = LocalTime.of(23, 59);

    // problemWarning: 기한 표현은 있었지만 비운 이유. null이면 비운 이유가 "기한 없음"이다.
    record Resolved(OffsetDateTime deadline, String problemWarning) {
        static final Resolved NONE = new Resolved(null, null);
        static final Resolved INVALID = new Resolved(null, GoalDraftWarnings.DEADLINE_INVALID);
        static final Resolved TOO_FAR = new Resolved(null, GoalDraftWarnings.DEADLINE_TOO_FAR);
    }

    private GoalDeadlines() {
    }

    static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ClockConfig.APP_ZONE));
    }

    static Resolved resolve(LocalDate date, LocalDate today) {
        if (date == null) {
            return Resolved.NONE;
        }
        if (date.isBefore(today)) {
            return Resolved.INVALID;
        }
        if (date.isAfter(today.plusMonths(MAX_MONTHS_AHEAD))) {
            return Resolved.TOO_FAR;
        }
        return new Resolved(date.atTime(END_OF_DAY).atZone(ClockConfig.APP_ZONE).toOffsetDateTime(), null);
    }
}

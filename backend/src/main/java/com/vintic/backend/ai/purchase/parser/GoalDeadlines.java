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
// 범위: 오늘 ~ 오늘+365일. 밖이면 파싱 오류(작년 날짜, 연도 착각)로 보고 비운 뒤 경고를 붙인다.
// 등록 API는 "현재 이후"만 검사하므로 여기 상한은 초안 단계의 상식선 검사일 뿐이다.
final class GoalDeadlines {

    static final int MAX_DAYS_AHEAD = 365;
    static final LocalTime END_OF_DAY = LocalTime.of(23, 59);

    record Resolved(OffsetDateTime deadline, boolean outOfRange) {
        static final Resolved NONE = new Resolved(null, false);
        static final Resolved OUT_OF_RANGE = new Resolved(null, true);
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
        if (date.isBefore(today) || date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            return Resolved.OUT_OF_RANGE;
        }
        return new Resolved(date.atTime(END_OF_DAY).atZone(ClockConfig.APP_ZONE).toOffsetDateTime(), false);
    }
}

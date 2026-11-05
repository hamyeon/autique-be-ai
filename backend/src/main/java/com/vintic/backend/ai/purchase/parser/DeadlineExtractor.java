package com.vintic.backend.ai.purchase.parser;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 규칙 파서용 기한 추출. "언제까지 찾을지"를 오늘 날짜 기준의 LocalDate로 바꾼다.
//
// 상대 기간("3일 안에", "한 달 동안")은 반드시 "안/이내/내/동안" 같은 꼬리가 붙어야 기한으로 본다.
// "한 달 신은 거"는 상태 설명이지 기한이 아니기 때문이다. 확실하지 않으면 null - 확인 화면이 다시 묻는다.
//
// 주 단위는 일요일에 끝난다고 본다: "이번 주까지" = 이번 주 일요일, "다음 주까지" = 다음 주 일요일.
// 연도 없는 날짜("11월 20일", "1/10")가 오늘보다 앞이면 내년으로 본다.
final class DeadlineExtractor {

    // 상대 기간 뒤에 붙어 "그 안에"를 뜻하는 꼬리. 긴 것부터 둬야 "안으로"가 "안"으로 잘리지 않는다.
    private static final String WITHIN = "(?:안으로|안에|안|이내로|이내에|이내|내로|내에|내|동안)";
    // 기준 시점 뒤에 붙는 꼬리("이번 주까지", "이번 달 중으로").
    private static final String BY = "(?:전까지|까지|중으로|중에|중|안으로|안에|안|내로|내에|내)";

    private record Span(long days, long months) {
    }

    private static final Map<String, Span> WORD_SPANS = Map.ofEntries(
            Map.entry("하루", new Span(1, 0)),
            Map.entry("이틀", new Span(2, 0)),
            Map.entry("사흘", new Span(3, 0)),
            Map.entry("나흘", new Span(4, 0)),
            Map.entry("일주일", new Span(7, 0)),
            Map.entry("한주", new Span(7, 0)),
            Map.entry("두주", new Span(14, 0)),
            Map.entry("보름", new Span(15, 0)),
            Map.entry("한달", new Span(0, 1)),
            Map.entry("두달", new Span(0, 2)),
            Map.entry("세달", new Span(0, 3)),
            Map.entry("석달", new Span(0, 3))
    );

    private record Rule(Pattern pattern, BiFunction<Matcher, LocalDate, LocalDate> resolve) {
        Rule(String regex, BiFunction<Matcher, LocalDate, LocalDate> resolve) {
            this(Pattern.compile(regex), resolve);
        }
    }

    // 위에서부터 처음 맞는 규칙 하나만 쓴다. 구체적인 날짜 표현을 상대 표현보다 먼저 본다.
    private static final List<Rule> RULES = List.of(
            // "11월 20일(까지)"
            new Rule("(?<!\\d)(1[0-2]|0?[1-9])\\s*월\\s*(3[01]|[12]\\d|0?[1-9])\\s*일(?:\\s*" + BY + ")?",
                    (m, today) -> rollToFuture(LocalDate.of(today.getYear(), number(m, 1), number(m, 2)), today)),
            // "11/20까지" - 꼬리가 있어야 한다(꼬리 없는 a/b는 다른 뜻일 수 있다).
            new Rule("(?<![\\d/])(1[0-2]|0?[1-9])\\s*/\\s*(3[01]|[12]\\d|0?[1-9])(?![\\d/])\\s*(?:전까지|까지|전|안|내)",
                    (m, today) -> rollToFuture(LocalDate.of(today.getYear(), number(m, 1), number(m, 2)), today)),
            // "12월 말까지", "12월까지", "12월 안에" - 그 달 말일
            new Rule("(?<!\\d)(1[0-2]|0?[1-9])\\s*월\\s*(?:말\\s*" + BY + "?|" + BY + ")",
                    (m, today) -> {
                        YearMonth month = YearMonth.of(today.getYear(), number(m, 1));
                        return (month.atEndOfMonth().isBefore(today) ? month.plusYears(1) : month).atEndOfMonth();
                    }),
            // "20일까지" - 이번 달 20일(지났으면 다음 달). "3일 안에"(상대 기간)와 꼬리로 구분한다.
            new Rule("(?<![\\d월])(3[01]|[12]\\d|0?[1-9])\\s*일\\s*(?:전까지|까지)",
                    (m, today) -> {
                        LocalDate candidate = today.withDayOfMonth(number(m, 1));
                        return candidate.isBefore(today) ? today.plusMonths(1).withDayOfMonth(number(m, 1)) : candidate;
                    }),
            // "3일 안에", "2주 이내", "1개월 동안"
            new Rule("(?<![\\d.])(\\d{1,3})\\s*(주일|주|개월|달|일)\\s*" + WITHIN,
                    (m, today) -> switch (m.group(2)) {
                        case "일" -> today.plusDays(number(m, 1));
                        case "주", "주일" -> today.plusWeeks(number(m, 1));
                        default -> today.plusMonths(number(m, 1));
                    }),
            // "일주일 안에", "한 달 동안", "보름 내로"
            new Rule("(하루|이틀|사흘|나흘|일주일|한\\s*주|두\\s*주|보름|한\\s*달|두\\s*달|세\\s*달|석\\s*달)\\s*" + WITHIN,
                    (m, today) -> {
                        Span span = WORD_SPANS.get(m.group(1).replaceAll("\\s", ""));
                        return today.plusDays(span.days()).plusMonths(span.months());
                    }),
            new Rule("오늘\\s*" + BY, (m, today) -> today),
            new Rule("내일\\s*" + BY, (m, today) -> today.plusDays(1)),
            new Rule("모레\\s*" + BY, (m, today) -> today.plusDays(2)),
            new Rule("다음\\s*주\\s*(?:말\\s*)?" + BY, (m, today) -> thisSunday(today).plusWeeks(1)),
            // "이번 주말", "주말까지", "이번 주 안에"
            new Rule("이번\\s*주\\s*말(?:\\s*" + BY + ")?|주말\\s*" + BY + "|이번\\s*주\\s*" + BY,
                    (m, today) -> thisSunday(today)),
            new Rule("다음\\s*달\\s*(?:말\\s*)?" + BY, (m, today) -> YearMonth.from(today).plusMonths(1).atEndOfMonth()),
            new Rule("(?:이번\\s*달|이달)(?:\\s*말)?(?:\\s*" + BY + ")?", (m, today) -> YearMonth.from(today).atEndOfMonth()),
            new Rule("연말(?:\\s*" + BY + ")?|올해\\s*" + BY, (m, today) -> LocalDate.of(today.getYear(), 12, 31))
    );

    // date: 해석한 날짜(없으면 null). invalid: 기한 표현은 있었지만 날짜로 성립하지 않음(2월 30일 등).
    record Result(LocalDate date, boolean invalid) {
        static final Result NONE = new Result(null, false);
    }

    private DeadlineExtractor() {
    }

    // 맞은 구간은 consumed에 넣어 자유 조건에 남지 않게 한다.
    static Result extract(String lower, LocalDate today, List<String> consumed) {
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(lower);
            if (!matcher.find()) {
                continue;
            }
            consumed.add(matcher.group().trim());
            try {
                return new Result(rule.resolve().apply(matcher, today), false);
            } catch (DateTimeException e) {
                return new Result(null, true);
            }
        }
        return Result.NONE;
    }

    private static int number(Matcher matcher, int group) {
        return Integer.parseInt(matcher.group(group));
    }

    private static LocalDate rollToFuture(LocalDate date, LocalDate today) {
        return date.isBefore(today) ? date.plusYears(1) : date;
    }

    private static LocalDate thisSunday(LocalDate today) {
        return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
    }
}

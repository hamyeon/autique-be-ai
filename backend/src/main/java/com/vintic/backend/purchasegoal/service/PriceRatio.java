package com.vintic.backend.purchasegoal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

// "금액이 시세의 몇 배인가"를 로그·알림에 같은 모양으로 쓰기 위한 도우미.
public final class PriceRatio {

    private PriceRatio() {
    }

    // 소수 첫째 자리까지("1.5", "2"). 시세가 0 이하면 비교할 기준이 없어 "?"를 돌려준다.
    public static String format(long amount, int estimatedPrice) {
        if (estimatedPrice <= 0) {
            return "?";
        }
        return BigDecimal.valueOf(amount)
                .divide(BigDecimal.valueOf(estimatedPrice), 1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    // 사용자 알림용 한 문장. 다음 입찰가가 시세보다 비싸면 "몇 배", 싸면 "몇 % 저렴"으로 말한다 -
    // 비싸게 사는 경우를 숨기지 않는 것이 목적이다.
    public static String engagementNote(long minNextBid, int estimatedPrice, long cap) {
        if (estimatedPrice <= 0) {
            return "";
        }
        String bidPart;
        if (minNextBid > estimatedPrice) {
            bidPart = "다음 입찰가 %,d원은 AI 시세(%,d원)의 %s배입니다.".formatted(
                    minNextBid, estimatedPrice, format(minNextBid, estimatedPrice));
        } else if (minNextBid == estimatedPrice) {
            bidPart = "다음 입찰가 %,d원은 AI 시세와 같습니다.".formatted(minNextBid);
        } else {
            long discountPercent = Math.round((estimatedPrice - minNextBid) * 100.0 / estimatedPrice);
            bidPart = "다음 입찰가 %,d원은 AI 시세(%,d원)보다 %d%% 저렴합니다.".formatted(
                    minNextBid, estimatedPrice, discountPercent);
        }
        return bidPart + " 최대 %,d원(시세의 %s배)까지 자동으로 입찰합니다.".formatted(cap, format(cap, estimatedPrice));
    }
}

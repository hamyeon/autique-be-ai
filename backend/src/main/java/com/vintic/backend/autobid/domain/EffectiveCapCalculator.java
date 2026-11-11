package com.vintic.backend.autobid.domain;

// §0.13 effectiveCap 공식의 유일한 계산 지점이다. AutoBidSetting(entity)과 ProxyPriceEngine
// (순수 계산, entity에 접근할 수 없음) 양쪽이 같은 공식을 각자 다시 구현하지 않도록 여기 하나로
// 통일한다.
public final class EffectiveCapCalculator {

    private EffectiveCapCalculator() {
    }

    // maxAmount 이하인 grid 지점(currentPrice + k * bidIncrement) 중 가장 큰 값을 반환한다.
    // maxAmount가 bidIncrement 배수로 정렬돼 있지 않아도 된다(등록 시 정렬을 요구하지 않음).
    // maxAmount < currentPrice이면 k가 음수가 되어 결과도 currentPrice보다 낮다 - "이 상한으로는
    // 현재가에 대응할 수 없다"는 뜻이고, ProxyPriceEngine은 이런 후보를 CAP_REACHED로 판정한다.
    // 반드시 floorDiv를 써야 한다: Java의 '/'는 0 쪽으로 버리므로 음수 차이에서 한 단계 위
    // 지점(maxAmount 초과)을 만든다(예: max 132000, 현재가 135000 -> 135000이 나옴).
    public static long calculate(long maxAmount, long currentPrice, long bidIncrement) {
        long steps = Math.floorDiv(maxAmount - currentPrice, bidIncrement);
        long effectiveCap = currentPrice + steps * bidIncrement;
        if (effectiveCap > maxAmount) {
            // floorDiv에서는 발생할 수 없다 - 계산식이 깨졌다는 신호이므로 조용히 넘기지 않는다.
            throw new IllegalStateException(
                    "effectiveCap이 maxAmount를 초과했습니다. maxAmount=" + maxAmount
                            + ", currentPrice=" + currentPrice + ", bidIncrement=" + bidIncrement
            );
        }
        return effectiveCap;
    }
}

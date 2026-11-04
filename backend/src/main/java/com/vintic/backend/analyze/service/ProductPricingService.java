package com.vintic.backend.analyze.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.naming.KoreanProductNameResolver;
import com.vintic.backend.common.exception.AiApiException;
import com.vintic.backend.common.exception.InvalidAnalysisStatusException;
import com.vintic.backend.product.dto.CalculatePriceRequest;
import com.vintic.backend.product.dto.CalculatePriceResponse;
import com.vintic.backend.product.pricing.PricingRequest;
import com.vintic.backend.product.pricing.PricingResult;
import com.vintic.backend.product.pricing.PricingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

// analysisId로 세션을 찾아 확정 입력값을 기록하고, PricingService를 호출해 상태를 갱신하는 오케스트레이터.
// Controller는 요청 수신/응답 반환만 담당하고, 세션 조회/상태 전환/실패 기록 책임은 여기로 모은다.
//
// 세션 조회/락/상태 전환은 PricingAttemptCoordinator가 담당한다(#127) - 외부 pricingService.calculate()
// 호출을 트랜잭션 밖에 두어야 하기 때문에(VisionAttemptCoordinator와 동일한 이유), 시작과 종료를
// 별도의 짧은 트랜잭션으로 쪼갠다. 그 사이 세션이 취소(ProductAnalysisSession.cancel())되면
// tryCompletePricing/tryFailPricing이 false를 반환해 결과가 버려진다.
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductPricingService {

    private static final int FAILURE_MESSAGE_MAX_LENGTH = 1000;

    private final PricingService pricingService;
    private final PricingAttemptCoordinator coordinator;
    private final ObjectMapper objectMapper;
    private final KoreanProductNameResolver koreanNameResolver;

    public CalculatePriceResponse calculatePrice(CalculatePriceRequest request, Long userId) {
        PricingRequest pricingRequest = new PricingRequest(
                request.brand(),
                request.modelName(),
                request.color(),
                request.size(),
                request.conditionGrade(),
                request.componentStatus()
        );

        // 직렬화가 실패하면(비정상적인 경우) 여기서 예외가 던져지고, startOwned가 호출되기 전이라
        // 세션 상태는 아직 AWAITING_USER_CONFIRMATION 그대로 유지된다 - PRICING_PROCESSING으로는
        // 절대 안 넘어간다.
        String confirmedInputJson = toJson(pricingRequest);

        // 존재하지 않는 세션/타인의 세션/취소된 세션/이미 Pricing 중이거나 끝난 세션은 여기서
        // AnalysisSessionNotFoundException 또는 InvalidAnalysisStatusException으로 막힌다.
        coordinator.startOwned(request.analysisId(), userId, confirmedInputJson);

        PricingResult result;
        try {
            result = pricingService.calculate(pricingRequest);
        } catch (RuntimeException e) {
            // 실패 기록 자체가 세션 취소로 버려지거나(tryFailPricing이 false 반환) 기록 중 추가
            // 오류가 나도, 원래 발생한 Pricing 예외가 덮어써지면 안 되므로 여기서 삼키고 로그만
            // 남긴다 - 호출자에게는 항상 "가격 계산이 실패했다"가 사실이다.
            recordFailureSafely(request.analysisId(), truncate(e.getMessage()));
            throw e;
        }

        boolean saved = coordinator.tryCompletePricing(request.analysisId(), toJson(result));
        if (!saved) {
            // Pricing 호출이 진행되는 동안 세션이 취소됐다 - 이미 비용이 든 계산 결과를 버렸다는
            // 사실을 호출자에게 알려야 한다(성공으로 응답하면 프론트가 취소된 분석을 등록에 쓸 수
            // 있다고 오해한다).
            throw new InvalidAnalysisStatusException(
                    "분석 세션이 취소되어 가격 계산 결과가 저장되지 않았습니다. analysisId: " + request.analysisId()
            );
        }

        // 상품 정보 확인 화면에 영문명과 함께 보여줄 한국어 상품명. 판매자가 분석 값을 그대로 두면 Worker가 분석 직후
        // 미리 저장해 둔 이름이 바로 나오고, 고쳤으면 여기서 번역한다(1~2초). 가격 계산이 성공한 뒤에만 부른다 -
        // 실패·취소된 요청에 번역 비용을 쓰지 않는다. 실패하면 null이고 가격 계산 응답은 그대로 나간다.
        String nameKo = koreanNameResolver.resolve(request.brand(), request.modelName(), request.color());

        return new CalculatePriceResponse(
                result.recommendedPrice(),
                result.baseMarketPrice(),
                result.kreamAveragePrice(),
                result.ebayAveragePrice(),
                result.minRecommendedPrice(),
                result.maxRecommendedPrice(),
                result.priceRange(),
                result.reason(),
                toCalculateMatches(result.kreamMatches()),
                toCalculateMatches(result.ebayMatches()),
                nameKo
        );
    }

    private void recordFailureSafely(Long sessionId, String message) {
        try {
            coordinator.tryFailPricing(sessionId, message);
        } catch (RuntimeException recordingError) {
            log.error("Pricing 실패 상태 기록 중 추가 오류가 발생했습니다. sessionId={}", sessionId, recordingError);
        }
    }

    private List<CalculatePriceResponse.MatchedMarketPrice> toCalculateMatches(
            List<PricingResult.MatchedMarketPrice> matches
    ) {
        return matches.stream()
                .map(match -> new CalculatePriceResponse.MatchedMarketPrice(
                        match.source(),
                        match.brand(),
                        match.modelName(),
                        match.color(),
                        match.size(),
                        match.conditionGrade(),
                        match.componentStatus(),
                        match.price(),
                        match.url()
                ))
                .toList();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new AiApiException("가격 계산 결과를 저장하는 중 오류가 발생했습니다.");
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > FAILURE_MESSAGE_MAX_LENGTH
                ? message.substring(0, FAILURE_MESSAGE_MAX_LENGTH)
                : message;
    }
}

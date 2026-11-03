package com.vintic.backend.ai.naming;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.observability.domain.AiCallFailureType;
import com.vintic.backend.ai.observability.domain.AiCallLog;
import com.vintic.backend.ai.observability.domain.AiCallType;
import com.vintic.backend.ai.observability.service.AiCallLogger;
import com.vintic.backend.ai.observability.service.AiCallRequestSummary;
import com.vintic.backend.ai.prompt.PromptTemplate;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.vision.client.ChatCompletionClient;
import com.vintic.backend.ai.vision.client.VisionChatRequest;
import com.vintic.backend.ai.vision.client.VisionChatResponse;
import com.vintic.backend.ai.vision.client.VisionClientConfig;
import com.vintic.backend.ai.vision.client.VisionImageDetail;
import com.vintic.backend.ai.vision.client.VisionProviderProperties;
import com.vintic.backend.common.exception.AiResponseFormatException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

// 영문 브랜드·모델명·색상을 KREAM식 한국어 상품명으로 옮긴다. 크림 데이터에 없는 신발에만 불린다
// (KoreanProductNameResolver).
//
// 프롬프트에 크림 상품명 34개와 거기서 뽑은 색상 사전을 넣어 표기를 크림에 맞춘다. 사전 없이 맡기면
// "Gray Fog"를 의역하거나 같은 단어를 매번 다르게 음차한다. 2026-11-03 검증: 크림 정답 12개 중 8개 완전
// 일치, 나머지는 로마숫자·붙여쓰기 차이였고 v1 규칙에 반영했다. v1 재측정(KoreanNameHarnessTest, 7건): 틀렸던 4건 모두
// 크림과 일치, 응답 1.35~4.2초(중앙값 1.6초, 4초대는 첫 호출 포함 2건), 호출당 입력 약 3,060 토큰.
@Service
@Slf4j
public class KoreanNameTranslator {

    static final String STAGE_NAME = "korean-name";
    private static final String PROMPT_CATEGORY = "naming";

    private final ChatCompletionClient chatClient;
    private final ObjectMapper objectMapper;
    private final AiCallLogger aiCallLogger;
    private final PromptTemplate template;
    private final VisionChatRequest.ResponseSchema responseSchema;
    private final String model;
    private final String promptVersion;
    private final int maxOutputTokens;

    public KoreanNameTranslator(@Qualifier(VisionClientConfig.VISION_CHAT_CLIENT) ChatCompletionClient chatClient,
                                ObjectMapper objectMapper,
                                AiCallLogger aiCallLogger,
                                PromptTemplateLoader promptTemplateLoader,
                                VisionProviderProperties providerProperties,
                                KoreanNameProperties properties) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.aiCallLogger = aiCallLogger;
        this.promptVersion = properties.getPromptVersion();
        this.template = promptTemplateLoader.load(PROMPT_CATEGORY, STAGE_NAME, promptVersion);
        this.responseSchema = new VisionChatRequest.ResponseSchema(
                "naming_korean_name_%s".formatted(promptVersion),
                promptTemplateLoader.loadSchema(PROMPT_CATEGORY, STAGE_NAME, promptVersion));
        this.model = resolveModel(properties.getModel(), providerProperties);
        this.maxOutputTokens = properties.getMaxOutputTokens();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(String nameKo) {
    }

    // 실패하면 예외를 던진다. 이름이 없어도 분석·등록은 진행돼야 하므로 삼키는 건 호출부(Resolver)의 몫이다.
    public String translate(String brand, String modelName, String color) {
        String userText = "브랜드: %s\n모델명: %s\n색상: %s".formatted(
                nullToEmpty(brand), nullToEmpty(modelName), nullToEmpty(color));
        VisionChatRequest request = new VisionChatRequest(
                model,
                template.content(),
                userText,
                List.of(),
                VisionImageDetail.LOW,
                responseSchema,
                maxOutputTokens
        );
        String requestSummary = AiCallRequestSummary.of(request, objectMapper);

        VisionChatResponse response;
        long startedAt = System.currentTimeMillis();
        try {
            response = chatClient.complete(request);
        } catch (RuntimeException e) {
            aiCallLogger.record(logBuilder(requestSummary)
                    .latencyMs(System.currentTimeMillis() - startedAt)
                    .failure(AiCallFailureType.API_ERROR, e.getMessage())
                    .build());
            throw e;
        }

        String nameKo;
        try {
            nameKo = objectMapper.readValue(response.content(), Response.class).nameKo();
        } catch (Exception e) {
            aiCallLogger.record(logBuilder(requestSummary)
                    .latencyMs(response.latencyMs())
                    .tokens(response.promptTokens(), response.completionTokens())
                    .responseBody(response.content())
                    .failure(AiCallFailureType.PARSE_ERROR, e.getMessage())
                    .build());
            throw new AiResponseFormatException("한국어 상품명 응답을 처리하는 중 오류가 발생했습니다.", e);
        }

        aiCallLogger.record(logBuilder(requestSummary)
                .latencyMs(response.latencyMs())
                .tokens(response.promptTokens(), response.completionTokens())
                .responseBody(response.content())
                .build());
        log.info("한국어 상품명 번역 - nameKo={}, model={}, promptTokens={}, completionTokens={}, latencyMs={}",
                nameKo, model, response.promptTokens(), response.completionTokens(), response.latencyMs());
        return nameKo == null || nameKo.isBlank() ? null : nameKo.trim();
    }

    private AiCallLog.Builder logBuilder(String requestSummary) {
        return AiCallLog.builder(AiCallType.CHAT, model)
                .stage(STAGE_NAME)
                .promptVersion(promptVersion)
                .requestSummary(requestSummary);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // PhotoViewClassifier.resolveModel과 같은 규칙 - 클라이언트는 provider당 하나라 다른 벤더의 모델명은 쓸 수 없다.
    private static String resolveModel(String configured, VisionProviderProperties providerProperties) {
        if (configured == null || configured.isBlank()) {
            return providerProperties.resolvedModel();
        }
        String trimmed = configured.trim();
        boolean claudeModel = trimmed.startsWith("claude-");
        if (claudeModel != (providerProperties.getProvider() == VisionProviderProperties.Provider.CLAUDE)) {
            log.warn("한국어 상품명 모델 {}은 provider={}의 모델이 아니라 무시하고 {}을 씁니다.",
                    trimmed, providerProperties.getProvider(), providerProperties.resolvedModel());
            return providerProperties.resolvedModel();
        }
        return trimmed;
    }
}

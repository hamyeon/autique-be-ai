package com.vintic.backend.ai.vision.view;

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
import com.vintic.backend.ai.vision.image.VisionImageLoader;
import com.vintic.backend.common.exception.AiApiException;
import com.vintic.backend.common.exception.AiResponseFormatException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

// 사진 한 장이 신발의 어느 면(측면/앞면/밑창 등)인지 판정한다.
//
// 왜 따로 부르나:
//   앱은 측면·앞면·밑창 칸을 필수로 받지만 지금은 장수만 확인한다. 분석 단계에서 잘못 넣은 걸 알면 사용자가
//   분석 화면에서 첫 화면으로 되돌아가야 해서 흐름이 꼬인다. 사진을 칸에 넣는 순간 이 판정만 먼저 돌려
//   그 자리에서 알려주는 게 목표다. 그래서 사진 한 장, 작은 해상도, 짧은 응답으로 1~2초 안에 끝나야 한다.
//
// 바이트를 받는다. 업로드 직후라 서버가 이미 사진을 들고 있고, 줄인 사본을 base64로 실어 보내면 벤더가
//   S3에서 다시 받아가는 왕복이 없다.
@Service
@Slf4j
public class PhotoViewClassifier {

    static final String STAGE_NAME = "photo-view";
    private static final String PROMPT_CATEGORY = "vision";

    private final ChatCompletionClient visionClient;
    private final VisionImageLoader imageLoader;
    private final ObjectMapper objectMapper;
    private final AiCallLogger aiCallLogger;
    private final PromptTemplate template;
    private final VisionChatRequest.ResponseSchema responseSchema;
    private final String model;
    private final String promptVersion;
    private final int maxEdge;
    private final int maxOutputTokens;

    public PhotoViewClassifier(@Qualifier(VisionClientConfig.VISION_CHAT_CLIENT) ChatCompletionClient visionClient,
                               VisionImageLoader imageLoader,
                               ObjectMapper objectMapper,
                               AiCallLogger aiCallLogger,
                               PromptTemplateLoader promptTemplateLoader,
                               VisionProviderProperties providerProperties,
                               PhotoViewProperties properties) {
        this.visionClient = visionClient;
        this.imageLoader = imageLoader;
        this.objectMapper = objectMapper;
        this.aiCallLogger = aiCallLogger;
        this.promptVersion = properties.getPromptVersion();
        this.template = promptTemplateLoader.load(PROMPT_CATEGORY, STAGE_NAME, promptVersion);
        this.responseSchema = new VisionChatRequest.ResponseSchema(
                "vision_photo_view_%s".formatted(promptVersion),
                promptTemplateLoader.loadSchema(PROMPT_CATEGORY, STAGE_NAME, promptVersion));
        this.model = resolveModel(properties.getModel(), providerProperties);
        this.maxEdge = Math.max(0, properties.getMaxEdge());
        this.maxOutputTokens = properties.getMaxOutputTokens();
    }

    // 결과와 함께 응답 시간을 돌려준다 - "넣자마자 알려줄 수 있나"가 이 기능의 전제라 하네스가 이 값을 본다.
    public record Result(PhotoView view, String reason, long latencyMs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(String view, String reason) {
    }

    public Result classify(byte[] image) {
        String imageInput = imageLoader.toImageInput(new VisionImageLoader.LoadedImage(null, image), maxEdge);
        if (imageInput == null) {
            // toImageInput은 형식을 모르면 원래 URL(여기선 없음)을 돌려준다. HEIC 등 벤더가 받지 않는 형식이다.
            throw new AiApiException("사진 형식을 판정에 쓸 수 없습니다(jpeg/png/gif/webp만 가능).");
        }

        VisionChatRequest request = new VisionChatRequest(
                model,
                template.content(),
                null,
                List.of(imageInput),
                VisionImageDetail.LOW,
                responseSchema,
                maxOutputTokens
        );
        String requestSummary = AiCallRequestSummary.of(request, objectMapper);

        VisionChatResponse response;
        long startedAt = System.currentTimeMillis();
        try {
            response = visionClient.complete(request);
        } catch (RuntimeException e) {
            aiCallLogger.record(logBuilder(requestSummary)
                    .latencyMs(System.currentTimeMillis() - startedAt)
                    .failure(AiCallFailureType.API_ERROR, e.getMessage())
                    .build());
            throw e;
        }

        try {
            Response parsed = objectMapper.readValue(response.content(), Response.class);
            aiCallLogger.record(logBuilder(requestSummary)
                    .latencyMs(response.latencyMs())
                    .tokens(response.promptTokens(), response.completionTokens())
                    .responseBody(response.content())
                    .build());
            log.info("사진 면 판정 - view={}, model={}, promptTokens={}, completionTokens={}, latencyMs={}",
                    parsed.view(), model, response.promptTokens(), response.completionTokens(), response.latencyMs());
            return new Result(PhotoView.from(parsed.view()), parsed.reason(), response.latencyMs());
        } catch (Exception e) {
            aiCallLogger.record(logBuilder(requestSummary)
                    .latencyMs(response.latencyMs())
                    .tokens(response.promptTokens(), response.completionTokens())
                    .responseBody(response.content())
                    .failure(AiCallFailureType.PARSE_ERROR, e.getMessage())
                    .build());
            throw new AiResponseFormatException("사진 면 판정 응답을 처리하는 중 오류가 발생했습니다.", e);
        }
    }

    private AiCallLog.Builder logBuilder(String requestSummary) {
        return AiCallLog.builder(AiCallType.VISION, model)
                .stage(STAGE_NAME)
                .promptVersion(promptVersion)
                .requestSummary(requestSummary);
    }

    // StagedVisionAnalysisService.resolveStageModel과 같은 규칙 - 클라이언트는 provider당 하나라
    // 다른 벤더의 모델명은 쓸 수 없다.
    private static String resolveModel(String configured, VisionProviderProperties providerProperties) {
        if (configured == null || configured.isBlank()) {
            return providerProperties.resolvedModel();
        }
        String trimmed = configured.trim();
        boolean claudeModel = trimmed.startsWith("claude-");
        if (claudeModel != (providerProperties.getProvider() == VisionProviderProperties.Provider.CLAUDE)) {
            log.warn("사진 면 판정 모델 {}은 provider={}의 모델이 아니라 무시하고 {}을 씁니다.",
                    trimmed, providerProperties.getProvider(), providerProperties.resolvedModel());
            return providerProperties.resolvedModel();
        }
        return trimmed;
    }
}

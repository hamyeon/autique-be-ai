package com.vintic.backend.ai.naming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.observability.service.AiCallLogger;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.vision.client.ChatCompletionClient;
import com.vintic.backend.ai.vision.client.ClaudeChatClient;
import com.vintic.backend.ai.vision.client.ClaudeClientProperties;
import com.vintic.backend.ai.vision.client.VisionChatResponse;
import com.vintic.backend.ai.vision.client.VisionProviderProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 한국어 상품명 번역(KoreanNameTranslator)을 실제 Claude API로 돌려 크림 표기와 비교하는 하네스(유료, 호출당 약 2,500 입력 토큰).
 *
 * 케이스는 2026-11-03 사전 검증에서 틀렸던 것(로마숫자·붙여쓰기·thinking 잘림)과 크림에 없는 실사용 예시다.
 * 크림 정답이 있는 케이스는 그 신발이 프롬프트 예시에도 들어 있어 정확도보다는 "규칙이 지켜지는지"를 본다.
 *
 * 실행:
 *   ./gradlew test --tests '*KoreanNameHarnessTest' -Dvision.harness.korean-name=true
 * 옵션 이름이 vision.harness로 시작해야 build.gradle이 테스트 JVM에 넘겨준다. effort 기본 low(운영값).
 */
@EnabledIfSystemProperty(named = "vision.harness.korean-name", matches = "true")
class KoreanNameHarnessTest {

    record Case(String brand, String modelName, String color, String expected) {
    }

    private static final List<Case> CASES = List.of(
            new Case("Adidas", "Superstar II", "Core Black Cloud White", "아디다스 슈퍼스타 2 코어 블랙 클라우드 화이트"),
            new Case("Nike", "SB Dunk Low", "Heineken", "나이키 SB 덩크 로우 하이네켄"),
            new Case("Nike", "Air Max 90 LTR", "Triple White", "나이키 에어맥스 90 LTR 트리플 화이트"),
            new Case("Nike", "Dunk High Retro", "Championship White", "나이키 덩크 하이 레트로 챔피언쉽 화이트"),
            new Case("Asics", "Gel-Kayano 14", "Cream Black", null),
            new Case("On", "Cloudmonster", "All Black", null),
            new Case("Asics", "Novablast 6", "Black - 2E Wide", null)
    );

    @Test
    void 번역_결과를_크림_표기와_비교한다() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "ANTHROPIC_API_KEY가 없어 건너뜁니다.");

        ClaudeClientProperties claudeProperties = new ClaudeClientProperties();
        claudeProperties.getApi().setKey(apiKey);
        claudeProperties.setEffort(System.getProperty("vision.harness.korean-name.effort", "low"));
        ClaudeChatClient claude = new ClaudeChatClient(new ObjectMapper(), claudeProperties);
        AtomicReference<VisionChatResponse> lastResponse = new AtomicReference<>();
        ChatCompletionClient client = request -> {
            VisionChatResponse response = claude.complete(request);
            lastResponse.set(response);
            return response;
        };

        VisionProviderProperties providerProperties = new VisionProviderProperties();
        providerProperties.setProvider(VisionProviderProperties.Provider.CLAUDE);
        KoreanNameTranslator translator = new KoreanNameTranslator(client, new ObjectMapper(),
                Mockito.mock(AiCallLogger.class), new PromptTemplateLoader(), providerProperties, new KoreanNameProperties());

        int matched = 0;
        int withAnswer = 0;
        long totalPromptTokens = 0;
        long totalCompletionTokens = 0;
        for (Case c : CASES) {
            String input = "%s | %s | %s".formatted(c.brand(), c.modelName(), c.color());
            try {
                String nameKo = translator.translate(c.brand(), c.modelName(), c.color());
                VisionChatResponse response = lastResponse.get();
                totalPromptTokens += response.promptTokens();
                totalCompletionTokens += response.completionTokens();
                String mark = "-";
                if (c.expected() != null) {
                    withAnswer++;
                    boolean ok = c.expected().equals(nameKo);
                    matched += ok ? 1 : 0;
                    mark = ok ? "O" : "X (정답 " + c.expected() + ")";
                }
                System.out.printf("[한국어명] %s -> %s | %s | %dms, 토큰 %d/%d%n", input, nameKo, mark,
                        response.latencyMs(), response.promptTokens(), response.completionTokens());
            } catch (RuntimeException e) {
                System.out.printf("[한국어명] %s -> 실패: %s%n", input, e.getMessage());
            }
        }
        System.out.printf("[한국어명] 크림 일치 %d/%d, 입력 토큰 %d, 출력 토큰 %d%n",
                matched, withAnswer, totalPromptTokens, totalCompletionTokens);
    }
}

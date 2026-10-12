package com.vintic.backend.ai.vision.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

/**
 * 워밍업(ClaudeChatClient.warmUp - 모델 조회)이 첫 호출 지연을 실제로 없애는지 재는 하네스. 실제 Claude API를 부른다.
 *
 * 첫 호출 비용(클래스 로딩·클라이언트 생성·TLS 연결)은 JVM마다 한 번이라, 켠 경우와 끈 경우를 같은 JVM에서
 * 연달아 재면 뒤쪽이 앞쪽 덕을 본다. 그래서 한 번 실행에 한 가지만 재고, gradle을 따로 돌려 비교한다.
 *
 *   ./gradlew test --tests '*ClaudeWarmupHarnessTest' -Dvision.harness.warmup=on
 *   ./gradlew test --tests '*ClaudeWarmupHarnessTest' -Dvision.harness.warmup=off
 *
 * 요청은 토큰 몇 개짜리 텍스트 한 번이라 비용은 사실상 0이다. 사진 크기와 무관한 "처음 한 번" 비용만 보려는 것이다.
 */
@EnabledIfSystemProperty(named = "vision.harness.warmup", matches = "on|off")
class ClaudeWarmupHarnessTest {

    private static final String MODEL = "claude-haiku-5-5";
    private static final int CALLS = 3;

    @Test
    void 워밍업_여부에_따른_첫_호출_지연을_잰다() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "ANTHROPIC_API_KEY가 없어 건너뜁니다.");
        boolean warmup = "on".equals(System.getProperty("vision.harness.warmup"));

        ClaudeClientProperties properties = new ClaudeClientProperties();
        properties.getApi().setKey(apiKey);
        properties.setEffort("low");
        ClaudeChatClient client = new ClaudeChatClient(new ObjectMapper(), properties);

        StringBuilder line = new StringBuilder("[워밍업 하네스] warmup=%s".formatted(warmup ? "on" : "off"));
        if (warmup) {
            long startedAt = System.nanoTime();
            client.warmUp(MODEL);
            line.append(", 워밍업 %dms".formatted((System.nanoTime() - startedAt) / 1_000_000));
        }

        VisionChatRequest request = new VisionChatRequest(MODEL, "Reply with the single word OK.", "ping",
                List.of(), VisionImageDetail.LOW, null, 16);
        for (int i = 1; i <= CALLS; i++) {
            long startedAt = System.nanoTime();
            client.complete(request);
            line.append(", 호출%d %dms".formatted(i, (System.nanoTime() - startedAt) / 1_000_000));
        }
        System.out.println(line);
    }
}

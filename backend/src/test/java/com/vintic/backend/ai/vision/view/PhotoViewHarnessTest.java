package com.vintic.backend.ai.vision.view;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.observability.service.AiCallLogger;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.vision.client.ClaudeChatClient;
import com.vintic.backend.ai.vision.client.ClaudeClientProperties;
import com.vintic.backend.ai.vision.client.VisionProviderProperties;
import com.vintic.backend.ai.vision.client.ChatCompletionClient;
import com.vintic.backend.ai.vision.client.VisionChatResponse;
import com.vintic.backend.ai.vision.image.ImageResizer;
import com.vintic.backend.ai.vision.image.VisionImageLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 사진 한 장 면 판정(PhotoViewClassifier)의 정확도와 응답 시간을 재는 하네스. 실제 Claude API를 부른다(유료).
 *
 * 평가 사진은 저장소에 넣지 않는다(직접 찍은 사진). 폴더를 지정하면 그 아래 사진을 전부 돈다.
 * 정답은 파일 이름 앞부분으로 정한다: side / front / back / top / outsole / other 로 시작해야 한다.
 *   photos/
 *     af1-white/side.jpg, front.jpg, outsole.jpg
 *     samba/side_angled.jpg, outsole_dirty.jpg
 *     wrong/other_box.jpg, top_laces.jpg
 *
 * 실행 (PowerShell에서는 -D 인자를 따옴표로 감싼다):
 *   ./gradlew test --tests '*PhotoViewHarnessTest' -Dvision.harness.photo-view=true \
 *     -Dvision.harness.photo-view.dir=C:/photos -Dvision.harness.photo-view.model=claude-haiku-5-5
 *
 * 옵션 이름이 vision.harness로 시작해야 build.gradle이 테스트 JVM에 넘겨준다.
 * model    기본 claude-haiku-5-5. max-edge 기본 512. effort 기본 low(운영값).
 *
 * 리포트(build/vision-harness/photo-view-*.txt)에 칸 기준 통과율(PhotoViewCheck), 면 그대로의 정확도,
 * 혼동표, 응답 시간(평균·중앙값·p90·최대, 첫 호출 따로), 사진당 토큰이 남는다.
 */
@EnabledIfSystemProperty(named = "vision.harness.photo-view", matches = "true")
class PhotoViewHarnessTest {

    private static final String PREFIX = "vision.harness.photo-view";
    private static final Path REPORT_DIRECTORY = Path.of("build", "vision-harness");
    private static final List<String> IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp");

    record Row(String file, PhotoView expected, PhotoView actual, String reason, long latencyMs,
               int promptTokens, int completionTokens, String error) {
    }

    @Test
    void 사진_폴더를_돌려_면_판정_정확도와_응답_시간을_잰다() throws IOException {
        String dir = System.getProperty(PREFIX + ".dir");
        Assumptions.assumeTrue(dir != null && !dir.isBlank(), PREFIX + ".dir이 없어 건너뜁니다.");
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "ANTHROPIC_API_KEY가 없어 건너뜁니다.");

        PhotoViewProperties properties = new PhotoViewProperties();
        properties.setModel(System.getProperty(PREFIX + ".model", properties.getModel()));
        properties.setMaxEdge(Integer.getInteger(PREFIX + ".max-edge", properties.getMaxEdge()));
        properties.setPromptVersion(System.getProperty(PREFIX + ".prompt-version", properties.getPromptVersion()));

        ClaudeClientProperties claudeProperties = new ClaudeClientProperties();
        claudeProperties.getApi().setKey(apiKey);
        claudeProperties.setEffort(System.getProperty(PREFIX + ".effort", "low"));
        ClaudeChatClient claude = new ClaudeChatClient(new ObjectMapper(), claudeProperties);
        // 판정 결과에는 토큰이 없어서, 마지막 호출의 응답을 붙잡아 사진당 토큰을 남긴다.
        AtomicReference<VisionChatResponse> lastResponse = new AtomicReference<>();
        ChatCompletionClient client = request -> {
            VisionChatResponse response = claude.complete(request);
            lastResponse.set(response);
            return response;
        };

        VisionProviderProperties providerProperties = new VisionProviderProperties();
        providerProperties.setProvider(VisionProviderProperties.Provider.CLAUDE);
        PhotoViewClassifier classifier = new PhotoViewClassifier(client, new VisionImageLoader(new ImageResizer()),
                new ObjectMapper(), Mockito.mock(AiCallLogger.class), new PromptTemplateLoader(),
                providerProperties, properties);

        Path root = Path.of(dir);
        List<Path> photos = photos(root);
        Assumptions.assumeFalse(photos.isEmpty(), "정답 이름(side/front/back/top/outsole/other)으로 시작하는 사진이 없습니다.");
        System.out.printf("[면 판정 하네스] model=%s maxEdge=%d - %d장 시작%n",
                properties.getModel(), properties.getMaxEdge(), photos.size());

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < photos.size(); i++) {
            Path photo = photos.get(i);
            String name = root.relativize(photo).toString().replace('\\', '/');
            PhotoView expected = expectedView(photo);
            lastResponse.set(null);
            try {
                PhotoViewClassifier.Result result = classifier.classify(Files.readAllBytes(photo));
                VisionChatResponse response = lastResponse.get();
                rows.add(new Row(name, expected, result.view(), result.reason(), result.latencyMs(),
                        response.promptTokens(), response.completionTokens(), null));
                System.out.printf("  [%d/%d] %s - 정답 %s, 판정 %s, %dms%n", i + 1, photos.size(), name,
                        expected.value(), result.view().value(), result.latencyMs());
            } catch (RuntimeException e) {
                rows.add(new Row(name, expected, null, null, 0, 0, 0, e.getMessage()));
                System.out.printf("  [%d/%d] %s - 실패: %s%n", i + 1, photos.size(), name, e.getMessage());
            }
        }

        String report = report(properties, rows);
        System.out.println(report);
        Files.createDirectories(REPORT_DIRECTORY);
        String baseName = "photo-view-%s-%s-%dpx-%s".formatted(properties.getPromptVersion(), properties.getModel(),
                properties.getMaxEdge(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Files.writeString(REPORT_DIRECTORY.resolve(baseName + ".txt"), report, StandardCharsets.UTF_8);
        Files.writeString(REPORT_DIRECTORY.resolve(baseName + ".csv"), csv(rows), StandardCharsets.UTF_8);
    }

    private static List<Path> photos(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> IMAGE_EXTENSIONS.stream()
                            .anyMatch(ext -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ext)))
                    .filter(path -> expectedView(path) != null)
                    .sorted()
                    .toList();
        }
    }

    // 파일 이름이 정답 면 이름으로 시작해야 한다. "other"는 PhotoView.from의 기본값과 겹치므로 접두어로 직접 비교한다.
    static PhotoView expectedView(Path photo) {
        String name = photo.getFileName().toString().toLowerCase(Locale.ROOT);
        for (PhotoView view : PhotoView.values()) {
            if (name.startsWith(view.value())) {
                return view;
            }
        }
        return null;
    }

    static String report(PhotoViewProperties properties, List<Row> rows) {
        List<Row> answered = rows.stream().filter(row -> row.actual() != null).toList();
        StringBuilder text = new StringBuilder();
        text.append("사진 면 판정 하네스 - prompt=%s, model=%s, maxEdge=%d, 사진 %d장(실패 %d)%n%n".formatted(
                properties.getPromptVersion(), properties.getModel(), properties.getMaxEdge(),
                rows.size(), rows.size() - answered.size()));

        // 신발 사진(윗부분·밑창)은 칸 기준으로 센다. other(박스·라벨·신발 아님)는 "잘못 넣은 사진"을 잡는지로 따로 본다.
        long slotTotal = answered.stream().filter(row -> isSlot(row.expected())).count();
        long slotPass = answered.stream().filter(row -> isSlot(row.expected()))
                .filter(row -> PhotoViewCheck.matches(row.expected(), row.actual())).count();
        long outsoleTotal = answered.stream().filter(row -> row.expected() == PhotoView.OUTSOLE).count();
        long outsolePass = answered.stream().filter(row -> row.expected() == PhotoView.OUTSOLE)
                .filter(row -> row.actual() == PhotoView.OUTSOLE).count();
        // 밑창이 아닌데 밑창이라고 한 것 - 밑창 칸에 엉뚱한 사진을 넣어도 통과시키는 오판이다.
        long falseOutsole = answered.stream()
                .filter(row -> row.expected() != PhotoView.OUTSOLE && row.actual() == PhotoView.OUTSOLE).count();
        long exact = answered.stream().filter(row -> row.expected() == row.actual()).count();

        // 잘못 넣은 사진(other)을 신발 사진으로 보면 어느 칸에서든 그냥 통과된다.
        long otherTotal = answered.stream().filter(row -> row.expected() == PhotoView.OTHER).count();
        long otherCaught = answered.stream().filter(row -> row.expected() == PhotoView.OTHER)
                .filter(row -> row.actual() == PhotoView.OTHER).count();
        text.append("칸 기준 통과(윗부분 칸은 side/front/top/back 허용): %s%n".formatted(ratio(slotPass, slotTotal)));
        text.append("잘못 넣은 사진(other)을 잡아냄: %s%n".formatted(ratio(otherCaught, otherTotal)));
        text.append("밑창 사진을 밑창으로: %s%n".formatted(ratio(outsolePass, outsoleTotal)));
        text.append("밑창이 아닌 사진을 밑창으로(거짓 통과): %d장%n".formatted(falseOutsole));
        text.append("면 그대로 정확도: %s%n%n".formatted(ratio(exact, answered.size())));

        text.append("혼동표 (행 = 정답, 열 = 판정)%n".formatted());
        text.append("%-9s".formatted(""));
        for (PhotoView actual : PhotoView.values()) {
            text.append("%9s".formatted(actual.value()));
        }
        text.append('\n');
        for (PhotoView expected : PhotoView.values()) {
            Map<PhotoView, Integer> counts = new EnumMap<>(PhotoView.class);
            answered.stream().filter(row -> row.expected() == expected)
                    .forEach(row -> counts.merge(row.actual(), 1, Integer::sum));
            if (counts.isEmpty()) {
                continue;
            }
            text.append("%-9s".formatted(expected.value()));
            for (PhotoView actual : PhotoView.values()) {
                text.append("%9d".formatted(counts.getOrDefault(actual, 0)));
            }
            text.append('\n');
        }

        // 첫 호출은 클라이언트 생성·TLS 연결이 붙어 느리다. 운영은 워밍업으로 이 비용을 미리 치르므로 따로 본다.
        List<Long> latencies = answered.stream().skip(1).map(Row::latencyMs).sorted().toList();
        text.append("%n응답 시간(첫 호출 제외 %d건): 평균 %dms, 중앙값 %dms, p90 %dms, 최대 %dms%n".formatted(
                latencies.size(), average(latencies), percentile(latencies, 50), percentile(latencies, 90),
                latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1)));
        if (!answered.isEmpty()) {
            text.append("첫 호출: %dms%n".formatted(answered.get(0).latencyMs()));
        }
        text.append("사진당 토큰: 입력 평균 %d, 출력 평균 %d%n".formatted(
                average(answered.stream().map(row -> (long) row.promptTokens()).toList()),
                average(answered.stream().map(row -> (long) row.completionTokens()).toList())));

        text.append("%n틀린 사진%n".formatted());
        answered.stream().filter(row -> row.expected() != row.actual()).forEach(row ->
                text.append("  %s - 정답 %s, 판정 %s (%s)%s%n".formatted(row.file(), row.expected().value(),
                        row.actual().value(), row.reason(),
                        PhotoViewCheck.matches(row.expected(), row.actual()) ? " - 칸 기준은 통과" : "")));
        rows.stream().filter(row -> row.error() != null).forEach(row ->
                text.append("  %s - 실패: %s%n".formatted(row.file(), row.error())));
        return text.toString();
    }

    private static boolean isSlot(PhotoView view) {
        return view != PhotoView.OTHER;
    }

    private static String ratio(long hit, long total) {
        return total == 0 ? "-" : "%d/%d (%.0f%%)".formatted(hit, total, hit * 100.0 / total);
    }

    private static long average(List<Long> values) {
        return values.isEmpty() ? 0 : Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private static long percentile(List<Long> sorted, int percent) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percent / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static String csv(List<Row> rows) {
        StringBuilder csv = new StringBuilder("file,expected,actual,slotPass,latencyMs,promptTokens,completionTokens,reason,error\n");
        for (Row row : rows) {
            csv.append("%s,%s,%s,%s,%d,%d,%d,\"%s\",\"%s\"%n".formatted(row.file(), row.expected().value(),
                    row.actual() == null ? "" : row.actual().value(),
                    row.actual() != null && PhotoViewCheck.matches(row.expected(), row.actual()),
                    row.latencyMs(), row.promptTokens(), row.completionTokens(),
                    row.reason() == null ? "" : row.reason().replace("\"", "'"),
                    row.error() == null ? "" : row.error().replace("\"", "'")));
        }
        return csv.toString();
    }
}

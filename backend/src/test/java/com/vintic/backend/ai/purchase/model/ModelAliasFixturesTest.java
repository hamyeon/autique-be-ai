package com.vintic.backend.ai.purchase.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// 별칭 표 회귀 셋(purchase/model-alias-fixtures.json). Vision 표기·실제 매물 제목·감사에서 틀리던 표기·
// 잡히면 안 되는 표기를 한 번에 돌려, 별칭 한 줄을 고쳤을 때 다른 모델이 깨지는지 바로 보이게 한다.
// 하네스와 달리 바닥값이 아니라 전부 맞아야 한다 - 결정적인 규칙이라 틀린 건 곧 회귀다.
class ModelAliasFixturesTest {

    private static final String PATH = "purchase/model-alias-fixtures.json";

    record Case(String text, String expected, String kind, String note) {
    }

    record Document(String version, String description, List<Case> cases) {
    }

    private final ModelAliases aliases = new ModelAliases();

    @Test
    void 모든_표기가_기대한_모델_키로_접힌다() {
        List<String> failures = new ArrayList<>();
        for (Case c : load().cases()) {
            String actual = aliases.find(c.text()).map(m -> m.model().modelKey()).orElse(null);
            if (!Objects.equals(actual, c.expected())) {
                failures.add("[%s] \"%s\" -> 기대 %s, 실제 %s".formatted(c.kind(), c.text(), c.expected(), actual));
            }
        }

        assertThat(failures).as("별칭 표 회귀").isEmpty();
    }

    @Test
    void 카탈로그의_모든_모델이_Vision_표기_케이스를_가진다() {
        // 새 모델을 카탈로그에 넣으면 Vision 표기 케이스도 같이 넣어야 한다
        Set<String> covered = new HashSet<>();
        load().cases().stream().filter(c -> "vision".equals(c.kind())).forEach(c -> covered.add(c.expected()));
        Set<String> catalog = new HashSet<>();
        aliases.catalog().forEach(model -> catalog.add(model.modelKey()));

        assertThat(covered).containsAll(catalog);
    }

    private static Document load() {
        try (InputStream inputStream = new ClassPathResource(PATH).getInputStream()) {
            return new ObjectMapper().readValue(inputStream, Document.class);
        } catch (IOException e) {
            throw new IllegalStateException("별칭 회귀 셋을 읽지 못했습니다: " + PATH, e);
        }
    }
}

package com.vintic.backend.ai.purchase.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import com.vintic.backend.config.ClockConfig;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

// src/test/resources/purchase/goal-parse-fixtures.json 로더.
final class GoalParseHarnessFixtures {

    static final String PATH = "purchase/goal-parse-fixtures.json";

    // today: 상대 기한("3일 안에")의 기준일. 파서의 Clock을 이 날짜 정오(서울)로 고정해 돌린다.
    record Document(String version, String description, String today, List<GoalParseHarnessCase> cases) {

        Clock clock() {
            return Clock.fixed(LocalDate.parse(today).atTime(12, 0).atZone(ClockConfig.APP_ZONE).toInstant(),
                    ClockConfig.APP_ZONE);
        }
    }

    private GoalParseHarnessFixtures() {
    }

    static Document load() {
        ClassPathResource resource = new ClassPathResource(PATH);
        try (InputStream inputStream = resource.getInputStream()) {
            return new ObjectMapper().readValue(inputStream, Document.class);
        } catch (IOException e) {
            throw new IllegalStateException("하네스 픽스처를 읽지 못했습니다: " + PATH, e);
        }
    }
}

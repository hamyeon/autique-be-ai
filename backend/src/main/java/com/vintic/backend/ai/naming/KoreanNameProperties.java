package com.vintic.backend.ai.naming;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// application.yml의 naming.korean-name.* 값을 바인딩한다(KoreanNameTranslator 참고).
@Component
@ConfigurationProperties(prefix = "naming.korean-name")
@Getter
@Setter
public class KoreanNameProperties {

    // false면 번역 호출 없이 저장된 이름·크림 이름만 쓴다. 비용을 끊어야 할 때의 스위치다.
    private boolean translationEnabled = true;
    // 음차 규칙을 따르는 짧은 텍스트 작업이라 가장 빠른 모델이 기본이다(2026-11-03 실측 중앙값 1.6초).
    private String model = "claude-haiku-5-5";
    private String promptVersion = "v1";
    // 응답은 한국어 상품명 한 줄뿐이다. thinking 여유분은 클라이언트가 더한다.
    private int maxOutputTokens = 100;
}

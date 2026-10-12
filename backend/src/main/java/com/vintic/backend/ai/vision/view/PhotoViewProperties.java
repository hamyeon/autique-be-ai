package com.vintic.backend.ai.vision.view;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// application.yml의 vision.photo-view.* 값을 바인딩한다(PhotoViewClassifier 참고).
@Component
@ConfigurationProperties(prefix = "vision.photo-view")
@Getter
@Setter
public class PhotoViewProperties {

    // 사진을 넣자마자 답해야 해서 가장 빠른 모델이 기본이다. vision.provider와 다른 벤더의 모델이면 vision.model을 쓴다.
    private String model = "claude-haiku-5-5";
    private String promptVersion = "v1";
    // 면 구분에는 큰 해상도가 필요 없다. 이미지 토큰이 줄면 응답도 빨라진다.
    private int maxEdge = 512;
    // 응답은 view 하나와 8단어 이유뿐이다. thinking 여유분은 클라이언트가 더한다.
    private int maxOutputTokens = 100;
}

package com.vintic.backend.config;

import com.vintic.backend.ai.vision.agent.VisionStageProperties;
import com.vintic.backend.ai.vision.client.ClaudeChatClient;
import com.vintic.backend.ai.vision.client.VisionProviderProperties;
import com.vintic.backend.ai.vision.image.ImageResizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

// 배포 직후 첫 상품 등록이 느린 문제를 줄이려고 기동 직후 한 번 미리 돌려두는 작업들.
//
// 첫 요청이 떠안던 비용:
//   - ImageIO/Thumbnailator 첫 사용(플러그인 스캔, 클래스 로딩). API는 업로드 때, Worker는 단계별 축소 때 쓴다.
//   - S3 클라이언트 첫 호출(자격 증명 조회 - EC2면 인스턴스 메타데이터, TLS 연결). API의 사진 업로드가 기다린다.
//   - Claude 클라이언트 생성과 TLS 연결(ClaudeChatClient는 지연 생성이다). Worker의 Vision 분석이 기다린다.
//
// 기동을 늦추지 않게 별도 스레드에서 돌린다. 실패해도 로그만 남긴다 - 워밍업 때문에 서버가 안 뜨면 안 된다.
// 유료 호출은 하지 않는다. S3 HeadBucket은 권한이 없어 403이 나도 자격 증명 조회와 연결은 끝난 뒤라 목적은 달성한다.
//
// 테스트·로컬에서 외부 호출이 나가지 않도록 기본은 꺼두고, 배포 profile(api, worker)에서만 켠다.
@Component
@ConditionalOnProperty(prefix = "startup.warmup", name = "enabled", havingValue = "true")
@Slf4j
public class StartupWarmup {

    private final ImageResizer imageResizer;
    private final S3Client s3Client;
    private final ClaudeChatClient claudeChatClient;
    private final VisionProviderProperties providerProperties;
    private final VisionStageProperties stageProperties;
    private final String bucket;
    // Vision 분석을 실제로 하는 프로세스(Worker)에서만 Claude 연결을 연다. API에는 Claude 키가 없을 수 있다.
    private final boolean analysisConsumerEnabled;

    public StartupWarmup(ImageResizer imageResizer,
                         S3Client s3Client,
                         ClaudeChatClient claudeChatClient,
                         VisionProviderProperties providerProperties,
                         VisionStageProperties stageProperties,
                         @Value("${cloud.aws.s3.bucket}") String bucket,
                         @Value("${analysis.stream.consumer.enabled:false}") boolean analysisConsumerEnabled) {
        this.imageResizer = imageResizer;
        this.s3Client = s3Client;
        this.claudeChatClient = claudeChatClient;
        this.providerProperties = providerProperties;
        this.stageProperties = stageProperties;
        this.bucket = bucket;
        this.analysisConsumerEnabled = analysisConsumerEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread thread = new Thread(this::run, "startup-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    void run() {
        step("이미지 축소", this::warmImageResizer);
        step("S3 연결", () -> s3Client.headBucket(builder -> builder.bucket(bucket)));
        if (analysisConsumerEnabled && providerProperties.getProvider() == VisionProviderProperties.Provider.CLAUDE) {
            // 단계별로 다른 모델(실루엣·상태 Haiku 등)을 쓰므로 전부 조회해 모델 ID 오타도 기동 직후에 드러나게 한다.
            for (String model : visionModels()) {
                step("Claude 연결(" + model + ")", () -> claudeChatClient.warmUp(model));
            }
        }
    }

    private void warmImageResizer() throws IOException {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, ImageResizer.OUTPUT_FORMAT, out);
        imageResizer.resize(out.toByteArray(), 32);
    }

    private Set<String> visionModels() {
        Set<String> models = new LinkedHashSet<>();
        models.add(providerProperties.resolvedModel());
        for (VisionStageProperties.Stage stage : new VisionStageProperties.Stage[]{
                stageProperties.getSilhouette(), stageProperties.getLabel(), stageProperties.getCondition()}) {
            if (stage.getModel() != null && !stage.getModel().isBlank()) {
                models.add(stage.getModel());
            }
        }
        return models;
    }

    private void step(String name, WarmupStep step) {
        long startedAt = System.currentTimeMillis();
        try {
            step.run();
            log.info("워밍업 완료 - step={}, latencyMs={}", name, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.warn("워밍업 실패(무시하고 계속) - step={}, latencyMs={}, message={}",
                    name, System.currentTimeMillis() - startedAt, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface WarmupStep {
        void run() throws Exception;
    }
}

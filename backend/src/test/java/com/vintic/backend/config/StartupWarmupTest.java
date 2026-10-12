package com.vintic.backend.config;

import com.vintic.backend.ai.vision.agent.VisionStageProperties;
import com.vintic.backend.ai.vision.client.ClaudeChatClient;
import com.vintic.backend.ai.vision.client.VisionProviderProperties;
import com.vintic.backend.ai.vision.image.ImageResizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 워밍업이 무엇을 부르는지, 그리고 한 단계가 실패해도 나머지를 계속하는지 고정한다.
class StartupWarmupTest {

    private ImageResizer imageResizer;
    private S3Client s3Client;
    private ClaudeChatClient claudeChatClient;
    private VisionProviderProperties providerProperties;
    private VisionStageProperties stageProperties;

    @BeforeEach
    void setUp() {
        imageResizer = mock(ImageResizer.class);
        s3Client = mock(S3Client.class);
        claudeChatClient = mock(ClaudeChatClient.class);
        providerProperties = new VisionProviderProperties();
        providerProperties.setProvider(VisionProviderProperties.Provider.CLAUDE);
        providerProperties.setModel("claude-sonnet-5");
        stageProperties = new VisionStageProperties();
    }

    private StartupWarmup warmup(boolean analysisConsumerEnabled) {
        return new StartupWarmup(imageResizer, s3Client, claudeChatClient, providerProperties, stageProperties,
                "test-bucket", analysisConsumerEnabled);
    }

    @Test
    @SuppressWarnings("unchecked")
    void Worker에서는_이미지_축소_S3_단계별_Claude_모델을_모두_데운다() {
        stageProperties.getSilhouette().setModel("claude-haiku-5-5");
        stageProperties.getCondition().setModel("claude-haiku-5-5");

        warmup(true).run();

        verify(imageResizer).resize(any(byte[].class), anyInt());
        verify(s3Client).headBucket(any(Consumer.class));
        // 같은 모델은 한 번만 조회한다.
        verify(claudeChatClient).warmUp("claude-sonnet-5");
        verify(claudeChatClient).warmUp("claude-haiku-5-5");
        verify(claudeChatClient, times(2)).warmUp(anyString());
    }

    @Test
    void Vision_분석을_하지_않는_API_서버는_Claude를_부르지_않는다() {
        warmup(false).run();

        verify(imageResizer).resize(any(byte[].class), anyInt());
        verify(claudeChatClient, never()).warmUp(anyString());
    }

    @Test
    void provider가_OpenAI면_Claude를_부르지_않는다() {
        providerProperties.setProvider(VisionProviderProperties.Provider.OPENAI);

        warmup(true).run();

        verify(claudeChatClient, never()).warmUp(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 한_단계가_실패해도_예외를_올리지_않고_다음_단계를_계속한다() {
        doThrow(new RuntimeException("403")).when(s3Client).headBucket(any(Consumer.class));

        warmup(true).run();

        verify(claudeChatClient).warmUp("claude-sonnet-5");
    }
}

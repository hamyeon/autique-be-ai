package com.vintic.backend.ai.vision.view;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.observability.service.AiCallLogger;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.vision.client.ChatCompletionClient;
import com.vintic.backend.ai.vision.client.VisionChatRequest;
import com.vintic.backend.ai.vision.client.VisionChatResponse;
import com.vintic.backend.ai.vision.client.VisionProviderProperties;
import com.vintic.backend.ai.vision.image.ImageResizer;
import com.vintic.backend.ai.vision.image.VisionImageLoader;
import com.vintic.backend.common.exception.AiApiException;
import com.vintic.backend.common.exception.AiResponseFormatException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PhotoViewClassifierTest {

    private final AtomicReference<VisionChatRequest> lastRequest = new AtomicReference<>();
    private final AiCallLogger aiCallLogger = mock(AiCallLogger.class);

    private PhotoViewClassifier classifier(String responseJson, VisionProviderProperties.Provider provider) {
        ChatCompletionClient client = request -> {
            lastRequest.set(request);
            return new VisionChatResponse(responseJson, 300, 20, 800);
        };
        VisionProviderProperties providerProperties = new VisionProviderProperties();
        providerProperties.setProvider(provider);
        return new PhotoViewClassifier(client, new VisionImageLoader(new ImageResizer()), new ObjectMapper(),
                aiCallLogger, new PromptTemplateLoader(), providerProperties, new PhotoViewProperties());
    }

    @Test
    void 사진_한_장을_줄여_base64로_보내고_판정_결과를_돌려준다() throws IOException {
        PhotoViewClassifier classifier = classifier(
                "{\"view\":\"outsole\",\"reason\":\"tread pattern fills frame\"}", VisionProviderProperties.Provider.CLAUDE);

        PhotoViewClassifier.Result result = classifier.classify(jpeg(2000, 1000));

        assertThat(result.view()).isEqualTo(PhotoView.OUTSOLE);
        assertThat(result.reason()).isEqualTo("tread pattern fills frame");
        assertThat(result.latencyMs()).isEqualTo(800);
        VisionChatRequest request = lastRequest.get();
        assertThat(request.model()).isEqualTo("claude-haiku-5-5");
        assertThat(request.imageUrls()).singleElement().asString().startsWith("data:image/jpeg;base64,");
        assertThat(request.responseSchema().name()).isEqualTo("vision_photo_view_v2");
    }

    @Test
    void provider가_OpenAI면_Claude_모델_대신_vision_model을_쓴다() throws IOException {
        PhotoViewClassifier classifier = classifier(
                "{\"view\":\"side\",\"reason\":\"profile visible\"}", VisionProviderProperties.Provider.OPENAI);

        classifier.classify(jpeg(100, 100));

        assertThat(lastRequest.get().model()).isEqualTo("gpt-4o");
    }

    @Test
    void 벤더가_받지_않는_형식이면_호출하지_않고_실패한다() {
        PhotoViewClassifier classifier = classifier("{}", VisionProviderProperties.Provider.CLAUDE);

        assertThatThrownBy(() -> classifier.classify(new byte[]{1, 2, 3, 4}))
                .isInstanceOf(AiApiException.class);
        assertThat(lastRequest.get()).isNull();
    }

    @Test
    void 응답이_JSON이_아니면_형식_오류로_올린다() {
        PhotoViewClassifier classifier = classifier("not json", VisionProviderProperties.Provider.CLAUDE);

        assertThatThrownBy(() -> classifier.classify(jpeg(100, 100)))
                .isInstanceOf(AiResponseFormatException.class);
    }

    private static byte[] jpeg(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }
}

package com.vintic.backend.ai.naming;

import com.vintic.backend.common.exception.AiApiException;
import com.vintic.backend.product.service.MarketPriceDataLoader;
import com.vintic.backend.product.service.MarketPriceDataLoader.KreamProductName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KoreanProductNameResolverTest {

    private static final String KEY = "asics|novablast 6|black";

    @Mock
    private KoreanProductNameRepository repository;

    @Mock
    private MarketPriceDataLoader marketPriceDataLoader;

    @Mock
    private KoreanNameTranslator translator;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final KoreanNameProperties properties = new KoreanNameProperties();

    private KoreanProductNameResolver sut;

    @BeforeEach
    void setUp() {
        sut = new KoreanProductNameResolver(repository, marketPriceDataLoader, translator, properties, transactionManager);
    }

    @Test
    void 대소문자와_공백이_달라도_같은_신발로_본다() {
        assertThat(KoreanProductNameResolver.lookupKey(" Asics ", "Novablast  6", "BLACK")).isEqualTo(KEY);
        assertThat(KoreanProductNameResolver.lookupKey("Asics", "Novablast 6", null)).isEqualTo("asics|novablast 6|");
    }

    @Test
    void 저장된_이름이_있으면_크림_조회나_번역_없이_그대로_쓴다() {
        when(repository.findByLookupKey(KEY))
                .thenReturn(Optional.of(KoreanProductName.create(KEY, "아식스 노바블라스트 6 블랙", KoreanProductName.Source.LLM)));

        assertThat(sut.resolve("Asics", "Novablast 6", "Black")).isEqualTo("아식스 노바블라스트 6 블랙");
        verifyNoInteractions(marketPriceDataLoader, translator);
    }

    @Test
    void 크림에_한국어_상품명이_있으면_번역하지_않고_저장한다() {
        when(repository.findByLookupKey(any())).thenReturn(Optional.empty());
        // 초기 수기 입력 행은 상품명이 영문이다 - 한글이 있는 이름만 쓴다
        when(marketPriceDataLoader.loadKreamProductNames()).thenReturn(List.of(
                new KreamProductName("Nike", "Dunk Low", "Panda", "Nike Dunk Low Panda"),
                new KreamProductName("Nike", "V2K Run", "Black Anthracite", "나이키 V2K 런 블랙 앤트러사이트")));

        // Vision 값과 CSV 값의 대소문자가 달라도 같은 신발로 찾는다
        assertThat(sut.resolve("NIKE", "V2K Run", "black anthracite")).isEqualTo("나이키 V2K 런 블랙 앤트러사이트");
        verify(translator, never()).translate(any(), any(), any());
        assertThat(savedName().getSource()).isEqualTo(KoreanProductName.Source.KREAM);
    }

    @Test
    void 크림_상품명이_영문뿐이면_번역한다() {
        when(repository.findByLookupKey(any())).thenReturn(Optional.empty());
        when(marketPriceDataLoader.loadKreamProductNames()).thenReturn(List.of(
                new KreamProductName("Nike", "Dunk Low", "Panda", "Nike Dunk Low Panda")));
        when(translator.translate("Nike", "Dunk Low", "Panda")).thenReturn("나이키 덩크 로우 판다");

        assertThat(sut.resolve("Nike", "Dunk Low", "Panda")).isEqualTo("나이키 덩크 로우 판다");
        assertThat(savedName().getSource()).isEqualTo(KoreanProductName.Source.LLM);
    }

    @Test
    void 크림에_없으면_번역해서_저장한다() {
        when(repository.findByLookupKey(KEY)).thenReturn(Optional.empty());
        when(marketPriceDataLoader.loadKreamProductNames()).thenReturn(List.of());
        when(translator.translate("Asics", "Novablast 6", "Black")).thenReturn("아식스 노바블라스트 6 블랙");

        assertThat(sut.resolve("Asics", "Novablast 6", "Black")).isEqualTo("아식스 노바블라스트 6 블랙");
        KoreanProductName saved = savedName();
        assertThat(saved.getLookupKey()).isEqualTo(KEY);
        assertThat(saved.getSource()).isEqualTo(KoreanProductName.Source.LLM);
    }

    @Test
    void 번역이_꺼져_있으면_크림에_없는_신발은_null이다() {
        properties.setTranslationEnabled(false);
        when(repository.findByLookupKey(KEY)).thenReturn(Optional.empty());
        when(marketPriceDataLoader.loadKreamProductNames()).thenReturn(List.of());

        assertThat(sut.resolve("Asics", "Novablast 6", "Black")).isNull();
        verifyNoInteractions(translator);
    }

    @Test
    void 번역이_실패해도_예외를_던지지_않고_null을_돌려준다() {
        when(repository.findByLookupKey(KEY)).thenReturn(Optional.empty());
        when(marketPriceDataLoader.loadKreamProductNames()).thenReturn(List.of());
        when(translator.translate(any(), any(), any())).thenThrow(new AiApiException("Claude API 오류 (status=529)"));

        assertThat(sut.resolve("Asics", "Novablast 6", "Black")).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void 동시에_같은_신발을_저장하다_충돌하면_먼저_저장된_이름을_따른다() {
        when(repository.findByLookupKey(KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(KoreanProductName.create(KEY, "아식스 노바블라스트 6 블랙", KoreanProductName.Source.LLM)));
        when(marketPriceDataLoader.loadKreamProductNames()).thenReturn(List.of());
        when(translator.translate(any(), any(), any())).thenReturn("아식스 노바 블라스트 6 블랙");
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate lookup_key"));

        assertThat(sut.resolve("Asics", "Novablast 6", "Black")).isEqualTo("아식스 노바블라스트 6 블랙");
    }

    @Test
    void 모델명이_없으면_아무것도_조회하지_않는다() {
        assertThat(sut.resolve("Asics", null, "Black")).isNull();
        assertThat(sut.findSaved("Asics", " ", "Black")).isNull();
        verifyNoInteractions(repository, marketPriceDataLoader, translator);
    }

    private KoreanProductName savedName() {
        ArgumentCaptor<KoreanProductName> captor = ArgumentCaptor.forClass(KoreanProductName.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}

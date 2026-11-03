package com.vintic.backend.ai.naming;

import com.vintic.backend.common.util.ProductDisplayName;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 한국어 상품명이 실제 MySQL에 저장·재사용되는지 확인한다: korean_product_name 테이블과 products.name_ko 컬럼이
// 만들어지고, 크림 CSV 이름은 번역 없이 저장되며, 번역은 신발마다 한 번만 불린다. 번역기는 목으로 바꾼다(실제 호출은
// KoreanNameHarnessTest).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class KoreanProductNameMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("LOCAL_REDIS_HOST", redis::getHost);
        registry.add("LOCAL_REDIS_PORT", () -> redis.getMappedPort(6379));
        registry.add("analysis.stream.consumer.enabled", () -> "false");
    }

    @MockitoBean
    private KoreanNameTranslator translator;

    @Autowired
    private KoreanProductNameResolver resolver;

    @Autowired
    private KoreanProductNameRepository repository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void 크림_CSV에_한국어_상품명이_있으면_번역_없이_저장된다() {
        assertThat(resolver.resolve("Nike", "V2K Run", "Black Anthracite")).isEqualTo("나이키 V2K 런 블랙 앤트러사이트");

        KoreanProductName saved = repository.findByLookupKey("nike|v2k run|black anthracite").orElseThrow();
        assertThat(saved.getSource()).isEqualTo(KoreanProductName.Source.KREAM);
        verify(translator, never()).translate("Nike", "V2K Run", "Black Anthracite");
    }

    @Test
    void 번역은_신발마다_한_번만_불리고_이후엔_저장된_이름을_쓴다() {
        when(translator.translate("Asics", "Novablast 6", "Black")).thenReturn("아식스 노바블라스트 6 블랙");

        assertThat(resolver.resolve("Asics", "Novablast 6", "Black")).isEqualTo("아식스 노바블라스트 6 블랙");
        assertThat(resolver.resolve("ASICS", "Novablast 6", " black ")).isEqualTo("아식스 노바블라스트 6 블랙");
        assertThat(resolver.findSaved("asics", "novablast 6", "BLACK")).isEqualTo("아식스 노바블라스트 6 블랙");

        verify(translator, times(1)).translate(any(), any(), any());
    }

    @Test
    void 상품에_한국어_상품명이_저장되고_표시명에_쓰인다() {
        User seller = userRepository.save(User.register("seller-korean-name@vintic.local", "판매자", null));
        Product product = productRepository.save(new Product(seller, List.of("https://bucket/a.jpg"),
                "Asics", "Novablast 6", "Black", 270, "A", "FULL",
                null, null, null, 120000, null, null, null, "아식스 노바블라스트 6 블랙"));

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getNameKo()).isEqualTo("아식스 노바블라스트 6 블랙");
        assertThat(ProductDisplayName.name(reloaded)).isEqualTo("아식스 노바블라스트 6 블랙");
        assertThat(ProductDisplayName.subName(reloaded)).isEqualTo("Asics Novablast 6 Black");
    }
}

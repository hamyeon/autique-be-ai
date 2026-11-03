package com.vintic.backend.common.util;

import com.vintic.backend.product.domain.Product;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductDisplayNameTest {

    @Test
    void 한국어_상품명이_있으면_큰_글씨는_한국어_작은_글씨는_영문_전체명이다() {
        Product product = product("아식스 노바블라스트 6 블랙");

        assertThat(ProductDisplayName.name(product)).isEqualTo("아식스 노바블라스트 6 블랙");
        assertThat(ProductDisplayName.subName(product)).isEqualTo("Asics Novablast 6 Black");
    }

    @Test
    void 한국어_상품명이_없으면_예전_규칙_그대로다() {
        Product product = product(null);

        assertThat(ProductDisplayName.name(product)).isEqualTo("Asics Novablast 6 Black");
        assertThat(ProductDisplayName.subName(product)).isEqualTo("Novablast 6");
    }

    private Product product(String nameKo) {
        return new Product(null, List.of(), "Asics", "Novablast 6", "Black", 270, "A", null,
                null, null, null, 120000, null, null, null, nameKo);
    }
}

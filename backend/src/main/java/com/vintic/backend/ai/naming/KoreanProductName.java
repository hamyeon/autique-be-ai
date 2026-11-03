package com.vintic.backend.ai.naming;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDateTime;

// 신발(브랜드·모델명·색상) 하나에 붙는 한국어 상품명. 같은 신발은 한 번만 만들고 이 표에서 다시 꺼낸다 -
// 번역을 매번 하면 비용·지연이 들고, 같은 신발이 상품마다 다른 표기로 보이게 된다.
//
// lookupKey는 세 값을 소문자·공백 정리 후 이어 붙인 값이다(KoreanProductNameResolver.lookupKey).
// Vision 결과와 판매자가 등록 때 보낸 값이 대소문자만 달라도 같은 행을 찾게 한다.
@Entity
@Table(name = "korean_product_name")
@Getter
public class KoreanProductName {

    public enum Source {
        KREAM, // KREAM CSV(data/kream_normalized.csv)의 한국어 상품명을 그대로 가져옴
        LLM    // KoreanNameTranslator가 만든 이름
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lookup_key", nullable = false, unique = true, length = 500)
    private String lookupKey;

    @Column(name = "name_ko", nullable = false, length = 300)
    private String nameKo;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private Source source;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected KoreanProductName() {
    }

    public static KoreanProductName create(String lookupKey, String nameKo, Source source) {
        KoreanProductName name = new KoreanProductName();
        name.lookupKey = lookupKey;
        name.nameKo = nameKo;
        name.source = source;
        name.createdAt = LocalDateTime.now();
        return name;
    }
}

package com.vintic.backend.ai.naming;

import com.vintic.backend.product.service.MarketPriceDataLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

// 신발의 한국어 상품명을 정한다. 찾는 순서:
//   1) korean_product_name에 저장된 이름 - 한 번 정해진 신발은 항상 같은 이름이 나온다.
//   2) KREAM CSV(data/kream_normalized.csv)의 한국어 상품명 - 크림 표기가 사용자에게 가장 익숙하다.
//      가격 계산과 같은 파일이다(kream_price 테이블은 채워지지 않는다). 다만 한국어 상품명이 있는 컬러웨이가 아직 18개뿐이다.
//   3) KoreanNameTranslator(Haiku) - 나머지 대부분이 여기로 온다.
// 2·3의 결과는 1에 저장한다.
//
// 이름은 화면 표시용 부가 정보다. 어떤 단계가 실패해도 예외를 던지지 않고 null을 돌려준다 - 분석·등록을
// 이 기능 때문에 실패시키지 않는다. null이면 화면은 지금처럼 영문명만 보여준다.
@Service
@Slf4j
public class KoreanProductNameResolver {

    private final KoreanProductNameRepository repository;
    private final MarketPriceDataLoader marketPriceDataLoader;
    private final KoreanNameTranslator translator;
    private final KoreanNameProperties properties;
    // 저장은 호출부 트랜잭션과 분리한다. 동시에 같은 신발을 저장하다 unique 위반이 나도 상품 등록
    // 트랜잭션이 rollback-only로 오염되지 않게 하기 위해서다.
    private final TransactionTemplate saveTransaction;
    // lookupKey → 크림 한국어 상품명. CSV는 배포 때만 바뀌므로 처음 필요할 때 한 번 읽어 둔다.
    private volatile Map<String, String> kreamNames;

    public KoreanProductNameResolver(KoreanProductNameRepository repository,
                                     MarketPriceDataLoader marketPriceDataLoader,
                                     KoreanNameTranslator translator,
                                     KoreanNameProperties properties,
                                     PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.marketPriceDataLoader = marketPriceDataLoader;
        this.translator = translator;
        this.properties = properties;
        this.saveTransaction = new TransactionTemplate(transactionManager);
        this.saveTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // 저장된 이름만 본다(DB 조회 한 번). 폴링되는 분석 상태 조회처럼 외부 호출을 하면 안 되는 곳에서 쓴다.
    public String findSaved(String brand, String modelName, String color) {
        if (isBlank(modelName)) {
            return null;
        }
        try {
            return repository.findByLookupKey(lookupKey(brand, modelName, color))
                    .map(KoreanProductName::getNameKo)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("저장된 한국어 상품명을 읽지 못했습니다. brand={}, modelName={}, color={}", brand, modelName, color, e);
            return null;
        }
    }

    // 저장된 이름이 없으면 크림 → 번역 순으로 만들어 저장한다. 번역까지 가면 1~2초 걸린다.
    public String resolve(String brand, String modelName, String color) {
        if (isBlank(modelName)) {
            return null;
        }
        String key = lookupKey(brand, modelName, color);
        try {
            Optional<KoreanProductName> saved = repository.findByLookupKey(key);
            if (saved.isPresent()) {
                return saved.get().getNameKo();
            }

            String kreamName = findKreamName(brand, modelName, color);
            if (kreamName != null) {
                return save(key, kreamName, KoreanProductName.Source.KREAM);
            }

            if (!properties.isTranslationEnabled()) {
                return null;
            }
            String translated = translator.translate(brand, modelName, color);
            return translated == null ? null : save(key, translated, KoreanProductName.Source.LLM);
        } catch (RuntimeException e) {
            log.warn("한국어 상품명을 정하지 못해 영문명만 씁니다. brand={}, modelName={}, color={}", brand, modelName, color, e);
            return null;
        }
    }

    private String findKreamName(String brand, String modelName, String color) {
        Map<String, String> names = kreamNames;
        if (names == null) {
            names = new HashMap<>();
            for (MarketPriceDataLoader.KreamProductName row : marketPriceDataLoader.loadKreamProductNames()) {
                if (containsHangul(row.productName())) {
                    names.putIfAbsent(lookupKey(row.brand(), row.model(), row.colorway()), row.productName());
                }
            }
            kreamNames = names;
        }
        return names.get(lookupKey(brand, modelName, color));
    }

    private String save(String key, String nameKo, KoreanProductName.Source source) {
        try {
            saveTransaction.executeWithoutResult(status -> repository.save(KoreanProductName.create(key, nameKo, source)));
            return nameKo;
        } catch (DataIntegrityViolationException e) {
            // 같은 신발을 다른 요청이 먼저 저장했다. 먼저 저장된 이름을 따라야 표기가 하나로 유지된다.
            return repository.findByLookupKey(key).map(KoreanProductName::getNameKo).orElse(nameKo);
        }
    }

    // 대소문자·연속 공백 차이는 같은 신발로 본다. 색상이 없으면 빈 칸으로 둔다.
    static String lookupKey(String brand, String modelName, String color) {
        return normalize(brand) + "|" + normalize(modelName) + "|" + normalize(color);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean containsHangul(String value) {
        return value.codePoints().anyMatch(cp -> cp >= 0xAC00 && cp <= 0xD7A3);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

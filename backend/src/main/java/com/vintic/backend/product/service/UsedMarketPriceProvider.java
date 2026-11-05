package com.vintic.backend.product.service;

import com.vintic.backend.ai.purchase.model.BrandAliases;
import com.vintic.backend.ai.purchase.model.ModelAliases;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// 모델별 중고 실거래 시세를 제공한다.
//
// 당근마켓·후르츠패밀리 매물에서 산출한 값이다(crawler/calibration/build_used_market_prices.py).
// 우리 서비스는 중고 경매라, 같은 모델의 중고 실거래가 있으면 "새제품가 x 상태계수"라는
// 추정보다 이쪽이 곧은 근거다(#86). 추천가 계산에서 이 시세가 1순위이고, 매칭이 없으면
// 기존 KREAM/eBay 방식으로 넘어간다.
//
// 알려진 한계: 당근 가격은 호가이지 체결가가 아니다. 안 팔린 매물도 섞여 있으므로
// 실제 체결가는 이보다 낮을 수 있다. 중앙값과 IQR을 함께 제공해 분포를 숨기지 않는다.
@Component
public class UsedMarketPriceProvider {

    private static final String CSV_PATH = "data/used_market_prices.csv";
    private static final String COLOR_CSV_PATH = "data/used_market_prices_by_color.csv";
    private static final String BRAND_CSV_PATH = "data/used_market_prices_by_brand.csv";

    private final List<UsedMarketPrice> rows = new ArrayList<>();
    private final List<ColorPrice> colorRows = new ArrayList<>();
    private final List<BrandPrice> brandRows = new ArrayList<>();
    private final ModelAliases modelAliases;

    public UsedMarketPriceProvider() {
        this(new ModelAliases());
    }

    @Autowired
    public UsedMarketPriceProvider(ModelAliases modelAliases) {
        this.modelAliases = modelAliases;
        load();
        loadColors();
        loadBrands();
    }

    /**
     * 브랜드 전체 매물의 중고 시세 - 모델을 특정 못 했을 때의 마지막 폴백.
     * 모델 미특정 매물까지 포함한 넓은 분포라 모델 시세가 있으면 절대 이것을 쓰지 않는다.
     */
    public Optional<BrandPrice> findBrand(String brand) {
        String requestBrand = canonicalBrand(brand);
        if (requestBrand.isEmpty()) {
            return Optional.empty();
        }
        return brandRows.stream()
                .filter(row -> row.brandNorm().equals(requestBrand))
                .findFirst();
    }

    /**
     * 요청한 브랜드·모델의 중고 시세. 없으면 empty - 호출부는 기존 방식으로 폴백한다.
     *
     * <p>별칭 표(model_aliases.csv)를 먼저 보고, 모르는 표기일 때만 포함 검사로 넘어간다.
     * 포함 검사만 쓰면 시세 CSV의 행 순서(표본 많은 순)가 승부를 갈라서
     * "Air Jordan 11"이 jordan1에, "Chuck Taylor All Star 70"이 chucktaylor에 붙었다.
     * 별칭 표는 긴 표기부터 검사하므로 더 구체적인 모델이 이긴다. 또 model_key와 글자가
     * 안 겹치는 표기("All Star" -> chucktaylor, "990v6" -> nb990, 한글 모델명)도 살린다.
     */
    public Optional<UsedMarketPrice> find(String brand, String modelName) {
        String requestBrand = canonicalBrand(brand);
        if (requestBrand.isEmpty()) {
            return Optional.empty();
        }
        String aliasKey = aliasModelKey(brand, modelName, requestBrand);
        if (aliasKey != null) {
            Optional<UsedMarketPrice> byAlias = rows.stream()
                    .filter(row -> row.brandNorm().equals(requestBrand))
                    .filter(row -> row.modelNorm().equals(aliasKey))
                    .findFirst();
            if (byAlias.isPresent()) {
                return byAlias;
            }
        }
        return findDirect(requestBrand, normalize(modelName));
    }

    private Optional<UsedMarketPrice> findDirect(String requestBrand, String requestModel) {
        if (requestModel.isEmpty()) {
            return Optional.empty();
        }
        // 표기가 흔들려도 매칭되게 양방향 포함을 본다("Jordan 1 Retro High" <-> "jordan1").
        // 브랜드는 정확히 같아야 한다 - "574"가 다른 브랜드 요청에 붙으면 안 된다.
        return rows.stream()
                .filter(row -> row.brandNorm().equals(requestBrand))
                .filter(row -> row.modelNorm().contains(requestModel)
                        || requestModel.contains(row.modelNorm()))
                .findFirst();
    }

    // 별칭 표가 아는 모델이면 그 model_key. 별칭의 브랜드가 요청 브랜드와 다르면 버린다 -
    // 브랜드가 정확히 같아야 한다는 규칙은 별칭 경로에서도 그대로다.
    private String aliasModelKey(String brand, String modelName, String requestBrand) {
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        return modelAliases.find(brand + " " + modelName)
                .map(ModelAliases.Match::model)
                .filter(model -> normalize(model.brand()).equals(requestBrand))
                .map(model -> normalize(model.modelKey()))
                .orElse(null);
    }

    /**
     * 같은 모델의 "같은 색상 계열" 시세 (#93). 색상 표기는 계열로 정규화해 비교하므로
     * "Wolf Gray"/"그레이"/"회색"이 전부 grey 버킷에 붙는다.
     * 색상 버킷이 없으면 empty - 호출부는 모델 시세로 폴백한다(정확도가 나빠지는 경로 없음).
     */
    public Optional<ColorPrice> findColor(String brand, String modelName, String color) {
        Optional<String> key = ColorFamilies.colorKey(color);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        // 모델 시세와 같은 행에 붙어야 한다 - 모델은 find()로 정하고, 색상 버킷은 그 키로 찾는다.
        // (직접 포함 검사를 따로 하면 모델 시세는 별칭으로 찾고 색상은 못 찾는 엇갈림이 생긴다)
        return find(brand, modelName)
                .flatMap(market -> colorRows.stream()
                        .filter(row -> row.brandNorm().equals(market.brandNorm()))
                        .filter(row -> row.modelNorm().equals(market.modelNorm()))
                        .filter(row -> row.colorFamily().equals(key.get()))
                        .findFirst());
    }

    // 브랜드 표기를 시세 CSV 표기로 접는다("Jordan" -> Nike, "ONITSUKA" -> Onitsuka Tiger).
    // 브랜드 별칭 표가 모르는 브랜드면 원문 그대로 비교한다.
    private String canonicalBrand(String brand) {
        return normalize(BrandAliases.canonical(brand).orElse(brand));
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private void load() {
        try {
            ClassPathResource resource = new ClassPathResource(CSV_PATH);
            if (!resource.exists()) {
                // 시세 파일이 없어도 가격 계산은 기존 방식으로 돌아야 한다
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                reader.readLine(); // 헤더
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] c = line.split(",", -1);
                    if (c.length < 7) {
                        continue;
                    }
                    try {
                        rows.add(new UsedMarketPrice(
                                c[0].trim(),
                                normalize(c[0]),
                                normalize(c[1]),
                                c[2].trim(),
                                Integer.parseInt(c[3].trim()),
                                Integer.parseInt(c[4].trim()),
                                Integer.parseInt(c[5].trim()),
                                Integer.parseInt(c[6].trim())
                        ));
                    } catch (NumberFormatException e) {
                        // 한 줄이 깨져도 나머지는 읽는다
                    }
                }
            }
        } catch (Exception e) {
            // 읽기 실패 = 중고 시세 없음으로 동작. 가격 계산 자체는 실패하면 안 된다.
            rows.clear();
        }
    }

    // 색상 CSV도 파일이 없거나 깨져도 색상 시세만 포기하고 동작한다
    private void loadColors() {
        try {
            ClassPathResource resource = new ClassPathResource(COLOR_CSV_PATH);
            if (!resource.exists()) {
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                reader.readLine(); // 헤더
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] c = line.split(",", -1);
                    if (c.length < 7) {
                        continue;
                    }
                    try {
                        colorRows.add(new ColorPrice(
                                normalize(c[0]),
                                normalize(c[1]),
                                c[2].trim(),
                                Integer.parseInt(c[3].trim()),
                                Integer.parseInt(c[4].trim()),
                                Integer.parseInt(c[5].trim()),
                                Integer.parseInt(c[6].trim())
                        ));
                    } catch (NumberFormatException e) {
                        // 한 줄이 깨져도 나머지는 읽는다
                    }
                }
            }
        } catch (Exception e) {
            colorRows.clear();
        }
    }

    // 브랜드 CSV도 파일이 없거나 깨져도 브랜드 시세만 포기하고 동작한다
    private void loadBrands() {
        try {
            ClassPathResource resource = new ClassPathResource(BRAND_CSV_PATH);
            if (!resource.exists()) {
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                reader.readLine(); // 헤더
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] c = line.split(",", -1);
                    if (c.length < 5) {
                        continue;
                    }
                    try {
                        brandRows.add(new BrandPrice(
                                c[0].trim(),
                                normalize(c[0]),
                                Integer.parseInt(c[1].trim()),
                                Integer.parseInt(c[2].trim()),
                                Integer.parseInt(c[3].trim()),
                                Integer.parseInt(c[4].trim())
                        ));
                    } catch (NumberFormatException e) {
                        // 한 줄이 깨져도 나머지는 읽는다
                    }
                }
            }
        } catch (Exception e) {
            brandRows.clear();
        }
    }

    public record UsedMarketPrice(
            String brand,
            String brandNorm,
            String modelNorm,
            String modelDisplay,
            int listingCount,
            int medianPrice,
            int q1Price,
            int q3Price
    ) {
    }

    /** (모델, 색상 계열) 시세 버킷. 표본이 서는 버킷만 CSV에 실린다(#93). */
    public record ColorPrice(
            String brandNorm,
            String modelNorm,
            String colorFamily,
            int listingCount,
            int medianPrice,
            int q1Price,
            int q3Price
    ) {
    }

    /** 브랜드 전체 시세. 모델·종류가 섞인 분포라 정확도가 가장 낮은 근거다. */
    public record BrandPrice(
            String brand,
            String brandNorm,
            int listingCount,
            int medianPrice,
            int q1Price,
            int q3Price
    ) {
    }
}

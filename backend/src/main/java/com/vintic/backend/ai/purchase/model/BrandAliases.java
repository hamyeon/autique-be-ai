package com.vintic.backend.ai.purchase.model;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// 브랜드 표기(한/영/약칭)를 시세 CSV의 brand 값으로 접는다.
//
// 모델을 못 알아봐도 브랜드만이라도 잡히면 pre-filter가 브랜드로 후보를 좁힐 수 있다.
// ModelAliases와 마찬가지로 긴 표기부터 검사한다 ("뉴발란스"가 "뉴발"보다 먼저).
public final class BrandAliases {

    public record Match(String brand, String matchedAlias) {
    }

    private record Alias(String raw, String normalized, String brand) {
    }

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("나이키", "Nike"), Map.entry("nike", "Nike"), Map.entry("조던", "Nike"), Map.entry("jordan", "Nike"),
            Map.entry("아디다스", "Adidas"), Map.entry("adidas", "Adidas"),
            // 이지(Yeezy)는 아디다스 라인이다. "이지" 단독은 흔한 어미라 숫자 표기만.
            Map.entry("이지350", "Adidas"), Map.entry("이지 350", "Adidas"), Map.entry("yeezy", "Adidas"),
            Map.entry("뉴발란스", "New Balance"), Map.entry("뉴발", "New Balance"),
            Map.entry("new balance", "New Balance"), Map.entry("newbalance", "New Balance"),
            Map.entry("아식스", "Asics"), Map.entry("asics", "Asics"),
            Map.entry("버켄스탁", "Birkenstock"), Map.entry("birkenstock", "Birkenstock"),
            Map.entry("컨버스", "Converse"), Map.entry("converse", "Converse"),
            Map.entry("크록스", "Crocs"), Map.entry("crocs", "Crocs"),
            Map.entry("닥터마틴", "Dr. Martens"), Map.entry("닥마", "Dr. Martens"),
            Map.entry("dr martens", "Dr. Martens"), Map.entry("dr. martens", "Dr. Martens"),
            Map.entry("drmartens", "Dr. Martens"),
            Map.entry("헌터", "Hunter"), Map.entry("hunter", "Hunter"),
            Map.entry("락피쉬", "Rockfish"), Map.entry("락피시", "Rockfish"), Map.entry("rockfish", "Rockfish"),
            Map.entry("살로몬", "Salomon"), Map.entry("salomon", "Salomon"),
            Map.entry("스케쳐스", "Skechers"), Map.entry("스케처스", "Skechers"), Map.entry("skechers", "Skechers"),
            Map.entry("어그", "UGG"), Map.entry("ugg", "UGG"),
            Map.entry("반스", "Vans"), Map.entry("vans", "Vans"),
            // 골든구스는 4차 확대부터 시세 카탈로그 브랜드다(goldengoose). 이름만 같은 아디다스 슈퍼스타와는
            // 여전히 브랜드 불일치로 갈린다.
            Map.entry("골든구스", "Golden Goose"), Map.entry("golden goose", "Golden Goose"),
            // 4차 카탈로그 확대로 들어온 브랜드. "킨" 단독은 "스킨·치킨"에 붙어 영문만 둔다.
            Map.entry("keen", "Keen"),
            Map.entry("핏플랍", "FitFlop"), Map.entry("fitflop", "FitFlop"),
            Map.entry("락포트", "Rockport"), Map.entry("rockport", "Rockport"),
            // 아래에는 시세 카탈로그 밖 브랜드도 섞여 있다. 이름만 같은 다른 브랜드 매물(알든 990 구두)을
            // 브랜드 불일치로 걸러내기 위해 둔다.
            Map.entry("알든", "Alden"), Map.entry("alden", "Alden"),
            Map.entry("푸마", "Puma"), Map.entry("puma", "Puma"),
            Map.entry("리복", "Reebok"), Map.entry("reebok", "Reebok"),
            Map.entry("오니츠카", "Onitsuka Tiger"), Map.entry("onitsuka", "Onitsuka Tiger"),
            Map.entry("호카", "Hoka"), Map.entry("hoka", "Hoka"),
            Map.entry("미즈노", "Mizuno"), Map.entry("mizuno", "Mizuno"),
            Map.entry("디아도라", "Diadora"), Map.entry("diadora", "Diadora"),
            Map.entry("오트리", "Autry"), Map.entry("autry", "Autry"),
            Map.entry("마르지엘라", "Maison Margiela"), Map.entry("margiela", "Maison Margiela"),
            Map.entry("커먼프로젝트", "Common Projects"), Map.entry("common projects", "Common Projects"),
            Map.entry("발렌시아가", "Balenciaga"), Map.entry("balenciaga", "Balenciaga"),
            Map.entry("구찌", "Gucci"), Map.entry("gucci", "Gucci"),
            Map.entry("프라다", "Prada"), Map.entry("prada", "Prada"),
            Map.entry("루이비통", "Louis Vuitton"), Map.entry("louis vuitton", "Louis Vuitton")
    );

    private static final Set<String> KNOWN_BRANDS = Set.copyOf(ALIASES.values());

    private static final List<Alias> LONGEST_FIRST = ALIASES.entrySet().stream()
            .map(entry -> new Alias(entry.getKey(), ModelAliases.normalize(entry.getKey()), entry.getValue()))
            .sorted(Comparator.comparingInt((Alias a) -> a.normalized().length()).reversed())
            .toList();

    private BrandAliases() {
    }

    public static Optional<Match> find(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = ModelAliases.normalize(text);
        for (Alias alias : LONGEST_FIRST) {
            if (normalized.contains(alias.normalized())) {
                return Optional.of(new Match(alias.brand(), alias.raw()));
            }
        }
        return Optional.empty();
    }

    // 텍스트에 언급된 브랜드 전부. 모델 별칭의 requires_brand·브랜드 일치 판정에 쓴다.
    public static Set<String> mentionedBrands(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String normalized = ModelAliases.normalize(text);
        Set<String> brands = new LinkedHashSet<>();
        for (Alias alias : LONGEST_FIRST) {
            if (normalized.contains(alias.normalized())) {
                brands.add(alias.brand());
            }
        }
        return brands;
    }

    public static boolean isKnownBrand(String brand) {
        return brand != null && KNOWN_BRANDS.contains(brand.trim());
    }

    // LLM이 낸 브랜드 문자열을 표준 표기로 접는다. 모르는 브랜드면 비운다 - 시세 CSV에 없는
    // 브랜드는 pre-filter에서 어차피 아무것도 못 찾는다.
    public static Optional<String> canonical(String brand) {
        if (brand == null || brand.isBlank()) {
            return Optional.empty();
        }
        if (KNOWN_BRANDS.contains(brand.trim())) {
            return Optional.of(brand.trim());
        }
        return find(brand).map(Match::brand);
    }
}

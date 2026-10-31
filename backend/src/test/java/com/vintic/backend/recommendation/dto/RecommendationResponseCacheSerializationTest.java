package com.vintic.backend.recommendation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// #142: Fallback 추천(RecommendationResponse)은 Redis 캐시(CacheConfig, GenericJackson2JsonRedisSerializer)에
// JSON으로 저장된다. 카드 필드를 추가한 뒤에도 (1) 새 형태가 그대로 왕복되고 (2) 배포 직전에 캐시된 예전 형태
// (카드 필드 없음)도 읽히는지 확인한다 - 읽지 못하면 TTL(60초) 동안 추천 API가 실패할 수 있다.
class RecommendationResponseCacheSerializationTest {

    private final GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer();

    private RecommendationResponse cachedFallback() {
        return new RecommendationResponse(false, "Fallback", List.of(
                RecommendationResponse.RecommendedAuction.withoutCard(
                        10L, 100L, "Nike", "Dunk Low", "Panda", 270, 100_000L, "2026-11-01T20:00", null)));
    }

    @Test
    void 카드_필드가_추가된_형태가_캐시_직렬화로_왕복된다() {
        RecommendationResponse original = cachedFallback();

        Object restored = serializer.deserialize(serializer.serialize(original));

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void 카드_필드가_없던_예전_캐시_값도_읽힌다() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode tree = mapper.readTree(serializer.serialize(cachedFallback()));
        // GenericJackson2JsonRedisSerializer는 리스트를 ["타입", [요소...]] 형태로 쓴다.
        JsonNode items = tree.get("items").get(1);
        for (JsonNode item : items) {
            ((ObjectNode) item).remove(List.of(
                    "name", "thumbnailUrl", "status", "grade", "startPrice", "likeCount", "isLiked"));
        }
        assertThat(items.get(0).has("name")).isFalse();

        Object restored = serializer.deserialize(mapper.writeValueAsBytes(tree));

        assertThat(restored).isEqualTo(cachedFallback());
    }
}

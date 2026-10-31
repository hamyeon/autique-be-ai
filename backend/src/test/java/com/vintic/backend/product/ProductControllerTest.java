package com.vintic.backend.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.analyze.service.ProductPricingService;
import com.vintic.backend.product.dto.CalculatePriceRequest;
import com.vintic.backend.product.dto.CalculatePriceResponse;
import com.vintic.backend.product.dto.CreateProductRequest;
import com.vintic.backend.product.dto.ProductResponse;
import com.vintic.backend.product.service.ProductRegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Controller는 요청 수신/응답 반환만 담당하므로, ProductPricingService를 모킹해
// /api/products/calculate-price의 요청/응답 형식이 그대로인지만 확인하는 얇은 회귀 테스트
@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductPricingService productPricingService;

    @MockitoBean
    private ProductRegistrationService productRegistrationService;

    @Test
    void 가격계산_API_요청_응답_형식이_유지된다() throws Exception {
        CalculatePriceRequest request = new CalculatePriceRequest(
                1L, "Nike", "Air Jordan 1 Retro High OG", "Chicago Lost and Found", 270, "B", "PARTIAL"
        );

        CalculatePriceResponse.MatchedMarketPrice matchedPrice = new CalculatePriceResponse.MatchedMarketPrice(
                "KREAM", "Nike", "Air Jordan 1 Retro High OG", "Chicago Lost and Found",
                270, "DS", "FULL", 400000, "https://kream.co.kr/products/1"
        );
        CalculatePriceResponse response = new CalculatePriceResponse(
                300000, 350000, 400000, 300000, 285000, 315000,
                "285,000원 ~ 315,000원", "테스트 사유",
                List.of(matchedPrice), List.of()
        );

        when(productPricingService.calculatePrice(any(), eq(1L))).thenReturn(response);

        mockMvc.perform(post("/api/products/calculate-price")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .requestAttr("currentUserId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.recommendedPrice").value(300000))
                .andExpect(jsonPath("$.data.baseMarketPrice").value(350000))
                .andExpect(jsonPath("$.data.priceRange").value("285,000원 ~ 315,000원"))
                .andExpect(jsonPath("$.data.reason").value("테스트 사유"))
                .andExpect(jsonPath("$.data.kreamMatches[0].source").value("KREAM"))
                .andExpect(jsonPath("$.data.kreamMatches[0].price").value(400000))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void 상품_등록시_currentUserId_요청속성이_seller로_전달된다() throws Exception {
        OffsetDateTime startAt = OffsetDateTime.now().plusHours(2);
        OffsetDateTime endAt = startAt.plusHours(1);
        CreateProductRequest request = new CreateProductRequest(
                1L,
                List.of("https://example.com/a.jpg", "https://example.com/b.jpg", "https://example.com/c.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명", null,
                10000L, startAt, endAt
        );
        ProductResponse response = new ProductResponse(
                1L, 1L, request.imageUrls(), request.brand(), request.modelName(), request.color(),
                request.size(), request.conditionGrade(), request.componentStatus(), request.recommendedPrice(),
                request.baseMarketPrice(), request.priceRange(), request.sellingPrice(), request.reason(),
                request.sellerDescription(), null,
                100L, "SCHEDULED", request.auctionStartPrice(), 5000L, startAt, endAt
        );
        when(productRegistrationService.createProduct(any(), eq(1L))).thenReturn(response);

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .requestAttr("currentUserId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sellerId").value(1));

        verify(productRegistrationService).createProduct(any(), eq(1L));
    }

    @Test
    void 상품_등록_요청에서_imageUrls를_생략해도_검증을_통과한다() throws Exception {
        // #141: 이미지는 분석 세션 기준으로 서버가 정한다 - 요청 본문에 imageUrls 키 자체가 없어도 400이 아니다.
        OffsetDateTime startAt = OffsetDateTime.now().plusHours(2);
        OffsetDateTime endAt = startAt.plusHours(1);
        String body = """
                {
                  "analysisId": 1,
                  "brand": "Nike", "modelName": "Dunk Low", "color": "Panda", "size": 270,
                  "conditionGrade": "B", "componentStatus": "PARTIAL",
                  "recommendedPrice": 300000, "sellingPrice": 290000,
                  "auctionStartPrice": 10000,
                  "auctionStartAt": "%s", "auctionEndAt": "%s"
                }
                """.formatted(startAt, endAt);
        ProductResponse response = new ProductResponse(
                1L, 1L, List.of("https://example.com/a.jpg"), "Nike", "Dunk Low", "Panda",
                270, "B", "PARTIAL", 300000, null, null, 290000, null, null, null,
                100L, "SCHEDULED", 10000L, 5000L, startAt, endAt
        );
        when(productRegistrationService.createProduct(any(), eq(1L))).thenReturn(response);

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .requestAttr("currentUserId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void 상품_등록_요청의_imageUrls가_3개_미만이면_400을_반환한다() throws Exception {
        OffsetDateTime startAt = OffsetDateTime.now().plusHours(2);
        CreateProductRequest request = new CreateProductRequest(
                1L, List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, null, null, 290000, null, null, null,
                10000L, startAt, startAt.plusHours(1)
        );

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .requestAttr("currentUserId", 1L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40001));
    }

    @Test
    void 상품_등록_요청의_상태_설명이_1000자를_넘으면_400을_반환한다() throws Exception {
        OffsetDateTime startAt = OffsetDateTime.now().plusHours(2);
        CreateProductRequest request = new CreateProductRequest(
                1L, null,
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, null, null, 290000, null, null, "가".repeat(1001),
                10000L, startAt, startAt.plusHours(1)
        );

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .requestAttr("currentUserId", 1L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40001));

        verify(productRegistrationService, never()).createProduct(any(), any());
    }
}

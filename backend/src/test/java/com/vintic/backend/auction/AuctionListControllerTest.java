package com.vintic.backend.auction;

import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.dto.AuctionCardResponse;
import com.vintic.backend.auction.dto.AuctionListResponse;
import com.vintic.backend.auction.dto.PopularAuctionsResponse;
import com.vintic.backend.auction.service.AuctionListQueryService;
import com.vintic.backend.auth.jwt.JwtProperties;
import com.vintic.backend.auth.jwt.JwtTokenProvider;
import com.vintic.backend.auth.security.JwtAuthenticationEntryPoint;
import com.vintic.backend.auth.security.JwtAuthenticationFilter;
import com.vintic.backend.common.exception.InvalidPageRequestException;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.config.JwtSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #142: 경매 목록 API의 요청 파라미터·응답 형태와, dev/prod SecurityFilterChain 아래에서의 비로그인 허용·
// JWT 식별을 확인한다(JwtAnonymousEndpointSecurityTest와 같은 구성). 조회 로직은 AuctionListQueryServiceTest가 본다.
@WebMvcTest(AuctionListController.class)
@ActiveProfiles("dev")
@Import({
        JwtSecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtTokenProvider.class, JwtProperties.class, ClockConfig.class
})
@TestPropertySource(properties = "jwt.secret=auction-list-controller-test-secret-32-bytes!!")
class AuctionListControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private AuctionListQueryService auctionListQueryService;

    private static AuctionCardResponse card() {
        return new AuctionCardResponse(30L, 20L, "Nike", "Nike Dunk Low Panda", "https://signed.example.com/a.jpg",
                AuctionStatus.LIVE, "A", 100000L, 125000L, 3, true);
    }

    // ===== GET /api/auctions/popular =====

    @Test
    void 인기_경매는_토큰_없이_조회되고_limit_기본값은_4다() throws Exception {
        when(auctionListQueryService.getPopular(4, null)).thenReturn(new PopularAuctionsResponse(List.of(card())));

        mockMvc.perform(get("/api/auctions/popular"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].auctionId").value(30))
                .andExpect(jsonPath("$.data.items[0].productId").value(20))
                .andExpect(jsonPath("$.data.items[0].brand").value("Nike"))
                .andExpect(jsonPath("$.data.items[0].name").value("Nike Dunk Low Panda"))
                .andExpect(jsonPath("$.data.items[0].thumbnailUrl").value("https://signed.example.com/a.jpg"))
                .andExpect(jsonPath("$.data.items[0].status").value("LIVE"))
                .andExpect(jsonPath("$.data.items[0].grade").value("A"))
                .andExpect(jsonPath("$.data.items[0].startPrice").value(100000))
                .andExpect(jsonPath("$.data.items[0].currentPrice").value(125000))
                .andExpect(jsonPath("$.data.items[0].likeCount").value(3))
                .andExpect(jsonPath("$.data.items[0].isLiked").value(true));

        verify(auctionListQueryService).getPopular(4, null);
    }

    @Test
    void 인기_경매는_유효한_토큰의_userId로_조회되고_X_User_Id는_무시된다() throws Exception {
        when(auctionListQueryService.getPopular(eq(4), eq(5L))).thenReturn(new PopularAuctionsResponse(List.of()));
        String token = jwtTokenProvider.issueAccessToken(5L);

        mockMvc.perform(get("/api/auctions/popular")
                        .header("Authorization", "Bearer " + token)
                        .header("X-User-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());

        verify(auctionListQueryService).getPopular(4, 5L);
    }

    @Test
    void 인기_경매는_X_User_Id만_보내면_익명으로_처리된다() throws Exception {
        when(auctionListQueryService.getPopular(4, null)).thenReturn(new PopularAuctionsResponse(List.of()));

        mockMvc.perform(get("/api/auctions/popular").header("X-User-Id", "999"))
                .andExpect(status().isOk());

        verify(auctionListQueryService).getPopular(4, null);
    }

    @Test
    void 인기_경매_limit_범위_오류는_400_40001이다() throws Exception {
        when(auctionListQueryService.getPopular(eq(0), isNull()))
                .thenThrow(new InvalidPageRequestException("limit은 1 이상 20 이하여야 합니다. 요청값: 0"));

        mockMvc.perform(get("/api/auctions/popular").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(40001));
    }

    @Test
    void 인기_경매_limit이_숫자가_아니면_400_40001이고_서비스를_호출하지_않는다() throws Exception {
        mockMvc.perform(get("/api/auctions/popular").param("limit", "four"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40001));

        verifyNoInteractions(auctionListQueryService);
    }

    @Test
    void 유효하지_않은_토큰이면_비로그인_허용_API여도_401이다() throws Exception {
        mockMvc.perform(get("/api/auctions/popular").header("Authorization", "Bearer garbage-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40101));
    }

    // ===== GET /api/auctions =====

    @Test
    void 기본_목록은_토큰_없이_조회되고_page_0_size_20이_기본값이다() throws Exception {
        when(auctionListQueryService.getList(0, 20, null))
                .thenReturn(new AuctionListResponse(List.of(card()), 0, 20, true));

        mockMvc.perform(get("/api/auctions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].auctionId").value(30))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        verify(auctionListQueryService).getList(0, 20, null);
    }

    @Test
    void 기본_목록은_토큰의_userId와_요청한_page_size로_조회된다() throws Exception {
        when(auctionListQueryService.getList(2, 10, 5L)).thenReturn(new AuctionListResponse(List.of(), 2, 10, false));
        String token = jwtTokenProvider.issueAccessToken(5L);

        mockMvc.perform(get("/api/auctions").param("page", "2").param("size", "10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.hasNext").value(false));

        verify(auctionListQueryService).getList(2, 10, 5L);
    }

    @Test
    void 기본_목록_size_범위_오류는_400_40001이다() throws Exception {
        when(auctionListQueryService.getList(0, 51, null))
                .thenThrow(new InvalidPageRequestException("size는 1 이상 50 이하여야 합니다. 요청값: 51"));

        mockMvc.perform(get("/api/auctions").param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40001));
    }

    @Test
    void 기본_목록_page가_숫자가_아니면_400_40001이다() throws Exception {
        mockMvc.perform(get("/api/auctions").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40001));

        verify(auctionListQueryService, never()).getList(anyInt(), anyInt(), isNull());
    }
}

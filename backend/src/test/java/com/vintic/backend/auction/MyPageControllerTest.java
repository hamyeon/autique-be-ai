package com.vintic.backend.auction;

import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.dto.AuctionCardResponse;
import com.vintic.backend.auction.dto.AuctionListResponse;
import com.vintic.backend.auction.service.MyAuctionQueryService;
import com.vintic.backend.auth.jwt.JwtProperties;
import com.vintic.backend.auth.jwt.JwtTokenProvider;
import com.vintic.backend.auth.security.JwtAuthenticationEntryPoint;
import com.vintic.backend.auth.security.JwtAuthenticationFilter;
import com.vintic.backend.common.exception.InvalidPageRequestException;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.config.JwtSecurityConfig;
import com.vintic.backend.user.MyProfileController;
import com.vintic.backend.user.dto.MyProfileResponse;
import com.vintic.backend.user.service.MyProfileQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #143: 마이페이지 API(GET /api/me, /api/me/auctions, /api/me/likes)가 dev/prod SecurityFilterChain 아래에서
// 로그인 필수이고, JWT의 userId로만 조회하며(X-User-Id 무시), 요청 파라미터를 서비스에 그대로 넘기는지 본다.
@WebMvcTest(controllers = {MyProfileController.class, MyAuctionController.class})
@ActiveProfiles("dev")
@Import({
        JwtSecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtTokenProvider.class, JwtProperties.class, ClockConfig.class
})
@TestPropertySource(properties = "jwt.secret=my-page-controller-test-secret-32-bytes-min!!")
class MyPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MyProfileQueryService myProfileQueryService;

    @MockitoBean
    private MyAuctionQueryService myAuctionQueryService;

    private String bearer(long userId) {
        return "Bearer " + jwtTokenProvider.issueAccessToken(userId);
    }

    private static AuctionListResponse oneCard() {
        return new AuctionListResponse(List.of(new AuctionCardResponse(30L, 20L, "Nike", "Nike Dunk Low Panda",
                "https://signed.example.com/a.jpg", AuctionStatus.ENDED, "A", 10000L, 25000L, 2, true)), 0, 20, false);
    }

    // ===== 인증 =====

    @Test
    void 토큰이_없으면_마이페이지_API_모두_401_40101이다() throws Exception {
        for (String url : List.of("/api/me", "/api/me/auctions", "/api/me/selling-auctions", "/api/me/likes")) {
            mockMvc.perform(get(url))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value(40101));
        }
        verifyNoInteractions(myProfileQueryService, myAuctionQueryService);
    }

    @Test
    void X_User_Id만_보내면_401이다() throws Exception {
        mockMvc.perform(get("/api/me").header("X-User-Id", "5"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(myProfileQueryService);
    }

    // ===== GET /api/me =====

    @Test
    void 내_정보는_JWT_사용자로_조회되고_다른_X_User_Id는_무시된다() throws Exception {
        when(myProfileQueryService.getMyProfile(5L)).thenReturn(new MyProfileResponse(5L, "jane", "jane@vintic.local"));

        mockMvc.perform(get("/api/me").header("Authorization", bearer(5L)).header("X-User-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(5))
                .andExpect(jsonPath("$.data.nickname").value("jane"))
                .andExpect(jsonPath("$.data.email").value("jane@vintic.local"));

        verify(myProfileQueryService).getMyProfile(5L);
    }

    @Test
    void 이메일이_없는_사용자는_email이_null로_나온다() throws Exception {
        when(myProfileQueryService.getMyProfile(6L)).thenReturn(new MyProfileResponse(6L, "회원-a1b2c3d4", null));

        mockMvc.perform(get("/api/me").header("Authorization", bearer(6L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("회원-a1b2c3d4"))
                .andExpect(jsonPath("$.data.email").isEmpty());
    }

    // ===== GET /api/me/auctions =====

    @Test
    void 나의_경매는_state_기본값_current와_page_0_size_20으로_조회된다() throws Exception {
        when(myAuctionQueryService.getMyParticipatedAuctions(5L, "current", 0, 20)).thenReturn(oneCard());

        mockMvc.perform(get("/api/me/auctions").header("Authorization", bearer(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].auctionId").value(30))
                .andExpect(jsonPath("$.data.items[0].status").value("ENDED"))
                .andExpect(jsonPath("$.data.items[0].currentPrice").value(25000))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        verify(myAuctionQueryService).getMyParticipatedAuctions(5L, "current", 0, 20);
    }

    @Test
    void 나의_경매는_요청한_state_page_size로_조회된다() throws Exception {
        when(myAuctionQueryService.getMyParticipatedAuctions(5L, "past", 1, 10))
                .thenReturn(new AuctionListResponse(List.of(), 1, 10, false));

        mockMvc.perform(get("/api/me/auctions").param("state", "past").param("page", "1").param("size", "10")
                        .header("Authorization", bearer(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());

        verify(myAuctionQueryService).getMyParticipatedAuctions(5L, "past", 1, 10);
    }

    @Test
    void 나의_경매_state가_잘못되면_400_40001이다() throws Exception {
        when(myAuctionQueryService.getMyParticipatedAuctions(5L, "all", 0, 20))
                .thenThrow(new InvalidPageRequestException("state는 current 또는 past여야 합니다. 요청값: all"));

        mockMvc.perform(get("/api/me/auctions").param("state", "all").header("Authorization", bearer(5L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40001));
    }

    @Test
    void 나의_경매_size가_숫자가_아니면_400_40001이다() throws Exception {
        mockMvc.perform(get("/api/me/auctions").param("size", "ten").header("Authorization", bearer(5L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40001));
        verifyNoInteractions(myAuctionQueryService);
    }

    // ===== GET /api/me/selling-auctions (#147) =====

    @Test
    void 판매_경매는_JWT_사용자로_state_current_page_0_size_20_기본값으로_조회된다() throws Exception {
        when(myAuctionQueryService.getMySellingAuctions(5L, "current", 0, 20)).thenReturn(oneCard());

        mockMvc.perform(get("/api/me/selling-auctions").header("Authorization", bearer(5L)).header("X-User-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].auctionId").value(30))
                .andExpect(jsonPath("$.data.items[0].productId").value(20))
                .andExpect(jsonPath("$.data.items[0].brand").value("Nike"))
                .andExpect(jsonPath("$.data.items[0].name").value("Nike Dunk Low Panda"))
                .andExpect(jsonPath("$.data.items[0].thumbnailUrl").value("https://signed.example.com/a.jpg"))
                .andExpect(jsonPath("$.data.items[0].status").value("ENDED"))
                .andExpect(jsonPath("$.data.items[0].grade").value("A"))
                .andExpect(jsonPath("$.data.items[0].startPrice").value(10000))
                .andExpect(jsonPath("$.data.items[0].currentPrice").value(25000))
                .andExpect(jsonPath("$.data.items[0].likeCount").value(2))
                .andExpect(jsonPath("$.data.items[0].isLiked").value(true))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        verify(myAuctionQueryService).getMySellingAuctions(5L, "current", 0, 20);
    }

    @Test
    void 판매_경매는_요청한_state_page_size로_조회된다() throws Exception {
        when(myAuctionQueryService.getMySellingAuctions(5L, "past", 2, 10))
                .thenReturn(new AuctionListResponse(List.of(), 2, 10, false));

        mockMvc.perform(get("/api/me/selling-auctions").param("state", "past").param("page", "2").param("size", "10")
                        .header("Authorization", bearer(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 판매_경매_state가_잘못되면_400_40001이다() throws Exception {
        when(myAuctionQueryService.getMySellingAuctions(5L, "sold", 0, 20))
                .thenThrow(new InvalidPageRequestException("state는 current 또는 past여야 합니다. 요청값: sold"));

        mockMvc.perform(get("/api/me/selling-auctions").param("state", "sold").header("Authorization", bearer(5L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40001));
    }

    @Test
    void 판매_경매_page가_숫자가_아니면_400_40001이다() throws Exception {
        mockMvc.perform(get("/api/me/selling-auctions").param("page", "first").header("Authorization", bearer(5L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40001));
        verifyNoInteractions(myAuctionQueryService);
    }

    // ===== GET /api/me/likes =====

    @Test
    void 관심_목록은_JWT_사용자로_조회된다() throws Exception {
        when(myAuctionQueryService.getMyLikedAuctions(5L, 0, 20)).thenReturn(oneCard());

        mockMvc.perform(get("/api/me/likes").header("Authorization", bearer(5L)).header("X-User-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].isLiked").value(true));

        verify(myAuctionQueryService).getMyLikedAuctions(5L, 0, 20);
    }

    @Test
    void 관심_목록_page_범위_오류는_400_40001이다() throws Exception {
        when(myAuctionQueryService.getMyLikedAuctions(5L, -1, 20))
                .thenThrow(new InvalidPageRequestException("page는 0 이상이어야 합니다. 요청값: -1"));

        mockMvc.perform(get("/api/me/likes").param("page", "-1").header("Authorization", bearer(5L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40001));
    }
}

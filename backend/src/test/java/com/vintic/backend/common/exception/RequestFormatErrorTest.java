package com.vintic.backend.common.exception;

import com.vintic.backend.auth.jwt.JwtProperties;
import com.vintic.backend.auth.jwt.JwtTokenProvider;
import com.vintic.backend.auth.security.JwtAuthenticationEntryPoint;
import com.vintic.backend.auth.security.JwtAuthenticationFilter;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.config.JwtSecurityConfig;
import com.vintic.backend.notification.NotificationController;
import com.vintic.backend.notification.service.NotificationCommandService;
import com.vintic.backend.notification.service.NotificationQueryService;
import com.vintic.backend.purchasegoal.PurchaseGoalController;
import com.vintic.backend.purchasegoal.service.PurchaseGoalCommandService;
import com.vintic.backend.purchasegoal.service.PurchaseGoalQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #147: 요청 형식 오류(본문 해석·타입 변환·메서드·Content-Type)가 500이 아니라 400/405/415로 응답하는지 본다.
// dev/prod SecurityFilterChain + 유효한 JWT를 붙여 실제 인증 필터를 통과한 요청으로 확인한다 - 인증 실패(401)를
// 형식 오류 처리로 오인하지 않기 위해서다. 컨트롤러 서비스는 호출되지 않아야 한다(요청 해석 단계에서 끝남).
@WebMvcTest(controllers = {PurchaseGoalController.class, NotificationController.class})
@ActiveProfiles("dev")
@Import({
        JwtSecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtTokenProvider.class, JwtProperties.class, ClockConfig.class
})
@TestPropertySource(properties = "jwt.secret=request-format-error-test-secret-32-bytes-min!!")
class RequestFormatErrorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private PurchaseGoalCommandService purchaseGoalCommandService;

    @MockitoBean
    private PurchaseGoalQueryService purchaseGoalQueryService;

    @MockitoBean
    private NotificationQueryService notificationQueryService;

    @MockitoBean
    private NotificationCommandService notificationCommandService;

    private String bearer() {
        return "Bearer " + jwtTokenProvider.issueAccessToken(5L);
    }

    // 공통 응답 형식(success=false, data=null, error.code/message)이고 내부 예외 원문이 섞이지 않는다.
    private static void assertCommonError(ResultActions result, int code, String message) throws Exception {
        result.andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").value(message))
                .andExpect(content().string(not(containsString("서버 내부 오류"))))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("JSON parse error"))));
    }

    @Test
    void 잘못된_JSON_본문은_400_40001이다() throws Exception {
        ResultActions result = mockMvc.perform(post("/api/purchase-goals")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest());

        assertCommonError(result, 40001, "요청 본문 형식이 올바르지 않습니다.");
        verifyNoInteractions(purchaseGoalCommandService);
    }

    @Test
    void 잘못된_날짜_형식은_400_40001이다() throws Exception {
        ResultActions result = mockMvc.perform(post("/api/purchase-goals")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelQuery":"뉴발란스 990","minCondition":"A","hardMaxAmount":150000,"deadline":"tomorrow"}
                                """))
                .andExpect(status().isBadRequest());

        assertCommonError(result, 40001, "요청 본문 형식이 올바르지 않습니다.");
        result.andExpect(content().string(not(containsString("tomorrow"))));
        verifyNoInteractions(purchaseGoalCommandService);
    }

    @Test
    void 숫자_필드에_문자를_보내면_400_40001이다() throws Exception {
        ResultActions result = mockMvc.perform(post("/api/purchase-goals")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelQuery":"뉴발란스 990","minCondition":"A","hardMaxAmount":"many",
                                 "deadline":"2099-01-01T00:00:00+09:00"}
                                """))
                .andExpect(status().isBadRequest());

        assertCommonError(result, 40001, "요청 본문 형식이 올바르지 않습니다.");
    }

    @Test
    void 경로_변수_타입이_맞지_않으면_400_40001이다() throws Exception {
        ResultActions result = mockMvc.perform(get("/api/purchase-goals/abc").header("Authorization", bearer()))
                .andExpect(status().isBadRequest());

        assertCommonError(result, 40001, "요청 값의 형식이 올바르지 않습니다.");
        verifyNoInteractions(purchaseGoalQueryService);
    }

    @Test
    void 쿼리_파라미터_타입이_맞지_않으면_400_40001이다() throws Exception {
        ResultActions result = mockMvc.perform(get("/api/notifications").param("page", "abc")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());

        assertCommonError(result, 40001, "요청 값의 형식이 올바르지 않습니다.");
        verifyNoInteractions(notificationQueryService);
    }

    @Test
    void 지원하지_않는_메서드는_405_40501과_Allow_헤더를_준다() throws Exception {
        ResultActions result = mockMvc.perform(put("/api/purchase-goals/1").header("Authorization", bearer()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(header().string("Allow", containsString("DELETE")));

        assertCommonError(result, 40501, "지원하지 않는 HTTP 메서드입니다.");
    }

    @Test
    void 지원하지_않는_Content_Type은_415_41501이다() throws Exception {
        ResultActions result = mockMvc.perform(post("/api/purchase-goals")
                        .header("Authorization", bearer())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType());

        assertCommonError(result, 41501, "지원하지 않는 Content-Type입니다.");
        verifyNoInteractions(purchaseGoalCommandService);
    }

    // ===== 기존 동작 유지 =====

    @Test
    void Bean_Validation_오류는_기존처럼_필드_메시지와_40001이다() throws Exception {
        mockMvc.perform(post("/api/purchase-goals")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"minCondition":"A","hardMaxAmount":150000,"deadline":"2099-01-01T00:00:00+09:00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40001))
                .andExpect(jsonPath("$.error.message").value("모델 설명은 필수입니다."));
    }

    @Test
    void 토큰이_없으면_형식_오류보다_인증_오류가_먼저다() throws Exception {
        mockMvc.perform(post("/api/purchase-goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40101));
        mockMvc.perform(put("/api/purchase-goals/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40101));
    }
}

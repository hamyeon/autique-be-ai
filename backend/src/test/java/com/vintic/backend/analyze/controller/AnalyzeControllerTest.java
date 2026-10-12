package com.vintic.backend.analyze.controller;

import com.vintic.backend.analyze.domain.PhotoSlot;
import com.vintic.backend.analyze.dto.AnalysisStatusResponse;
import com.vintic.backend.analyze.dto.AnalyzeAcceptedResponse;
import com.vintic.backend.analyze.dto.AnalyzeCancelResponse;
import com.vintic.backend.analyze.dto.PhotoUploadResponse;
import com.vintic.backend.analyze.service.PhotoUploadService;
import com.vintic.backend.analyze.service.ProductAnalyzeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 비동기 분석 API(202 Accepted + taskId 발급, 상태 폴링)의 요청/응답 형식을 확인하는 회귀 테스트
@WebMvcTest(AnalyzeController.class)
class AnalyzeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductAnalyzeService productAnalyzeService;

    @MockitoBean
    private PhotoUploadService photoUploadService;

    // #135 --------------------------------------------------------------------------------

    @Test
    void 사진_업로드는_200과_판정_결과를_반환한다() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "outsole.jpg", "image/jpeg", new byte[]{1, 2, 3});
        PhotoUploadResponse response = new PhotoUploadResponse(123L, "OUTSOLE", "MISMATCH", "SIDE",
                "밑창이 보이게 신발을 뒤집어 찍어주세요.");
        when(photoUploadService.upload(org.mockito.ArgumentMatchers.any(), eq(PhotoSlot.OUTSOLE), eq(2L)))
                .thenReturn(response);

        mockMvc.perform(multipart("/api/products/photos")
                        .file(image)
                        .param("slot", "OUTSOLE")
                        .requestAttr("currentUserId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.photoId").value(123))
                .andExpect(jsonPath("$.data.slot").value("OUTSOLE"))
                .andExpect(jsonPath("$.data.check").value("MISMATCH"))
                .andExpect(jsonPath("$.data.detectedView").value("SIDE"))
                .andExpect(jsonPath("$.data.message").value("밑창이 보이게 신발을 뒤집어 찍어주세요."));
    }

    @Test
    void photoIds_분석_요청은_202와_taskId를_반환한다() throws Exception {
        AnalyzeAcceptedResponse response = new AnalyzeAcceptedResponse(5L, "QUEUED");
        when(productAnalyzeService.submitForAnalysisByPhotoIds(List.of(1L, 2L, 3L), 2L)).thenReturn(response);

        mockMvc.perform(post("/api/products/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoIds\":[1,2,3]}")
                        .requestAttr("currentUserId", 2L))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.analysisId").value(5))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @Test
    void 분석_요청은_202와_taskId를_반환한다() throws Exception {
        MockMultipartFile image = new MockMultipartFile("images", "shoe.jpg", "image/jpeg", new byte[]{1, 2, 3});

        AnalyzeAcceptedResponse response = new AnalyzeAcceptedResponse(1L, "QUEUED");
        when(productAnalyzeService.submitForAnalysis(anyList(), eq(2L))).thenReturn(response);

        mockMvc.perform(multipart("/api/products/analyze").file(image).requestAttr("currentUserId", 2L))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.analysisId").value(1))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void 상태_조회는_taskId로_분석_진행상황을_반환한다() throws Exception {
        AnalysisStatusResponse response = new AnalysisStatusResponse(
                1L,
                "AWAITING_USER_CONFIRMATION",
                List.of("https://bucket.s3.amazonaws.com/shoe.jpg"),
                null,
                null,
                "Nike",
                "Air Jordan 1 Retro High OG",
                "Chicago Lost and Found",
                270,
                true,
                "사용감이 거의 없습니다.",
                "B",
                List.of(),
                List.of(),
                0.82,
                true,
                List.of("2단계 size: 사이즈 라벨이 사진에 없습니다."),
                null,
                null
        );
        when(productAnalyzeService.getStatus(1L, 2L)).thenReturn(response);

        mockMvc.perform(get("/api/products/analyze/{taskId}", 1L).requestAttr("currentUserId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.analysisId").value(1))
                .andExpect(jsonPath("$.data.status").value("AWAITING_USER_CONFIRMATION"))
                .andExpect(jsonPath("$.data.brand").value("Nike"))
                .andExpect(jsonPath("$.data.modelName").value("Air Jordan 1 Retro High OG"))
                .andExpect(jsonPath("$.data.conditionGrade").value("B"))
                // 사용자에게 무엇을 확인받아야 하는지가 응답에 실려야 한다
                .andExpect(jsonPath("$.data.needsUserConfirmation").value(true))
                .andExpect(jsonPath("$.data.warnings[0]").value("2단계 size: 사이즈 라벨이 사진에 없습니다."))
                .andExpect(jsonPath("$.data.boxIncluded").value(true))
                .andExpect(jsonPath("$.data.confidence").value(0.82))
                // 판단 근거는 폴링 응답에 싣지 않는다
                .andExpect(jsonPath("$.data.evidence").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void 취소_요청은_CANCELLED_상태를_반환한다() throws Exception {
        AnalyzeCancelResponse response = new AnalyzeCancelResponse(1L, "CANCELLED");
        when(productAnalyzeService.cancel(1L, 2L)).thenReturn(response);

        mockMvc.perform(post("/api/products/analyze/{taskId}/cancel", 1L).requestAttr("currentUserId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.analysisId").value(1))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }
}

package com.vintic.backend.analyze.controller;

import com.vintic.backend.analyze.domain.PhotoSlot;
import com.vintic.backend.analyze.dto.AnalysisStatusResponse;
import com.vintic.backend.analyze.dto.AnalyzeAcceptedResponse;
import com.vintic.backend.analyze.dto.AnalyzeByPhotoIdsRequest;
import com.vintic.backend.analyze.dto.AnalyzeCancelResponse;
import com.vintic.backend.analyze.dto.PhotoUploadResponse;
import com.vintic.backend.analyze.service.PhotoUploadService;
import com.vintic.backend.analyze.service.ProductAnalyzeService;
import com.vintic.backend.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

// 프론트가 보낸 이미지를 받아 S3 업로드 및 Queue 적재까지 수행하고, 실제 Vision 분석은
// 비동기로 처리되므로 taskId(analysisId)로 상태를 폴링하는 API를 함께 제공하는 컨트롤러
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class AnalyzeController {

    private final ProductAnalyzeService productAnalyzeService;
    private final PhotoUploadService photoUploadService;

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AnalyzeAcceptedResponse>> analyzeImage(
            @RequestPart("images") List<MultipartFile> images,
            @RequestAttribute("currentUserId") Long userId
    ) {
        // 에러나면 서비스가 알아서 던지고 Advice가 알아서 처리함
        AnalyzeAcceptedResponse response = productAnalyzeService.submitForAnalysis(images, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(response));
    }

    // #135: 기존 multipart 요청과 같은 경로를 쓰되 consumes로 구분한다 - 이미 업로드해 둔
    // photoId로 분석을 시작한다(사진 선업로드 화면). 기존 multipart 엔드포인트는 그대로 둔다.
    @PostMapping(value = "/analyze", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<AnalyzeAcceptedResponse>> analyzeByPhotoIds(
            @RequestBody AnalyzeByPhotoIdsRequest request,
            @RequestAttribute("currentUserId") Long userId
    ) {
        AnalyzeAcceptedResponse response = productAnalyzeService.submitForAnalysisByPhotoIds(request.photoIds(), userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(response));
    }

    // #135: 사진 한 장을 칸(slot)에 넣는 즉시 S3 업로드 + 촬영 면 판정을 수행한다("사진 선업로드").
    @PostMapping(value = "/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<PhotoUploadResponse>> uploadPhoto(
            @RequestPart("image") MultipartFile image,
            @RequestParam("slot") PhotoSlot slot,
            @RequestAttribute("currentUserId") Long userId
    ) {
        PhotoUploadResponse response = photoUploadService.upload(image, slot, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/analyze/{taskId}")
    public ResponseEntity<ApiResponse<AnalysisStatusResponse>> getAnalysisStatus(
            @PathVariable Long taskId,
            @RequestAttribute("currentUserId") Long userId
    ) {
        AnalysisStatusResponse response = productAnalyzeService.getStatus(taskId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // #127: 이미지 분석 화면의 X 버튼. 반복 호출해도 같은 성공 응답(status="CANCELLED")을 받는다.
    @PostMapping("/analyze/{taskId}/cancel")
    public ResponseEntity<ApiResponse<AnalyzeCancelResponse>> cancelAnalysis(
            @PathVariable Long taskId,
            @RequestAttribute("currentUserId") Long userId
    ) {
        AnalyzeCancelResponse response = productAnalyzeService.cancel(taskId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}

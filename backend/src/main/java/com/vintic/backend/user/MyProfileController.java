package com.vintic.backend.user;

import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.user.dto.MyProfileResponse;
import com.vintic.backend.user.service.MyProfileQueryService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// #143: 마이페이지 내 정보(닉네임·이메일). 로그인 필수 - currentUserId로만 식별한다.
@RestController
@RequestMapping("/api/me")
public class MyProfileController {

    private final MyProfileQueryService myProfileQueryService;

    public MyProfileController(MyProfileQueryService myProfileQueryService) {
        this.myProfileQueryService = myProfileQueryService;
    }

    @Operation(summary = "내 정보", description = "로그인 사용자의 nickname(항상 값 있음)과 email(null 가능).")
    @GetMapping
    public ResponseEntity<ApiResponse<MyProfileResponse>> getMyProfile(@RequestAttribute("currentUserId") Long userId) {
        return ResponseEntity.ok(ApiResponse.success(myProfileQueryService.getMyProfile(userId)));
    }
}

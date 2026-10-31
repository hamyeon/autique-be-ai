package com.vintic.backend.auction;

import com.vintic.backend.auction.dto.AuctionListResponse;
import com.vintic.backend.auction.dto.PopularAuctionsResponse;
import com.vintic.backend.auction.service.AuctionListQueryService;
import com.vintic.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

// #142: 경매 목록 조회(홈 인기 경매, 상품 찾기 기본 목록). 둘 다 비로그인 조회를 허용한다 - 로그인
// 사용자는 currentUserId(dev/prod: JWT, local: MockAuthInterceptor)로만 식별하고, 클라이언트가 보낸
// X-User-Id를 직접 읽지 않는다(AuctionController의 anonymous GET과 같은 방식).
//
// /api/auctions/popular는 AuctionController의 /api/auctions/{auctionId}보다 구체적인 경로라 먼저 매칭된다.
@RestController
@RequestMapping("/api/auctions")
public class AuctionListController {

    private final AuctionListQueryService auctionListQueryService;

    public AuctionListController(AuctionListQueryService auctionListQueryService) {
        this.auctionListQueryService = auctionListQueryService;
    }

    @Operation(summary = "지금 인기 있는 경매", description = "SCHEDULED/LIVE 경매를 입찰 수 내림차순 -> 종료 시각 오름차순 -> "
            + "경매 ID 오름차순으로 limit개(기본 4, 최대 20) 반환한다. 비로그인 허용.")
    @GetMapping("/popular")
    public ResponseEntity<ApiResponse<PopularAuctionsResponse>> getPopular(
            @RequestAttribute(value = "currentUserId", required = false) Long userId,
            @RequestParam(defaultValue = "" + AuctionListQueryService.DEFAULT_POPULAR_LIMIT) int limit
    ) {
        return ResponseEntity.ok(ApiResponse.success(auctionListQueryService.getPopular(limit, userId)));
    }

    @Operation(summary = "기본 경매 목록", description = "SCHEDULED/LIVE 경매를 생성 시각 내림차순 -> 경매 ID 내림차순으로 "
            + "페이지 조회한다(page 기본 0, size 기본 20, 최대 50). 비로그인 허용.")
    @GetMapping
    public ResponseEntity<ApiResponse<AuctionListResponse>> getList(
            @RequestAttribute(value = "currentUserId", required = false) Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + AuctionListQueryService.DEFAULT_PAGE_SIZE) int size
    ) {
        return ResponseEntity.ok(ApiResponse.success(auctionListQueryService.getList(page, size, userId)));
    }

    // page=abc처럼 숫자가 아닌 값. 범위 오류(InvalidPageRequestException)와 같은 400/40001로 맞춘다.
    // 이 컨트롤러 안에서만 적용된다 - 다른 API의 기존 오류 응답은 바꾸지 않는다.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(40001, e.getName() + " 값이 숫자가 아닙니다: " + e.getValue()));
    }
}

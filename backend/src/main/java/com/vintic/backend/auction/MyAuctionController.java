package com.vintic.backend.auction;

import com.vintic.backend.auction.dto.AuctionListResponse;
import com.vintic.backend.auction.service.AuctionListQueryService;
import com.vintic.backend.auction.service.MyAuctionQueryService;
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

// #143: 마이페이지 경매 목록. 로그인 필수 - 사용자는 currentUserId(dev/prod: JWT, local: MockAuthInterceptor)로만
// 식별하고 경로·쿼리로 다른 사용자를 지정할 수 없다.
@RestController
@RequestMapping("/api/me")
public class MyAuctionController {

    private final MyAuctionQueryService myAuctionQueryService;

    public MyAuctionController(MyAuctionQueryService myAuctionQueryService) {
        this.myAuctionQueryService = myAuctionQueryService;
    }

    @Operation(summary = "내가 참여한 경매", description = "내 입찰이 있거나 취소되지 않은 내 자동입찰 설정이 있는 경매(경매당 1건). "
            + "state=current(SCHEDULED/LIVE, 기본) | past(ENDED/CANCELED). "
            + "경매 생성 시각 최신순 -> 경매 ID 내림차순. page 기본 0, size 기본 20, 최대 50.")
    @GetMapping("/auctions")
    public ResponseEntity<ApiResponse<AuctionListResponse>> getMyParticipatedAuctions(
            @RequestAttribute("currentUserId") Long userId,
            @RequestParam(defaultValue = "current") String state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + AuctionListQueryService.DEFAULT_PAGE_SIZE) int size
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                myAuctionQueryService.getMyParticipatedAuctions(userId, state, page, size)));
    }

    @Operation(summary = "내 관심 경매", description = "관심 등록 시각 최신순 -> 관심 ID 내림차순. 종료·취소 경매도 포함한다. "
            + "page 기본 0, size 기본 20, 최대 50.")
    @GetMapping("/likes")
    public ResponseEntity<ApiResponse<AuctionListResponse>> getMyLikedAuctions(
            @RequestAttribute("currentUserId") Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + AuctionListQueryService.DEFAULT_PAGE_SIZE) int size
    ) {
        return ResponseEntity.ok(ApiResponse.success(myAuctionQueryService.getMyLikedAuctions(userId, page, size)));
    }

    // AuctionListController와 같은 규칙: 숫자가 아닌 page/size는 400/40001. 이 컨트롤러 안에서만 적용된다.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(40001, e.getName() + " 값이 숫자가 아닙니다: " + e.getValue()));
    }
}

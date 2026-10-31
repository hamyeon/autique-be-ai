package com.vintic.backend.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

// 상품 등록과 첫 경매 등록을 한 번의 제출로 함께 처리한다(기획 확정 - 두 단계로 분리하지
// 않는다). auctionStartPrice는 sellingPrice(=Product.finalPrice, 참고용 판매 희망가)와 무관한
// 별개 값이다 - 같은 값으로 자동 매핑하지 않는다. bidIncrement는 요청으로 받지 않는다
// (BidIncrementPolicy.DEFAULT_BID_INCREMENT 고정, 판매자가 설정하지 않음).
public record CreateProductRequest(

        // #127: 이 상품 등록이 사용하는 분석 세션(analysisId/taskId와 동일한 값). 서버가 이 세션을
        // 잠그고 소유권·취소 여부·중복 등록 여부를 확인한 뒤 "등록에 확정 사용됨"으로 표시한다 -
        // 생략하면 취소된 세션으로도 등록이 가능해지므로 필수로 받는다.
        @NotNull(message = "분석 세션 ID는 필수입니다.")
        Long analysisId,

        // #141: 선택값이고 등록에 쓰이지 않는다. 상품 이미지는 항상 분석 세션에 서버가 저장한
        // 이미지로 정한다(ProductRegistrationService.resolveImageUrls). 기존 클라이언트가 보내도
        // 깨지지 않도록 필드와 형식 검증만 남겨 둔다.
        @Size(min = 3, max = 4, message = "이미지 URL은 최소 3개, 최대 4개까지 등록할 수 있습니다.")
        List<@NotBlank(message = "이미지 URL은 비어 있을 수 없습니다.") String> imageUrls,

        @NotBlank(message = "브랜드는 필수입니다.")
        String brand,

        @NotBlank(message = "모델명은 필수입니다.")
        String modelName,

        @NotBlank(message = "색상은 필수입니다.")
        String color,

        @NotNull(message = "한국 사이즈는 필수입니다.")
        Integer size,

        @NotBlank(message = "상품 상태 등급은 필수입니다.")
        String conditionGrade,

        @NotBlank(message = "구성품 상태는 필수입니다.")
        String componentStatus,

        @NotNull(message = "추천 가격은 필수입니다.")
        Integer recommendedPrice,

        Integer baseMarketPrice,

        String priceRange,

        @NotNull(message = "최종 판매 가격은 필수입니다.")
        Integer sellingPrice,

        String reason,

        String sellerDescription,

        // #141: 판매자가 수정한 상품 상태 설명(선택). null이거나 공백뿐이면 분석 세션의 AI 상태
        // 설명(conditionDescription)을 그대로 저장한다. sellerDescription과는 별도 컬럼에 저장한다.
        @Size(max = 1000, message = "상태 설명은 1000자 이하여야 합니다.")
        String conditionDescription,

        @NotNull(message = "경매 시작가는 필수입니다.")
        @Positive(message = "경매 시작가는 0보다 커야 합니다.")
        Long auctionStartPrice,

        @NotNull(message = "경매 시작 시각은 필수입니다.")
        OffsetDateTime auctionStartAt,

        @NotNull(message = "경매 종료 시각은 필수입니다.")
        OffsetDateTime auctionEndAt
) {
}
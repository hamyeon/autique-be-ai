package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.dto.AuctionCardResponse;
import com.vintic.backend.common.util.ProductDisplayName;
import com.vintic.backend.common.util.S3UrlPresigner;
import com.vintic.backend.like.repository.AuctionLikeRepository;
import com.vintic.backend.product.domain.Product;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// #142: 경매 목록(인기·기본 목록·홈 추천)을 카드로 바꾼다. 세 곳이 같은 카드 정보를 내므로 한 곳에 둔다.
//
// 목록 길이와 무관하게 쿼리 수가 고정되도록 likeCount/isLiked는 배치로 읽는다(비슷한 상품과 같은 방식):
// 관심 수 집계 1번 + (로그인이면) 내 관심 여부 1번. 상품 이미지는 Product.imageUrls의 @BatchSize로
// 묶여 로딩된다. 호출부는 product를 fetch join한 Auction을 넘겨야 한다.
@Component
public class AuctionCardAssembler {

    // 버킷을 public-read로 열지 않으므로 조회마다 새로 서명한다(경매 상세·비슷한 상품과 같은 TTL).
    // 서명 URL은 응답에만 쓰고 저장하지 않는다.
    private static final Duration IMAGE_URL_TTL = Duration.ofHours(24);

    private final AuctionLikeRepository auctionLikeRepository;
    private final S3UrlPresigner s3UrlPresigner;

    public AuctionCardAssembler(AuctionLikeRepository auctionLikeRepository, S3UrlPresigner s3UrlPresigner) {
        this.auctionLikeRepository = auctionLikeRepository;
        this.s3UrlPresigner = s3UrlPresigner;
    }

    // 입력 순서를 그대로 유지한다. viewerUserId가 null이면(비로그인) isLiked는 모두 false다.
    public List<AuctionCardResponse> toCards(List<Auction> auctions, Long viewerUserId) {
        if (auctions.isEmpty()) {
            return List.of();
        }
        List<Long> auctionIds = auctions.stream().map(Auction::getId).toList();
        Map<Long, Long> likeCounts = auctionLikeRepository.countByAuctionIdIn(auctionIds).stream()
                .collect(Collectors.toMap(
                        AuctionLikeRepository.AuctionLikeCount::getAuctionId,
                        AuctionLikeRepository.AuctionLikeCount::getLikeCount
                ));
        Set<Long> likedAuctionIds = viewerUserId == null
                ? Set.of()
                : new HashSet<>(auctionLikeRepository.findLikedAuctionIds(viewerUserId, auctionIds));

        return auctions.stream()
                .map(auction -> toCard(auction, likeCounts, likedAuctionIds))
                .toList();
    }

    private AuctionCardResponse toCard(Auction auction, Map<Long, Long> likeCounts, Set<Long> likedAuctionIds) {
        Product product = auction.getProduct();
        return new AuctionCardResponse(
                auction.getId(),
                product.getId(),
                product.getBrand(),
                ProductDisplayName.name(product),
                product.getImageUrls().isEmpty()
                        ? null
                        : s3UrlPresigner.presign(product.getImageUrls().get(0), IMAGE_URL_TTL),
                auction.getStatus(),
                product.getConditionGrade(),
                auction.getStartPrice(),
                auction.getCurrentPrice(),
                likeCounts.getOrDefault(auction.getId(), 0L).intValue(),
                likedAuctionIds.contains(auction.getId())
        );
    }
}

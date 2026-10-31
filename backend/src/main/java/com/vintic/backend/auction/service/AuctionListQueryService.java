package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.dto.AuctionListResponse;
import com.vintic.backend.auction.dto.PopularAuctionsResponse;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.common.exception.InvalidPageRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// #142: 홈 "지금 인기 있는 경매"와 상품 찾기 화면의 기본 경매 목록.
//
// 두 목록 모두 아직 참여할 수 있는 경매(SCHEDULED/LIVE)만 담는다 - 종료(ENDED)·취소(CANCELED)
// 경매는 조회 조건에서 빠진다. 카드 변환은 AuctionCardAssembler가 배치 조회로 한다.
@Service
public class AuctionListQueryService {

    static final List<AuctionStatus> OPEN_STATUSES = List.of(AuctionStatus.SCHEDULED, AuctionStatus.LIVE);

    public static final int DEFAULT_POPULAR_LIMIT = 4;
    public static final int MAX_POPULAR_LIMIT = 20;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;

    private final AuctionRepository auctionRepository;
    private final AuctionCardAssembler auctionCardAssembler;

    public AuctionListQueryService(AuctionRepository auctionRepository, AuctionCardAssembler auctionCardAssembler) {
        this.auctionRepository = auctionRepository;
        this.auctionCardAssembler = auctionCardAssembler;
    }

    // 인기 기준은 기존 Fallback 추천과 같은 findPopular(): 입찰(Bid) 행 수 내림차순 -> 종료 시각 오름차순
    // -> 경매 ID 오름차순. 관심 수는 기준이 아니다.
    @Transactional(readOnly = true)
    public PopularAuctionsResponse getPopular(int limit, Long viewerUserId) {
        if (limit < 1 || limit > MAX_POPULAR_LIMIT) {
            throw new InvalidPageRequestException(
                    "limit은 1 이상 " + MAX_POPULAR_LIMIT + " 이하여야 합니다. 요청값: " + limit);
        }
        List<Auction> auctions = auctionRepository.findPopular(OPEN_STATUSES, PageRequest.of(0, limit));
        return new PopularAuctionsResponse(auctionCardAssembler.toCards(auctions, viewerUserId));
    }

    // 기본 정렬은 경매 생성 시각(Auction.createdAt) 내림차순, 같으면 경매 ID 내림차순이다.
    @Transactional(readOnly = true)
    public AuctionListResponse getList(int page, int size, Long viewerUserId) {
        validatePage(page, size);
        Slice<Auction> slice = auctionRepository.findListByStatuses(OPEN_STATUSES, PageRequest.of(page, size));
        return new AuctionListResponse(
                auctionCardAssembler.toCards(slice.getContent(), viewerUserId),
                page,
                size,
                slice.hasNext()
        );
    }

    // 경매 카드 목록 공통 page/size 규칙(#142 기본 목록, #143 마이페이지 목록). page >= 0, 1 <= size <= 50.
    public static void validatePage(int page, int size) {
        if (page < 0) {
            throw new InvalidPageRequestException("page는 0 이상이어야 합니다. 요청값: " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidPageRequestException(
                    "size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다. 요청값: " + size);
        }
    }
}

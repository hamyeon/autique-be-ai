package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.dto.AuctionListResponse;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.common.exception.InvalidPageRequestException;
import com.vintic.backend.like.repository.AuctionLikeRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

// #143: 마이페이지 경매 목록 두 가지. 둘 다 로그인 사용자(userId) 본인 데이터만 다루고, 카드·페이지 규칙은
// #142(AuctionCardAssembler, AuctionListQueryService.validatePage, AuctionListResponse)를 그대로 쓴다.
@Service
public class MyAuctionQueryService {

    static final List<AuctionStatus> CURRENT_STATUSES = List.of(AuctionStatus.SCHEDULED, AuctionStatus.LIVE);
    static final List<AuctionStatus> PAST_STATUSES = List.of(AuctionStatus.ENDED, AuctionStatus.CANCELED);

    private final AuctionRepository auctionRepository;
    private final AuctionLikeRepository auctionLikeRepository;
    private final AuctionCardAssembler auctionCardAssembler;

    public MyAuctionQueryService(
            AuctionRepository auctionRepository,
            AuctionLikeRepository auctionLikeRepository,
            AuctionCardAssembler auctionCardAssembler
    ) {
        this.auctionRepository = auctionRepository;
        this.auctionLikeRepository = auctionLikeRepository;
        this.auctionCardAssembler = auctionCardAssembler;
    }

    // "나의 경매" = 내가 참여한 경매(내 입찰이 있거나 취소되지 않은 내 자동입찰 설정이 있는 경매 - 판정 기준은
    // AuctionRepository.findParticipatedByUserAndStatuses). 판매 등록한 경매가 아니다.
    // state: current(SCHEDULED/LIVE) | past(ENDED/CANCELED, 낙찰·패찰 모두). 경매 생성 시각 최신순, 같으면 id 내림차순.
    @Transactional(readOnly = true)
    public AuctionListResponse getMyParticipatedAuctions(Long userId, String state, int page, int size) {
        List<AuctionStatus> statuses = statusesOf(state);
        AuctionListQueryService.validatePage(page, size);
        Slice<Auction> slice = auctionRepository.findParticipatedByUserAndStatuses(userId, statuses, PageRequest.of(page, size));
        return new AuctionListResponse(
                auctionCardAssembler.toCards(slice.getContent(), userId), page, size, slice.hasNext());
    }

    // 관심 목록. 관심 등록 시각 최신순(같으면 관심 id 내림차순). 경매 상태와 무관하게 전부 보여준다.
    // 1) 관심 경매 id를 페이지로 읽고 2) 그 경매들을 product와 함께 한 번에 읽어 3) 1)의 순서로 다시 맞춘다.
    @Transactional(readOnly = true)
    public AuctionListResponse getMyLikedAuctions(Long userId, int page, int size) {
        AuctionListQueryService.validatePage(page, size);
        Slice<Long> likedIds = auctionLikeRepository.findLikedAuctionIdsByUserId(userId, PageRequest.of(page, size));
        if (likedIds.isEmpty()) {
            return new AuctionListResponse(List.of(), page, size, likedIds.hasNext());
        }
        Map<Long, Auction> auctionsById = auctionRepository.findAllWithProductByIdIn(likedIds.getContent()).stream()
                .collect(Collectors.toMap(Auction::getId, Function.identity()));
        List<Auction> ordered = likedIds.getContent().stream()
                .map(auctionsById::get)
                .filter(Objects::nonNull)
                .toList();
        return new AuctionListResponse(
                auctionCardAssembler.toCards(ordered, userId), page, size, likedIds.hasNext());
    }

    private static List<AuctionStatus> statusesOf(String state) {
        if ("current".equals(state)) {
            return CURRENT_STATUSES;
        }
        if ("past".equals(state)) {
            return PAST_STATUSES;
        }
        throw new InvalidPageRequestException("state는 current 또는 past여야 합니다. 요청값: " + state);
    }
}

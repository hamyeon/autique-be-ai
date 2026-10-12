package com.vintic.backend.analyze.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Objects;

// #135: 분석 요청(ProductAnalysisSession) 생성 전에 칸(slot) 단위로 먼저 올려 둔 사진.
// photoIds 분석 요청(PhotoUsageCommitter)이 이 중 쓰지 않은 사진을 analysisId로 "사용 처리"한다.
@Entity
@Table(name = "product_photo")
@Getter
public class ProductPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "slot", nullable = false, length = 20)
    private PhotoSlot slot;

    @Column(name = "original_url", nullable = false, length = 1000)
    private String originalUrl;

    @Column(name = "analysis_url", nullable = false, length = 1000)
    private String analysisUrl;

    // DEFECT 칸은 판정을 생략하므로 null - MATCH/MISMATCH/UNKNOWN 중 하나면 판정이 이뤄졌다는 뜻이다.
    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", length = 20)
    private PhotoMatchStatus matchStatus;

    // PhotoView.name() 그대로 저장(side/front/back/top/outsole/other). 판정 생략·실패 시 null.
    @Column(name = "detected_view", length = 20)
    private String detectedView;

    // null이면 아직 어떤 분석에도 쓰이지 않은 사진이다 - PhotoUsageCommitter.claimPhotos()가
    // 이 값을 채우는 유일한 경로이고, 한 번 채워지면 같은 사진을 다른 분석에 다시 쓸 수 없다
    // (isUsed() 참고). PhotoCleanupService가 미사용 사진을 찾을 때도 이 값으로 판단한다.
    @Column(name = "analysis_id")
    private Long analysisId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ProductPhoto() {
    }

    public static ProductPhoto create(Long ownerId, PhotoSlot slot, String originalUrl, String analysisUrl,
                                       PhotoMatchStatus matchStatus, String detectedView) {
        ProductPhoto photo = new ProductPhoto();
        photo.ownerId = ownerId;
        photo.slot = slot;
        photo.originalUrl = originalUrl;
        photo.analysisUrl = analysisUrl;
        photo.matchStatus = matchStatus;
        photo.detectedView = detectedView;
        photo.createdAt = LocalDateTime.now();
        return photo;
    }

    public boolean isOwnedBy(Long userId) {
        return Objects.equals(this.ownerId, userId);
    }

    public boolean isUsed() {
        return analysisId != null;
    }

    // PhotoUsageCommitter.claimPhotos()가 행 잠금(findAllByIdInForUpdate) 안에서만 호출한다 -
    // isUsed() 확인과 이 호출 사이에 다른 트랜잭션이 끼어들 수 없다.
    public void markUsed(Long analysisId) {
        this.analysisId = analysisId;
    }
}

package com.vintic.backend.bid;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// D1 회귀 테스트(docs/study-14days/current-code-review.md §3).
// 상한이 단위(5,000원) 배수가 아닌 ACTIVE 자동입찰(132,000) 위로 정상 수동입찰(135,000/140,000)을 넣으면,
// EffectiveCapCalculator가 0 쪽 버림 나눗셈으로 상한보다 높은 cap을 만들어 IllegalStateException이 났고
// 입찰 전체가 500으로 롤백됐다. 계약 §0.13대로 수동입찰이 이기고 그 자동입찰은 CAP_REACHED여야 한다.
// 실제 컨트롤러 -> 서비스 -> MySQL(비관적 락 포함) 경로를 그대로 탄다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
class ManualBidOverUnalignedAutoBidCapMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("analysis.stream.consumer.enabled", () -> "false");
        registry.add("purchase-agent.scan.enabled", () -> "false");
        registry.add("auction.lifecycle.start.enabled", () -> "false");
        registry.add("auction.lifecycle.end.enabled", () -> "false");
        registry.add("payment.expiration.enabled", () -> "false");
        registry.add("backup-offer.expiration.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private AutoBidSettingRepository autoBidSettingRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    private User persistUser(String email) {
        return userRepository.save(User.register(email, email, null));
    }

    private Auction persistLiveAuction(User seller) {
        Product product = productRepository.save(new Product(
                seller, List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "", 290000, "", ""
        ));
        Auction auction = Auction.schedule(product, 100000L, 5000L,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        auction.start();
        return auctionRepository.save(auction);
    }

    @ParameterizedTest
    @ValueSource(longs = {135000L, 140000L})
    void 배수가_아닌_자동입찰_상한_위로_수동입찰하면_수동입찰이_이기고_자동입찰은_CAP_REACHED다(long manualAmount) throws Exception {
        User seller = persistUser("d1-seller-" + manualAmount + "@vintic.local");
        User autoBidder = persistUser("d1-auto-" + manualAmount + "@vintic.local");
        User manualBidder = persistUser("d1-manual-" + manualAmount + "@vintic.local");
        Long auctionId = persistLiveAuction(seller).getId();

        // 1) 자동입찰 상한 132,000(배수 아님) 등록 -> 혼자이므로 105,000에서 승자가 된다.
        mockMvc.perform(post("/api/auctions/{id}/auto-bids", auctionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", autoBidder.getId())
                        .header("Idempotency-Key", "d1-auto-" + manualAmount)
                        .content("{\"maxAmount\": 132000}"))
                .andExpect(status().isCreated());
        Auction afterAutoBid = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(afterAutoBid.getCurrentPrice()).isEqualTo(105000L);
        assertThat(afterAutoBid.getCurrentWinner().getId()).isEqualTo(autoBidder.getId());

        // 2) 상한보다 높은 정상 수동입찰 -> 이전에는 HTTP 500(50001)으로 롤백됐다.
        mockMvc.perform(post("/api/auctions/{id}/bids", auctionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", manualBidder.getId())
                        .header("Idempotency-Key", "d1-manual-" + manualAmount)
                        .content("{\"amount\": %d}".formatted(manualAmount)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.currentPrice").value(manualAmount))
                .andExpect(jsonPath("$.data.proxyResponded").value(false));

        // 3) DB 상태: 수동입찰자가 M에서 승자, 자동입찰은 CAP_REACHED, 반격 AUTO Bid는 없다.
        Auction reloaded = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(manualAmount);
        assertThat(reloaded.getCurrentWinner().getId()).isEqualTo(manualBidder.getId());
        assertThat(bidRepository.countByAuctionId(auctionId)).isEqualTo(2); // AUTO 105,000 + MANUAL M

        AutoBidSetting setting = autoBidSettingRepository
                .findByAuctionIdAndUserIdAndActiveSlotTrue(auctionId, autoBidder.getId())
                .orElseThrow();
        assertThat(setting.getStatus()).isEqualTo(AutoBidSettingStatus.CAP_REACHED);
        assertThat(setting.getMaxAmount()).isEqualTo(132000L);
    }
}

package com.vintic.backend.concurrency;

import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import com.vintic.backend.analyze.domain.AnalysisStatus;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.dto.AnalyzeAcceptedResponse;
import com.vintic.backend.analyze.queue.AnalysisStreamProperties;
import com.vintic.backend.analyze.queue.AnalysisTaskConsumer;
import com.vintic.backend.analyze.queue.AnalysisTaskMessage;
import com.vintic.backend.analyze.queue.AnalysisTaskProducer;
import com.vintic.backend.analyze.service.AnalysisQueueCommitter;
import com.vintic.backend.analyze.service.ProductAnalyzeService;
import com.vintic.backend.analyze.service.S3UploaderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// #127: AnalysisCancellationMySqlIT/AnalysisCancellationRegistrationRaceMySqlIT는
// VisionAttemptCoordinator/ProductAnalyzeService 메서드를 직접 호출해 "DB 행 잠금" 원자성만
// 검증했을 뿐, Redis는 전혀 쓰지 않았다(실제 XADD/XREADGROUP/XCLAIM/XACK가 한 번도 일어나지 않음).
// 이 클래스는 그 간극을 메운다 - 실제 Redis(Testcontainers)와 실제 MySQL, 그리고 Mock이 아닌
// 실제 AnalysisTaskConsumer/VisionAttemptCoordinator 빈을 그대로 써서, "취소된 세션의 메시지가
// 실제 Redis Stream을 통해 들어와도 Vision을 호출하지 않고 실제 XACK되는지"를 검증한다.
// VisionAnalysisService(AI 호출)만 Mock으로 대체한다 - 외부 AI 호출 자체는 이번 기능의 검증
// 대상이 아니다.
//
// analysis.stream.consumer.enabled=false로 앱 자신의 백그라운드 StreamMessageListenerContainer를
// 꺼서, 이 테스트가 직접 미는 메시지를 그 백그라운드 컨테이너가 동시에 집어가 결과가 섞이는
// 것을 막는다(AnalysisStreamRecoveryProperties.enabled는 기본값이 이미 false라 회수 스케줄러는
// 원래도 돌지 않는다).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class AnalysisCancellationRedisMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        // application-local.yml: spring.data.redis.host/port = ${LOCAL_REDIS_HOST}/${LOCAL_REDIS_PORT}
        registry.add("LOCAL_REDIS_HOST", redis::getHost);
        registry.add("LOCAL_REDIS_PORT", () -> redis.getMappedPort(6379));
        registry.add("analysis.stream.consumer.enabled", () -> "false");
    }

    @MockitoBean
    private VisionAnalysisService visionAnalysisService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AnalysisStreamProperties streamProperties;

    @Autowired
    private AnalysisTaskConsumer analysisTaskConsumer;

    // #130: 실제 XADD는 그대로 수행하면서(기존 세 테스트는 영향받지 않음) 필요한 테스트에서만
    // enqueue() 호출을 가로채 "그 시점에 DB가 어떤 상태인지"를 들여다보거나 실패를 흉내낸다.
    @MockitoSpyBean
    private AnalysisTaskProducer analysisTaskProducer;

    @MockitoBean
    private S3UploaderService s3UploaderService;

    @Autowired
    private ProductAnalysisSessionRepository sessionRepository;

    @Autowired
    private ProductAnalyzeService productAnalyzeService;

    @Autowired
    private AnalysisQueueCommitter queueCommitter;

    private static final Long USER_ID = 1L;

    private void stubUpload() {
        when(s3UploaderService.uploadImages(any())).thenReturn(List.of(
                new S3UploaderService.UploadedImage("https://example.com/a.jpg", "https://example.com/a-analysis.jpg")));
    }

    @BeforeEach
    void setUp() {
        // 테스트마다 같은 기본 stream key를 재사용하므로, 이전 테스트가 남긴 엔트리/PEL과
        // 섞이지 않도록 매번 비우고 Consumer Group을 다시 만든다.
        redisTemplate.delete(streamProperties.getKey());
        try {
            redisTemplate.opsForStream().createGroup(streamProperties.getKey(), ReadOffset.from("0"), streamProperties.getGroup());
        } catch (Exception ignored) {
            // BUSYGROUP 등 - 무시(RedisStreamConsumerConfig.createConsumerGroupIfAbsent와 동일 원칙)
        }
    }

    private Long queuedSessionId() {
        ProductAnalysisSession session = ProductAnalysisSession.create(USER_ID);
        session.markImageUploaded(List.of("https://example.com/a.jpg"));
        session.markQueued();
        return sessionRepository.save(session).getId();
    }

    private Long imageUploadedSessionId() {
        ProductAnalysisSession session = ProductAnalysisSession.create(USER_ID);
        session.markImageUploaded(List.of("https://example.com/a.jpg"));
        return sessionRepository.save(session).getId();
    }

    private long pendingCount() {
        return redisTemplate.opsForStream()
                .pending(streamProperties.getKey(), streamProperties.getGroup(), Range.unbounded(), 10)
                .size();
    }

    @SuppressWarnings("unchecked")
    private MapRecord<String, String, String> readOneFromRealRedis(String consumerName) {
        List<MapRecord<String, String, String>> records = (List<MapRecord<String, String, String>>) (List<?>) redisTemplate.opsForStream().read(
                Consumer.from(streamProperties.getGroup(), consumerName),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(streamProperties.getKey(), ReadOffset.lastConsumed())
        );
        assertThat(records).hasSize(1);
        return records.get(0);
    }

    @Test
    void 대기중_세션을_취소한_뒤_실제_Redis로_새로_배달된_메시지를_처리하면_Vision을_호출하지_않고_실제_XACK된다() {
        Long sessionId = queuedSessionId();
        productAnalyzeService.cancel(sessionId, USER_ID);

        analysisTaskProducer.enqueue(new AnalysisTaskMessage(
                sessionId, List.of("https://example.com/a.jpg"), List.of("https://example.com/a.jpg")));
        MapRecord<String, String, String> delivered = readOneFromRealRedis("consumer-1");
        assertThat(pendingCount()).isEqualTo(1); // ack 전에는 PEL에 남아있어야 한다

        analysisTaskConsumer.onMessage(delivered);

        verify(visionAnalysisService, never()).analyze(any(), any());
        assertThat(pendingCount()).isZero(); // 실제 XACK으로 PEL에서 사라졌다
    }

    @Test
    void 취소된_세션의_메시지가_실제_XCLAIM으로_회수돼도_Vision을_호출하지_않고_실제_XACK된다() throws InterruptedException {
        // Worker가 claim(DB 저장)조차 하기 전에 죽어 메시지가 PEL에만 남은 상황을 흉내낸다
        // (AnalysisStreamRecoveryScheduler가 실제로 XCLAIM하는 것과 동일한 Redis 연산).
        Long sessionId = queuedSessionId();
        analysisTaskProducer.enqueue(new AnalysisTaskMessage(
                sessionId, List.of("https://example.com/a.jpg"), List.of("https://example.com/a.jpg")));
        MapRecord<String, String, String> delivered = readOneFromRealRedis("dead-consumer");

        productAnalyzeService.cancel(sessionId, USER_ID);

        Thread.sleep(20);
        List<MapRecord<String, String, String>> claimed = redisTemplate.<String, String>opsForStream().claim(
                streamProperties.getKey(), streamProperties.getGroup(), "recovery-consumer",
                Duration.ofMillis(10), delivered.getId()
        );
        assertThat(claimed).hasSize(1);

        analysisTaskConsumer.processReclaimed(claimed.get(0), 2L);

        verify(visionAnalysisService, never()).analyze(any(), any());
        assertThat(pendingCount()).isZero();
    }

    @Test
    void Vision_처리_중_취소한_뒤_뒤늦은_성공_응답이_와도_결과를_저장하지_않고_실제_XACK만_한다() throws InterruptedException {
        Long sessionId = queuedSessionId();
        CountDownLatch visionCalled = new CountDownLatch(1);
        CountDownLatch cancelledByTest = new CountDownLatch(1);
        VisionAnalysisResult result = new VisionAnalysisResult(
                "Nike", "Dunk Low", "Panda", 270, "설명", null, true, 0.9, false, List.of(), List.of(), List.of(), List.of()
        );
        when(visionAnalysisService.analyze(any(), any())).thenAnswer(invocation -> {
            visionCalled.countDown();
            assertThat(cancelledByTest.await(5, TimeUnit.SECONDS)).isTrue();
            return result; // 취소가 이미 커밋된 뒤에야 "늦게" 성공 응답이 도착한다.
        });

        analysisTaskProducer.enqueue(new AnalysisTaskMessage(
                sessionId, List.of("https://example.com/a.jpg"), List.of("https://example.com/a.jpg")));
        MapRecord<String, String, String> delivered = readOneFromRealRedis("consumer-1");

        Thread worker = new Thread(() -> analysisTaskConsumer.onMessage(delivered));
        worker.start();
        try {
            assertThat(visionCalled.await(5, TimeUnit.SECONDS)).isTrue();

            productAnalyzeService.cancel(sessionId, USER_ID);
            cancelledByTest.countDown();

            worker.join(5000);
            assertThat(worker.isAlive()).isFalse();

            ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
            assertThat(reloaded.getVisionResultJson()).isNull();
            assertThat(pendingCount()).isZero(); // 결과는 버렸지만 메시지는 실제 XACK된다
        } finally {
            worker.interrupt();
        }
    }

    // #130: ProductAnalyzeService.submitForAnalysis()가 QUEUED를 커밋한 뒤에만 enqueue()를
    // 호출하는지를, enqueue 호출 "그 시점"에 별도로(이 요청 스레드가 들고 있는 in-memory
    // 엔티티가 아니라 리포지토리로 새로) 세션을 다시 읽어 검증한다. 과거 순서(enqueue 먼저,
    // QUEUED 커밋은 나중)였다면 여기서 IMAGE_UPLOADED가 읽혔을 것이다.
    @Test
    void submitForAnalysis는_Redis_발행_시점에_이미_QUEUED를_커밋해둔다() throws Exception {
        stubUpload();
        MultipartFile image = new MockMultipartFile("images", "shoe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        AtomicReference<AnalysisStatus> statusDuringEnqueue = new AtomicReference<>();

        doAnswer(invocation -> {
            AnalysisTaskMessage message = invocation.getArgument(0);
            statusDuringEnqueue.set(sessionRepository.findById(message.analysisId()).orElseThrow().getStatus());
            return invocation.callRealMethod(); // 실제 XADD는 그대로 수행한다.
        }).when(analysisTaskProducer).enqueue(any());

        productAnalyzeService.submitForAnalysis(List.of(image), USER_ID);

        assertThat(statusDuringEnqueue.get()).isEqualTo(AnalysisStatus.QUEUED);
    }

    // #130: 방금 발행된 메시지를 곧바로 실제 Redis에서 읽어 처리해도(= Worker가 가장 빠르게
    // 반응하는 극단적인 경우) 정상적으로 claim돼 Vision이 호출되는지 - 수정 전이었다면 이
    // 시점에 세션이 아직 IMAGE_UPLOADED라 ALREADY_FINALIZED로 오판되어 ACK만 되고 유실됐을
    // 경로다.
    @Test
    void 발행_직후_실제_Redis_메시지를_처리하면_Vision이_호출되고_결과가_반영된다() throws Exception {
        stubUpload();
        MultipartFile image = new MockMultipartFile("images", "shoe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        VisionAnalysisResult result = new VisionAnalysisResult(
                "Nike", "Dunk Low", "Panda", 270, "설명", null, true, 0.9, false, List.of(), List.of(), List.of(), List.of()
        );
        when(visionAnalysisService.analyze(any(), any())).thenReturn(result);

        AnalyzeAcceptedResponse response = productAnalyzeService.submitForAnalysis(List.of(image), USER_ID);
        MapRecord<String, String, String> delivered = readOneFromRealRedis("consumer-1");

        analysisTaskConsumer.onMessage(delivered);

        verify(visionAnalysisService).analyze(any(), any());
        ProductAnalysisSession reloaded = sessionRepository.findById(response.analysisId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.AWAITING_USER_CONFIRMATION);
        assertThat(pendingCount()).isZero();
    }

    // #130: enqueue 예외는 "발행되지 않았다"를 보장하지 않는다 - 그 모호한 실패를 기록하는
    // 도중에 사용자가 취소하면, 뒤늦은 발행 실패 기록이 CANCELLED를 QUEUE_FAILED로 덮어쓰면
    // 안 된다. 래치로 "enqueue가 실패를 던지기 직전" ↔ "그 사이 취소가 커밋됨" 순서를 결정론적으로
    // 만든다(sleep 없음).
    @Test
    void 모호한_발행_실패_처리가_그_사이_취소된_세션을_덮어쓰지_않는다() throws Exception {
        stubUpload();
        MultipartFile image = new MockMultipartFile("images", "shoe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        CountDownLatch queuedCommitted = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicReference<Long> sessionIdRef = new AtomicReference<>();

        doAnswer(invocation -> {
            AnalysisTaskMessage message = invocation.getArgument(0);
            sessionIdRef.set(message.analysisId());
            queuedCommitted.countDown();
            assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
            throw new RuntimeException("Redis 응답 타임아웃(실제로는 발행됐을 수 있음)");
        }).when(analysisTaskProducer).enqueue(any());

        Thread submitter = new Thread(() -> {
            try {
                productAnalyzeService.submitForAnalysis(List.of(image), USER_ID);
            } catch (RuntimeException ignored) {
                // submitForAnalysis는 enqueue 예외를 그대로 다시 던진다 - 이 테스트의 관심사는
                // 그 뒤에 남는 DB 상태이지 이 호출의 응답이 아니다.
            }
        });
        submitter.start();

        try {
            assertThat(queuedCommitted.await(5, TimeUnit.SECONDS)).isTrue();
            productAnalyzeService.cancel(sessionIdRef.get(), USER_ID);
            cancelled.countDown();
            submitter.join(5000);
            assertThat(submitter.isAlive()).isFalse();

            ProductAnalysisSession reloaded = sessionRepository.findById(sessionIdRef.get()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        } finally {
            submitter.interrupt();
        }
    }

    // #130: 단위 테스트(AnalysisTaskConsumerTest)는 coordinator.claim()을 모킹해 NOT_YET_QUEUED일
    // 때 ACK를 호출하지 않는 "분기 자체"만 본다. 이 테스트는 그 메시지가 실제 Redis PEL에
    // 정말로 남아있다가, 세션이 QUEUED로 정상 전이된 뒤 실제 회수 경로(XCLAIM +
    // AnalysisTaskConsumer.processReclaimed - AnalysisStreamRecoveryScheduler가 스캔 때마다
    // 호출하는 것과 동일한 메서드)로 끝까지 처리되는지, 즉 "방어선이 실제로 회수 가능한
    // 상태를 남기는지"까지 확인한다. 운영 회수 주기(min-idle-time-ms 기본 240s)는 바꾸지 않고,
    // 대신 그 스케줄러가 쓰는 것과 동일한 Redis 연산(XCLAIM)을 짧은 minIdleTime으로 직접
    // 수행한다 - 기존 XCLAIM 회수 테스트와 동일한 기법이다.
    @Test
    void 준비_상태_세션의_메시지는_실제_PEL에_남아있다가_QUEUED_전이_후_실제_회수로_정상_처리된다() throws InterruptedException {
        // 1) CREATED/IMAGE_UPLOADED 세션의 메시지를 실제 Redis Consumer Group으로 배달한다.
        Long sessionId = imageUploadedSessionId();
        analysisTaskProducer.enqueue(new AnalysisTaskMessage(
                sessionId, List.of("https://example.com/a.jpg"), List.of("https://example.com/a.jpg")));
        MapRecord<String, String, String> delivered = readOneFromRealRedis("consumer-1");

        // 2) 실제 Consumer가 NOT_YET_QUEUED로 판정하고 Vision을 호출하지 않는다.
        analysisTaskConsumer.onMessage(delivered);
        verify(visionAnalysisService, never()).analyze(any(), any());

        // 3) 실제 XPENDING 조회로 이 recordId가 PEL에 남아있음을 확인한다(ACK되지 않았다).
        PendingMessages pendingAfterFirstAttempt = redisTemplate.opsForStream()
                .pending(streamProperties.getKey(), streamProperties.getGroup(), Range.unbounded(), 10);
        assertThat(pendingAfterFirstAttempt).hasSize(1);
        assertThat(pendingAfterFirstAttempt.get(0).getId()).isEqualTo(delivered.getId());

        // 4) 세션을 정상 상태 전이(프로덕션과 동일한 경로)로 QUEUED로 커밋한다.
        queueCommitter.commitQueued(sessionId);

        // 5) 현재 회수 경로(XCLAIM + processReclaimed)로 같은 메시지를 다시 처리한다.
        Thread.sleep(20);
        List<MapRecord<String, String, String>> claimed = redisTemplate.<String, String>opsForStream().claim(
                streamProperties.getKey(), streamProperties.getGroup(), "recovery-consumer",
                Duration.ofMillis(10), delivered.getId()
        );
        assertThat(claimed).hasSize(1);

        VisionAnalysisResult result = new VisionAnalysisResult(
                "Nike", "Dunk Low", "Panda", 270, "설명", null, true, 0.9, false, List.of(), List.of(), List.of(), List.of()
        );
        when(visionAnalysisService.analyze(any(), any())).thenReturn(result);

        analysisTaskConsumer.processReclaimed(claimed.get(0), 2L);

        // 6) 이번에는 Vision이 호출되고 결과가 저장되며, 실제 XACK로 PEL에서 제거된다.
        verify(visionAnalysisService).analyze(any(), any());
        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.AWAITING_USER_CONFIRMATION);
        assertThat(pendingCount()).isZero();
    }
}

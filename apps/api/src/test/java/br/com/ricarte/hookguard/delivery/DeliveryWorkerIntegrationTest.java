package br.com.ricarte.hookguard.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.ricarte.hookguard.domain.DeliveryAttemptRepository;
import br.com.ricarte.hookguard.domain.DeliveryJobRepository;
import br.com.ricarte.hookguard.domain.EventStatus;
import br.com.ricarte.hookguard.domain.JobState;
import br.com.ricarte.hookguard.domain.Project;
import br.com.ricarte.hookguard.domain.WebhookEvent;
import br.com.ricarte.hookguard.domain.WebhookEventRepository;
import br.com.ricarte.hookguard.ingest.IngestService;
import br.com.ricarte.hookguard.project.ProjectService;
import br.com.ricarte.hookguard.support.DatabaseCleaner;
import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class DeliveryWorkerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private DeliveryWorker deliveryWorker;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private DeliveryJobRepository deliveryJobRepository;

    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.clean();
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        deliveryWorker.setBeforeSettle(null);
        server.shutdown();
    }

    @Test
    void deliversSuccessfully() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        Project project = createProject(server.url("/hook").toString(), 6);

        UUID eventId = ingestService.accept(
                project.getProjectKey(),
                Map.of("X-Test", "1"),
                "{\"hello\":true}".getBytes(),
                "application/json"
        );

        deliveryWorker.processBatch();

        WebhookEvent event = webhookEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(EventStatus.DELIVERED);
        assertThat(deliveryWorker.lastClaimRanInTransaction()).isTrue();
        assertThat(deliveryWorker.lastHttpRanInTransaction()).isFalse();
    }

    @Test
    void marksDeadAfterNonRetryableClientError() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad"));
        Project project = createProject(server.url("/hook").toString(), 6);

        UUID eventId = ingestService.accept(
                project.getProjectKey(),
                Map.of(),
                "x".getBytes(),
                "text/plain"
        );

        deliveryWorker.processBatch();

        WebhookEvent event = webhookEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(EventStatus.DEAD);
    }

    @Test
    void retriesServerErrorAndHonorsRetryAfterFloor() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "90").setBody("later"));
        Project project = createProject(server.url("/hook").toString(), 6);
        UUID eventId = ingestService.accept(project.getProjectKey(), Map.of(), "x".getBytes(), "text/plain");
        Instant before = Instant.now();

        deliveryWorker.processBatch();

        WebhookEvent event = webhookEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(EventStatus.RECEIVED);
        var job = deliveryJobRepository.findAll().getFirst();
        assertThat(job.getState()).isEqualTo(JobState.PENDING);
        assertThat(job.getAvailableAt()).isAfter(before.plusSeconds(80));
    }

    @Test
    void doesNotForwardOriginCredentials() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        Project project = createProject(server.url("/hook").toString(), 6);
        byte[] body = "{\"ok\":true}".getBytes();
        UUID eventId = ingestService.accept(
                project.getProjectKey(),
                Map.of(
                        "Authorization", "Bearer secret-value",
                        "Cookie", "sid=abc",
                        "X-Api-Key", "key-value",
                        "Stripe-Signature", "t=1,v1=abc",
                        "X-Test", "nope"
                ),
                body,
                "application/json"
        );

        deliveryWorker.processBatch();

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getHeader("Authorization")).isNull();
        assertThat(request.getHeader("Cookie")).isNull();
        assertThat(request.getHeader("X-Api-Key")).isNull();
        assertThat(request.getHeader("X-Test")).isNull();
        assertThat(request.getHeader("Stripe-Signature")).isEqualTo("t=1,v1=abc");
        assertThat(request.getHeader("X-HookGuard-Event-Id")).isEqualTo(eventId.toString());
        assertThat(request.getHeader("X-HookGuard-Signature")).contains("t=").contains("v1=");
        assertThat(request.getHeader("X-HookGuard-Signature")).doesNotContain(project.getSigningSecret());
        assertThat(request.getBody().readByteArray()).isEqualTo(body);
    }

    @Test
    void twoWorkersDoNotDeliverTheSameJob() throws Exception {
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                inFlight.countDown();
                if (!release.await(8, TimeUnit.SECONDS)) {
                    return new MockResponse().setResponseCode(500);
                }
                return new MockResponse().setResponseCode(200).setBody("ok");
            }
        });
        Project project = createProject(server.url("/hook").toString(), 6);
        UUID eventId = ingestService.accept(project.getProjectKey(), Map.of(), "x".getBytes(), "text/plain");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> deliveryWorker.processBatch());
            assertThat(inFlight.await(8, TimeUnit.SECONDS)).isTrue();
            pool.submit(() -> deliveryWorker.processBatch()).get(8, TimeUnit.SECONDS);
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(8, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(webhookEventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.DELIVERED);
    }

    @Test
    void staleLeaseIsRetriedAndLostSettleDoesNotMarkDelivered() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("first"));
        Project project = createProject(server.url("/hook").toString(), 6);
        UUID eventId = ingestService.accept(project.getProjectKey(), Map.of(), "x".getBytes(), "text/plain");
        deliveryWorker.setBeforeSettle(() -> jdbcTemplate.update(
                "UPDATE delivery_jobs SET locked_by = 'stolen' WHERE event_id = ?",
                eventId
        ));

        deliveryWorker.processBatch();

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(webhookEventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.DELIVERING);
        deliveryWorker.setBeforeSettle(null);
        jdbcTemplate.update(
                "UPDATE delivery_jobs SET locked_at = ? WHERE event_id = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)),
                eventId
        );
        server.enqueue(new MockResponse().setResponseCode(200).setBody("second"));

        deliveryWorker.processBatch();

        assertThat(webhookEventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.DELIVERED);
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(deliveryAttemptRepository.findByEventIdOrderByAttemptNumberAsc(eventId))
                .anyMatch(attempt -> "lease_expired".equals(attempt.getErrorMessage()));
    }

    @Test
    void pausedProjectDoesNotDeliverUntilResumed() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        Project project = createProject(server.url("/hook").toString(), 6);
        projectService.setPaused(project.getAccountId(), project.getId(), true);
        UUID eventId = ingestService.accept(project.getProjectKey(), Map.of(), "x".getBytes(), "text/plain");

        deliveryWorker.processBatch();

        assertThat(server.getRequestCount()).isZero();
        assertThat(webhookEventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.RECEIVED);
        projectService.setPaused(project.getAccountId(), project.getId(), false);
        deliveryWorker.processBatch();
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(webhookEventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.DELIVERED);
    }

    @Test
    void metadataDestinationIsRejectedEvenWhenHttpIsAllowed() {
        var account = projectService.ensureAccount("ssrf-" + UUID.randomUUID() + "@example.com", "SSRF");
        assertThatThrownBy(() -> projectService.create(account.getId(), "meta", "http://169.254.169.254/latest", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projectService.create(account.getId(), "name", "https://metadata.google.internal/computeMetadata/v1/", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Project createProject(String destination, int maxAttempts) {
        var account = projectService.ensureAccount("delivery-" + UUID.randomUUID() + "@example.com", "Delivery");
        Project project = projectService.create(account.getId(), "p", destination, null);
        return projectService.update(account.getId(), project.getId(), destination, 2000, maxAttempts, null);
    }
}

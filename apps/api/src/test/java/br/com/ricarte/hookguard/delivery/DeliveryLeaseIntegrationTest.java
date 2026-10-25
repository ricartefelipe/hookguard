package br.com.ricarte.hookguard.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.domain.DeliveryAttempt;
import br.com.ricarte.hookguard.domain.DeliveryAttemptRepository;
import br.com.ricarte.hookguard.domain.DeliveryJobRepository;
import br.com.ricarte.hookguard.domain.EventStatus;
import br.com.ricarte.hookguard.domain.JobState;
import br.com.ricarte.hookguard.domain.Project;
import br.com.ricarte.hookguard.domain.ProjectRepository;
import br.com.ricarte.hookguard.domain.WebhookEventRepository;
import br.com.ricarte.hookguard.ingest.IngestService;
import br.com.ricarte.hookguard.project.ProjectService;
import br.com.ricarte.hookguard.support.DatabaseCleaner;
import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Lease ownership across two worker instances sharing one database and one destination. */
class DeliveryLeaseIntegrationTest extends PostgresIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private IngestService ingestService;
    @Autowired private DeliveryWorker workerA;
    @Autowired private WebhookEventRepository eventRepository;
    @Autowired private DeliveryJobRepository jobRepository;
    @Autowired private DeliveryAttemptRepository attemptRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private DeliveryClient deliveryClient;
    @Autowired private HookguardProperties properties;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleaner cleaner;

    private DeliveryWorker workerB;
    private MockWebServer destination;

    @BeforeEach
    void setUp() throws Exception {
        cleaner.clean();
        workerB = new DeliveryWorker(jobRepository, eventRepository, projectRepository, attemptRepository,
                deliveryClient, properties, objectMapper, meterRegistry, transactionManager);
        destination = new MockWebServer();
        destination.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        workerA.setBeforeSend(null);
        workerA.setBeforeSettle(null);
        workerB.setBeforeSend(null);
        workerB.setBeforeSettle(null);
        destination.shutdown();
    }

    @Test
    void onlyTheJobBeingSentIsLeasedWhileSlowDestinationsDrain() throws Exception {
        destination.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(200).setBodyDelay(300, TimeUnit.MILLISECONDS).setBody("ok");
            }
        });
        Project project = project(6);
        List<UUID> events = ingest(project, 6);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> run = pool.submit(() -> workerA.processBatch());
            int maxInProgress = 0;
            while (!run.isDone()) {
                Integer inProgress = jdbc.queryForObject("SELECT count(*) FROM delivery_jobs WHERE state = 'in_progress'", Integer.class);
                maxInProgress = Math.max(maxInProgress, inProgress == null ? 0 : inProgress);
                Integer untouchedLeases = jdbc.queryForObject(
                        "SELECT count(*) FROM delivery_jobs WHERE state = 'pending' AND (locked_by IS NOT NULL OR attempt_number > 0)", Integer.class);
                assertThat(untouchedLeases).isZero();
                Thread.sleep(20);
            }
            run.get();
            assertThat(maxInProgress).isLessThanOrEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(destination.getRequestCount()).isEqualTo(6);
        for (UUID id : events) {
            assertThat(eventRepository.findById(id).orElseThrow().getStatus()).isEqualTo(EventStatus.DELIVERED);
        }
    }

    @Test
    void twoWorkersWithABatchDeliverEachEventOnce() throws Exception {
        Map<String, AtomicInteger> perEvent = new ConcurrentHashMap<>();
        destination.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                perEvent.computeIfAbsent(request.getHeader("X-HookGuard-Event-Id"), k -> new AtomicInteger()).incrementAndGet();
                return new MockResponse().setResponseCode(200).setBodyDelay(80, TimeUnit.MILLISECONDS).setBody("ok");
            }
        });
        Project project = project(6);
        List<UUID> events = ingest(project, 20);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> workerA.processBatch());
            Future<?> b = pool.submit(() -> workerB.processBatch());
            a.get(60, TimeUnit.SECONDS);
            b.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(perEvent).hasSize(20);
        assertThat(perEvent.values()).allMatch(count -> count.get() == 1);
        for (UUID id : events) {
            assertThat(eventRepository.findById(id).orElseThrow().getStatus()).isEqualTo(EventStatus.DELIVERED);
            assertThat(attemptRepository.findByEventIdOrderByAttemptNumberAsc(id)).hasSize(1);
        }
    }

    @Test
    void leaseThatExpiresWhileWaitingForItsTurnIsNotSentByTheOldOwner() {
        destination.enqueue(new MockResponse().setResponseCode(200).setBody("from-b"));
        Project project = project(6);
        UUID eventId = ingest(project, 1).get(0);
        AtomicBoolean recovered = new AtomicBoolean();
        workerA.setBeforeSend(() -> {
            age(eventId);
            recovered.set(true);
            workerB.processBatch();
        });

        workerA.processBatch();

        assertThat(recovered).isTrue();
        assertThat(destination.getRequestCount()).isEqualTo(1);
        var attempts = attemptRepository.findByEventIdOrderByAttemptNumberAsc(eventId);
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0).getErrorMessage()).isEqualTo("lease_expired");
        assertThat(attempts.get(0).getHttpStatus()).isNull();
        assertThat(attempts.get(1).getHttpStatus()).isEqualTo(200);
        assertThat(jobRepository.findByEventIdOrderByAvailableAtAsc(eventId)).singleElement()
                .satisfies(job -> {
                    assertThat(job.getState()).isEqualTo(JobState.DONE);
                    assertThat(job.getAttemptNumber()).isEqualTo(2);
                });
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.DELIVERED);
    }

    @Test
    void staleWorkerResultIsDiscardedAfterAnotherWorkerTookOver() {
        destination.enqueue(new MockResponse().setResponseCode(500).setBody("stale-failure"));
        destination.enqueue(new MockResponse().setResponseCode(200).setBody("fresh"));
        Project project = project(6);
        UUID eventId = ingest(project, 1).get(0);
        workerA.setBeforeSettle(() -> {
            workerA.setBeforeSettle(null);
            age(eventId);
            workerB.processBatch();
        });

        workerA.processBatch();

        assertThat(destination.getRequestCount()).isEqualTo(2);
        var attempts = attemptRepository.findByEventIdOrderByAttemptNumberAsc(eventId);
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0).getErrorMessage()).isEqualTo("lease_expired");
        assertThat(attempts.get(0).getResponseBodySnippet()).isNull();
        assertThat(attempts.get(1).getHttpStatus()).isEqualTo(200);
        assertThat(jobRepository.findByEventIdOrderByAvailableAtAsc(eventId)).singleElement()
                .extracting(job -> job.getState()).isEqualTo(JobState.DONE);
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.DELIVERED);
    }

    @Test
    void crashAfterClaimIsRecoveredWithoutSendingTwice() {
        destination.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        Project project = project(6);
        UUID eventId = ingest(project, 1).get(0);
        workerA.setBeforeSend(() -> {
            throw new IllegalStateException("simulated crash after claim");
        });

        assertThatThrownBy(() -> workerA.processBatch()).hasMessageContaining("simulated crash");

        assertThat(destination.getRequestCount()).isZero();
        assertThat(jobRepository.findByEventIdOrderByAvailableAtAsc(eventId)).singleElement()
                .extracting(job -> job.getState()).isEqualTo(JobState.IN_PROGRESS);
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.DELIVERING);

        age(eventId);
        workerB.processBatch();

        assertThat(destination.getRequestCount()).isEqualTo(1);
        var attempts = attemptRepository.findByEventIdOrderByAttemptNumberAsc(eventId);
        assertThat(attempts).extracting(DeliveryAttempt::getErrorMessage).containsExactly("lease_expired", null);
        assertThat(attempts.get(1).getHttpStatus()).isEqualTo(200);
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.DELIVERED);
    }

    @Test
    void crashAfterTheDestinationReceivedItMeansAtLeastOnceRedelivery() throws Exception {
        destination.enqueue(new MockResponse().setResponseCode(200).setBody("received"));
        destination.enqueue(new MockResponse().setResponseCode(200).setBody("received-again"));
        Project project = project(6);
        UUID eventId = ingest(project, 1).get(0);
        workerA.setBeforeSettle(() -> {
            throw new IllegalStateException("simulated crash before settle");
        });

        assertThatThrownBy(() -> workerA.processBatch()).hasMessageContaining("simulated crash");
        assertThat(destination.getRequestCount()).isEqualTo(1);
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.DELIVERING);

        workerA.setBeforeSettle(null);
        age(eventId);
        workerB.processBatch();

        assertThat(destination.getRequestCount()).isEqualTo(2);
        RecordedRequest first = destination.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest second = destination.takeRequest(1, TimeUnit.SECONDS);
        assertThat(first.getHeader("X-HookGuard-Event-Id")).isEqualTo(second.getHeader("X-HookGuard-Event-Id"));
        assertThat(first.getHeader("X-HookGuard-Attempt")).isEqualTo("1");
        assertThat(second.getHeader("X-HookGuard-Attempt")).isEqualTo("2");
        assertThat(attemptRepository.findByEventIdOrderByAttemptNumberAsc(eventId))
                .extracting(DeliveryAttempt::getErrorMessage).containsExactly("lease_expired", null);
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.DELIVERED);
    }

    @Test
    void repeatedCrashesExhaustTheAttemptBudget() {
        Project project = project(2);
        UUID eventId = ingest(project, 1).get(0);
        Runnable crash = () -> {
            throw new IllegalStateException("simulated crash");
        };
        workerA.setBeforeSend(crash);
        workerB.setBeforeSend(crash);

        assertThatThrownBy(() -> workerA.processBatch()).hasMessageContaining("simulated crash");
        age(eventId);
        assertThatThrownBy(() -> workerB.processBatch()).hasMessageContaining("simulated crash");
        age(eventId);
        workerA.setBeforeSend(null);
        workerA.processBatch();

        assertThat(destination.getRequestCount()).isZero();
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.DEAD);
        assertThat(jobRepository.findByEventIdOrderByAvailableAtAsc(eventId)).singleElement()
                .extracting(job -> job.getState()).isEqualTo(JobState.DEAD);
        assertThat(attemptRepository.findByEventIdOrderByAttemptNumberAsc(eventId))
                .extracting(DeliveryAttempt::getErrorMessage).containsExactly("lease_expired", "lease_expired");
    }

    @Test
    void totalAttemptBudgetPerEventIsEnforcedWhenClaiming() {
        Project project = project(6);
        UUID eventId = ingest(project, 1).get(0);
        for (int i = 1; i <= DeliveryWorker.MAX_ATTEMPTS_PER_EVENT; i++) {
            jdbc.update("INSERT INTO delivery_attempts (id, event_id, attempt_number, started_at, finished_at, http_status) VALUES (?, ?, ?, now(), now(), 500)",
                    UUID.randomUUID(), eventId, i);
        }

        workerA.processBatch();

        assertThat(destination.getRequestCount()).isZero();
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.DEAD);
        assertThat(attemptRepository.countByEventId(eventId)).isEqualTo(DeliveryWorker.MAX_ATTEMPTS_PER_EVENT);
    }

    @Test
    void renewedLeaseIsNotExpiredByAConcurrentReaper() {
        destination.enqueue(new MockResponse().setResponseCode(200));
        Project project = project(6);
        UUID eventId = ingest(project, 1).get(0);
        workerA.setBeforeSend(() -> {
            // Another worker sees the claim as stale only if it is stale at update time.
            age(eventId);
        });
        workerA.setBeforeSettle(() -> workerB.processBatch());

        workerA.processBatch();

        assertThat(destination.getRequestCount()).isEqualTo(1);
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.DELIVERED);
        assertThat(attemptRepository.findByEventIdOrderByAttemptNumberAsc(eventId))
                .singleElement().satisfies(attempt -> assertThat(attempt.getHttpStatus()).isEqualTo(200));
    }

    @Test
    void pauseBetweenRenewalAndSendThatEatsTheMarginPreventsTheRequest() {
        destination.enqueue(new MockResponse().setResponseCode(200).setBody("from-b"));
        Project project = project(6);
        UUID eventId = ingest(project, 1).get(0);
        HookguardProperties shortLease = new HookguardProperties(
                new HookguardProperties.Worker(false, 200, 5, 7), properties.delivery(), properties.retention(),
                properties.billing(), properties.auth(), properties.rateLimit(), properties.ingest(), properties.totalrecall());
        DeliveryWorker paused = new DeliveryWorker(jobRepository, eventRepository, projectRepository, attemptRepository,
                deliveryClient, shortLease, objectMapper, meterRegistry, transactionManager);
        paused.setAfterRenew(() -> {
            try {
                Thread.sleep(1600); // stands in for a long pause after the renewal (lease budget here: 2 s)
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        paused.processBatch();

        assertThat(destination.getRequestCount()).isZero();
        assertThat(jobRepository.findByEventIdOrderByAvailableAtAsc(eventId)).singleElement()
                .extracting(job -> job.getState()).isEqualTo(JobState.IN_PROGRESS);
        age(eventId);
        workerB.processBatch();
        assertThat(destination.getRequestCount()).isEqualTo(1);
        assertThat(attemptRepository.findByEventIdOrderByAttemptNumberAsc(eventId))
                .extracting(DeliveryAttempt::getErrorMessage).containsExactly("lease_expired", null);
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.DELIVERED);
    }

    private void age(UUID eventId) {
        jdbc.update("UPDATE delivery_jobs SET locked_at = ? WHERE event_id = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)), eventId);
    }

    private Project project(int maxAttempts) {
        var account = projectService.ensureAccount("lease-" + UUID.randomUUID() + "@example.com", "Lease");
        jdbc.update("UPDATE accounts SET plan = 'pro' WHERE id = ?", account.getId());
        Project project = projectService.create(account.getId(), "p", destination.url("/hook").toString(), null);
        return projectService.update(account.getId(), project.getId(), destination.url("/hook").toString(), 3000, maxAttempts, null);
    }

    private List<UUID> ingest(Project project, int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(ingestService.accept(project.getProjectKey(), Map.of(), ("{\"n\":" + i + "}").getBytes(), "application/json"));
        }
        return ids;
    }
}

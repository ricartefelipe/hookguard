package br.com.ricarte.hookguard.billing;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.domain.Account;
import br.com.ricarte.hookguard.domain.AccountRepository;
import br.com.ricarte.hookguard.domain.DeliveryJob;
import br.com.ricarte.hookguard.domain.DeliveryJobRepository;
import br.com.ricarte.hookguard.domain.JobState;
import br.com.ricarte.hookguard.domain.Project;
import br.com.ricarte.hookguard.events.ReplayService;
import br.com.ricarte.hookguard.ingest.IngestService;
import br.com.ricarte.hookguard.project.ProjectService;
import br.com.ricarte.hookguard.support.DatabaseCleaner;
import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses a local stand-in for the Stripe endpoint; no real Stripe call is made. */
class UsageOutboxIntegrationTest extends PostgresIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private IngestService ingestService;
    @Autowired private UsageService usageService;
    @Autowired private ReplayService replayService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private DeliveryJobRepository jobRepository;
    @Autowired private HookguardProperties properties;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleaner cleaner;

    private MockWebServer stripe;
    private Account account;
    private Project project;

    @BeforeEach
    void setUp() throws Exception {
        cleaner.clean();
        stripe = new MockWebServer();
        stripe.start();
        account = projectService.ensureAccount("bill-" + UUID.randomUUID() + "@example.com", "Bill");
        jdbc.update("UPDATE accounts SET plan = 'pro', stripe_customer_id = 'cus_test_123' WHERE id = ?", account.getId());
        project = projectService.create(account.getId(), "p", "https://example.com/h", "X-Id");
        // The plan includes 100 events in the test profile: start right at the edge.
        jdbc.update("INSERT INTO usage_monthly (account_id, year_month, event_count) VALUES (?, to_char(now() at time zone 'utc', 'YYYY-MM'), 100)",
                account.getId());
    }

    @AfterEach
    void tearDown() throws Exception {
        stripe.shutdown();
    }

    private UsageOutbox outbox(long timeoutMs) {
        HookguardProperties.Billing b = properties.billing();
        HookguardProperties configured = new HookguardProperties(properties.worker(), properties.delivery(), properties.retention(),
                new HookguardProperties.Billing(b.freeMonthlyEvents(), b.proMonthlyEvents(), b.businessMonthlyEvents(),
                        "sk_test_local", "", "", "", "api_requests"),
                properties.auth(), properties.rateLimit(), properties.ingest(), properties.totalrecall());
        StripeUsageReporter reporter = new StripeUsageReporter(configured, stripe.url("/").toString().replaceAll("/$", ""), timeoutMs);
        return new UsageOutbox(jdbc, accountRepository, reporter);
    }

    private UUID ingestOne(String key) {
        return ingestService.accept(project.getProjectKey(), Map.of("X-Id", key), ("{\"k\":\"" + key + "\"}").getBytes(), "application/json");
    }

    private String state(UUID eventId) {
        return jdbc.queryForObject("SELECT state FROM usage_outbox WHERE event_id = ?", String.class, eventId);
    }

    private void makeDue() {
        jdbc.update("UPDATE usage_outbox SET next_attempt_at = now() - interval '1 second' WHERE state = 'pending'");
    }

    @Test
    void rolledBackIngestionLeavesNoIntent() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID eventId = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            usageService.assertWithinQuotaAndIncrement(account.getId(), eventId);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_outbox", Integer.class)).isZero();
        assertThat(usageService.currentUsage(account.getId())).isEqualTo(100);
        assertThat(stripe.getRequestCount()).isZero();
    }

    @Test
    void intentIsCommittedWithTheEventAndSurvivesUntilSent() throws Exception {
        UUID eventId = ingestOne("a");
        assertThat(state(eventId)).isEqualTo("pending");
        assertThat(stripe.getRequestCount()).isZero();

        stripe.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        // A fresh sender instance stands in for a restarted process.
        assertThat(outbox(2000).processDue(10)).isEqualTo(1);

        assertThat(state(eventId)).isEqualTo("sent");
        RecordedRequest request = stripe.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getPath()).isEqualTo("/v1/billing/meter_events");
        assertThat(request.getHeader("Idempotency-Key")).isNull();
        assertThat(request.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("event_name=api_requests").contains("identifier=" + eventId)
                .contains("payload[stripe_customer_id]=cus_test_123").contains("payload[value]=1").contains("timestamp=");
        assertThat(outbox(2000).processDue(10)).isZero();
    }

    @Test
    void dedupeRepeatsAndReplaysAddNoUsage() {
        UUID eventId = ingestOne("same");
        ingestOne("same");
        jdbc.update("UPDATE delivery_jobs SET state = 'dead' WHERE event_id = ?", eventId);
        replayService.replay(account.getId(), eventId, "again");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_outbox", Integer.class)).isEqualTo(1);
        assertThat(usageService.currentUsage(account.getId())).isEqualTo(101);
    }

    @Test
    void eventsInsideTheIncludedQuotaAreNotBilled() {
        jdbc.update("UPDATE usage_monthly SET event_count = 10 WHERE account_id = ?", account.getId());
        ingestOne("inside");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_outbox", Integer.class)).isZero();
    }

    @Test
    void timeoutAfterStripeAcceptedDoesNotDuplicateOnRetry() throws Exception {
        UUID eventId = ingestOne("timeout");
        Set<String> recorded = ConcurrentHashMap.newKeySet();
        List<String> attempts = new CopyOnWriteArrayList<>();
        stripe.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String identifier = request.getBody().readUtf8().replaceAll(".*identifier=([^&]*).*", "$1");
                attempts.add(identifier);
                boolean firstTime = recorded.add(identifier); // Stripe-like: records once per identifier
                MockResponse response = new MockResponse().setResponseCode(200).setBody("{}");
                return firstTime ? response.setHeadersDelay(1500, TimeUnit.MILLISECONDS) : response;
            }
        });
        UsageOutbox sender = outbox(300);

        sender.processDue(10);
        assertThat(state(eventId)).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT ambiguous FROM usage_outbox WHERE event_id = ?", Boolean.class, eventId)).isTrue();

        makeDue();
        sender.processDue(10);

        assertThat(state(eventId)).isEqualTo("sent");
        assertThat(attempts).hasSize(2).containsOnly(eventId.toString());
        assertThat(recorded).containsExactly(eventId.toString());
    }

    @Test
    void throttlingServerErrorsAndRejectionsStayVisibleInTheLedger() {
        UUID throttled = ingestOne("t429");
        stripe.enqueue(new MockResponse().setResponseCode(429));
        outbox(2000).processDue(10);
        assertThat(state(throttled)).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT last_http_status FROM usage_outbox WHERE event_id = ?", Integer.class, throttled)).isEqualTo(429);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at > now() FROM usage_outbox WHERE event_id = ?", Boolean.class, throttled)).isTrue();
        assertThat(jdbc.queryForObject("SELECT ambiguous FROM usage_outbox WHERE event_id = ?", Boolean.class, throttled)).isFalse();

        UUID broken = ingestOne("t500");
        makeDue();
        stripe.enqueue(new MockResponse().setResponseCode(200));
        stripe.enqueue(new MockResponse().setResponseCode(500));
        outbox(2000).processDue(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_outbox WHERE state = 'pending' AND ambiguous", Integer.class)).isGreaterThanOrEqualTo(1);

        jdbc.update("DELETE FROM usage_outbox");
        UUID rejected = ingestOne("t400");
        stripe.enqueue(new MockResponse().setResponseCode(400));
        outbox(2000).processDue(10);
        assertThat(state(rejected)).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT last_error FROM usage_outbox WHERE event_id = ?", String.class, rejected)).isEqualTo("http_400");
        assertThat(broken).isNotNull();
    }

    @Test
    void refusalAfterAnAmbiguousAttemptGoesToReviewWithTheStripeCode() {
        UUID eventId = ingestOne("dupe");
        jdbc.update("UPDATE usage_outbox SET state = 'pending', ambiguous = true, attempts = 1, first_attempt_at = now() - interval '1 hour', next_attempt_at = now() - interval '1 second'");
        stripe.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"some_code\",\"message\":\"private text\"}}"));
        outbox(2000).processDue(10);
        assertThat(state(eventId)).isEqualTo("review");
        assertThat(jdbc.queryForObject("SELECT last_error FROM usage_outbox WHERE event_id = ?", String.class, eventId)).isEqualTo("http_400:some_code");
    }

    @Test
    void ambiguousAttemptOlderThanTheIdentifierWindowGoesToReviewInsteadOfResending() {
        UUID eventId = ingestOne("old");
        jdbc.update("UPDATE usage_outbox SET state = 'pending', ambiguous = true, attempts = 3, first_attempt_at = now() - interval '24 hours', next_attempt_at = now() - interval '1 second'");
        stripe.enqueue(new MockResponse().setResponseCode(503));
        outbox(2000).processDue(10);
        assertThat(state(eventId)).isEqualTo("review");
    }

    @Test
    void staleSendingRowIsReclaimedAndMarkedAmbiguous() {
        UUID eventId = ingestOne("crash");
        jdbc.update("UPDATE usage_outbox SET state = 'sending', locked_by = 'dead-reporter', locked_at = now() - interval '10 minutes', attempts = 1, first_attempt_at = now() - interval '10 minutes'");
        stripe.enqueue(new MockResponse().setResponseCode(200));
        outbox(2000).processDue(10);
        assertThat(state(eventId)).isEqualTo("sent");
        assertThat(jdbc.queryForObject("SELECT ambiguous FROM usage_outbox WHERE event_id = ?", Boolean.class, eventId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM usage_outbox WHERE event_id = ?", Integer.class, eventId)).isEqualTo(2);
    }

    @Test
    void freshSendingRowOfAnotherReporterIsLeftAlone() {
        UUID eventId = ingestOne("busy");
        jdbc.update("UPDATE usage_outbox SET state = 'sending', locked_by = 'other', locked_at = now(), attempts = 1, first_attempt_at = now()");
        assertThat(outbox(2000).processDue(10)).isZero();
        assertThat(state(eventId)).isEqualTo("sending");
        assertThat(stripe.getRequestCount()).isZero();
    }

    @Test
    void concurrentReportersSendEachIntentExactlyOnce() throws Exception {
        List<UUID> events = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            events.add(ingestOne("c" + i));
        }
        Map<String, Integer> perIdentifier = new ConcurrentHashMap<>();
        stripe.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                perIdentifier.merge(request.getBody().readUtf8().replaceAll(".*identifier=([^&]*).*", "$1"), 1, Integer::sum);
                return new MockResponse().setResponseCode(200).setBody("{}");
            }
        });
        int reporters = 4;
        ExecutorService pool = Executors.newFixedThreadPool(reporters);
        CyclicBarrier barrier = new CyclicBarrier(reporters);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < reporters; i++) {
                UsageOutbox sender = outbox(3000);
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    while (sender.processDue(5) > 0) {
                        // drain
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(perIdentifier).hasSize(24).allSatisfy((id, count) -> assertThat(count).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_outbox WHERE state = 'sent'", Integer.class)).isEqualTo(24);
    }

    @Test
    void missingCustomerOrExpiredTimestampDoNotLoseOrMisreport() {
        jdbc.update("UPDATE accounts SET stripe_customer_id = NULL WHERE id = ?", account.getId());
        UUID noCustomer = ingestOne("nocus");
        UsageOutbox sender = outbox(2000);
        sender.processDue(10);
        assertThat(state(noCustomer)).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT attempts FROM usage_outbox WHERE event_id = ?", Integer.class, noCustomer)).isZero();
        assertThat(stripe.getRequestCount()).isZero();

        jdbc.update("UPDATE accounts SET stripe_customer_id = 'cus_test_123' WHERE id = ?", account.getId());
        jdbc.update("UPDATE usage_outbox SET occurred_at = ?, next_attempt_at = now() - interval '1 second'",
                Timestamp.from(Instant.now().minusSeconds(31L * 24 * 3600)));
        sender.processDue(10);
        assertThat(state(noCustomer)).isEqualTo("failed");
        assertThat(stripe.getRequestCount()).isZero();
    }

    @Test
    void nothingIsSentWhileStripeIsNotConfigured() {
        UUID eventId = ingestOne("unconfigured");
        StripeUsageReporter unconfigured = new StripeUsageReporter(properties, stripe.url("/").toString(), 1000);
        assertThat(new UsageOutbox(jdbc, accountRepository, unconfigured).processDue(10)).isZero();
        assertThat(state(eventId)).isEqualTo("pending");
    }

    @Test
    void summaryListsWhatNeedsAttention() {
        UUID eventId = ingestOne("sum");
        jdbc.update("UPDATE usage_outbox SET state = 'review', last_error = 'IOException'");
        Map<String, Object> summary = outbox(1000).summary(account.getId());
        assertThat(summary.toString()).contains("review=1").contains(eventId.toString());
    }
}

package br.com.ricarte.hookguard.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.ricarte.hookguard.domain.AccountRepository;
import br.com.ricarte.hookguard.project.ProjectService;
import br.com.ricarte.hookguard.support.DatabaseCleaner;
import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The ambiguity window is enforced at the moment of sending, with a controllable clock and a reporter
 * double that records every call. "No call" is asserted on the double, not inferred from the final state.
 */
class UsageOutboxAmbiguityTest extends PostgresIntegrationTest {

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        void advance(Duration by) {
            now.updateAndGet(i -> i.plus(by));
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now.get();
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ProjectService projectService;
    @Autowired private DatabaseCleaner cleaner;

    private final MutableClock clock = new MutableClock();
    private StripeUsageReporter reporter;
    private UsageOutbox outbox;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        accountId = projectService.ensureAccount("amb-" + UUID.randomUUID() + "@example.com", "A").getId();
        jdbc.update("UPDATE accounts SET plan = 'pro', stripe_customer_id = 'cus_synthetic' WHERE id = ?", accountId);
        reporter = mock(StripeUsageReporter.class);
        when(reporter.configured()).thenReturn(true);
        when(reporter.report(anyString(), anyString(), any()))
                .thenReturn(new StripeUsageReporter.Outcome(StripeUsageReporter.Kind.SENT, 200, null));
        outbox = new UsageOutbox(jdbc, accountRepository, reporter, clock);
    }

    private UUID row(String state, boolean ambiguous, Duration firstAttemptAgo, String lastError) {
        UUID eventId = UUID.randomUUID();
        Instant first = clock.instant().minus(firstAttemptAgo);
        jdbc.update("""
                INSERT INTO usage_outbox (id, account_id, event_id, identifier, occurred_at, state, attempts, ambiguous,
                       next_attempt_at, first_attempt_at, last_attempt_at, locked_by, locked_at, last_error, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, now() - interval '1 second', ?, ?, ?, ?, ?, now())
                """,
                UUID.randomUUID(), accountId, eventId, eventId.toString(), Timestamp.from(clock.instant()), state, ambiguous,
                Timestamp.from(first), Timestamp.from(first),
                state.equals("sending") ? "dead-reporter" : null,
                state.equals("sending") ? Timestamp.from(Instant.now().minusSeconds(3600)) : null,
                lastError);
        return eventId;
    }

    private String col(UUID eventId, String column) {
        return jdbc.queryForObject("SELECT " + column + "::text FROM usage_outbox WHERE event_id = ?", String.class, eventId);
    }

    @Test
    void ambiguousAttemptTwentyFiveHoursOldIsHeldWithoutCallingTheProvider() {
        UUID id = row("pending", true, Duration.ofHours(25), "IOException");

        assertThat(outbox.processDue(10)).isEqualTo(1);

        verify(reporter, never()).report(anyString(), anyString(), any());
        assertThat(col(id, "state")).isEqualTo("review");
        assertThat(col(id, "last_error")).startsWith("ambiguous_window_expired").contains("IOException");
        assertThat(col(id, "ambiguous")).isEqualTo("true");
        assertThat(col(id, "attempts")).isEqualTo("1");
    }

    @Test
    void staleSendingRowWhoseFirstAttemptIsOutsideTheWindowIsHeldWithoutCalls() {
        UUID id = row("sending", false, Duration.ofHours(25), null);

        outbox.processDue(10);

        verify(reporter, never()).report(anyString(), anyString(), any());
        assertThat(col(id, "state")).isEqualTo("review");
        assertThat(col(id, "ambiguous")).isEqualTo("true");
    }

    @Test
    void windowBoundaryIsExclusiveAtTwentyThreeHours() {
        UUID atLimit = row("pending", true, UsageOutbox.AMBIGUOUS_RETRY_LIMIT, null);
        outbox.processDue(10);
        verify(reporter, never()).report(anyString(), anyString(), any());
        assertThat(col(atLimit, "state")).isEqualTo("review");

        UUID justBefore = row("pending", true, UsageOutbox.AMBIGUOUS_RETRY_LIMIT.minusSeconds(1), null);
        outbox.processDue(10);
        verify(reporter, times(1)).report(anyString(), anyString(), any());
        assertThat(col(justBefore, "state")).isEqualTo("sent");
    }

    @Test
    void retryInsideTheWindowIsAllowedAndKeepsTheAmbiguityFlag() {
        UUID id = row("pending", true, Duration.ofHours(2), "IOException");
        outbox.processDue(10);
        verify(reporter, times(1)).report(eq("cus_synthetic"), eq(id.toString()), any());
        assertThat(col(id, "state")).isEqualTo("sent");
        assertThat(col(id, "ambiguous")).isEqualTo("true");
    }

    @Test
    void rowThatCrossesTheLimitWhileWaitingForItsTurnIsNotSent() {
        UUID first = row("pending", false, Duration.ofMinutes(1), null);
        UUID waiting = row("pending", true, UsageOutbox.AMBIGUOUS_RETRY_LIMIT.minusMinutes(30), "IOException");
        jdbc.update("UPDATE usage_outbox SET next_attempt_at = now() - interval '10 seconds' WHERE event_id = ?", first);
        // the row claimed first takes "an hour" to be processed: when the second one's turn comes it is over the limit
        when(reporter.report(anyString(), anyString(), any())).thenAnswer(call -> {
            clock.advance(Duration.ofHours(1));
            return new StripeUsageReporter.Outcome(StripeUsageReporter.Kind.SENT, 200, null);
        });

        assertThat(outbox.processDue(10)).isEqualTo(2);

        verify(reporter, times(1)).report(anyString(), anyString(), any());
        assertThat(col(first, "state")).isEqualTo("sent");
        assertThat(col(waiting, "state")).isEqualTo("review");
        assertThat(col(waiting, "last_error")).startsWith("ambiguous_window_expired");
    }

    @Test
    void reporterThatLostItsReservationDoesNotSend() {
        UUID id = row("pending", false, Duration.ofMinutes(1), null);
        outbox.setBeforeSend(() -> jdbc.update("UPDATE usage_outbox SET locked_by = 'another-reporter', locked_at = now() WHERE event_id = ?", id));

        outbox.processDue(10);

        verify(reporter, never()).report(anyString(), anyString(), any());
        assertThat(col(id, "locked_by")).isEqualTo("another-reporter");
        assertThat(col(id, "state")).isEqualTo("sending");
    }

    @Test
    void lateResultOfAReporterThatLostTheRowIsDiscarded() {
        UUID id = row("pending", false, Duration.ofMinutes(1), null);
        when(reporter.report(anyString(), anyString(), any())).thenAnswer(call -> {
            // while the call is in flight the reservation is taken over, and the new owner settles the row as a failure
            jdbc.update("UPDATE usage_outbox SET locked_by = 'another-reporter', locked_at = now() WHERE event_id = ?", id);
            return new StripeUsageReporter.Outcome(StripeUsageReporter.Kind.SENT, 200, null);
        });

        outbox.processDue(10);

        assertThat(col(id, "state")).isEqualTo("sending");
        assertThat(col(id, "locked_by")).isEqualTo("another-reporter");
        assertThat(col(id, "sent_at")).isNull();
    }

    @Test
    void refusalAfterAnUncertainAttemptKeepsItsHistoryAndDoesNotAuthoriseAResend() {
        UUID id = row("pending", true, Duration.ofHours(1), "SocketTimeoutException");
        when(reporter.report(anyString(), anyString(), any()))
                .thenReturn(new StripeUsageReporter.Outcome(StripeUsageReporter.Kind.REJECTED, 400, "http_400:some_code"));

        outbox.processDue(10);

        assertThat(col(id, "state")).isEqualTo("review");
        assertThat(col(id, "ambiguous")).isEqualTo("true");
        assertThat(col(id, "last_error")).startsWith("http_400:some_code").contains("SocketTimeoutException");
        // a review row is never picked up again by the sender
        outbox.processDue(10);
        verify(reporter, times(1)).report(anyString(), anyString(), any());
    }
}

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
import java.time.temporal.ChronoUnit;
import java.util.List;
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
        // PostgreSQL stores microseconds: a clock with nanoseconds would be rounded by the database and move
        // the 23 h boundary relative to the in-memory instant. Start on a whole microsecond and only add whole durations.
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MICROS));

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
        Duration limit = UsageOutbox.AMBIGUOUS_RETRY_LIMIT;
        UUID atLimit = row("pending", true, limit, null);
        // the persisted instant is exactly the one the clock produced: no rounding moves the boundary
        assertThat(storedFirstAttempt(atLimit)).isEqualTo(clock.instant().minus(limit));
        outbox.processDue(10);
        verify(reporter, never()).report(anyString(), anyString(), any());
        assertThat(col(atLimit, "state")).isEqualTo("review");

        UUID afterLimit = row("pending", true, limit.plusNanos(1_000), null); // one microsecond past it
        outbox.processDue(10);
        verify(reporter, never()).report(anyString(), anyString(), any());
        assertThat(col(afterLimit, "state")).isEqualTo("review");

        UUID justBefore = row("pending", true, limit.minusNanos(1_000), null); // one microsecond short of it
        assertThat(storedFirstAttempt(justBefore)).isEqualTo(clock.instant().minus(limit.minusNanos(1_000)));
        outbox.processDue(10);
        verify(reporter, times(1)).report(anyString(), anyString(), any());
        assertThat(col(justBefore, "state")).isEqualTo("sent");
    }

    private Instant storedFirstAttempt(UUID eventId) {
        return jdbc.queryForObject("SELECT first_attempt_at FROM usage_outbox WHERE event_id = ?", Timestamp.class, eventId).toInstant();
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
    void rowsReservedInsideTheWindowAreHeldIfTheClockCrossesTheLimitBeforeTheSend() {
        // two reserved rows, both inside the window (22.5 h) when claimed; neither order of processing matters
        UUID a = row("pending", true, UsageOutbox.AMBIGUOUS_RETRY_LIMIT.minusMinutes(30), "IOException");
        UUID b = row("pending", true, UsageOutbox.AMBIGUOUS_RETRY_LIMIT.minusMinutes(30), "IOException");
        AtomicReference<Integer> reservedWhenClockMoved = new AtomicReference<>();
        // the hook runs after the claim and before the first send decision: the reservations exist, then time passes
        outbox.setBeforeSend(() -> {
            if (reservedWhenClockMoved.compareAndSet(null, jdbc.queryForObject(
                    "SELECT count(*) FROM usage_outbox WHERE state = 'sending' AND locked_by LIKE 'reporter-%'", Integer.class))) {
                clock.advance(Duration.ofHours(1));
            }
        });

        assertThat(outbox.processDue(10)).isEqualTo(2);

        assertThat(reservedWhenClockMoved.get()).isEqualTo(2);
        verify(reporter, never()).report(anyString(), anyString(), any());
        for (UUID id : new UUID[] {a, b}) {
            assertThat(col(id, "state")).isEqualTo("review");
            assertThat(col(id, "last_error")).startsWith("ambiguous_window_expired").contains("IOException");
        }
    }

    @Test
    void aRowThatWaitedBehindAnotherSendIsHeldWhenTheSendTookLongEnough() {
        // two symmetric rows, so the result does not depend on which one the database returns first:
        // whichever is sent first takes "an hour", and the other one is then over the limit
        UUID a = row("pending", true, UsageOutbox.AMBIGUOUS_RETRY_LIMIT.minusMinutes(30), "IOException");
        UUID b = row("pending", true, UsageOutbox.AMBIGUOUS_RETRY_LIMIT.minusMinutes(30), "IOException");
        when(reporter.report(anyString(), anyString(), any())).thenAnswer(call -> {
            clock.advance(Duration.ofHours(1));
            return new StripeUsageReporter.Outcome(StripeUsageReporter.Kind.SENT, 200, null);
        });

        assertThat(outbox.processDue(10)).isEqualTo(2);

        verify(reporter, times(1)).report(anyString(), anyString(), any());
        List<String> states = List.of(col(a, "state"), col(b, "state"));
        assertThat(states).containsExactlyInAnyOrder("sent", "review");
        UUID held = col(a, "state").equals("review") ? a : b;
        assertThat(col(held, "last_error")).startsWith("ambiguous_window_expired");
        verify(reporter, never()).report(anyString(), org.mockito.ArgumentMatchers.eq(held.toString()), any());
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

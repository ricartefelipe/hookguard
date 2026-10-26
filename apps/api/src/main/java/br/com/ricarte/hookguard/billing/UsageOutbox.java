package br.com.ricarte.hookguard.billing;

import br.com.ricarte.hookguard.domain.Account;
import br.com.ricarte.hookguard.domain.AccountRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Durable ledger of metered usage owed to Stripe. A row is written in the same transaction that
 * counts the accepted event, and is sent later by {@link #processDue(int)}, outside the ingest path.
 *
 * States: pending -> sending -> sent | failed | review. "review" means an earlier attempt may have
 * been recorded by Stripe and the identifier window (24 h) is over, so a person must reconcile it.
 */
@Service
public class UsageOutbox {

    private static final Logger log = LoggerFactory.getLogger(UsageOutbox.class);

    static final Duration LEASE = Duration.ofMinutes(2);
    /** Stripe guarantees identifier uniqueness for at least 24 h; stop re-sending ambiguous attempts before that. */
    static final Duration AMBIGUOUS_RETRY_LIMIT = Duration.ofHours(23);
    /** Stripe rejects timestamps older than 35 days; give up a little earlier. */
    static final Duration MAX_AGE = Duration.ofDays(30);
    static final int MAX_AUTH_ATTEMPTS = 20;

    private final JdbcTemplate jdbc;
    private final AccountRepository accountRepository;
    private final StripeUsageReporter reporter;
    private final Clock clock;
    private volatile Runnable beforeSend = () -> {
    };

    @Autowired
    public UsageOutbox(JdbcTemplate jdbc, AccountRepository accountRepository, StripeUsageReporter reporter) {
        this(jdbc, accountRepository, reporter, Clock.systemUTC());
    }

    UsageOutbox(JdbcTemplate jdbc, AccountRepository accountRepository, StripeUsageReporter reporter, Clock clock) {
        this.jdbc = jdbc;
        this.accountRepository = accountRepository;
        this.reporter = reporter;
        this.clock = clock;
    }

    /** Test hook: runs for each claimed row just before the send checks. */
    void setBeforeSend(Runnable beforeSend) {
        this.beforeSend = beforeSend == null ? () -> {
        } : beforeSend;
    }

    /** Called inside the ingest transaction; idempotent per event. */
    public void enqueue(UUID accountId, UUID eventId, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO usage_outbox (id, account_id, event_id, identifier, occurred_at, next_attempt_at, created_at)
                VALUES (?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (event_id) DO NOTHING
                """,
                UUID.randomUUID(), accountId, eventId, eventId.toString(), Timestamp.from(occurredAt));
    }

    private record Claimed(UUID id, UUID accountId, String identifier, Instant occurredAt, int attempts,
                           Instant firstAttemptAt, boolean ambiguous, String previousError) {
    }

    /** Sends due rows; safe to run from several instances at once. Returns how many rows were handled. */
    public int processDue(int limit) {
        if (!reporter.configured()) {
            return 0;
        }
        String token = "reporter-" + UUID.randomUUID();
        List<Claimed> claimed = claim(token, limit);
        for (Claimed row : claimed) {
            handle(token, row);
        }
        return claimed.size();
    }

    private List<Claimed> claim(String token, int limit) {
        return jdbc.query("""
                UPDATE usage_outbox
                   SET ambiguous = ambiguous OR state = 'sending',
                       state = 'sending',
                       locked_by = ?,
                       locked_at = now(),
                       attempts = attempts + 1,
                       first_attempt_at = COALESCE(first_attempt_at, now()),
                       last_attempt_at = now()
                 WHERE id IN (
                       SELECT id FROM usage_outbox
                        WHERE (state = 'pending' AND next_attempt_at <= now())
                           OR (state = 'sending' AND locked_at < now() - make_interval(secs => ?))
                        ORDER BY next_attempt_at, id
                        FOR UPDATE SKIP LOCKED
                        LIMIT ?)
                RETURNING id, account_id, identifier, occurred_at, attempts, first_attempt_at, ambiguous, last_error
                """,
                (rs, n) -> new Claimed(
                        rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getString("identifier"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getInt("attempts"),
                        rs.getTimestamp("first_attempt_at").toInstant(),
                        rs.getBoolean("ambiguous"),
                        rs.getString("last_error")),
                token, LEASE.toSeconds(), limit);
    }

    private void handle(String token, Claimed row) {
        beforeSend.run();
        Account account = accountRepository.findById(row.accountId()).orElse(null);
        if (account == null || account.getStripeCustomerId() == null || account.getStripeCustomerId().isBlank()) {
            // Nothing was sent, so the attempt does not count; look again later.
            jdbc.update("""
                    UPDATE usage_outbox SET state = 'pending', attempts = attempts - 1, locked_by = NULL, locked_at = NULL,
                           next_attempt_at = now() + interval '1 hour', last_error = 'no_stripe_customer'
                     WHERE id = ? AND locked_by = ? AND state = 'sending'
                    """, row.id(), token);
            return;
        }
        // Ownership and the send windows are checked at the moment of sending, not when the batch was claimed:
        // earlier rows of the batch may have taken long enough for this reservation to go stale or cross a limit.
        if (!confirmOwnership(token, row)) {
            log.warn("Usage report not sent, lease lost before the send identifier={}", row.identifier());
            return;
        }
        Instant now = clock.instant();
        if (row.ambiguous() && !row.firstAttemptAt().plus(AMBIGUOUS_RETRY_LIMIT).isAfter(now)) {
            holdWithoutSending(token, row, "ambiguous_window_expired", "review");
            log.error("Usage report held for manual reconciliation, ambiguous window over identifier={}", row.identifier());
            return;
        }
        if (row.occurredAt().isBefore(now.minus(MAX_AGE))) {
            holdWithoutSending(token, row, "timestamp_window_exceeded", row.ambiguous() ? "review" : "failed");
            log.error("Usage report expired before it could be sent identifier={}", row.identifier());
            return;
        }
        StripeUsageReporter.Outcome outcome = reporter.report(account.getStripeCustomerId(), row.identifier(), row.occurredAt());
        Duration retryIn = backoff(row.attempts());
        String detail = outcome.detail() == null ? null : withHistory(outcome.detail(), row);
        switch (outcome.kind()) {
            case SENT -> settle(token, row, "sent", Duration.ZERO, outcome.httpStatus(), null, row.ambiguous(), clock.instant());
            case REJECTED -> {
                // After an ambiguous attempt a refusal may just be Stripe reporting a duplicate identifier,
                // which would mean the first attempt was recorded. Only a person can tell: keep it for review.
                String state = row.ambiguous() ? "review" : "failed";
                settle(token, row, state, Duration.ZERO, outcome.httpStatus(), detail, row.ambiguous(), null);
                log.error("Stripe refused usage report identifier={} status={} state={}", row.identifier(), outcome.httpStatus(), state);
            }
            case RETRY_LATER -> {
                if (outcome.httpStatus() != null && outcome.httpStatus() != 429 && row.attempts() >= MAX_AUTH_ATTEMPTS) {
                    settle(token, row, "failed", Duration.ZERO, outcome.httpStatus(), detail, row.ambiguous(), null);
                } else {
                    settle(token, row, "pending", retryIn, outcome.httpStatus(), detail, row.ambiguous(), null);
                }
            }
            case AMBIGUOUS -> {
                if (!row.firstAttemptAt().plus(AMBIGUOUS_RETRY_LIMIT).isAfter(clock.instant())) {
                    settle(token, row, "review", Duration.ZERO, outcome.httpStatus(), detail, true, null);
                    log.error("Usage report needs manual reconciliation identifier={}", row.identifier());
                } else {
                    settle(token, row, "pending", retryIn, outcome.httpStatus(), detail, true, null);
                }
            }
        }
    }

    /** Renews the reservation only while this reporter still holds it; false means someone else owns the row now. */
    private boolean confirmOwnership(String token, Claimed row) {
        return jdbc.update("""
                UPDATE usage_outbox SET locked_at = now()
                 WHERE id = ? AND locked_by = ? AND state = 'sending'
                """, row.id(), token) == 1;
    }

    /** Closes the reservation without any call to the provider; the earlier uncertain result stays in last_error. */
    private void holdWithoutSending(String token, Claimed row, String reason, String state) {
        String error = withHistory(reason, row);
        jdbc.update("""
                UPDATE usage_outbox
                   SET state = ?, ambiguous = ?, attempts = attempts - 1, last_error = ?,
                       locked_by = NULL, locked_at = NULL
                 WHERE id = ? AND locked_by = ? AND state = 'sending'
                """, state, row.ambiguous(), error, row.id(), token);
    }

    /** A later outcome must not erase what is known about an earlier uncertain attempt. */
    private static String withHistory(String detail, Claimed row) {
        if (!row.ambiguous() || row.previousError() == null || row.previousError().isBlank()
                || row.previousError().startsWith(detail)) {
            return detail;
        }
        String merged = detail + " | anterior: " + row.previousError();
        return merged.length() > 500 ? merged.substring(0, 500) : merged;
    }

    static Duration backoff(int attempts) {
        long seconds = 60L << Math.min(Math.max(attempts - 1, 0), 6);
        return Duration.ofSeconds(Math.min(seconds, 3600));
    }

    private void settle(String token, Claimed row, String state, Duration delay, Integer httpStatus,
                        String error, boolean ambiguous, Instant sentAt) {
        int updated = jdbc.update("""
                UPDATE usage_outbox
                   SET state = ?, next_attempt_at = now() + make_interval(secs => ?), last_http_status = ?, last_error = ?, ambiguous = ?,
                       sent_at = ?, locked_by = NULL, locked_at = NULL
                 WHERE id = ? AND locked_by = ? AND state = 'sending'
                """,
                state, (double) delay.toSeconds(), httpStatus, error, ambiguous,
                sentAt == null ? null : Timestamp.from(sentAt), row.id(), token);
        if (updated != 1) {
            log.warn("Usage report result ignored, lease lost identifier={}", row.identifier());
        }
    }

    /** Per-account view used by the panel and by reconciliation. */
    public Map<String, Object> summary(UUID accountId) {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String state : List.of("pending", "sending", "sent", "failed", "review")) {
            counts.put(state, 0L);
        }
        jdbc.query("SELECT state, count(*) AS total FROM usage_outbox WHERE account_id = ? GROUP BY state",
                rs -> {
                    counts.put(rs.getString("state"), rs.getLong("total"));
                }, accountId);
        body.put("counts", counts);
        List<Map<String, Object>> attention = new ArrayList<>();
        jdbc.query("""
                SELECT event_id, state, attempts, last_http_status, last_error, created_at
                  FROM usage_outbox WHERE account_id = ? AND state IN ('failed', 'review')
                 ORDER BY created_at DESC LIMIT 50
                """, rs -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("eventId", rs.getString("event_id"));
            item.put("state", rs.getString("state"));
            item.put("attempts", rs.getInt("attempts"));
            item.put("lastHttpStatus", rs.getObject("last_http_status"));
            item.put("lastError", rs.getString("last_error"));
            item.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            attention.add(item);
        }, accountId);
        body.put("needsAttention", attention);
        return body;
    }
}

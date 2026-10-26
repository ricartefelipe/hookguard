package br.com.ricarte.hookguard.ingest;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.web.ApiException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-project request budget shared by every API instance: a fixed 60-second window counted in
 * PostgreSQL with one atomic upsert. The count is committed on its own, so a rejected or failed
 * ingestion still consumes budget. If the database cannot answer, the request is refused (fail closed).
 */
@Component
public class IngestRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(IngestRateLimiter.class);
    static final int WINDOW_SECONDS = 60;

    private static final String UPSERT = """
            INSERT INTO ingest_rate_windows (project_id, window_start, hits)
            VALUES (?, ?, 1)
            ON CONFLICT (project_id, window_start)
            DO UPDATE SET hits = ingest_rate_windows.hits + 1
            WHERE ingest_rate_windows.hits < ?
            RETURNING hits
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transaction;
    private final HookguardProperties properties;
    private final LongSupplier epochSeconds;

    @Autowired
    public IngestRateLimiter(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            HookguardProperties properties
    ) {
        this(jdbcTemplate, transactionManager, properties, () -> Instant.now().getEpochSecond());
    }

    IngestRateLimiter(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            HookguardProperties properties,
            LongSupplier epochSeconds
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.properties = properties;
        this.epochSeconds = epochSeconds;
    }

    public void check(UUID projectId) {
        int limit = properties.rateLimit().ingestPerMinute();
        if (limit <= 0) {
            return;
        }
        long now = epochSeconds.getAsLong();
        long window = now / WINDOW_SECONDS;
        Integer hits;
        try {
            hits = transaction.execute(status -> {
                jdbcTemplate.execute("SET LOCAL lock_timeout = '2s'");
                return jdbcTemplate.query(
                        UPSERT,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        projectId,
                        window,
                        limit
                );
            });
        } catch (DataAccessException | TransactionException ex) {
            log.warn("Ingest rate limiter unavailable projectId={}", projectId);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "rate_limiter_unavailable", 1);
        }
        if (hits == null) {
            int retryAfter = (int) (WINDOW_SECONDS - (now % WINDOW_SECONDS));
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", Math.max(retryAfter, 1));
        }
    }
}

package br.com.ricarte.hookguard.retention;

import br.com.ricarte.hookguard.config.HookguardProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcTemplate jdbcTemplate;
    private final HookguardProperties properties;

    public RetentionJob(JdbcTemplate jdbcTemplate, HookguardProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Scheduled(cron = "0 20 3 * * *")
    @Transactional
    public void purgeOldEvents() {
        Instant cutoff = Instant.now().minus(properties.retention().days(), ChronoUnit.DAYS);
        Instant now = Instant.now();
        int expiredDedupes = jdbcTemplate.update("DELETE FROM ingest_dedupes WHERE expires_at < ?", now);
        jdbcTemplate.update(
                "DELETE FROM ingest_rate_windows WHERE window_start < ?",
                now.getEpochSecond() / 60 - 60
        );
        int dedupes = jdbcTemplate.update(
                "DELETE FROM ingest_dedupes WHERE event_id IN (SELECT id FROM events WHERE received_at < ?)",
                cutoff
        );
        int attempts = jdbcTemplate.update(
                "DELETE FROM delivery_attempts WHERE event_id IN (SELECT id FROM events WHERE received_at < ?)",
                cutoff
        );
        int jobs = jdbcTemplate.update(
                "DELETE FROM delivery_jobs WHERE event_id IN (SELECT id FROM events WHERE received_at < ?)",
                cutoff
        );
        int events = jdbcTemplate.update("DELETE FROM events WHERE received_at < ?", cutoff);
        if (events > 0 || jobs > 0 || attempts > 0 || dedupes > 0 || expiredDedupes > 0) {
            log.info(
                    "Retention purge events={} jobs={} attempts={} dedupes={} expiredDedupes={} cutoff={}",
                    events,
                    jobs,
                    attempts,
                    dedupes,
                    expiredDedupes,
                    cutoff
            );
        }
    }
}

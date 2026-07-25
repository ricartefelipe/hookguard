package br.com.ricarte.hookguard.retention;

import br.com.ricarte.hookguard.config.HookguardProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
    public void purgeOldEvents() {
        Instant cutoff = Instant.now().minus(properties.retention().days(), ChronoUnit.DAYS);
        int attempts = jdbcTemplate.update(
                "DELETE FROM delivery_attempts WHERE event_id IN (SELECT id FROM events WHERE received_at < ?)",
                cutoff
        );
        int jobs = jdbcTemplate.update(
                "DELETE FROM delivery_jobs WHERE event_id IN (SELECT id FROM events WHERE received_at < ?)",
                cutoff
        );
        int events = jdbcTemplate.update("DELETE FROM events WHERE received_at < ?", cutoff);
        if (events > 0 || jobs > 0 || attempts > 0) {
            log.info("Retention purge events={} jobs={} attempts={} cutoff={}", events, jobs, attempts, cutoff);
        }
    }
}

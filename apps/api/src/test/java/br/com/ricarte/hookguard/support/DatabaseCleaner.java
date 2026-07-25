package br.com.ricarte.hookguard.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseCleaner {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void clean() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE delivery_attempts, delivery_jobs, events, usage_monthly, projects, accounts
                CASCADE
                """);
    }
}

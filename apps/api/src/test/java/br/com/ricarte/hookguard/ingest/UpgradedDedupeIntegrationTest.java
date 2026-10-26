package br.com.ricarte.hookguard.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.ricarte.hookguard.MigrationUpgradeTestSupport;
import br.com.ricarte.hookguard.billing.UsageService;
import br.com.ricarte.hookguard.domain.WebhookEventRepository;
import br.com.ricarte.hookguard.support.TempDatabase;
import br.com.ricarte.hookguard.web.ApiException;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The application starts against a database created by the previous release (schema 004 plus data),
 * so Liquibase performs the real upgrade at startup before any request is served.
 */
@SpringBootTest
@ActiveProfiles("test")
class UpgradedDedupeIntegrationTest {

    private static TempDatabase database;
    private static UUID[] ids;

    static {
        try {
            database = TempDatabase.create();
            try (Connection connection = database.connect()) {
                MigrationUpgradeTestSupport.migrateTo(connection, 4);
                ids = MigrationUpgradeTestSupport.seedLegacy(connection);
            }
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::url);
        registry.add("spring.datasource.username", database::user);
        registry.add("spring.datasource.password", database::password);
    }

    @AfterAll
    static void drop() throws Exception {
        database.close();
    }

    @Autowired private IngestService ingestService;
    @Autowired private UsageService usageService;
    @Autowired private WebhookEventRepository eventRepository;
    @Autowired private JdbcTemplate jdbc;

    private String keyOf(UUID projectId) {
        return jdbc.queryForObject("SELECT project_key FROM projects WHERE id = ?", String.class, projectId);
    }

    private void setDedupeHeader(UUID projectId) {
        jdbc.update("UPDATE projects SET dedupe_header = 'X-Idempotency-Key' WHERE id = ?", projectId);
    }

    @Test
    void upgradedInstallationKeepsDedupeAndUsage() {
        UUID account = ids[0];
        UUID projectA = ids[1];
        UUID projectB = ids[2];
        UUID recentEvent = ids[3];
        setDedupeHeader(projectA);
        setDedupeHeader(projectB);
        long events = eventRepository.count();
        long usage = usageService.currentUsage(account);

        UUID again = ingestService.accept(keyOf(projectA), Map.of("X-Idempotency-Key", "order-1"), "{\"v\":1}".getBytes(), "application/json");
        assertThat(again).isEqualTo(recentEvent);
        assertThat(eventRepository.count()).isEqualTo(events);
        assertThat(usageService.currentUsage(account)).isEqualTo(usage);

        assertThatThrownBy(() -> ingestService.accept(keyOf(projectA), Map.of("X-Idempotency-Key", "order-1"), "{\"v\":999}".getBytes(), "application/json"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) ex).getCode()).isEqualTo("dedupe_payload_conflict");
                });
        assertThat(usageService.currentUsage(account)).isEqualTo(usage);

        // The same key in another project is a different namespace and keeps its own event.
        UUID otherProject = ingestService.accept(keyOf(projectB), Map.of("X-Idempotency-Key", "order-1"), "{\"v\":9}".getBytes(), "application/json");
        assertThat(otherProject).isEqualTo(ids[5]);

        // Outside the window the earlier event no longer blocks: accepted and counted as new.
        UUID fresh = ingestService.accept(keyOf(projectA), Map.of("X-Idempotency-Key", "order-old"), "{\"v\":0}".getBytes(), "application/json");
        assertThat(fresh).isNotEqualTo(ids[4]);
        assertThat(eventRepository.count()).isEqualTo(events + 1);
        assertThat(usageService.currentUsage(account)).isEqualTo(usage + 1);
        UUID repeated = ingestService.accept(keyOf(projectA), Map.of("X-Idempotency-Key", "order-old"), "{\"v\":0}".getBytes(), "application/json");
        assertThat(repeated).isEqualTo(fresh);
    }
}

package br.com.ricarte.hookguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.ricarte.hookguard.support.TempDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

/** Runs the real changelog on a scratch database that already holds data from earlier versions. */
class MigrationUpgradeTest {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

    static Liquibase liquibase(Connection connection, String ttlHours) throws Exception {
        Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
        liquibase.setChangeLogParameter("dedupeTtlHours", ttlHours);
        return liquibase;
    }

    /** Creates the schema as it was before the delivery-guarantee release and loads legacy rows. */
    static UUID[] seedLegacy(Connection connection) throws Exception {
        UUID account = UUID.randomUUID();
        UUID projectA = UUID.randomUUID();
        UUID projectB = UUID.randomUUID();
        exec(connection, "INSERT INTO accounts (id, email, plan, created_at) VALUES (?, ?, 'pro', now())", account, "legacy@example.com");
        for (UUID project : new UUID[] {projectA, projectB}) {
            exec(connection, "INSERT INTO projects (id, account_id, name, project_key, destination_url, signing_secret, created_at) "
                    + "VALUES (?, ?, 'p', ?, 'https://example.com/h', 'whsec_x', now())", project, account, "key-" + project);
        }
        UUID recent = insertEvent(connection, projectA, "order-1", "{\"v\":1}", Instant.now().minusSeconds(3600));
        UUID old = insertEvent(connection, projectA, "order-old", "{\"v\":0}", Instant.now().minusSeconds(48 * 3600));
        UUID otherProject = insertEvent(connection, projectB, "order-1", "{\"v\":9}", Instant.now().minusSeconds(60));
        insertEvent(connection, projectA, null, "{\"v\":2}", Instant.now().minusSeconds(30));
        exec(connection, "INSERT INTO usage_monthly (account_id, year_month, event_count) VALUES (?, to_char(now() at time zone 'utc', 'YYYY-MM'), 4)", account);
        if (!connection.getAutoCommit()) {
            connection.commit();
        }
        return new UUID[] {account, projectA, projectB, recent, old, otherProject};
    }

    private static UUID insertEvent(Connection connection, UUID project, String key, String body, Instant at) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO events (id, project_id, received_at, headers_json, body, content_type, dedupe_key, status) "
                        + "VALUES (?, ?, ?, '{}', ?, 'application/json', ?, 'delivered')")) {
            statement.setObject(1, id);
            statement.setObject(2, project);
            statement.setTimestamp(3, Timestamp.from(at));
            statement.setBytes(4, body.getBytes());
            statement.setString(5, key);
            statement.executeUpdate();
        }
        return id;
    }

    private static void exec(Connection connection, String sql, Object... args) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }

    private static long count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Applies the first N changesets only (the pre-upgrade state). */
    static void migrateTo(Connection connection, int changesets) throws Exception {
        liquibase(connection, "24").update(changesets, "");
    }

    @Test
    void backfillCarriesLegacyDedupeInsideTheWindowAndLeavesExpiredOnesOut() throws Exception {
        try (TempDatabase database = TempDatabase.create(); Connection connection = database.connect()) {
            migrateTo(connection, 4);
            UUID[] ids = seedLegacy(connection);

            liquibase(connection, "24").update("");

            assertThat(count(connection, "SELECT count(*) FROM ingest_dedupes")).isEqualTo(2);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT event_id, fingerprint, expires_at > now() FROM ingest_dedupes WHERE project_id = ? AND dedupe_key = 'order-1'")) {
                statement.setObject(1, ids[1]);
                try (ResultSet rs = statement.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getObject(1, UUID.class)).isEqualTo(ids[3]);
                    assertThat(rs.getString(2)).isEqualTo(sha256Hex("{\"v\":1}"));
                    assertThat(rs.getBoolean(3)).isTrue();
                }
            }
            assertThat(count(connection, "SELECT count(*) FROM ingest_dedupes WHERE dedupe_key = 'order-old'")).isZero();
            assertThat(count(connection, "SELECT count(*) FROM ingest_dedupes WHERE project_id = '" + ids[2] + "' AND dedupe_key = 'order-1'")).isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM events")).isEqualTo(4);
            assertThat(count(connection, "SELECT count(*) FROM events WHERE payload_fingerprint IS NULL")).isZero();
        }
    }

    @Test
    void failedBackfillLeavesNothingPartialAndResumesCleanly() throws Exception {
        try (TempDatabase database = TempDatabase.create(); Connection connection = database.connect()) {
            migrateTo(connection, 4);
            seedLegacy(connection);

            assertThatThrownBy(() -> liquibase(connection, "not_a_number").update(""))
                    .hasMessageContaining("not_a_number");

            assertThat(count(connection, "SELECT count(*) FROM databasechangelog WHERE id = '007-dedupe-backfill'")).isZero();
            assertThat(count(connection, "SELECT count(*) FROM ingest_dedupes")).isZero();
            assertThat(count(connection, "SELECT count(*) FROM events WHERE payload_fingerprint IS NOT NULL")).isZero();
            assertThat(count(connection, "SELECT count(*) FROM databasechangelog WHERE id = '006-single-open-job'")).isEqualTo(1);

            liquibase(connection, "24").update("");
            assertThat(count(connection, "SELECT count(*) FROM ingest_dedupes")).isEqualTo(2);
            assertThat(count(connection, "SELECT count(*) FROM events WHERE payload_fingerprint IS NULL")).isZero();

            liquibase(connection, "24").update("");
            assertThat(count(connection, "SELECT count(*) FROM ingest_dedupes")).isEqualTo(2);
        }
    }

    @Test
    void duplicateOpenJobsFromEarlierVersionsAreClosedNotDeleted() throws Exception {
        try (TempDatabase database = TempDatabase.create(); Connection connection = database.connect()) {
            migrateTo(connection, 5);
            UUID[] ids = seedLegacyAt005(connection);
            UUID event = ids[3];
            for (String state : new String[] {"pending", "pending", "in_progress", "done"}) {
                exec(connection, "INSERT INTO delivery_jobs (id, event_id, project_id, available_at, state, attempt_number, trigger_type) "
                        + "VALUES (?, ?, ?, now(), ?, 0, 'replay')", UUID.randomUUID(), event, ids[1], state);
            }

            liquibase(connection, "24").update("");

            assertThat(count(connection, "SELECT count(*) FROM delivery_jobs")).isEqualTo(4);
            assertThat(count(connection, "SELECT count(*) FROM delivery_jobs WHERE state IN ('pending','in_progress')")).isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM delivery_jobs WHERE state = 'in_progress'")).isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM delivery_jobs WHERE state = 'dead'")).isEqualTo(2);
            assertThat(count(connection, "SELECT count(*) FROM delivery_jobs WHERE state = 'done'")).isEqualTo(1);
        }
    }

    private static UUID[] seedLegacyAt005(Connection connection) throws Exception {
        UUID account = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        exec(connection, "INSERT INTO accounts (id, email, plan, created_at) VALUES (?, ?, 'free', now())", account, "v5@example.com");
        exec(connection, "INSERT INTO projects (id, account_id, name, project_key, destination_url, signing_secret, created_at) "
                + "VALUES (?, ?, 'p', ?, 'https://example.com/h', 'whsec_x', now())", project, account, "key-" + project);
        UUID event = insertEvent(connection, project, null, "{}", Instant.now());
        return new UUID[] {account, project, null, event};
    }

    static String sha256Hex(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes()));
    }
}

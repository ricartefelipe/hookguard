package br.com.ricarte.hookguard;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SchemaMigrationTest extends PostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void createsCoreTables() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(tableExists(connection, "events")).isTrue();
            assertThat(tableExists(connection, "delivery_jobs")).isTrue();
            assertThat(tableExists(connection, "accounts")).isTrue();
            assertThat(tableExists(connection, "projects")).isTrue();
        }
    }

    private boolean tableExists(Connection connection, String table) throws Exception {
        try (ResultSet rs = connection.getMetaData().getTables(null, null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }
}

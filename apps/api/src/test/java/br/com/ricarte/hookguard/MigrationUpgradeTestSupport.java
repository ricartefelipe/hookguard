package br.com.ricarte.hookguard;

import java.sql.Connection;
import java.util.UUID;

/** Entry points into the upgrade fixtures for tests that live in other packages. */
public final class MigrationUpgradeTestSupport {

    private MigrationUpgradeTestSupport() {
    }

    public static void migrateTo(Connection connection, int changesets) throws Exception {
        MigrationUpgradeTest.migrateTo(connection, changesets);
    }

    public static UUID[] seedLegacy(Connection connection) throws Exception {
        return MigrationUpgradeTest.seedLegacy(connection);
    }
}

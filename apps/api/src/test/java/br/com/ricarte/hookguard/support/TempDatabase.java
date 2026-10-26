package br.com.ricarte.hookguard.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** A throw-away database next to the test database, for upgrade and restore scenarios. */
public final class TempDatabase implements AutoCloseable {

    private final String adminUrl;
    private final String user;
    private final String password;
    private final String name;

    private TempDatabase(String adminUrl, String user, String password, String name) {
        this.adminUrl = adminUrl;
        this.user = user;
        this.password = password;
        this.name = name;
    }

    public static TempDatabase create() throws SQLException {
        String url = System.getenv().getOrDefault("HOOKGUARD_TEST_DB_URL", "jdbc:postgresql://localhost:5432/hookguard");
        String user = System.getenv().getOrDefault("HOOKGUARD_TEST_DB_USER", "hookguard");
        String password = System.getenv().getOrDefault("HOOKGUARD_TEST_DB_PASSWORD", "hookguard");
        String name = "hg_tmp_" + UUID.randomUUID().toString().replace("-", "");
        TempDatabase database = new TempDatabase(url, user, password, name);
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return database;
    }

    public String url() {
        int slash = adminUrl.lastIndexOf('/');
        int query = adminUrl.indexOf('?', slash);
        String tail = query < 0 ? "" : adminUrl.substring(query);
        return adminUrl.substring(0, slash + 1) + name + tail;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url(), user, password);
    }

    @Override
    public void close() throws SQLException {
        try (Connection connection = DriverManager.getConnection(adminUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        }
    }
}

package br.com.ricarte.hookguard.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        String url = System.getenv().getOrDefault(
                "HOOKGUARD_TEST_DB_URL",
                "jdbc:postgresql://localhost:5432/hookguard"
        );
        String user = System.getenv().getOrDefault("HOOKGUARD_TEST_DB_USER", "hookguard");
        String password = System.getenv().getOrDefault("HOOKGUARD_TEST_DB_PASSWORD", "hookguard");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
    }
}

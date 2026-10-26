package br.com.ricarte.hookguard.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import br.com.ricarte.hookguard.domain.Session;
import br.com.ricarte.hookguard.domain.SessionRepository;
import br.com.ricarte.hookguard.support.DatabaseCleaner;
import br.com.ricarte.hookguard.support.NoOpMailConfig;
import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import br.com.ricarte.hookguard.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Import(NoOpMailConfig.class)
class MagicLinkConsumptionTest extends PostgresIntegrationTest {

    @Autowired private AuthService authService;
    @MockitoSpyBean private SessionRepository sessionRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner.clean();
    }

    private String issue(String email) {
        String link = String.valueOf(authService.requestMagicLink(email, "T").get("magicLink"));
        return link.substring(link.indexOf("token=") + 6);
    }

    private int sessions() {
        return jdbc.queryForObject("SELECT count(*) FROM sessions", Integer.class);
    }

    private ApiException failure(Throwable t) {
        return t instanceof ApiException api ? api : null;
    }

    @Test
    void simultaneousVerificationsOfOneTokenYieldExactlyOneSession() throws Exception {
        String token = issue("race-" + UUID.randomUUID() + "@example.com");
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CyclicBarrier barrier = new CyclicBarrier(callers);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        return authService.verifyMagicLink(token);
                    } catch (RuntimeException ex) {
                        return ex;
                    }
                }));
            }
            int ok = 0;
            for (Future<Object> future : futures) {
                Object result = future.get(30, TimeUnit.SECONDS);
                if (result instanceof RuntimeException ex) {
                    ApiException api = failure(ex);
                    assertThat(api).as(String.valueOf(ex)).isNotNull();
                    assertThat(api.getCode()).isEqualTo("invalid_token");
                } else {
                    ok++;
                }
            }
            assertThat(ok).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(sessions()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_tokens WHERE consumed_at IS NOT NULL", Integer.class)).isEqualTo(1);
    }

    @Test
    void consumedAndExpiredTokensCreateNoSession() {
        String used = issue("used-" + UUID.randomUUID() + "@example.com");
        authService.verifyMagicLink(used);
        assertThat(sessions()).isEqualTo(1);
        assertThatThrownBy(() -> authService.verifyMagicLink(used)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("invalid_token");

        String expired = issue("expired-" + UUID.randomUUID() + "@example.com");
        jdbc.update("UPDATE login_tokens SET expires_at = now() - interval '1 second' WHERE consumed_at IS NULL");
        assertThatThrownBy(() -> authService.verifyMagicLink(expired)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("invalid_token");
        assertThat(sessions()).isEqualTo(1);
        assertThatThrownBy(() -> authService.verifyMagicLink("not-a-token")).isInstanceOf(ApiException.class);
    }

    @Test
    void disabledAccountGetsNoSessionAndTheAttemptIsRolledBackConsistently() {
        String token = issue("disabled-" + UUID.randomUUID() + "@example.com");
        jdbc.update("UPDATE accounts SET enabled = false");

        assertThatThrownBy(() -> authService.verifyMagicLink(token)).isInstanceOf(ApiException.class);

        assertThat(sessions()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_tokens WHERE consumed_at IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void failureWhileCreatingTheSessionLeavesNothingCommitted() {
        String token = issue("fail-" + UUID.randomUUID() + "@example.com");
        doThrow(new IllegalStateException("simulated failure")).when(sessionRepository).save(any(Session.class));

        assertThatThrownBy(() -> authService.verifyMagicLink(token)).hasMessageContaining("simulated failure");

        org.mockito.Mockito.reset(sessionRepository);
        assertThat(sessions()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_tokens WHERE consumed_at IS NOT NULL", Integer.class)).isZero();
        // the same token still works once the failure is gone: nothing was half-consumed
        assertThat(authService.verifyMagicLink(token).get("sessionToken")).isNotNull();
        assertThat(sessions()).isEqualTo(1);
    }
}

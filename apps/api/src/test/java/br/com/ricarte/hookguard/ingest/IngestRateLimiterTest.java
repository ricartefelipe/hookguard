package br.com.ricarte.hookguard.ingest;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.web.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IngestRateLimiterTest {

    @Test
    void blocksWhenLimitExceeded() {
        HookguardProperties properties = new HookguardProperties(
                new HookguardProperties.Worker(false, 1000, 10, 30),
                new HookguardProperties.Delivery(1000, 1000, 6),
                new HookguardProperties.Retention(14),
                new HookguardProperties.Billing(5, 100, 1000, "", "", "", "", ""),
                new HookguardProperties.Auth(
                        "http://localhost:3000",
                        "http://localhost:8080",
                        "a@b.c",
                        20,
                        30,
                        true,
                        false,
                        "",
                        ""
                ),
                new HookguardProperties.RateLimit(2),
                new HookguardProperties.Totalrecall("")
        );
        IngestRateLimiter limiter = new IngestRateLimiter(properties);
        UUID projectId = UUID.randomUUID();
        assertThatCode(() -> limiter.check(projectId)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.check(projectId)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check(projectId)).isInstanceOf(ApiException.class);
    }
}

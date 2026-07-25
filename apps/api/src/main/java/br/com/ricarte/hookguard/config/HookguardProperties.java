package br.com.ricarte.hookguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hookguard")
public record HookguardProperties(
        Worker worker,
        Delivery delivery,
        Retention retention,
        Billing billing,
        Auth auth
) {
    public record Worker(boolean enabled, long pollIntervalMs, int batchSize, int lockTimeoutSeconds) {
    }

    public record Delivery(int connectTimeoutMs, int defaultTimeoutMs, int maxAttempts) {
    }

    public record Retention(int days) {
    }

    public record Billing(
            long freeMonthlyEvents,
            String stripeApiKey,
            String stripeWebhookSecret,
            String stripeProPriceId
    ) {
    }

    public record Auth(
            String appBaseUrl,
            String fromEmail,
            int magicLinkTtlMinutes,
            int sessionTtlDays,
            boolean exposeMagicLink
    ) {
    }
}

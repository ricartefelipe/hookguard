package br.com.ricarte.hookguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hookguard")
public record HookguardProperties(
        Worker worker,
        Delivery delivery,
        Retention retention,
        Billing billing,
        Auth auth,
        RateLimit rateLimit
) {
    public record Worker(boolean enabled, long pollIntervalMs, int batchSize, int lockTimeoutSeconds) {
    }

    public record Delivery(int connectTimeoutMs, int defaultTimeoutMs, int maxAttempts) {
    }

    public record Retention(int days) {
    }

    public record Billing(
            long freeMonthlyEvents,
            long proMonthlyEvents,
            long businessMonthlyEvents,
            String stripeApiKey,
            String stripeWebhookSecret,
            String stripeProPriceId,
            String stripeBusinessPriceId,
            String stripeMeterEventName
    ) {
    }

    public record Auth(
            String appBaseUrl,
            String apiBaseUrl,
            String fromEmail,
            int magicLinkTtlMinutes,
            int sessionTtlDays,
            boolean exposeMagicLink,
            boolean trustForwardedHost,
            String githubClientId,
            String githubClientSecret
    ) {
    }

    public record RateLimit(int ingestPerMinute) {
    }
}

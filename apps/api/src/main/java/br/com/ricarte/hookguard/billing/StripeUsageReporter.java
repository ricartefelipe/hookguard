package br.com.ricarte.hookguard.billing;

import br.com.ricarte.hookguard.config.HookguardProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sends one meter event to Stripe: POST /v1/billing/meter_events, form-encoded, deduplicated by the
 * meter-event {@code identifier}. No Idempotency-Key header is sent on purpose: for API v1 Stripe saves
 * and replays the first response for a key, including 5xx, so a retry with the same key could never
 * succeed. Stripe enforces identifier uniqueness only inside a rolling window of at least 24 hours,
 * so callers must not re-send an ambiguous attempt after that window.
 */
@Component
public class StripeUsageReporter {

    public enum Kind {
        /** Stripe accepted the event (2xx). */
        SENT,
        /** Not processed and safe to send again later: 429, or credentials/configuration to fix (401/403). */
        RETRY_LATER,
        /** Unknown whether Stripe recorded it: network failure, timeout or 5xx. */
        AMBIGUOUS,
        /** Stripe refused the event itself (other 4xx); sending it again will not help. */
        REJECTED
    }

    public record Outcome(Kind kind, Integer httpStatus, String detail) {
    }

    private final HookguardProperties properties;
    private final String apiBase;
    private final Duration requestTimeout;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Autowired
    public StripeUsageReporter(
            HookguardProperties properties,
            @Value("${hookguard.billing.stripe-api-base:https://api.stripe.com}") String apiBase,
            @Value("${hookguard.billing.stripe-request-timeout-ms:10000}") long requestTimeoutMs
    ) {
        this.properties = properties;
        this.apiBase = apiBase;
        this.requestTimeout = Duration.ofMillis(Math.max(requestTimeoutMs, 100));
    }

    public boolean configured() {
        String apiKey = properties.billing().stripeApiKey();
        String eventName = properties.billing().stripeMeterEventName();
        return apiKey != null && !apiKey.isBlank() && eventName != null && !eventName.isBlank();
    }

    public Outcome report(String stripeCustomerId, String identifier, Instant occurredAt) {
        String body = "event_name=" + enc(properties.billing().stripeMeterEventName())
                + "&payload[stripe_customer_id]=" + enc(stripeCustomerId)
                + "&payload[value]=1"
                + "&identifier=" + enc(identifier)
                + "&timestamp=" + occurredAt.getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/v1/billing/meter_events"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + properties.billing().stripeApiKey())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return new Outcome(Kind.SENT, status, null);
            }
            String detail = "http_" + status + errorCode(response.body());
            if (status == 429 || status == 401 || status == 403) {
                return new Outcome(Kind.RETRY_LATER, status, detail);
            }
            if (status >= 500) {
                return new Outcome(Kind.AMBIGUOUS, status, detail);
            }
            return new Outcome(Kind.REJECTED, status, detail);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Outcome(Kind.AMBIGUOUS, null, "interrupted");
        } catch (IOException ex) {
            return new Outcome(Kind.AMBIGUOUS, null, ex.getClass().getSimpleName());
        }
    }

    /** Stripe error code, when present, so a refusal can be told apart later (only the code, never the message body). */
    private static String errorCode(String body) {
        if (body == null) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"code\"\\s*:\\s*\"([a-z0-9_]{1,60})\"").matcher(body.length() > 4096 ? body.substring(0, 4096) : body);
        return matcher.find() ? ":" + matcher.group(1) : "";
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

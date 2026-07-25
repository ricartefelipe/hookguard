package br.com.ricarte.hookguard.billing;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.domain.Account;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StripeUsageReporter {

    private static final Logger log = LoggerFactory.getLogger(StripeUsageReporter.class);

    private final HookguardProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public StripeUsageReporter(HookguardProperties properties) {
        this.properties = properties;
    }

    public void reportOverageEvent(Account account) {
        String apiKey = properties.billing().stripeApiKey();
        String eventName = properties.billing().stripeMeterEventName();
        if (apiKey == null || apiKey.isBlank() || eventName == null || eventName.isBlank()) {
            return;
        }
        if (account.getStripeCustomerId() == null || account.getStripeCustomerId().isBlank()) {
            return;
        }
        try {
            String body = "event_name=" + enc(eventName)
                    + "&payload[stripe_customer_id]=" + enc(account.getStripeCustomerId())
                    + "&payload[value]=1"
                    + "&identifier=" + enc(UUID.randomUUID().toString());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.stripe.com/v2/billing/meter_events"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("Stripe meter event rejected status={} accountId={}", response.statusCode(), account.getId());
            }
        } catch (Exception ex) {
            log.warn("Failed to report Stripe meter event accountId={}", account.getId());
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

package br.com.ricarte.hookguard.billing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.web.ApiExceptionHandler;
import com.stripe.net.Webhook;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StripeWebhookTest {

    private static final String SECRET = "whsec_test_secret";
    private static final String PAYLOAD = """
            {"id":"evt_test","object":"event","api_version":"2024-06-20","created":1710000000,"type":"ping","data":{"object":{"id":"obj_test","object":"ping"}},"livemode":false,"pending_webhooks":1,"request":{"id":null,"idempotency_key":null}}""";

    @Test
    void acceptsASignedStripeEvent() throws Exception {
        mockMvc(SECRET).perform(post("/v1/billing/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", signature(PAYLOAD, SECRET))
                        .content(PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value("true"));
    }

    @Test
    void rejectsABadSignature() throws Exception {
        mockMvc(SECRET).perform(post("/v1/billing/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1,v1=deadbeef")
                        .content(PAYLOAD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_stripe_signature"));
    }

    @Test
    void rejectsWhenTheSecretIsNotConfigured() throws Exception {
        mockMvc("").perform(post("/v1/billing/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1,v1=deadbeef")
                        .content(PAYLOAD))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("stripe_not_configured"));
    }

    private static String signature(String payload, String secret) {
        long timestamp = Webhook.Util.getTimeNow();
        try {
            String mac = Webhook.Util.computeHmacSha256(secret, timestamp + "." + payload);
            return "t=" + timestamp + ",v1=" + mac;
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static MockMvc mockMvc(String secret) {
        StripeWebhookController controller = new StripeWebhookController(properties(secret), org.mockito.Mockito.mock(StripeWebhookService.class));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static HookguardProperties properties(String secret) {
        return new HookguardProperties(
                new HookguardProperties.Worker(false, 1000, 10, 120),
                new HookguardProperties.Delivery(1000, 10000, 6, 65536, 21600, 24),
                new HookguardProperties.Retention(14),
                new HookguardProperties.Billing(5, 100, 1000, "", secret, "", "", ""),
                new HookguardProperties.Auth(
                        "http://localhost:3000",
                        "http://localhost:8080",
                        "test@example.com",
                        20,
                        30,
                        false,
                        false,
                        "",
                        "",
                        ""
                ),
                new HookguardProperties.RateLimit(100),
                new HookguardProperties.Ingest(2048, 24, 64),
                new HookguardProperties.Totalrecall("")
        );
    }
}

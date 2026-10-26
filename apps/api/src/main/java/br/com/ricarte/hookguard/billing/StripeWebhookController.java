package br.com.ricarte.hookguard.billing;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.web.ApiException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/billing/stripe")
public class StripeWebhookController {

    private final HookguardProperties properties;
    private final StripeWebhookService webhookService;

    public StripeWebhookController(HookguardProperties properties, StripeWebhookService webhookService) {
        this.properties = properties;
        this.webhookService = webhookService;
    }

    @PostMapping("/webhook")
    public Map<String, String> webhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature
    ) {
        String secret = properties.billing().stripeWebhookSecret();
        if (secret == null || secret.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "stripe_not_configured");
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, secret);
        } catch (SignatureVerificationException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_stripe_signature");
        }

        StripeWebhookService.Disposition disposition = webhookService.handle(event);
        if (disposition == StripeWebhookService.Disposition.DUPLICATE) {
            return Map.of("received", "true", "duplicate", "true");
        }
        return Map.of("received", "true");
    }
}

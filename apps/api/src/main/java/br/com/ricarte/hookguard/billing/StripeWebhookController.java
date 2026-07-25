package br.com.ricarte.hookguard.billing;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.domain.AccountPlan;
import br.com.ricarte.hookguard.domain.AccountRepository;
import br.com.ricarte.hookguard.web.ApiException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
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
    private final AccountRepository accountRepository;

    public StripeWebhookController(HookguardProperties properties, AccountRepository accountRepository) {
        this.properties = properties;
        this.accountRepository = accountRepository;
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

        switch (event.getType()) {
            case "customer.subscription.updated", "customer.subscription.created" -> {
                Subscription subscription = (Subscription) event.getDataObjectDeserializer()
                        .getObject()
                        .orElse(null);
                if (subscription != null) {
                    applySubscription(subscription);
                }
            }
            case "customer.subscription.deleted" -> {
                Subscription subscription = (Subscription) event.getDataObjectDeserializer()
                        .getObject()
                        .orElse(null);
                if (subscription != null && subscription.getCustomer() != null) {
                    accountRepository.findByStripeCustomerId(subscription.getCustomer())
                            .ifPresent(account -> {
                                account.setPlan(AccountPlan.FREE);
                                accountRepository.save(account);
                            });
                }
            }
            default -> {
            }
        }
        return Map.of("received", "true");
    }

    private void applySubscription(Subscription subscription) {
        String customerId = subscription.getCustomer();
        if (customerId == null) {
            return;
        }
        AccountPlan plan = mapPlan(subscription);
        accountRepository.findByStripeCustomerId(customerId).ifPresent(account -> {
            account.setPlan(plan);
            accountRepository.save(account);
        });
    }

    private AccountPlan mapPlan(Subscription subscription) {
        String status = subscription.getStatus();
        if (!"active".equals(status) && !"trialing".equals(status)) {
            return AccountPlan.FREE;
        }
        String businessPrice = properties.billing().stripeBusinessPriceId();
        if (subscription.getItems() != null) {
            for (SubscriptionItem item : subscription.getItems().getData()) {
                if (item.getPrice() != null && item.getPrice().getId() != null
                        && item.getPrice().getId().equals(businessPrice)) {
                    return AccountPlan.BUSINESS;
                }
            }
        }
        return AccountPlan.PRO;
    }
}

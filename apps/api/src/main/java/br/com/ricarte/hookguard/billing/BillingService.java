package br.com.ricarte.hookguard.billing;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.domain.Account;
import br.com.ricarte.hookguard.domain.AccountPlan;
import br.com.ricarte.hookguard.domain.AccountRepository;
import br.com.ricarte.hookguard.web.ApiException;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.billingportal.Session;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingService {

    private final AccountRepository accountRepository;
    private final HookguardProperties properties;

    public BillingService(AccountRepository accountRepository, HookguardProperties properties) {
        this.accountRepository = accountRepository;
        this.properties = properties;
    }

    @Transactional
    public Map<String, String> createCheckoutSession(
            UUID accountId,
            String successUrl,
            String cancelUrl,
            String plan
    ) {
        ensureStripeConfigured();
        Account account = load(accountId);
        AccountPlan target = parsePlan(plan);
        String priceId = priceFor(target);
        if (priceId == null || priceId.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "stripe_price_not_configured");
        }
        try {
            Stripe.apiKey = properties.billing().stripeApiKey();
            String customerId = ensureCustomer(account);
            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setCustomer(customerId)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPrice(priceId)
                            .build())
                    .putMetadata("accountId", accountId.toString())
                    .putMetadata("plan", target.toStorage())
                    .build();
            com.stripe.model.checkout.Session session = com.stripe.model.checkout.Session.create(params);
            Map<String, String> body = new HashMap<>();
            body.put("url", session.getUrl());
            body.put("sessionId", session.getId());
            return body;
        } catch (ApiException ex) {
            throw ex;
        } catch (StripeException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "stripe_error");
        }
    }

    @Transactional
    public Map<String, String> createPortalSession(UUID accountId, String returnUrl) {
        ensureStripeConfigured();
        Account account = load(accountId);
        if (account.getStripeCustomerId() == null || account.getStripeCustomerId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "no_stripe_customer");
        }
        try {
            Stripe.apiKey = properties.billing().stripeApiKey();
            Session session = Session.create(
                    com.stripe.param.billingportal.SessionCreateParams.builder()
                            .setCustomer(account.getStripeCustomerId())
                            .setReturnUrl(returnUrl)
                            .build()
            );
            return Map.of("url", session.getUrl());
        } catch (StripeException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "stripe_error");
        }
    }

    private AccountPlan parsePlan(String plan) {
        if (plan == null || plan.isBlank()) {
            return AccountPlan.PRO;
        }
        String normalized = plan.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "pro" -> AccountPlan.PRO;
            case "business" -> AccountPlan.BUSINESS;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_plan");
        };
    }

    private String priceFor(AccountPlan plan) {
        return switch (plan) {
            case PRO -> properties.billing().stripeProPriceId();
            case BUSINESS -> properties.billing().stripeBusinessPriceId();
            case FREE -> null;
        };
    }

    private Account load(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "account_not_found"));
    }

    private void ensureStripeConfigured() {
        if (properties.billing().stripeApiKey() == null || properties.billing().stripeApiKey().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "stripe_not_configured");
        }
    }

    private String ensureCustomer(Account account) throws StripeException {
        if (account.getStripeCustomerId() != null && !account.getStripeCustomerId().isBlank()) {
            return account.getStripeCustomerId();
        }
        Customer customer = Customer.create(CustomerCreateParams.builder()
                .setEmail(account.getEmail())
                .setName(account.getName())
                .putMetadata("accountId", account.getId().toString())
                .build());
        account.setStripeCustomerId(customer.getId());
        accountRepository.save(account);
        return customer.getId();
    }
}

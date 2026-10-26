package br.com.ricarte.hookguard.billing;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.domain.AccountPlan;
import br.com.ricarte.hookguard.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Applies subscription events without trusting their order or their payload.
 *
 * Stripe does not guarantee delivery order, may deliver an event more than once and may give distinct
 * events the same timestamp. So an event is only a notification: it is recorded once by id, and the
 * account is reconciled with the subscription's CURRENT state, read from the provider. A late or repeated
 * event therefore reads the same current state and cannot bring back an older plan. Two reconciliations
 * of one account that overlap are ordered by the instant their provider read began (database clock): a
 * write based on an older read is dropped. No database lock is held while the provider is being called.
 */
@Service
public class StripeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);
    private static final int CLAIM_LEASE_SECONDS = 90;
    private static final int MAX_WRITE_ATTEMPTS = 3;

    public enum Disposition { PROCESSED, DUPLICATE }

    private final JdbcTemplate jdbc;
    private final HookguardProperties properties;
    private final StripeSubscriptionGateway gateway;

    public StripeWebhookService(JdbcTemplate jdbc, HookguardProperties properties, StripeSubscriptionGateway gateway) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.gateway = gateway;
    }

    public Disposition handle(Event event) {
        Integer attempt = claim(event);
        if (attempt == null) {
            String state = jdbc.query("SELECT state FROM stripe_events WHERE event_id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, event.getId());
            if ("done".equals(state)) {
                return Disposition.DUPLICATE;
            }
            throw new ApiException(HttpStatus.CONFLICT, "event_in_progress", 30);
        }
        String result;
        try {
            result = process(event);
        } catch (StripeException | RuntimeException ex) {
            // Not processed: leave it retryable, and let the provider redeliver.
            jdbc.update("UPDATE stripe_events SET state = 'failed', result = ? WHERE event_id = ? AND state = 'processing' AND attempts = ?",
                    "error:" + ex.getClass().getSimpleName(), event.getId(), attempt);
            log.warn("Stripe event {} not applied ({}); it stays retryable", event.getId(), ex.getClass().getSimpleName());
            if (ex instanceof ApiException api) {
                throw api;
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "stripe_reconcile_failed", 30);
        }
        jdbc.update("UPDATE stripe_events SET state = 'done', result = ?, processed_at = now() WHERE event_id = ? AND state = 'processing' AND attempts = ?",
                result, event.getId(), attempt);
        return Disposition.PROCESSED;
    }

    /** Returns the attempt number if this caller now owns the event, or null if it is done or being handled. */
    private Integer claim(Event event) {
        return jdbc.query("""
                INSERT INTO stripe_events (event_id, event_type, state, attempts, claimed_at, received_at)
                VALUES (?, ?, 'processing', 1, now(), now())
                ON CONFLICT (event_id) DO UPDATE
                   SET state = 'processing', attempts = stripe_events.attempts + 1, claimed_at = now()
                 WHERE stripe_events.state = 'failed'
                    OR (stripe_events.state = 'processing' AND stripe_events.claimed_at < now() - make_interval(secs => ?))
                RETURNING attempts
                """,
                rs -> rs.next() ? rs.getInt(1) : null,
                event.getId(), event.getType(), (double) CLAIM_LEASE_SECONDS);
    }

    private String process(Event event) throws StripeException {
        if (event.getType() == null || !event.getType().startsWith("customer.subscription.")) {
            return "ignored:type";
        }
        String subscriptionId = subscriptionId(event);
        if (subscriptionId == null) {
            return "ignored:no_subscription";
        }
        String apiKey = properties.billing().stripeApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            // The subscription is read back from Stripe; without the API key the event cannot be applied.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "stripe_not_configured");
        }
        Timestamp readStartedAt = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        Subscription current = gateway.retrieve(subscriptionId);
        return reconcile(current, readStartedAt);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String subscriptionId(Event event) {
        try {
            String raw = event.getDataObjectDeserializer().getRawJson();
            JsonNode id = raw == null ? null : JSON.readTree(raw).path("id");
            return id == null || !id.isTextual() || id.asText().isBlank() ? null : id.asText();
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    private record AccountRow(UUID id, String plan, String subscriptionId, Timestamp syncedAt) {
    }

    private String reconcile(Subscription subscription, Timestamp readStartedAt) {
        String customerId = subscription.getCustomer();
        if (customerId == null) {
            return "ignored:no_customer";
        }
        for (int attempt = 0; attempt < MAX_WRITE_ATTEMPTS; attempt++) {
            List<AccountRow> rows = jdbc.query(
                    "SELECT id, plan, stripe_subscription_id, billing_synced_at FROM accounts WHERE stripe_customer_id = ?",
                    (rs, n) -> new AccountRow(rs.getObject("id", UUID.class), rs.getString("plan"),
                            rs.getString("stripe_subscription_id"), rs.getTimestamp("billing_synced_at")),
                    customerId);
            if (rows.size() != 1) {
                return rows.isEmpty() ? "ignored:unknown_customer" : "ignored:ambiguous_customer";
            }
            AccountRow account = rows.get(0);
            if (account.syncedAt() != null && account.syncedAt().after(readStartedAt)) {
                return "ignored:stale";
            }
            boolean active = "active".equals(subscription.getStatus()) || "trialing".equals(subscription.getStatus());
            AccountPlan priced = recognisedPlan(subscription);
            boolean tracked = subscription.getId().equals(account.subscriptionId());
            AccountPlan target;
            String newSubscription = account.subscriptionId();
            String result;
            if (active && priced == null) {
                if (!tracked) {
                    return "ignored:unknown_price";
                }
                target = AccountPlan.FREE;
                result = "applied:unknown_price_downgrade";
            } else if (account.subscriptionId() != null && !tracked && !"free".equals(account.plan())) {
                return "ignored:other_subscription";
            } else {
                target = active ? priced : AccountPlan.FREE;
                if (target != AccountPlan.FREE) {
                    newSubscription = subscription.getId();
                }
                result = "applied:" + target.toStorage();
            }
            int updated = jdbc.update("""
                    UPDATE accounts SET plan = ?, stripe_subscription_id = ?, billing_synced_at = ?
                     WHERE id = ?
                       AND plan IS NOT DISTINCT FROM ?
                       AND stripe_subscription_id IS NOT DISTINCT FROM ?
                       AND (billing_synced_at IS NULL OR billing_synced_at <= ?)
                    """,
                    target.toStorage(), newSubscription, readStartedAt, account.id(),
                    account.plan(), account.subscriptionId(), readStartedAt);
            if (updated == 1) {
                return result;
            }
            // Someone else changed the account between our read and our write: look again and decide again.
        }
        throw new IllegalStateException("account changed concurrently");
    }

    private AccountPlan recognisedPlan(Subscription subscription) {
        String pro = properties.billing().stripeProPriceId();
        String business = properties.billing().stripeBusinessPriceId();
        AccountPlan found = null;
        if (subscription.getItems() != null && subscription.getItems().getData() != null) {
            for (SubscriptionItem item : subscription.getItems().getData()) {
                String price = item.getPrice() == null ? null : item.getPrice().getId();
                if (price == null || price.isBlank()) {
                    continue;
                }
                if (price.equals(business)) {
                    return AccountPlan.BUSINESS;
                }
                if (price.equals(pro)) {
                    found = AccountPlan.PRO;
                }
            }
        }
        return found;
    }
}

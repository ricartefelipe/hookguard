package br.com.ricarte.hookguard.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.ricarte.hookguard.project.ProjectService;
import br.com.ricarte.hookguard.support.DatabaseCleaner;
import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.net.ApiResource;
import com.stripe.net.Webhook;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The provider is a programmable stand-in; nothing here reaches Stripe. */
@AutoConfigureMockMvc
@Import(StripeSubscriptionWebhookIntegrationTest.FakeProvider.class)
@TestPropertySource(properties = {
        "hookguard.billing.stripe-webhook-secret=whsec_unit_test",
        "hookguard.billing.stripe-api-key=sk_test_not_used",
        "hookguard.billing.stripe-pro-price-id=price_pro",
        "hookguard.billing.stripe-business-price-id=price_business"
})
class StripeSubscriptionWebhookIntegrationTest extends PostgresIntegrationTest {

    static final String SECRET = "whsec_unit_test";

    /** Current provider state per subscription id, plus hooks to force interleavings. */
    static final class Provider implements StripeSubscriptionGateway {
        final Map<String, String> state = new ConcurrentHashMap<>(); // subId -> json
        final AtomicInteger calls = new AtomicInteger();
        volatile int failNext;
        volatile CountDownLatch entered;   // released-by-test gate for the NEXT call
        volatile CountDownLatch release;

        @Override
        public Subscription retrieve(String id) throws StripeException {
            calls.incrementAndGet();
            if (failNext > 0) {
                failNext--;
                throw new ApiConnectionException("simulated provider outage");
            }
            String snapshot = state.get(id); // the state as of the moment the read happens
            CountDownLatch in = entered;
            CountDownLatch out = release;
            if (in != null) {
                entered = null;
                release = null;
                in.countDown();
                try {
                    out.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return ApiResource.GSON.fromJson(snapshot, Subscription.class);
        }
    }

    @TestConfiguration
    static class FakeProvider {
        static final Provider PROVIDER = new Provider();

        @Bean
        @Primary
        StripeSubscriptionGateway fakeGateway() {
            return PROVIDER;
        }
    }

    private static final Provider provider = FakeProvider.PROVIDER;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProjectService projectService;
    @Autowired private DatabaseCleaner cleaner;

    private UUID accountId;
    private String customer;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        jdbc.update("DELETE FROM stripe_events");
        provider.state.clear();
        provider.calls.set(0);
        provider.failNext = 0;
        customer = "cus_" + UUID.randomUUID().toString().substring(0, 8);
        accountId = account("main-" + UUID.randomUUID() + "@example.com", customer);
    }

    private UUID account(String email, String customerId) {
        UUID id = projectService.ensureAccount(email, "A").getId();
        jdbc.update("UPDATE accounts SET stripe_customer_id = ? WHERE id = ?", customerId, id);
        return id;
    }

    private static String subscriptionJson(String id, String customerId, String status, String price) {
        return "{\"id\":\"" + id + "\",\"object\":\"subscription\",\"customer\":\"" + customerId + "\",\"status\":\"" + status
                + "\",\"items\":{\"object\":\"list\",\"data\":[{\"id\":\"si_1\",\"object\":\"subscription_item\","
                + "\"price\":{\"id\":\"" + price + "\",\"object\":\"price\"}}],\"has_more\":false}}";
    }

    private void setProvider(String subId, String customerId, String status, String price) {
        provider.state.put(subId, subscriptionJson(subId, customerId, status, price));
    }

    private int deliver(String eventId, String type, String subId) throws Exception {
        String payload = "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"api_version\":\"2024-06-20\",\"created\":1710000000,"
                + "\"type\":\"" + type + "\",\"data\":{\"object\":{\"id\":\"" + subId + "\",\"object\":\"subscription\"}},"
                + "\"livemode\":false,\"pending_webhooks\":1,\"request\":{\"id\":null,\"idempotency_key\":null}}";
        long ts = Webhook.Util.getTimeNow();
        String signature = "t=" + ts + ",v1=" + Webhook.Util.computeHmacSha256(SECRET, ts + "." + payload);
        return mvc.perform(post("/v1/billing/stripe/webhook").contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", signature).content(payload)).andReturn().getResponse().getStatus();
    }

    private String plan(UUID id) {
        return jdbc.queryForObject("SELECT plan FROM accounts WHERE id = ?", String.class, id);
    }

    private String eventState(String eventId) {
        return jdbc.queryForObject("SELECT state FROM stripe_events WHERE event_id = ?", String.class, eventId);
    }

    @Test
    void signedEventIsAppliedFromTheProviderStateAndRecordedOnce() throws Exception {
        setProvider("sub_1", customer, "active", "price_business");
        assertThat(deliver("evt_a", "customer.subscription.created", "sub_1")).isEqualTo(200);
        assertThat(plan(accountId)).isEqualTo("business");
        assertThat(jdbc.queryForObject("SELECT stripe_subscription_id FROM accounts WHERE id = ?", String.class, accountId)).isEqualTo("sub_1");
        assertThat(eventState("evt_a")).isEqualTo("done");
    }

    @Test
    void duplicateEventIsAcknowledgedWithoutReprocessing() throws Exception {
        setProvider("sub_1", customer, "active", "price_pro");
        assertThat(deliver("evt_dup", "customer.subscription.updated", "sub_1")).isEqualTo(200);
        assertThat(deliver("evt_dup", "customer.subscription.updated", "sub_1")).isEqualTo(200);
        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stripe_events WHERE event_id = 'evt_dup'", Integer.class)).isEqualTo(1);
    }

    @Test
    void oldUpdatedArrivingAfterDeletedDoesNotRestoreThePaidPlan() throws Exception {
        setProvider("sub_1", customer, "active", "price_pro");
        deliver("evt_updated_old", "customer.subscription.updated", "sub_1");
        assertThat(plan(accountId)).isEqualTo("pro");

        setProvider("sub_1", customer, "canceled", "price_pro"); // the subscription ends at the provider
        deliver("evt_deleted", "customer.subscription.deleted", "sub_1");
        assertThat(plan(accountId)).isEqualTo("free");

        // a different, older updated event shows up late; the payload it carries is irrelevant, the current state is read
        assertThat(deliver("evt_updated_late", "customer.subscription.updated", "sub_1")).isEqualTo(200);
        assertThat(plan(accountId)).isEqualTo("free");
    }

    @Test
    void oldCreatedAfterALaterUpdateDoesNotRegressThePlan() throws Exception {
        setProvider("sub_1", customer, "active", "price_pro");
        deliver("evt_created", "customer.subscription.created", "sub_1");
        setProvider("sub_1", customer, "active", "price_business"); // upgraded afterwards
        deliver("evt_upgrade", "customer.subscription.updated", "sub_1");
        assertThat(plan(accountId)).isEqualTo("business");

        assertThat(deliver("evt_created_redelivered_as_new_id", "customer.subscription.created", "sub_1")).isEqualTo(200);
        assertThat(plan(accountId)).isEqualTo("business");
    }

    @Test
    void twoConcurrentDeliveriesOfTheSameEventApplyItOnce() throws Exception {
        setProvider("sub_1", customer, "active", "price_pro");
        provider.entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        provider.release = release;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> deliver("evt_race", "customer.subscription.updated", "sub_1"));
            // the first delivery is now inside the provider call, holding the claim (and no database lock)
            assertThat(waitFor(() -> provider.entered == null)).isTrue();
            int second = deliver("evt_race", "customer.subscription.updated", "sub_1");
            assertThat(second).isEqualTo(409);
            release.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(plan(accountId)).isEqualTo("pro");
        assertThat(deliver("evt_race", "customer.subscription.updated", "sub_1")).isEqualTo(200);
        assertThat(provider.calls.get()).isEqualTo(1);
    }

    @Test
    void distinctConcurrentEventsForOneSubscriptionCannotLetTheOlderReadWin() throws Exception {
        setProvider("sub_1", customer, "active", "price_pro");
        provider.entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        provider.release = release;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // event 1 reads "active/pro" and is held right after that read
            Future<Integer> older = pool.submit(() -> deliver("evt_read_first", "customer.subscription.updated", "sub_1"));
            assertThat(waitFor(() -> provider.entered == null)).isTrue();
            // meanwhile the subscription is cancelled and event 2 is fully processed
            setProvider("sub_1", customer, "canceled", "price_pro");
            assertThat(deliver("evt_read_second", "customer.subscription.deleted", "sub_1")).isEqualTo(200);
            assertThat(plan(accountId)).isEqualTo("free");
            // event 1 now finishes with the older snapshot and must not write it
            release.countDown();
            assertThat(older.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(plan(accountId)).isEqualTo("free");
        assertThat(jdbc.queryForObject("SELECT result FROM stripe_events WHERE event_id = 'evt_read_first'", String.class)).isEqualTo("ignored:stale");
    }

    @Test
    void temporaryProviderFailureIsNotConfirmedAndRecoversOnRedelivery() throws Exception {
        setProvider("sub_1", customer, "active", "price_pro");
        provider.failNext = 1;
        assertThat(deliver("evt_retry", "customer.subscription.updated", "sub_1")).isEqualTo(503);
        assertThat(plan(accountId)).isEqualTo("free");
        assertThat(eventState("evt_retry")).isEqualTo("failed");

        assertThat(deliver("evt_retry", "customer.subscription.updated", "sub_1")).isEqualTo(200);
        assertThat(plan(accountId)).isEqualTo("pro");
        assertThat(eventState("evt_retry")).isEqualTo("done");
        assertThat(jdbc.queryForObject("SELECT attempts FROM stripe_events WHERE event_id = 'evt_retry'", Integer.class)).isEqualTo(2);
    }

    @Test
    void unknownCustomerSubscriptionOrPriceNeverGrantsAccess() throws Exception {
        // unknown customer: nothing to update
        setProvider("sub_x", "cus_nobody", "active", "price_pro");
        assertThat(deliver("evt_unknown_customer", "customer.subscription.created", "sub_x")).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT result FROM stripe_events WHERE event_id = 'evt_unknown_customer'", String.class)).isEqualTo("ignored:unknown_customer");

        // known customer, active subscription, price that is not one of ours
        setProvider("sub_1", customer, "active", "price_somebody_elses");
        assertThat(deliver("evt_unknown_price", "customer.subscription.created", "sub_1")).isEqualTo(200);
        assertThat(plan(accountId)).isEqualTo("free");
        assertThat(jdbc.queryForObject("SELECT result FROM stripe_events WHERE event_id = 'evt_unknown_price'", String.class)).isEqualTo("ignored:unknown_price");

        // a tracked subscription that moves to an unrecognised price loses the paid plan
        setProvider("sub_2", customer, "active", "price_pro");
        deliver("evt_ok", "customer.subscription.created", "sub_2");
        assertThat(plan(accountId)).isEqualTo("pro");
        setProvider("sub_2", customer, "active", "price_somebody_elses");
        deliver("evt_moved", "customer.subscription.updated", "sub_2");
        assertThat(plan(accountId)).isEqualTo("free");

        // past_due and other non-active statuses grant nothing
        setProvider("sub_3", customer, "past_due", "price_business");
        deliver("evt_past_due", "customer.subscription.updated", "sub_3");
        assertThat(plan(accountId)).isEqualTo("free");
    }

    @Test
    void anotherSubscriptionOfTheSameCustomerDoesNotOverrideTheTrackedPaidOne() throws Exception {
        setProvider("sub_main", customer, "active", "price_business");
        deliver("evt_main", "customer.subscription.created", "sub_main");
        setProvider("sub_other", customer, "active", "price_pro");
        deliver("evt_other", "customer.subscription.created", "sub_other");
        assertThat(plan(accountId)).isEqualTo("business");
        setProvider("sub_other", customer, "canceled", "price_pro");
        deliver("evt_other_gone", "customer.subscription.deleted", "sub_other");
        assertThat(plan(accountId)).isEqualTo("business");
    }

    @Test
    void eventsOfAnotherAccountDoNotChangeThisOne() throws Exception {
        String otherCustomer = "cus_other_" + UUID.randomUUID().toString().substring(0, 6);
        UUID other = account("other-" + UUID.randomUUID() + "@example.com", otherCustomer);
        setProvider("sub_other", otherCustomer, "active", "price_business");
        deliver("evt_for_other", "customer.subscription.created", "sub_other");
        assertThat(plan(other)).isEqualTo("business");
        assertThat(plan(accountId)).isEqualTo("free");
        setProvider("sub_other", otherCustomer, "canceled", "price_business");
        deliver("evt_other_cancel", "customer.subscription.deleted", "sub_other");
        assertThat(plan(other)).isEqualTo("free");
        assertThat(plan(accountId)).isEqualTo("free");
    }

    @Test
    void invalidSignatureAndMissingApiKeyRecordNothing() throws Exception {
        String payload = "{\"id\":\"evt_bad\",\"object\":\"event\",\"api_version\":\"2024-06-20\",\"created\":1,\"type\":\"customer.subscription.updated\","
                + "\"data\":{\"object\":{\"id\":\"sub_1\",\"object\":\"subscription\"}},\"livemode\":false,\"pending_webhooks\":1}";
        int status = mvc.perform(post("/v1/billing/stripe/webhook").contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "t=1,v1=deadbeef").content(payload)).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stripe_events", Integer.class)).isZero();
        assertThat(provider.calls.get()).isZero();
    }

    @Test
    void unrelatedEventTypesAreRecordedAndIgnored() throws Exception {
        assertThat(deliver("evt_other_type", "checkout.session.completed", "cs_1")).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT result FROM stripe_events WHERE event_id = 'evt_other_type'", String.class)).isEqualTo("ignored:type");
        assertThat(provider.calls.get()).isZero();
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }
}

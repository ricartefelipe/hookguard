package br.com.ricarte.hookguard.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.ricarte.hookguard.auth.AuthService;
import br.com.ricarte.hookguard.billing.UsageService;
import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.domain.Account;
import br.com.ricarte.hookguard.domain.Project;
import br.com.ricarte.hookguard.domain.WebhookEvent;
import br.com.ricarte.hookguard.domain.WebhookEventRepository;
import br.com.ricarte.hookguard.project.ProjectService;
import br.com.ricarte.hookguard.support.DatabaseCleaner;
import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import br.com.ricarte.hookguard.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

@AutoConfigureMockMvc
class IngestAuthAndRateLimitIntegrationTest extends PostgresIntegrationTest {

    private static final String SECRET = "whsec_project_one_secret";

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectService projectService;
    @Autowired private AuthService authService;
    @Autowired private UsageService usageService;
    @Autowired private WebhookEventRepository eventRepository;
    @Autowired private HookguardProperties properties;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DatabaseCleaner cleaner;

    private Account account;
    private Project project;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        account = projectService.ensureAccount("auth-" + UUID.randomUUID() + "@example.com", "Auth");
        jdbc.update("UPDATE accounts SET plan = 'pro' WHERE id = ?", account.getId());
        project = projectService.create(account.getId(), "stripe", "https://example.com/hooks", null);
    }

    private static String hmac(String secret, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data));
    }

    private static String stripeHeader(String secret, long t, byte[] body) throws Exception {
        byte[] prefix = (t + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        return "t=" + t + ",v1=" + hmac(secret, signed);
    }

    // ---- origin authenticity ----

    @Test
    void validStripeSignatureIsAcceptedAndOriginalBytesAreKept() throws Exception {
        projectService.configureSourceAuth(account.getId(), project.getId(), "stripe", SECRET, 300);
        byte[] body = "{ \"id\" : \"evt_1\",\n  \"type\":\"invoice.paid\" }".getBytes(StandardCharsets.UTF_8);
        String signature = stripeHeader(SECRET, Instant.now().getEpochSecond(), body);

        String response = mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", signature)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        UUID eventId = UUID.fromString(objectMapper.readTree(response).get("eventId").asText());
        WebhookEvent stored = eventRepository.findById(eventId).orElseThrow();
        assertThat(stored.getBody()).isEqualTo(body);
        assertThat(stored.getHeadersJson()).contains(signature);
    }

    @Test
    void invalidSignaturesCreateNoEventAndCountNoUsage() throws Exception {
        projectService.configureSourceAuth(account.getId(), project.getId(), "stripe", SECRET, 300);
        byte[] body = "{\"id\":\"evt_2\"}".getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        Object[][] cases = {
                {null, "signature_missing"},
                {stripeHeader(SECRET, now, "{\"id\":\"other\"}".getBytes()), "signature_invalid"},
                {stripeHeader("whsec_secret_of_another_project", now, body), "signature_invalid"},
                {stripeHeader(SECRET, now - 4000, body), "signature_expired"},
        };
        for (Object[] testCase : cases) {
            var request = post("/v1/ingest/" + project.getProjectKey()).contentType(MediaType.APPLICATION_JSON).content(body);
            if (testCase[0] != null) {
                request.header("Stripe-Signature", (String) testCase[0]);
            }
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value((String) testCase[1]));
        }
        assertThat(eventRepository.count()).isZero();
        assertThat(usageService.currentUsage(account.getId())).isZero();
    }

    @Test
    void sameSignedDeliveryReplayedInsideTheWindowIsOneEvent() throws Exception {
        projectService.configureSourceAuth(account.getId(), project.getId(), "stripe", SECRET, 300);
        byte[] body = "{\"id\":\"evt_3\"}".getBytes(StandardCharsets.UTF_8);
        String signature = stripeHeader(SECRET, Instant.now().getEpochSecond(), body);
        String first = null;
        for (int i = 0; i < 3; i++) {
            String response = mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Stripe-Signature", signature)
                            .content(body))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();
            String eventId = objectMapper.readTree(response).get("eventId").asText();
            first = first == null ? eventId : first;
            assertThat(eventId).isEqualTo(first);
        }
        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(usageService.currentUsage(account.getId())).isEqualTo(1);
        // A legitimate provider retry carries a fresh timestamp and signature, and is a separate delivery.
        String retried = stripeHeader(SECRET, Instant.now().getEpochSecond() + 1, body);
        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", retried)
                        .content(body))
                .andExpect(status().isAccepted());
        assertThat(eventRepository.count()).isEqualTo(2);
    }

    @Test
    void secretsAreIsolatedPerProject() throws Exception {
        Project other = projectService.create(account.getId(), "other", "https://example.com/hooks", null);
        projectService.configureSourceAuth(account.getId(), project.getId(), "github", SECRET, 300);
        projectService.configureSourceAuth(account.getId(), other.getId(), "github", "another-project-secret", 300);
        byte[] body = "{\"zen\":true}".getBytes();
        String signature = "sha256=" + hmac(SECRET, body);

        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey()).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", signature).content(body))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/v1/ingest/" + other.getProjectKey()).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", signature).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void incompleteOrUnknownConfigurationIsRefusedNotAccepted() throws Exception {
        jdbc.update("UPDATE projects SET source_auth_mode = 'stripe', source_secret = NULL WHERE id = ?", project.getId());
        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("source_auth_misconfigured"));
        jdbc.update("UPDATE projects SET source_auth_mode = 'paypal', source_secret = 'whatever-secret' WHERE id = ?", project.getId());
        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable());
        assertThat(eventRepository.count()).isZero();
        assertThatThrownBy(() -> projectService.configureSourceAuth(account.getId(), project.getId(), "paypal", SECRET, 300))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projectService.configureSourceAuth(account.getId(), project.getId(), "stripe", "short", 300))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projectService.configureSourceAuth(account.getId(), project.getId(), "stripe", SECRET, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unauthenticatedModeStaysAvailableAndConfigEndpointNeverReturnsTheSecret() throws Exception {
        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());
        String token = String.valueOf(authService.createSessionForAccount(account).get("sessionToken"));
        String response = mockMvc.perform(put("/v1/projects/" + project.getId() + "/source-auth")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"github\",\"secret\":\"" + SECRET + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceAuthMode").value("github"))
                .andExpect(jsonPath("$.sourceAuthConfigured").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(SECRET);
        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        // Panel test events come from the authenticated owner, not from the external sender.
        assertThat(projectService.sendTestEvent(account.getId(), project.getId())).isNotNull();
    }

    @Test
    void outboundSignatureIsIndependentFromTheSourceSecret() {
        projectService.configureSourceAuth(account.getId(), project.getId(), "github", SECRET, 300);
        Project reloaded = projectService.getOwned(account.getId(), project.getId());
        assertThat(reloaded.getSigningSecret()).isNotEqualTo(reloaded.getSourceSecret());
    }

    // ---- rate limit across instances ----

    private IngestRateLimiter instance(AtomicLong clock) {
        return new IngestRateLimiter(jdbc, transactionManager, properties, clock::get);
    }

    private int limit() {
        return properties.rateLimit().ingestPerMinute();
    }

    @Test
    void twoInstancesShareOneBudgetPerProjectUnderConcurrency() throws Exception {
        int budget = 25;
        HookguardProperties limited = withLimit(budget);
        AtomicLong clock = new AtomicLong(1_800_000_000L);
        IngestRateLimiter a = new IngestRateLimiter(jdbc, transactionManager, limited, clock::get);
        IngestRateLimiter b = new IngestRateLimiter(jdbc, transactionManager, limited, clock::get);
        int callers = 16;
        int perCaller = 6;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CyclicBarrier barrier = new CyclicBarrier(callers);
        try {
            List<Future<int[]>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                IngestRateLimiter limiter = i % 2 == 0 ? a : b;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    int admitted = 0;
                    int refused = 0;
                    for (int n = 0; n < perCaller; n++) {
                        try {
                            limiter.check(project.getId());
                            admitted++;
                        } catch (ApiException ex) {
                            assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                            assertThat(ex.getRetryAfterSeconds()).isBetween(1, 60);
                            refused++;
                        }
                    }
                    return new int[] {admitted, refused};
                }));
            }
            int admitted = 0;
            int refused = 0;
            for (Future<int[]> future : futures) {
                admitted += future.get(30, TimeUnit.SECONDS)[0];
                refused += future.get()[1];
            }
            assertThat(admitted).isEqualTo(budget);
            assertThat(refused).isEqualTo(callers * perCaller - budget);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void projectsAreIsolatedAndTheWindowExpires() {
        HookguardProperties limited = withLimit(2);
        AtomicLong clock = new AtomicLong(1_800_000_030L);
        IngestRateLimiter limiter = new IngestRateLimiter(jdbc, transactionManager, limited, clock::get);
        Project other = projectService.create(account.getId(), "other", "https://example.com/hooks", null);

        limiter.check(project.getId());
        limiter.check(project.getId());
        assertThatThrownBy(() -> limiter.check(project.getId())).isInstanceOf(ApiException.class);
        limiter.check(other.getId());
        limiter.check(other.getId());

        clock.addAndGet(29);
        assertThatThrownBy(() -> limiter.check(project.getId())).isInstanceOf(ApiException.class);
        clock.addAndGet(1);
        limiter.check(project.getId());
        limiter.check(project.getId());
        assertThatThrownBy(() -> limiter.check(project.getId())).isInstanceOf(ApiException.class);
    }

    @Test
    void databaseFailureRefusesTrafficInsteadOfOpeningTheGate() {
        DriverManagerDataSource broken = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none", "x", "x");
        JdbcTemplate brokenJdbc = new JdbcTemplate(broken);
        PlatformTransactionManager brokenTx = new org.springframework.jdbc.support.JdbcTransactionManager(broken);
        IngestRateLimiter limiter = new IngestRateLimiter(brokenJdbc, brokenTx, withLimit(5), () -> 1_800_000_000L);

        assertThatThrownBy(() -> limiter.check(project.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(((ApiException) ex).getCode()).isEqualTo("rate_limiter_unavailable");
                });
        // After the outage the shared budget is intact and enforced again.
        IngestRateLimiter recovered = new IngestRateLimiter(jdbc, transactionManager, withLimit(1), () -> 1_800_000_000L);
        recovered.check(project.getId());
        assertThatThrownBy(() -> recovered.check(project.getId())).isInstanceOf(ApiException.class);
    }

    @Test
    void rateLimitAnswers429WithRetryAfterBeforeAnyBodyWork() throws Exception {
        long window = Instant.now().getEpochSecond() / 60;
        jdbc.update("INSERT INTO ingest_rate_windows (project_id, window_start, hits) VALUES (?, ?, ?) "
                        + "ON CONFLICT (project_id, window_start) DO UPDATE SET hits = EXCLUDED.hits",
                project.getId(), window, limit());
        jdbc.update("INSERT INTO ingest_rate_windows (project_id, window_start, hits) VALUES (?, ?, ?) "
                        + "ON CONFLICT (project_id, window_start) DO UPDATE SET hits = EXCLUDED.hits",
                project.getId(), window + 1, limit());
        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("rate_limited"));
        assertThat(eventRepository.count()).isZero();
    }

    private HookguardProperties withLimit(int perMinute) {
        return new HookguardProperties(
                properties.worker(), properties.delivery(), properties.retention(), properties.billing(),
                properties.auth(), new HookguardProperties.RateLimit(perMinute), properties.ingest(), properties.totalrecall());
    }
}

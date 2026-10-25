package br.com.ricarte.hookguard.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.ricarte.hookguard.billing.UsageService;
import br.com.ricarte.hookguard.domain.DeliveryJobRepository;
import br.com.ricarte.hookguard.domain.Project;
import br.com.ricarte.hookguard.domain.WebhookEventRepository;
import br.com.ricarte.hookguard.project.ProjectService;
import br.com.ricarte.hookguard.support.DatabaseCleaner;
import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class IngestIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private DeliveryJobRepository deliveryJobRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private UsageService usageService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private Project project;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        var account = projectService.ensureAccount("ingest-" + UUID.randomUUID() + "@example.com", "Ingest");
        project = projectService.create(
                account.getId(),
                "demo",
                "https://example.com/hooks",
                "X-Idempotency-Key"
        );
    }

    @Test
    void acceptsWebhookAndEnqueuesJob() throws Exception {
        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "evt-1")
                        .content("{\"ok\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").isNotEmpty());

        assertThat(webhookEventRepository.count()).isEqualTo(1);
        assertThat(deliveryJobRepository.count()).isEqualTo(1);
    }

    @Test
    void dedupeReturnsSameEventWithoutSecondJob() throws Exception {
        String payload = "{\"n\":1}";
        MvcResult first = mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "same-key")
                        .content(payload))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        String firstId = firstJson.get("eventId").asText();

        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "same-key")
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value(firstId));

        assertThat(webhookEventRepository.count()).isEqualTo(1);
        assertThat(deliveryJobRepository.count()).isEqualTo(1);
        assertThat(usageService.currentUsage(project.getAccountId())).isEqualTo(1);
    }

    @Test
    void dedupeRejectsDifferentPayloadInsideWindow() throws Exception {
        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "same-key")
                        .content("{\"n\":1}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "same-key")
                        .content("{\"n\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("dedupe_payload_conflict"));

        assertThat(webhookEventRepository.count()).isEqualTo(1);
        assertThat(usageService.currentUsage(project.getAccountId())).isEqualTo(1);
    }

    @Test
    void dedupeWindowAllowsANewEventAfterExpiry() throws Exception {
        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "same-key")
                        .content("{\"n\":1}"))
                .andExpect(status().isAccepted());
        jdbcTemplate.update(
                "UPDATE ingest_dedupes SET expires_at = ? WHERE project_id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)),
                project.getId()
        );

        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "same-key")
                        .content("{\"n\":1}"))
                .andExpect(status().isAccepted());

        assertThat(webhookEventRepository.count()).isEqualTo(2);
    }

    @Test
    void sameDedupeKeyOnAnotherProjectIsIndependent() throws Exception {
        Project other = projectService.create(
                project.getAccountId(),
                "other",
                "https://example.com/hooks",
                "X-Idempotency-Key"
        );
        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "shared")
                        .content("{\"n\":1}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/v1/ingest/" + other.getProjectKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "shared")
                        .content("{\"n\":1}"))
                .andExpect(status().isAccepted());
        assertThat(webhookEventRepository.count()).isEqualTo(2);
    }

    @Test
    void storesProviderSignatureAndDropsOriginCredentials() throws Exception {
        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer secret-value")
                        .header("Cookie", "sid=abc")
                        .header("X-Api-Key", "key-value")
                        .header("Stripe-Signature", "t=1,v1=abc")
                        .content("{\"ok\":true}"))
                .andExpect(status().isAccepted());

        String headers = webhookEventRepository.findAll().getFirst().getHeadersJson();
        assertThat(headers).contains("Stripe-Signature");
        assertThat(headers).doesNotContain("Authorization");
        assertThat(headers).doesNotContain("secret-value");
        assertThat(headers).doesNotContain("Cookie");
        assertThat(headers).doesNotContain("X-Api-Key");
    }

    @Test
    void rejectsOversizedBodyWithoutPersisting() throws Exception {
        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[2049]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("payload_too_large"));
        assertThat(webhookEventRepository.count()).isZero();
        assertThat(deliveryJobRepository.count()).isZero();
    }

    @Test
    void rejectsUnsupportedMediaType() throws Exception {
        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                        .contentType(MediaType.TEXT_HTML)
                        .content("<p>x</p>"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("unsupported_media_type"));
        assertThat(webhookEventRepository.count()).isZero();
    }

    @Test
    void freeQuotaReturnsStableError() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Idempotency-Key", "q-" + i)
                            .content("{\"n\":" + i + "}"))
                    .andExpect(status().isAccepted());
        }
        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "q-over")
                        .content("{\"n\":9}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("free_quota_exceeded"));
        assertThat(usageService.currentUsage(project.getAccountId())).isEqualTo(5);
        assertThat(webhookEventRepository.count()).isEqualTo(5);
    }
}

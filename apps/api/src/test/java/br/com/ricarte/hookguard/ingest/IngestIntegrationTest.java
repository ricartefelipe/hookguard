package br.com.ricarte.hookguard.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        MvcResult first = mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "same-key")
                        .content("{\"n\":1}"))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        String firstId = firstJson.get("eventId").asText();

        mockMvc.perform(post("/v1/ingest/" + project.getProjectKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "same-key")
                        .content("{\"n\":2}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value(firstId));

        assertThat(webhookEventRepository.count()).isEqualTo(1);
        assertThat(deliveryJobRepository.count()).isEqualTo(1);
    }
}

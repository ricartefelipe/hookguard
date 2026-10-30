package br.com.ricarte.hookguard.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.ricarte.hookguard.auth.AuthService;
import br.com.ricarte.hookguard.billing.UsageService;
import br.com.ricarte.hookguard.domain.Account;
import br.com.ricarte.hookguard.domain.DeliveryJob;
import br.com.ricarte.hookguard.domain.DeliveryJobRepository;
import br.com.ricarte.hookguard.domain.JobState;
import br.com.ricarte.hookguard.domain.Project;
import br.com.ricarte.hookguard.ingest.IngestService;
import br.com.ricarte.hookguard.project.ProjectService;
import br.com.ricarte.hookguard.support.DatabaseCleaner;
import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import br.com.ricarte.hookguard.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ReplayIsolationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private AuthService authService;

    @Autowired
    private UsageService usageService;

    @Autowired
    private ReplayService replayService;

    @Autowired
    private DeliveryJobRepository deliveryJobRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private Account owner;
    private Account other;
    private Project project;
    private UUID eventId;
    private String ownerToken;
    private String otherToken;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        owner = projectService.ensureAccount("owner-" + UUID.randomUUID() + "@example.com", "Owner");
        other = projectService.ensureAccount("other-" + UUID.randomUUID() + "@example.com", "Other");
        project = projectService.create(owner.getId(), "demo", "https://example.com/hooks", null);
        eventId = ingestService.accept(project.getProjectKey(), Map.of(), "{\"n\":1}".getBytes(), "application/json");
        deliveryJobRepository.findAll().forEach(job -> {
            job.setState(JobState.DONE);
            deliveryJobRepository.save(job);
        });
        ownerToken = String.valueOf(authService.createSessionForAccount(owner).get("sessionToken"));
        otherToken = String.valueOf(authService.createSessionForAccount(other).get("sessionToken"));
    }

    @Test
    void otherTenantCannotReadReplayOrDelete() throws Exception {
        mockMvc.perform(get("/v1/events/" + eventId).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/events/" + eventId + "/replay")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"nao e meu\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/v1/projects/" + project.getId())
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        assertThatThrownBy(() -> replayService.replay(other.getId(), eventId, "fora"))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void replayKeepsHistoryAndDoesNotBillAgain() throws Exception {
        long before = usageService.currentUsage(owner.getId());
        mockMvc.perform(post("/v1/events/" + eventId + "/replay")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"corrigir destino\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").isNotEmpty());

        mockMvc.perform(post("/v1/events/" + eventId + "/replay")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"de novo\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("replay_already_queued"));

        assertThat(usageService.currentUsage(owner.getId())).isEqualTo(before);
        DeliveryJob replay = deliveryJobRepository.findAll().stream()
                .filter(job -> "replay".equals(job.getTriggerType()))
                .findFirst()
                .orElseThrow();
        assertThat(replay.getReplayActor()).isEqualTo(owner.getId());
        assertThat(replay.getReplayReason()).isEqualTo("corrigir destino");
        assertThat(deliveryJobRepository.findAll()).hasSize(2);

        String detail = mockMvc.perform(get("/v1/events/" + eventId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(detail).contains("corrigir destino");
        assertThat(detail).doesNotContain("bodyBase64");
        assertThat(objectMapper.readTree(detail).get("bodyPreview").asText()).doesNotContain("password");
    }
}

package br.com.ricarte.hookguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.ricarte.hookguard.delivery.DeliveryWorker;
import br.com.ricarte.hookguard.support.DatabaseCleaner;
import br.com.ricarte.hookguard.support.NoOpMailConfig;
import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Whole journey through the public HTTP surface. Boundary stand-ins: the mail sender is a no-op
 * (the magic link comes back in the response because expose-magic-link is on in the test profile),
 * and the customer's endpoint is a local MockWebServer. Stripe and GitHub are not involved.
 */
@AutoConfigureMockMvc
@Import(NoOpMailConfig.class)
class JourneyIntegrationTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private DeliveryWorker worker;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleaner cleaner;

    private MockWebServer destination;

    @BeforeEach
    void setUp() throws Exception {
        cleaner.clean();
        destination = new MockWebServer();
        destination.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        destination.shutdown();
    }

    private String login(String email) throws Exception {
        JsonNode link = json.readTree(mvc.perform(post("/v1/auth/magic-link").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String token = link.get("magicLink").asText().replaceAll(".*token=", "");
        JsonNode session = json.readTree(mvc.perform(post("/v1/auth/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return "Bearer " + session.get("sessionToken").asText();
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    @Test
    void fromLoginToReplayWithAccountIsolation() throws Exception {
        String owner = login("owner-" + UUID.randomUUID() + "@example.com");
        String stranger = login("stranger-" + UUID.randomUUID() + "@example.com");

        mvc.perform(get("/v1/projects")).andExpect(status().isUnauthorized());

        JsonNode project = body(mvc.perform(post("/v1/projects").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"journey\",\"destinationUrl\":\"" + destination.url("/hook") + "\"}")).andExpect(status().isCreated()));
        String projectId = project.get("id").asText();
        String projectKey = project.get("projectKey").asText();
        String signingSecret = project.get("signingSecret").asText();
        mvc.perform(put("/v1/projects/" + projectId).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxAttempts\":2,\"timeoutMs\":2000}")).andExpect(status().isOk());

        // valid ingest, signed delivery with the original bytes
        byte[] payload = "{\"order\": 42,\n \"card\":{\"cvv\":123}}".getBytes(StandardCharsets.UTF_8);
        destination.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        String eventId = body(mvc.perform(post("/v1/ingest/" + projectKey).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isAccepted())).get("eventId").asText();
        worker.processBatch();
        RecordedRequest delivered = destination.takeRequest(2, TimeUnit.SECONDS);
        assertThat(delivered.getBody().readByteArray()).isEqualTo(payload);
        assertThat(delivered.getHeader("X-HookGuard-Signature")).startsWith("t=").doesNotContain(signingSecret);
        assertThat(delivered.getHeader("X-HookGuard-Event-Id")).isEqualTo(eventId);

        // preview masks the structured secret, the stored bytes stay as received
        JsonNode detail = body(mvc.perform(get("/v1/events/" + eventId).header("Authorization", owner)).andExpect(status().isOk()));
        assertThat(detail.get("status").asText()).isEqualTo("delivered");
        assertThat(detail.get("bodyPreview").asText()).doesNotContain("123");

        // controlled failure -> retry -> DLQ
        destination.enqueue(new MockResponse().setResponseCode(503));
        destination.enqueue(new MockResponse().setResponseCode(503));
        String failing = body(mvc.perform(post("/v1/ingest/" + projectKey).contentType(MediaType.APPLICATION_JSON).content("{\"n\":2}"))
                .andExpect(status().isAccepted())).get("eventId").asText();
        worker.processBatch();
        assertThat(jdbc.queryForObject("SELECT state FROM delivery_jobs WHERE event_id = ?::uuid", String.class, failing)).isEqualTo("pending");
        jdbc.update("UPDATE delivery_jobs SET available_at = now() WHERE event_id = ?::uuid", failing);
        worker.processBatch();
        assertThat(jdbc.queryForObject("SELECT status FROM events WHERE id = ?::uuid", String.class, failing)).isEqualTo("dead");

        // isolation: another account sees nothing of it
        mvc.perform(get("/v1/events/" + failing).header("Authorization", stranger)).andExpect(status().isNotFound());
        mvc.perform(post("/v1/events/" + failing + "/replay").header("Authorization", stranger)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")).andExpect(status().isNotFound());

        // authorised replay, with reason, delivers; history is kept
        destination.enqueue(new MockResponse().setResponseCode(200));
        mvc.perform(post("/v1/events/" + failing + "/replay").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"destino corrigido\"}")).andExpect(status().isOk());
        worker.processBatch();
        JsonNode after = body(mvc.perform(get("/v1/events/" + failing).header("Authorization", owner)).andExpect(status().isOk()));
        assertThat(after.get("status").asText()).isEqualTo("delivered");
        assertThat(after.get("attempts")).hasSize(3);
        assertThat(after.get("jobs")).hasSize(2);
        assertThat(after.toString()).contains("destino corrigido");
        assertThat(jdbc.queryForObject("SELECT event_count FROM usage_monthly", Long.class)).isEqualTo(2L);
    }
}

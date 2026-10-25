package br.com.ricarte.hookguard.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.ricarte.hookguard.auth.AuthService;
import br.com.ricarte.hookguard.delivery.DeliveryWorker;
import br.com.ricarte.hookguard.domain.Account;
import br.com.ricarte.hookguard.domain.Project;
import br.com.ricarte.hookguard.ingest.IngestService;
import br.com.ricarte.hookguard.project.ProjectService;
import br.com.ricarte.hookguard.support.DatabaseCleaner;
import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** A destination answers with secrets; follow them through the worker, the database and the API. */
@AutoConfigureMockMvc
class ResponseSnippetExposureTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ProjectService projectService;
    @Autowired private IngestService ingestService;
    @Autowired private AuthService authService;
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

    private record Seen(String stored, String exposed) {
    }

    private Seen deliverAndRead(String responseBody, int status) throws Exception {
        Account account = projectService.ensureAccount("snip-" + UUID.randomUUID() + "@example.com", "S");
        jdbc.update("UPDATE accounts SET plan = 'pro' WHERE id = ?", account.getId());
        Project project = projectService.create(account.getId(), "p", destination.url("/h").toString(), null);
        destination.enqueue(new MockResponse().setResponseCode(status).setBody(responseBody));
        UUID eventId = ingestService.accept(project.getProjectKey(), Map.of(), "{\"n\":1}".getBytes(), "application/json");
        worker.processBatch();
        String stored = jdbc.queryForObject("SELECT response_body_snippet FROM delivery_attempts WHERE event_id = ?", String.class, eventId);
        String token = String.valueOf(authService.createSessionForAccount(account).get("sessionToken"));
        String exposed = mvc.perform(get("/v1/events/" + eventId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Seen(stored, exposed);
    }

    private void assertNoSecret(Seen seen, String... secrets) {
        for (String secret : secrets) {
            assertThat(seen.stored()).as("stored").doesNotContain(secret);
            assertThat(seen.exposed()).as("exposed").doesNotContain(secret);
        }
    }

    @Test
    void structuredSecretsInNumbersObjectsListsAndEscapesAreRemovedBeforeStorage() throws Exception {
        String json = "{\"error\":\"denied\",\"cvv\":4471,\"card\":{\"number\":\"4111111111111111\"},"
                + "\"tokens\":[\"tk-list-1\"],\"pass\\u0077ord\":\"esc-secret\",\"api_key\":\"ak-\\\"quoted\\\"-secret\"}";
        Seen seen = deliverAndRead(json, 500);
        assertNoSecret(seen, "4471", "4111111111111111", "tk-list-1", "esc-secret", "quoted");
        assertThat(seen.stored()).contains("denied");
    }

    @Test
    void secretCrossingTheStorageLimitIsStillMasked() throws Exception {
        String json = "{\"detail\":\"" + "d".repeat(1985) + "\",\"token\":\"limit-crossing-secret\"}";
        Seen seen = deliverAndRead(json, 500);
        assertNoSecret(seen, "limit-crossing", "crossing-secret");
        assertThat(seen.stored().length()).isLessThanOrEqualTo(2000);
    }

    @Test
    void unstructuredAndMalformedResponsesAreNotKeptRaw() throws Exception {
        Seen text = deliverAndRead("Internal error. password=hunter2 Authorization: Bearer abc.def.ghi", 500);
        assertNoSecret(text, "hunter2", "abc.def.ghi");
        assertThat(text.stored()).isEqualTo(PayloadMasker.RESPONSE_WITHHELD);

        Seen html = deliverAndRead("<html><!-- secret=zzz --></html>", 502);
        assertNoSecret(html, "zzz");

        Seen cut = deliverAndRead("{\"ok\":false,\"secret\":\"cut-off-sec", 500);
        assertNoSecret(cut, "cut-off-sec");
    }

    @Test
    void legacyRowsWrittenByEarlierVersionsAreNotExposedRaw() throws Exception {
        Account account = projectService.ensureAccount("legacy-" + UUID.randomUUID() + "@example.com", "L");
        jdbc.update("UPDATE accounts SET plan = 'pro' WHERE id = ?", account.getId());
        Project project = projectService.create(account.getId(), "p", destination.url("/h").toString(), null);
        UUID eventId = ingestService.accept(project.getProjectKey(), Map.of(), "{}".getBytes(), "application/json");
        jdbc.update("INSERT INTO delivery_attempts (id, event_id, attempt_number, started_at, finished_at, http_status, response_body_snippet) "
                + "VALUES (?, ?, 1, now(), now(), 500, ?)", UUID.randomUUID(), eventId, "raw legacy text token=legacy-secret");
        String token = String.valueOf(authService.createSessionForAccount(account).get("sessionToken"));
        String exposed = mvc.perform(get("/v1/events/" + eventId).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertThat(exposed).doesNotContain("legacy-secret");
    }

    @Test
    void deliveredBytesAreNotAffectedByMasking() throws Exception {
        Account account = projectService.ensureAccount("bytes-" + UUID.randomUUID() + "@example.com", "B");
        Project project = projectService.create(account.getId(), "p", destination.url("/h").toString(), null);
        destination.enqueue(new MockResponse().setResponseCode(200));
        byte[] payload = "{\"password\":\"keep-original\",\"cvv\":123}".getBytes();
        ingestService.accept(project.getProjectKey(), Map.of(), payload, "application/json");
        worker.processBatch();
        assertThat(destination.takeRequest().getBody().readByteArray()).isEqualTo(payload);
        List<byte[]> stored = jdbc.query("SELECT body FROM events", (rs, n) -> rs.getBytes(1));
        assertThat(stored).singleElement().isEqualTo(payload);
    }
}

package br.com.ricarte.hookguard.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import br.com.ricarte.hookguard.auth.AuthService;
import br.com.ricarte.hookguard.delivery.DeliveryWorker;
import br.com.ricarte.hookguard.domain.Account;
import br.com.ricarte.hookguard.domain.Project;
import br.com.ricarte.hookguard.support.DatabaseCleaner;
import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Follows the project secrets through the API responses, error bodies and log output of the flows
 * that handle them. It checks these concrete paths; it is not a proof that no other path leaks.
 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SecretExposureTest extends PostgresIntegrationTest {

    private static final String SOURCE_SECRET = "whsec_source_secret_must_stay_private";

    @Autowired private MockMvc mvc;
    @Autowired private ProjectService projectService;
    @Autowired private AuthService authService;
    @Autowired private DeliveryWorker worker;
    @Autowired private ObjectMapper json;
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

    private String body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    @Test
    void secretsDoNotAppearInResponsesErrorsOrLogsOutsideCreationAndRotation(CapturedOutput output) throws Exception {
        Account account = projectService.ensureAccount("secrets-" + UUID.randomUUID() + "@example.com", "S");
        String auth = "Bearer " + authService.createSessionForAccount(account).get("sessionToken");
        Project project = projectService.create(account.getId(), "p", destination.url("/h").toString(), null);
        projectService.configureSourceAuth(account.getId(), project.getId(), "stripe", SOURCE_SECRET, 300);
        String signing = projectService.getOwned(account.getId(), project.getId()).getSigningSecret();
        String id = project.getId().toString();
        MediaType jsonType = MediaType.APPLICATION_JSON;

        List<String> bodies = new ArrayList<>();
        bodies.add(body(mvc.perform(get("/v1/projects").header("Authorization", auth))));
        bodies.add(body(mvc.perform(get("/v1/projects/" + id).header("Authorization", auth))));
        bodies.add(body(mvc.perform(put("/v1/projects/" + id).header("Authorization", auth).contentType(jsonType).content("{\"maxAttempts\":3}"))));
        bodies.add(body(mvc.perform(put("/v1/projects/" + id + "/source-auth").header("Authorization", auth).contentType(jsonType)
                .content("{\"mode\":\"github\",\"secret\":\"" + SOURCE_SECRET + "\"}"))));
        // refused configurations must not echo what was submitted
        bodies.add(body(mvc.perform(put("/v1/projects/" + id + "/source-auth").header("Authorization", auth).contentType(jsonType)
                .content("{\"mode\":\"paypal\",\"secret\":\"" + SOURCE_SECRET + "\"}"))));
        bodies.add(body(mvc.perform(put("/v1/projects/" + id + "/source-auth").header("Authorization", auth).contentType(jsonType)
                .content("{\"mode\":\"github\",\"secret\":\"" + SOURCE_SECRET + "\",\"toleranceSeconds\":1}"))));
        bodies.add(body(mvc.perform(post("/v1/projects/" + id + "/pause").header("Authorization", auth))));
        bodies.add(body(mvc.perform(post("/v1/projects/" + id + "/resume").header("Authorization", auth))));

        // ingest refused for a bad signature; owner test event delivered to a failing destination
        bodies.add(body(mvc.perform(post("/v1/ingest/" + project.getProjectKey()).contentType(jsonType)
                .header("X-Hub-Signature-256", "sha256=" + "00".repeat(32)).content("{}"))));
        String eventId = json.readTree(body(mvc.perform(post("/v1/projects/" + id + "/test-event").header("Authorization", auth)))).get("eventId").asText();
        destination.enqueue(new MockResponse().setResponseCode(500).setBody("{\"echo\":\"x\"}"));
        worker.processBatch();
        bodies.add(body(mvc.perform(get("/v1/events/" + eventId).header("Authorization", auth))));
        bodies.add(body(mvc.perform(get("/v1/events?projectId=" + id).header("Authorization", auth))));

        for (String body : bodies) {
            assertThat(body).doesNotContain(SOURCE_SECRET).doesNotContain(signing);
        }
        assertThat(output.getAll()).doesNotContain(SOURCE_SECRET).doesNotContain(signing);

        // the documented exception: the new signing secret is shown once on rotation, never the source secret
        String rotated = body(mvc.perform(post("/v1/projects/" + id + "/rotate-secret").header("Authorization", auth)));
        assertThat(rotated).doesNotContain(SOURCE_SECRET);
        assertThat(output.getAll()).doesNotContain(json.readTree(rotated).get("signingSecret").asText());
    }
}

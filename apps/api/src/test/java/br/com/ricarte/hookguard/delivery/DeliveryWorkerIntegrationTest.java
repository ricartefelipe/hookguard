package br.com.ricarte.hookguard.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.ricarte.hookguard.domain.EventStatus;
import br.com.ricarte.hookguard.domain.Project;
import br.com.ricarte.hookguard.domain.WebhookEvent;
import br.com.ricarte.hookguard.domain.WebhookEventRepository;
import br.com.ricarte.hookguard.ingest.IngestService;
import br.com.ricarte.hookguard.project.ProjectService;
import br.com.ricarte.hookguard.support.DatabaseCleaner;
import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import java.util.Map;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DeliveryWorkerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private DeliveryWorker deliveryWorker;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.clean();
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void deliversSuccessfully() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        Project project = createProject(server.url("/hook").toString(), 6);

        UUID eventId = ingestService.accept(
                project.getProjectKey(),
                Map.of("X-Test", "1"),
                "{\"hello\":true}".getBytes(),
                "application/json"
        );

        deliveryWorker.processBatch();

        WebhookEvent event = webhookEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(EventStatus.DELIVERED);
    }

    @Test
    void marksDeadAfterNonRetryableClientError() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad"));
        Project project = createProject(server.url("/hook").toString(), 6);

        UUID eventId = ingestService.accept(
                project.getProjectKey(),
                Map.of(),
                "x".getBytes(),
                "text/plain"
        );

        deliveryWorker.processBatch();

        WebhookEvent event = webhookEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(EventStatus.DEAD);
    }

    private Project createProject(String destination, int maxAttempts) {
        var account = projectService.ensureAccount("delivery-" + UUID.randomUUID() + "@example.com", "Delivery");
        Project project = projectService.create(account.getId(), "p", destination, null);
        return projectService.update(account.getId(), project.getId(), destination, 2000, maxAttempts, null);
    }
}

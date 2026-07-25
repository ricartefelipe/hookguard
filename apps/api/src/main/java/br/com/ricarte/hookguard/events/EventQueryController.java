package br.com.ricarte.hookguard.events;

import br.com.ricarte.hookguard.domain.DeliveryAttempt;
import br.com.ricarte.hookguard.domain.DeliveryAttemptRepository;
import br.com.ricarte.hookguard.domain.EventStatus;
import br.com.ricarte.hookguard.domain.Project;
import br.com.ricarte.hookguard.domain.ProjectRepository;
import br.com.ricarte.hookguard.domain.WebhookEvent;
import br.com.ricarte.hookguard.domain.WebhookEventRepository;
import br.com.ricarte.hookguard.web.AccountContext;
import br.com.ricarte.hookguard.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/events")
public class EventQueryController {

    private final WebhookEventRepository webhookEventRepository;
    private final ProjectRepository projectRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final ReplayService replayService;

    public EventQueryController(
            WebhookEventRepository webhookEventRepository,
            ProjectRepository projectRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            ReplayService replayService
    ) {
        this.webhookEventRepository = webhookEventRepository;
        this.projectRepository = projectRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.replayService = replayService;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam UUID projectId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        UUID accountId = AccountContext.requireAccountId();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project_not_found"));
        if (!project.getAccountId().equals(accountId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "project_not_found");
        }
        int pageSize = Math.min(Math.max(limit, 1), 100);
        List<WebhookEvent> events;
        if (status == null || status.isBlank()) {
            events = webhookEventRepository.findByProjectIdOrderByReceivedAtDesc(
                    projectId,
                    PageRequest.of(0, pageSize)
            );
        } else {
            EventStatus parsed = EventStatus.fromStorage(status);
            events = webhookEventRepository.findByProjectIdAndStatusOrderByReceivedAtDesc(
                    projectId,
                    parsed.toStorage(),
                    PageRequest.of(0, pageSize)
            );
        }
        return events.stream().map(this::summary).toList();
    }

    @GetMapping("/{eventId}")
    public Map<String, Object> get(@PathVariable UUID eventId) {
        UUID accountId = AccountContext.requireAccountId();
        WebhookEvent event = loadOwned(accountId, eventId);
        Map<String, Object> body = summary(event);
        body.put("headersJson", event.getHeadersJson());
        body.put("bodyBase64", Base64.getEncoder().encodeToString(event.getBody()));
        body.put("bodyPreview", preview(event.getBody()));
        body.put("contentType", event.getContentType());
        body.put("dedupeKey", event.getDedupeKey());
        List<Map<String, Object>> attempts = deliveryAttemptRepository
                .findByEventIdOrderByAttemptNumberAsc(eventId)
                .stream()
                .map(this::attemptView)
                .toList();
        body.put("attempts", attempts);
        return body;
    }

    @PostMapping("/{eventId}/replay")
    public Map<String, String> replay(@PathVariable UUID eventId) {
        UUID accountId = AccountContext.requireAccountId();
        UUID jobId = replayService.replay(accountId, eventId);
        return Map.of("jobId", jobId.toString());
    }

    private WebhookEvent loadOwned(UUID accountId, UUID eventId) {
        WebhookEvent event = webhookEventRepository.findById(eventId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "event_not_found"));
        Project project = projectRepository.findById(event.getProjectId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "event_not_found"));
        if (!project.getAccountId().equals(accountId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "event_not_found");
        }
        return event;
    }

    private Map<String, Object> summary(WebhookEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", event.getId().toString());
        body.put("projectId", event.getProjectId().toString());
        body.put("receivedAt", event.getReceivedAt().toString());
        body.put("status", event.getStatus().toStorage());
        return body;
    }

    private Map<String, Object> attemptView(DeliveryAttempt attempt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", attempt.getId().toString());
        body.put("attemptNumber", attempt.getAttemptNumber());
        body.put("startedAt", attempt.getStartedAt().toString());
        body.put("finishedAt", attempt.getFinishedAt() == null ? null : attempt.getFinishedAt().toString());
        body.put("httpStatus", attempt.getHttpStatus());
        body.put("errorMessage", attempt.getErrorMessage());
        body.put("responseBodySnippet", attempt.getResponseBodySnippet());
        return body;
    }

    private String preview(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        int len = Math.min(body.length, 500);
        return new String(body, 0, len, StandardCharsets.UTF_8);
    }
}

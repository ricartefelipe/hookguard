package br.com.ricarte.hookguard.ingest;

import br.com.ricarte.hookguard.billing.UsageService;
import br.com.ricarte.hookguard.domain.DeliveryJob;
import br.com.ricarte.hookguard.domain.DeliveryJobRepository;
import br.com.ricarte.hookguard.domain.EventStatus;
import br.com.ricarte.hookguard.domain.JobState;
import br.com.ricarte.hookguard.domain.Project;
import br.com.ricarte.hookguard.domain.ProjectRepository;
import br.com.ricarte.hookguard.domain.ProjectStatus;
import br.com.ricarte.hookguard.domain.WebhookEvent;
import br.com.ricarte.hookguard.domain.WebhookEventRepository;
import br.com.ricarte.hookguard.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestService {

    private final ProjectRepository projectRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final DeliveryJobRepository deliveryJobRepository;
    private final UsageService usageService;
    private final ObjectMapper objectMapper;

    public IngestService(
            ProjectRepository projectRepository,
            WebhookEventRepository webhookEventRepository,
            DeliveryJobRepository deliveryJobRepository,
            UsageService usageService,
            ObjectMapper objectMapper
    ) {
        this.projectRepository = projectRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.deliveryJobRepository = deliveryJobRepository;
        this.usageService = usageService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UUID accept(String projectKey, Map<String, String> headers, byte[] body, String contentType) {
        Project project = projectRepository.findByProjectKey(projectKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project_not_found"));
        if (project.getStatus() == ProjectStatus.SUSPENDED) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "project_suspended");
        }

        String dedupeKey = resolveDedupeKey(project, headers);
        if (dedupeKey != null) {
            Optional<WebhookEvent> existing = webhookEventRepository.findByProjectIdAndDedupeKey(
                    project.getId(),
                    dedupeKey
            );
            if (existing.isPresent()) {
                return existing.get().getId();
            }
        }

        usageService.assertWithinQuotaAndIncrement(project.getAccountId());

        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        WebhookEvent event = new WebhookEvent(
                eventId,
                project.getId(),
                now,
                toJson(headers),
                body == null ? new byte[0] : body,
                contentType,
                dedupeKey,
                EventStatus.RECEIVED
        );
        webhookEventRepository.save(event);
        deliveryJobRepository.save(new DeliveryJob(
                UUID.randomUUID(),
                eventId,
                project.getId(),
                now,
                JobState.PENDING
        ));
        return eventId;
    }

    private String resolveDedupeKey(Project project, Map<String, String> headers) {
        if (project.getDedupeHeader() == null || project.getDedupeHeader().isBlank()) {
            return null;
        }
        String wanted = project.getDedupeHeader().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(wanted)) {
                String value = entry.getValue();
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }

    private String toJson(Map<String, String> headers) {
        try {
            return objectMapper.writeValueAsString(headers == null ? Map.of() : headers);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize headers", ex);
        }
    }
}

package br.com.ricarte.hookguard.ingest;

import br.com.ricarte.hookguard.billing.UsageService;
import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.delivery.OutboundHeaderPolicy;
import br.com.ricarte.hookguard.domain.DeliveryJob;
import br.com.ricarte.hookguard.domain.DeliveryJobRepository;
import br.com.ricarte.hookguard.domain.EventStatus;
import br.com.ricarte.hookguard.domain.IngestDedupe;
import br.com.ricarte.hookguard.domain.IngestDedupeRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IngestService {

    private final ProjectRepository projectRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final DeliveryJobRepository deliveryJobRepository;
    private final IngestDedupeRepository ingestDedupeRepository;
    private final UsageService usageService;
    private final IngestRateLimiter ingestRateLimiter;
    private final ObjectMapper objectMapper;
    private final HookguardProperties properties;
    private final TransactionTemplate transactionTemplate;

    public IngestService(
            ProjectRepository projectRepository,
            WebhookEventRepository webhookEventRepository,
            DeliveryJobRepository deliveryJobRepository,
            IngestDedupeRepository ingestDedupeRepository,
            UsageService usageService,
            IngestRateLimiter ingestRateLimiter,
            ObjectMapper objectMapper,
            HookguardProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this.projectRepository = projectRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.deliveryJobRepository = deliveryJobRepository;
        this.ingestDedupeRepository = ingestDedupeRepository;
        this.usageService = usageService;
        this.ingestRateLimiter = ingestRateLimiter;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public UUID accept(String projectKey, Map<String, String> headers, byte[] body, String contentType) {
        try {
            return execute(projectKey, headers, body, contentType);
        } catch (RuntimeException ex) {
            if (!isIntegrity(ex)) {
                throw ex;
            }
            return execute(projectKey, headers, body, contentType);
        }
    }

    private UUID execute(String projectKey, Map<String, String> headers, byte[] body, String contentType) {
        UUID id = transactionTemplate.execute(status -> doAccept(projectKey, headers, body, contentType));
        if (id == null) {
            throw new IllegalStateException("ingest did not persist an event");
        }
        return id;
    }

    private UUID doAccept(String projectKey, Map<String, String> headers, byte[] body, String contentType) {
        Project project = projectRepository.findByProjectKey(projectKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project_not_found"));
        if (project.getStatus() == ProjectStatus.SUSPENDED) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "project_suspended");
        }
        if (!IngestContentTypes.allowed(contentType)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type");
        }
        byte[] payload = body == null ? new byte[0] : body.clone();
        int maxBody = properties.ingest().maxBodyBytes();
        if (maxBody > 0 && payload.length > maxBody) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large");
        }
        ingestRateLimiter.check(project.getId());

        String fingerprint = PayloadFingerprint.sha256(payload);
        String dedupeKey = resolveDedupeKey(project, headers);
        Instant now = Instant.now();
        if (dedupeKey != null) {
            Optional<IngestDedupe> existing = ingestDedupeRepository.findById(new IngestDedupe.Key(project.getId(), dedupeKey));
            if (existing.isPresent()) {
                IngestDedupe row = existing.get();
                if (row.getExpiresAt().isAfter(now)) {
                    if (!row.getFingerprint().equals(fingerprint)) {
                        throw new ApiException(HttpStatus.CONFLICT, "dedupe_payload_conflict");
                    }
                    return row.getEventId();
                }
                ingestDedupeRepository.delete(row);
                ingestDedupeRepository.flush();
            }
        }

        UUID eventId = UUID.randomUUID();
        usageService.assertWithinQuotaAndIncrement(project.getAccountId(), eventId);
        WebhookEvent event = new WebhookEvent(
                eventId,
                project.getId(),
                now,
                toJson(OutboundHeaderPolicy.forStorage(headers)),
                payload,
                contentType,
                dedupeKey,
                EventStatus.RECEIVED
        );
        event.setPayloadFingerprint(fingerprint);
        webhookEventRepository.save(event);
        deliveryJobRepository.save(new DeliveryJob(
                UUID.randomUUID(),
                eventId,
                project.getId(),
                now,
                JobState.PENDING
        ));
        if (dedupeKey != null) {
            int ttlHours = Math.max(properties.ingest().dedupeTtlHours(), 1);
            ingestDedupeRepository.save(new IngestDedupe(
                    project.getId(),
                    dedupeKey,
                    eventId,
                    fingerprint,
                    now.plus(ttlHours, ChronoUnit.HOURS)
            ));
        }
        return eventId;
    }

    private String resolveDedupeKey(Project project, Map<String, String> headers) {
        if (project.getDedupeHeader() == null || project.getDedupeHeader().isBlank() || headers == null) {
            return null;
        }
        String wanted = project.getDedupeHeader().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(wanted)) {
                String value = entry.getValue();
                if (value == null || value.isBlank()) {
                    return null;
                }
                String trimmed = value.trim();
                if (trimmed.length() > 500 || trimmed.indexOf('\r') >= 0 || trimmed.indexOf('\n') >= 0) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "dedupe_key_invalid");
                }
                return trimmed;
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

    private static boolean isIntegrity(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DataIntegrityViolationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

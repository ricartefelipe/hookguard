package br.com.ricarte.hookguard.delivery;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.domain.DeliveryAttempt;
import br.com.ricarte.hookguard.domain.DeliveryAttemptRepository;
import br.com.ricarte.hookguard.domain.DeliveryJob;
import br.com.ricarte.hookguard.domain.DeliveryJobRepository;
import br.com.ricarte.hookguard.domain.EventStatus;
import br.com.ricarte.hookguard.domain.JobState;
import br.com.ricarte.hookguard.domain.Project;
import br.com.ricarte.hookguard.domain.ProjectRepository;
import br.com.ricarte.hookguard.domain.WebhookEvent;
import br.com.ricarte.hookguard.domain.WebhookEventRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final DeliveryJobRepository deliveryJobRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final ProjectRepository projectRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final DeliveryClient deliveryClient;
    private final HookguardProperties properties;
    private final ObjectMapper objectMapper;
    private final String workerId;
    private final boolean allowHttpDestinations;

    public DeliveryWorker(
            DeliveryJobRepository deliveryJobRepository,
            WebhookEventRepository webhookEventRepository,
            ProjectRepository projectRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            DeliveryClient deliveryClient,
            HookguardProperties properties,
            ObjectMapper objectMapper,
            @Value("${hookguard.delivery.allow-http:false}") boolean allowHttpDestinations
    ) {
        this.deliveryJobRepository = deliveryJobRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.projectRepository = projectRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.deliveryClient = deliveryClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.workerId = "worker-" + UUID.randomUUID();
        this.allowHttpDestinations = allowHttpDestinations;
    }

    @Scheduled(fixedDelayString = "${hookguard.worker.poll-interval-ms:1000}")
    public void poll() {
        if (!properties.worker().enabled()) {
            return;
        }
        Instant now = Instant.now();
        deliveryJobRepository.releaseStaleLocks(
                now.minusSeconds(properties.worker().lockTimeoutSeconds()),
                now
        );
        processBatch();
    }

    @Transactional
    public void processBatch() {
        Instant now = Instant.now();
        List<UUID> ids = deliveryJobRepository.claimCandidateIds(now, properties.worker().batchSize());
        if (ids.isEmpty()) {
            return;
        }
        deliveryJobRepository.markInProgress(ids, workerId, now);
        for (UUID id : ids) {
            deliveryJobRepository.findById(id).ifPresent(this::processJob);
        }
    }

    private void processJob(DeliveryJob job) {
        WebhookEvent event = webhookEventRepository.findById(job.getEventId()).orElse(null);
        Project project = projectRepository.findById(job.getProjectId()).orElse(null);
        if (event == null || project == null) {
            job.setState(JobState.DEAD);
            job.clearLock();
            deliveryJobRepository.save(job);
            return;
        }

        int attemptNumber = job.getAttemptNumber() + 1;
        job.setAttemptNumber(attemptNumber);
        event.setStatus(EventStatus.DELIVERING);
        webhookEventRepository.save(event);

        Instant startedAt = Instant.now();
        DeliveryAttempt attempt = new DeliveryAttempt(UUID.randomUUID(), event.getId(), attemptNumber, startedAt);

        try {
            DestinationUrlValidator.validate(project.getDestinationUrl(), allowHttpDestinations);
        } catch (IllegalArgumentException ex) {
            attempt.finish(Instant.now(), null, ex.getMessage(), null);
            deliveryAttemptRepository.save(attempt);
            markDead(job, event);
            return;
        }

        Map<String, String> outbound = buildOutboundHeaders(event, project, attemptNumber);
        DeliveryClient.DeliveryResult result = deliveryClient.post(
                project.getDestinationUrl(),
                event.getBody(),
                event.getContentType(),
                outbound,
                project.getTimeoutMs()
        );
        attempt.finish(Instant.now(), result.httpStatus(), result.errorMessage(), result.responseBodySnippet());
        deliveryAttemptRepository.save(attempt);

        if (result.success()) {
            job.setState(JobState.DONE);
            job.clearLock();
            deliveryJobRepository.save(job);
            event.setStatus(EventStatus.DELIVERED);
            webhookEventRepository.save(event);
            return;
        }

        boolean retryable = isRetryable(result.httpStatus());
        if (!retryable || attemptNumber >= project.getMaxAttempts()) {
            markDead(job, event);
            return;
        }

        job.setState(JobState.PENDING);
        job.clearLock();
        job.setAvailableAt(Instant.now().plus(BackoffPolicy.delayAfterAttempt(attemptNumber)));
        deliveryJobRepository.save(job);
        event.setStatus(EventStatus.RECEIVED);
        webhookEventRepository.save(event);
        log.info("Scheduled retry eventId={} attempt={}", event.getId(), attemptNumber);
    }

    private void markDead(DeliveryJob job, WebhookEvent event) {
        job.setState(JobState.DEAD);
        job.clearLock();
        deliveryJobRepository.save(job);
        event.setStatus(EventStatus.DEAD);
        webhookEventRepository.save(event);
    }

    private boolean isRetryable(Integer status) {
        if (status == null) {
            return true;
        }
        if (status == 408 || status == 429) {
            return true;
        }
        return status >= 500;
    }

    private Map<String, String> buildOutboundHeaders(WebhookEvent event, Project project, int attemptNumber) {
        Map<String, String> headers = new HashMap<>();
        try {
            Map<String, String> original = objectMapper.readValue(
                    event.getHeadersJson(),
                    new TypeReference<>() {
                    }
            );
            original.forEach((k, v) -> {
                if (k != null && !k.toLowerCase().startsWith("x-hookguard-")) {
                    headers.put(k, v);
                }
            });
        } catch (Exception ignored) {
        }
        headers.put("X-HookGuard-Event-Id", event.getId().toString());
        headers.put("X-HookGuard-Attempt", String.valueOf(attemptNumber));
        headers.put("X-HookGuard-Signature", HookSignature.sign(event.getBody(), project.getSigningSecret()));
        return headers;
    }
}

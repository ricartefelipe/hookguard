package br.com.ricarte.hookguard.events;

import br.com.ricarte.hookguard.domain.DeliveryAttemptRepository;
import br.com.ricarte.hookguard.domain.DeliveryJob;
import br.com.ricarte.hookguard.domain.DeliveryJobRepository;
import br.com.ricarte.hookguard.domain.EventStatus;
import br.com.ricarte.hookguard.domain.JobState;
import br.com.ricarte.hookguard.domain.Project;
import br.com.ricarte.hookguard.domain.ProjectRepository;
import br.com.ricarte.hookguard.domain.WebhookEvent;
import br.com.ricarte.hookguard.domain.WebhookEventRepository;
import br.com.ricarte.hookguard.web.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReplayService {

    static final int MAX_ATTEMPTS_PER_EVENT = 50;  // same budget the worker enforces when claiming

    private final WebhookEventRepository webhookEventRepository;
    private final ProjectRepository projectRepository;
    private final DeliveryJobRepository deliveryJobRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;

    public ReplayService(
            WebhookEventRepository webhookEventRepository,
            ProjectRepository projectRepository,
            DeliveryJobRepository deliveryJobRepository,
            DeliveryAttemptRepository deliveryAttemptRepository
    ) {
        this.webhookEventRepository = webhookEventRepository;
        this.projectRepository = projectRepository;
        this.deliveryJobRepository = deliveryJobRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
    }

    @Transactional
    public UUID replay(UUID accountId, UUID eventId, String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "replay_reason_invalid");
        }
        WebhookEvent unlocked = webhookEventRepository.findById(eventId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "event_not_found"));
        Project project = projectRepository.findById(unlocked.getProjectId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project_not_found"));
        if (!project.getAccountId().equals(accountId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "event_not_found");
        }
        // Concurrent replays of one event queue up here; the partial unique index is the backstop.
        WebhookEvent event = webhookEventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "event_not_found"));
        if (!deliveryJobRepository.findOpenByEventId(event.getId()).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "replay_already_queued");
        }
        if (deliveryAttemptRepository.findByEventIdOrderByAttemptNumberAsc(eventId).size() >= MAX_ATTEMPTS_PER_EVENT) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "replay_limit");
        }
        Instant now = Instant.now();
        DeliveryJob job = new DeliveryJob(
                UUID.randomUUID(),
                event.getId(),
                project.getId(),
                now,
                JobState.PENDING
        );
        job.setTriggerType("replay");
        job.setReplayActor(accountId);
        job.setReplayReason(reason.trim());
        try {
            deliveryJobRepository.saveAndFlush(job);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "replay_already_queued");
        }
        event.setStatus(EventStatus.RECEIVED);
        webhookEventRepository.save(event);
        return job.getId();
    }
}

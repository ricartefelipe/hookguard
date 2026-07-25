package br.com.ricarte.hookguard.events;

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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReplayService {

    private final WebhookEventRepository webhookEventRepository;
    private final ProjectRepository projectRepository;
    private final DeliveryJobRepository deliveryJobRepository;

    public ReplayService(
            WebhookEventRepository webhookEventRepository,
            ProjectRepository projectRepository,
            DeliveryJobRepository deliveryJobRepository
    ) {
        this.webhookEventRepository = webhookEventRepository;
        this.projectRepository = projectRepository;
        this.deliveryJobRepository = deliveryJobRepository;
    }

    @Transactional
    public UUID replay(UUID accountId, UUID eventId) {
        WebhookEvent event = webhookEventRepository.findById(eventId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "event_not_found"));
        Project project = projectRepository.findById(event.getProjectId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project_not_found"));
        if (!project.getAccountId().equals(accountId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "event_not_found");
        }
        Instant now = Instant.now();
        event.setStatus(EventStatus.RECEIVED);
        webhookEventRepository.save(event);
        DeliveryJob job = new DeliveryJob(
                UUID.randomUUID(),
                event.getId(),
                project.getId(),
                now,
                JobState.PENDING
        );
        deliveryJobRepository.save(job);
        return job.getId();
    }
}

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
import br.com.ricarte.hookguard.domain.ProjectStatus;
import br.com.ricarte.hookguard.domain.WebhookEvent;
import br.com.ricarte.hookguard.domain.WebhookEventRepository;
import br.com.ricarte.hookguard.events.PayloadMasker;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate shortTx;
    private final String workerId;
    private final AtomicBoolean lastClaimRanInTransaction = new AtomicBoolean();
    private final AtomicBoolean lastHttpRanInTransaction = new AtomicBoolean();
    private volatile Runnable beforeSettle = () -> {
    };

    public DeliveryWorker(
            DeliveryJobRepository deliveryJobRepository,
            WebhookEventRepository webhookEventRepository,
            ProjectRepository projectRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            DeliveryClient deliveryClient,
            HookguardProperties properties,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager
    ) {
        this.deliveryJobRepository = deliveryJobRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.projectRepository = projectRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.deliveryClient = deliveryClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.shortTx = new TransactionTemplate(transactionManager);
        this.shortTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.workerId = "worker-" + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${hookguard.worker.poll-interval-ms:1000}")
    public void poll() {
        if (!properties.worker().enabled()) {
            return;
        }
        processBatch();
    }

    public void processBatch() {
        lastClaimRanInTransaction.set(false);
        lastHttpRanInTransaction.set(false);
        reap();
        for (DeliveryLease lease : claim()) {
            dispatch(lease);
        }
    }

    boolean lastClaimRanInTransaction() {
        return lastClaimRanInTransaction.get();
    }

    boolean lastHttpRanInTransaction() {
        return lastHttpRanInTransaction.get();
    }

    void setBeforeSettle(Runnable beforeSettle) {
        this.beforeSettle = beforeSettle == null ? () -> {
        } : beforeSettle;
    }

    private void reap() {
        Instant now = Instant.now();
        Instant staleBefore = now.minusSeconds(properties.worker().lockTimeoutSeconds());
        shortTx.executeWithoutResult(status -> {
            List<DeliveryJob> stale = deliveryJobRepository.findByStateAndLockedAtBefore(
                    JobState.IN_PROGRESS.toStorage(),
                    staleBefore
            );
            List<StaleLease> snapshot = new ArrayList<>();
            for (DeliveryJob job : stale) {
                if (job.getLockedBy() != null) {
                    snapshot.add(new StaleLease(
                            job.getId(),
                            job.getEventId(),
                            job.getProjectId(),
                            job.getLockedBy(),
                            job.getAttemptNumber()
                    ));
                }
            }
            for (StaleLease job : snapshot) {
                Project project = projectRepository.findById(job.projectId()).orElse(null);
                int maxAttempts = project == null ? 1 : project.getMaxAttempts();
                boolean dead = project == null || job.attemptNumber() >= maxAttempts;
                int updated = deliveryJobRepository.finishLease(
                        job.id(),
                        job.token(),
                        (dead ? JobState.DEAD : JobState.PENDING).toStorage(),
                        now
                );
                if (updated != 1) {
                    continue;
                }
                for (DeliveryAttempt attempt : deliveryAttemptRepository.findByEventIdAndFinishedAtIsNull(job.eventId())) {
                    attempt.finish(now, null, "lease_expired", null);
                    deliveryAttemptRepository.save(attempt);
                }
                webhookEventRepository.findById(job.eventId()).ifPresent(event -> {
                    event.setStatus(dead ? EventStatus.DEAD : EventStatus.RECEIVED);
                    webhookEventRepository.save(event);
                });
                log.info("Recovered stale delivery lease eventId={} dead={}", job.eventId(), dead);
            }
        });
    }

    private List<DeliveryLease> claim() {
        List<DeliveryLease> leases = shortTx.execute(status -> {
            lastClaimRanInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return lockBatch(Instant.now());
        });
        return leases == null ? List.of() : leases;
    }

    private List<DeliveryLease> lockBatch(Instant now) {
        List<UUID> ids = deliveryJobRepository.claimCandidateIds(now, properties.worker().batchSize());
        List<DeliveryLease> leases = new ArrayList<>();
        for (UUID id : ids) {
            DeliveryJob job = deliveryJobRepository.findById(id).orElse(null);
            if (job == null || job.getState() != JobState.PENDING) {
                continue;
            }
            Project project = projectRepository.findById(job.getProjectId()).orElse(null);
            WebhookEvent event = webhookEventRepository.findById(job.getEventId()).orElse(null);
            if (event == null || project == null) {
                job.setState(JobState.DEAD);
                job.clearLock();
                deliveryJobRepository.save(job);
                if (event != null) {
                    event.setStatus(EventStatus.DEAD);
                    webhookEventRepository.save(event);
                }
                continue;
            }
            if (project.getStatus() != ProjectStatus.ACTIVE) {
                job.setAvailableAt(now.plusSeconds(30));
                deliveryJobRepository.save(job);
                continue;
            }
            int attemptNumber = job.getAttemptNumber() + 1;
            String token = workerId + ":" + UUID.randomUUID();
            job.lock(token, now);
            job.setAttemptNumber(attemptNumber);
            deliveryJobRepository.save(job);
            UUID attemptId = UUID.randomUUID();
            deliveryAttemptRepository.save(new DeliveryAttempt(attemptId, event.getId(), attemptNumber, now));
            event.setStatus(EventStatus.DELIVERING);
            webhookEventRepository.save(event);
            byte[] body = event.getBody() == null ? new byte[0] : event.getBody().clone();
            leases.add(new DeliveryLease(
                    job.getId(),
                    event.getId(),
                    attemptId,
                    token,
                    attemptNumber,
                    project.getMaxAttempts(),
                    project.getDestinationUrl(),
                    project.getTimeoutMs(),
                    body,
                    event.getContentType(),
                    event.getHeadersJson(),
                    project.getSigningSecret(),
                    project.getSigningSecretPrevious(),
                    project.getSigningSecretRotatedAt()
            ));
        }
        return leases;
    }

    private void dispatch(DeliveryLease lease) {
        lastHttpRanInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
        try {
            DeliveryClient.DeliveryResult result = deliveryClient.post(
                    lease.destinationUrl(),
                    lease.body(),
                    lease.contentType(),
                    buildHeaders(lease),
                    effectiveTimeout(lease.timeoutMs())
            );
            settle(lease, result);
        } catch (IllegalArgumentException ex) {
            settleDead(lease, ex.getMessage());
        }
    }

    private Map<String, String> buildHeaders(DeliveryLease lease) {
        Map<String, String> headers = OutboundHeaderPolicy.fromStoredJson(lease.headersJson(), objectMapper);
        Instant now = Instant.now();
        String signature = HookSignature.signatureHeader(
                lease.body(),
                lease.signingSecret(),
                lease.signingSecretPrevious(),
                lease.signingSecretRotatedAt(),
                Duration.ofHours(Math.max(properties.delivery().signatureRotationHours(), 0)),
                now,
                lease.eventId()
        );
        headers.put("X-HookGuard-Event-Id", lease.eventId().toString());
        headers.put("X-HookGuard-Job-Id", lease.jobId().toString());
        headers.put("X-HookGuard-Attempt", String.valueOf(lease.attemptNumber()));
        headers.put("X-HookGuard-Timestamp", String.valueOf(now.getEpochSecond()));
        headers.put("X-HookGuard-Signature", signature);
        return headers;
    }

    private void settle(DeliveryLease lease, DeliveryClient.DeliveryResult result) {
        beforeSettle.run();
        boolean success = result.success();
        boolean retryable = isRetryable(result.httpStatus());
        boolean dead = !success && (!retryable || lease.attemptNumber() >= lease.maxAttempts());
        JobState state = success ? JobState.DONE : dead ? JobState.DEAD : JobState.PENDING;
        EventStatus status = success ? EventStatus.DELIVERED : dead ? EventStatus.DEAD : EventStatus.RECEIVED;
        Duration delay = success || dead
                ? Duration.ZERO
                : BackoffPolicy.scheduleDelay(
                        lease.attemptNumber(),
                        result.retryAfter(),
                        Duration.ofSeconds(properties.delivery().retryAfterCapSeconds()),
                        ThreadLocalRandom.current()
                );
        String error = success
                ? null
                : safe(result.errorMessage() != null ? result.errorMessage() : "http_" + result.httpStatus());
        String snippet = clip(PayloadMasker.mask(result.responseBodySnippet()));
        apply(lease, state, status, Instant.now().plus(delay), result.httpStatus(), error, snippet);
        meter(success ? "success" : dead ? "dead" : "retry");
        if (!success && !dead) {
            log.info("Scheduled retry eventId={} attempt={}", lease.eventId(), lease.attemptNumber());
        }
    }

    private void settleDead(DeliveryLease lease, String error) {
        beforeSettle.run();
        apply(lease, JobState.DEAD, EventStatus.DEAD, Instant.now(), null, safe(error), null);
        meter("dead");
        log.info("Delivery blocked eventId={}", lease.eventId());
    }

    private void apply(
            DeliveryLease lease,
            JobState state,
            EventStatus status,
            Instant availableAt,
            Integer httpStatus,
            String error,
            String snippet
    ) {
        shortTx.executeWithoutResult(tx -> {
            int updated = deliveryJobRepository.finishLease(
                    lease.jobId(),
                    lease.claimToken(),
                    state.toStorage(),
                    availableAt
            );
            if (updated != 1) {
                log.info("Ignored stale delivery result eventId={}", lease.eventId());
                return;
            }
            deliveryAttemptRepository.findById(lease.attemptId()).ifPresent(attempt -> {
                if (attempt.getFinishedAt() == null) {
                    attempt.finish(Instant.now(), httpStatus, error, snippet);
                    deliveryAttemptRepository.save(attempt);
                }
            });
            webhookEventRepository.findById(lease.eventId()).ifPresent(event -> {
                event.setStatus(status);
                webhookEventRepository.save(event);
            });
        });
    }

    private int effectiveTimeout(int requested) {
        int budget = properties.worker().lockTimeoutSeconds() * 1000 - 5000;
        if (budget < 1000) {
            budget = 1000;
        }
        int timeout = requested <= 0 ? properties.delivery().defaultTimeoutMs() : requested;
        return Math.min(timeout, budget);
    }

    private void meter(String result) {
        meterRegistry.counter("hookguard.delivery", "result", result).increment();
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

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "delivery_failed";
        }
        String flat = message.replace('\r', ' ').replace('\n', ' ').trim();
        return flat.length() > 900 ? flat.substring(0, 900) : flat;
    }

    private static String clip(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }

    private record StaleLease(UUID id, UUID eventId, UUID projectId, String token, int attemptNumber) {
    }
}

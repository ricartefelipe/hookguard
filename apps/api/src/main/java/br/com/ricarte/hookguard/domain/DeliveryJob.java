package br.com.ricarte.hookguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "delivery_jobs")
public class DeliveryJob {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "locked_by", length = 120)
    private String lockedBy;

    @Column(nullable = false, length = 40)
    private String state;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    protected DeliveryJob() {
    }

    public DeliveryJob(UUID id, UUID eventId, UUID projectId, Instant availableAt, JobState state) {
        this.id = id;
        this.eventId = eventId;
        this.projectId = projectId;
        this.availableAt = availableAt;
        this.state = state.toStorage();
        this.attemptNumber = 0;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public void setAvailableAt(Instant availableAt) {
        this.availableAt = availableAt;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public JobState getState() {
        return JobState.fromStorage(state);
    }

    public void setState(JobState state) {
        this.state = state.toStorage();
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public void setAttemptNumber(int attemptNumber) {
        this.attemptNumber = attemptNumber;
    }

    public void lock(String workerId, Instant lockedAt) {
        this.lockedBy = workerId;
        this.lockedAt = lockedAt;
        this.state = JobState.IN_PROGRESS.toStorage();
    }

    public void clearLock() {
        this.lockedBy = null;
        this.lockedAt = null;
    }
}

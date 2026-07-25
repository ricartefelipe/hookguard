package br.com.ricarte.hookguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "delivery_attempts")
public class DeliveryAttempt {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "response_body_snippet", length = 2000)
    private String responseBodySnippet;

    protected DeliveryAttempt() {
    }

    public DeliveryAttempt(UUID id, UUID eventId, int attemptNumber, Instant startedAt) {
        this.id = id;
        this.eventId = eventId;
        this.attemptNumber = attemptNumber;
        this.startedAt = startedAt;
    }

    public void finish(Instant finishedAt, Integer httpStatus, String errorMessage, String responseBodySnippet) {
        this.finishedAt = finishedAt;
        this.httpStatus = httpStatus;
        this.errorMessage = errorMessage;
        this.responseBodySnippet = responseBodySnippet;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getResponseBodySnippet() {
        return responseBodySnippet;
    }
}

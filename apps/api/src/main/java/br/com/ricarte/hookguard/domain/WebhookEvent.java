package br.com.ricarte.hookguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "events")
public class WebhookEvent {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "headers_json", nullable = false, columnDefinition = "text")
    private String headersJson;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] body;

    @Column(name = "content_type", length = 200)
    private String contentType;

    @Column(name = "dedupe_key", length = 500)
    private String dedupeKey;

    @Column(nullable = false, length = 40)
    private String status;

    protected WebhookEvent() {
    }

    public WebhookEvent(
            UUID id,
            UUID projectId,
            Instant receivedAt,
            String headersJson,
            byte[] body,
            String contentType,
            String dedupeKey,
            EventStatus status
    ) {
        this.id = id;
        this.projectId = projectId;
        this.receivedAt = receivedAt;
        this.headersJson = headersJson;
        this.body = body;
        this.contentType = contentType;
        this.dedupeKey = dedupeKey;
        this.status = status.toStorage();
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getHeadersJson() {
        return headersJson;
    }

    public byte[] getBody() {
        return body;
    }

    public String getContentType() {
        return contentType;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public EventStatus getStatus() {
        return EventStatus.fromStorage(status);
    }

    public void setStatus(EventStatus status) {
        this.status = status.toStorage();
    }
}

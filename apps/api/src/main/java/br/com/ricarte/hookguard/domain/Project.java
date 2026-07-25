package br.com.ricarte.hookguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "projects")
public class Project {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "project_key", nullable = false, unique = true, length = 80)
    private String projectKey;

    @Column(name = "destination_url", nullable = false, length = 2000)
    private String destinationUrl;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "dedupe_header", length = 120)
    private String dedupeHeader;

    @Column(name = "signing_secret", nullable = false, length = 120)
    private String signingSecret;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Project() {
    }

    public Project(
            UUID id,
            UUID accountId,
            String name,
            String projectKey,
            String destinationUrl,
            int timeoutMs,
            int maxAttempts,
            String dedupeHeader,
            String signingSecret,
            ProjectStatus status,
            Instant createdAt
    ) {
        this.id = id;
        this.accountId = accountId;
        this.name = name;
        this.projectKey = projectKey;
        this.destinationUrl = destinationUrl;
        this.timeoutMs = timeoutMs;
        this.maxAttempts = maxAttempts;
        this.dedupeHeader = dedupeHeader;
        this.signingSecret = signingSecret;
        this.status = status.toStorage();
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getName() {
        return name;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public String getDestinationUrl() {
        return destinationUrl;
    }

    public void setDestinationUrl(String destinationUrl) {
        this.destinationUrl = destinationUrl;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public String getDedupeHeader() {
        return dedupeHeader;
    }

    public void setDedupeHeader(String dedupeHeader) {
        this.dedupeHeader = dedupeHeader;
    }

    public String getSigningSecret() {
        return signingSecret;
    }

    public ProjectStatus getStatus() {
        return ProjectStatus.fromStorage(status);
    }

    public void setStatus(ProjectStatus status) {
        this.status = status.toStorage();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

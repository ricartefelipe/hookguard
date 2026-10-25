package br.com.ricarte.hookguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ingest_dedupes")
public class IngestDedupe {

    @EmbeddedId
    private Key id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IngestDedupe() {
    }

    public IngestDedupe(UUID projectId, String dedupeKey, UUID eventId, String fingerprint, Instant expiresAt) {
        this.id = new Key(projectId, dedupeKey);
        this.eventId = eventId;
        this.fingerprint = fingerprint;
        this.expiresAt = expiresAt;
    }

    public Key getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "project_id", nullable = false)
        private UUID projectId;

        @Column(name = "dedupe_key", nullable = false, length = 500)
        private String dedupeKey;

        protected Key() {
        }

        public Key(UUID projectId, String dedupeKey) {
            this.projectId = projectId;
            this.dedupeKey = dedupeKey;
        }

        public UUID getProjectId() {
            return projectId;
        }

        public String getDedupeKey() {
            return dedupeKey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(projectId, key.projectId) && Objects.equals(dedupeKey, key.dedupeKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(projectId, dedupeKey);
        }
    }
}

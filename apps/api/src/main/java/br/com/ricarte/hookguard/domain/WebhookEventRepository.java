package br.com.ricarte.hookguard.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {
    /** Row lock that serialises every writer of an event's status and its job set (replay, settle, recovery). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from WebhookEvent e where e.id = :id")
    Optional<WebhookEvent> findByIdForUpdate(@Param("id") UUID id);

    Optional<WebhookEvent> findByProjectIdAndDedupeKey(UUID projectId, String dedupeKey);

    List<WebhookEvent> findByProjectIdOrderByReceivedAtDesc(UUID projectId, Pageable pageable);

    List<WebhookEvent> findByProjectIdAndStatusOrderByReceivedAtDesc(
            UUID projectId,
            String status,
            Pageable pageable
    );
}

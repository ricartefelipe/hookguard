package br.com.ricarte.hookguard.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {
    Optional<WebhookEvent> findByProjectIdAndDedupeKey(UUID projectId, String dedupeKey);

    List<WebhookEvent> findByProjectIdOrderByReceivedAtDesc(UUID projectId, Pageable pageable);

    List<WebhookEvent> findByProjectIdAndStatusOrderByReceivedAtDesc(
            UUID projectId,
            String status,
            Pageable pageable
    );
}

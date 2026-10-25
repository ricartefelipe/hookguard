package br.com.ricarte.hookguard.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {
    List<DeliveryAttempt> findByEventIdOrderByAttemptNumberAsc(UUID eventId);

    List<DeliveryAttempt> findByEventIdAndFinishedAtIsNull(UUID eventId);

    long countByEventId(UUID eventId);

    @org.springframework.data.jpa.repository.Query("select coalesce(max(a.attemptNumber), 0) from DeliveryAttempt a where a.eventId = :eventId")
    int maxAttemptNumber(@org.springframework.data.repository.query.Param("eventId") UUID eventId);
}

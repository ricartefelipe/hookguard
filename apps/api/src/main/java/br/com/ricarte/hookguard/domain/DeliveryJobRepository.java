package br.com.ricarte.hookguard.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryJobRepository extends JpaRepository<DeliveryJob, UUID> {

    @Query(value = """
            SELECT id FROM delivery_jobs
            WHERE state = 'pending'
              AND available_at <= :now
            ORDER BY available_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> claimCandidateIds(@Param("now") Instant now, @Param("limit") int limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeliveryJob j
               set j.state = :state,
                   j.lockedBy = null,
                   j.lockedAt = null,
                   j.availableAt = :availableAt
             where j.id = :id
               and j.lockedBy = :token
               and j.state = 'in_progress'
            """)
    int finishLease(
            @Param("id") UUID id,
            @Param("token") String token,
            @Param("state") String state,
            @Param("availableAt") Instant availableAt
    );

    @Query("""
            select j from DeliveryJob j
             where j.eventId = :eventId
               and j.state in ('pending', 'in_progress')
            """)
    List<DeliveryJob> findOpenByEventId(@Param("eventId") UUID eventId);

    List<DeliveryJob> findByEventIdOrderByAvailableAtAsc(UUID eventId);

    List<DeliveryJob> findByStateAndLockedAtBefore(String state, Instant lockedAt);
}

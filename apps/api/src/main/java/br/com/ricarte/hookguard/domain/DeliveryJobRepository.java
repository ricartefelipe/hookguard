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
            ORDER BY available_at
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> claimCandidateIds(@Param("now") Instant now, @Param("limit") int limit);

    @Modifying
    @Query(value = """
            UPDATE delivery_jobs
            SET state = 'in_progress',
                locked_at = :lockedAt,
                locked_by = :workerId
            WHERE id IN (:ids)
              AND state = 'pending'
            """, nativeQuery = true)
    int markInProgress(
            @Param("ids") List<UUID> ids,
            @Param("workerId") String workerId,
            @Param("lockedAt") Instant lockedAt
    );

    @Modifying
    @Query(value = """
            UPDATE delivery_jobs
            SET state = 'pending',
                locked_at = NULL,
                locked_by = NULL,
                available_at = :now
            WHERE state = 'in_progress'
              AND locked_at < :staleBefore
            """, nativeQuery = true)
    int releaseStaleLocks(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now);
}

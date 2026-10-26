package br.com.ricarte.hookguard.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginTokenRepository extends JpaRepository<LoginToken, UUID> {
    Optional<LoginToken> findByTokenHash(String tokenHash);

    /**
     * Single conditional update: only one caller can move a token from unused to consumed, and only
     * while it has not expired. A concurrent second call waits for the row lock and then matches nothing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update LoginToken t set t.consumedAt = :now where t.id = :id and t.consumedAt is null and t.expiresAt > :now")
    int consumeIfUsable(@Param("id") UUID id, @Param("now") Instant now);
}

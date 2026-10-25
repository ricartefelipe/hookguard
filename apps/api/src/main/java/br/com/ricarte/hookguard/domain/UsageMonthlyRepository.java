package br.com.ricarte.hookguard.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageMonthlyRepository extends JpaRepository<UsageMonthly, UsageMonthly.UsageMonthlyId> {

    Optional<UsageMonthly> findByAccountIdAndYearMonth(UUID accountId, String yearMonth);
}

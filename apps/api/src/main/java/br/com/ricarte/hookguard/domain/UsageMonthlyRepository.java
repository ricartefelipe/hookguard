package br.com.ricarte.hookguard.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsageMonthlyRepository extends JpaRepository<UsageMonthly, UsageMonthly.UsageMonthlyId> {

    Optional<UsageMonthly> findByAccountIdAndYearMonth(UUID accountId, String yearMonth);

    @Modifying
    @Query(value = """
            INSERT INTO usage_monthly (account_id, year_month, event_count)
            VALUES (:accountId, :yearMonth, 1)
            ON CONFLICT (account_id, year_month)
            DO UPDATE SET event_count = usage_monthly.event_count + 1
            """, nativeQuery = true)
    void increment(@Param("accountId") UUID accountId, @Param("yearMonth") String yearMonth);
}

package br.com.ricarte.hookguard.billing;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.domain.Account;
import br.com.ricarte.hookguard.domain.AccountPlan;
import br.com.ricarte.hookguard.domain.AccountRepository;
import br.com.ricarte.hookguard.domain.UsageMonthly;
import br.com.ricarte.hookguard.domain.UsageMonthlyRepository;
import br.com.ricarte.hookguard.web.ApiException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsageService {

    private final UsageMonthlyRepository usageMonthlyRepository;
    private final AccountRepository accountRepository;
    private final HookguardProperties properties;
    private final UsageOutbox usageOutbox;
    private final JdbcTemplate jdbcTemplate;

    public UsageService(
            UsageMonthlyRepository usageMonthlyRepository,
            AccountRepository accountRepository,
            HookguardProperties properties,
            UsageOutbox usageOutbox,
            JdbcTemplate jdbcTemplate
    ) {
        this.usageMonthlyRepository = usageMonthlyRepository;
        this.accountRepository = accountRepository;
        this.properties = properties;
        this.usageOutbox = usageOutbox;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void assertWithinQuotaAndIncrement(UUID accountId, UUID eventId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "account_not_found"));
        String yearMonth = YearMonth.now(ZoneOffset.UTC).toString();
        long included = includedEvents(account.getPlan());
        long ceiling = account.getPlan() == AccountPlan.FREE ? included : Long.MAX_VALUE;
        Long updated = jdbcTemplate.query(
                """
                INSERT INTO usage_monthly (account_id, year_month, event_count)
                VALUES (?, ?, 1)
                ON CONFLICT (account_id, year_month)
                DO UPDATE SET event_count = usage_monthly.event_count + 1
                WHERE usage_monthly.event_count < ?
                RETURNING event_count
                """,
                rs -> rs.next() ? rs.getLong(1) : null,
                accountId,
                yearMonth,
                ceiling
        );
        if (updated == null) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "free_quota_exceeded");
        }
        if (account.getPlan() != AccountPlan.FREE && updated > included) {
            // Same transaction as the count: a rollback drops the intent, a commit keeps it for the sender.
            usageOutbox.enqueue(accountId, eventId, Instant.now());
        }
    }

    @Transactional(readOnly = true)
    public long currentUsage(UUID accountId) {
        String yearMonth = YearMonth.now(ZoneOffset.UTC).toString();
        return usageMonthlyRepository.findByAccountIdAndYearMonth(accountId, yearMonth)
                .map(UsageMonthly::getEventCount)
                .orElse(0L);
    }

    public long includedEvents(AccountPlan plan) {
        return switch (plan) {
            case FREE -> properties.billing().freeMonthlyEvents();
            case PRO -> properties.billing().proMonthlyEvents();
            case BUSINESS -> properties.billing().businessMonthlyEvents();
        };
    }
}

package br.com.ricarte.hookguard.billing;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.domain.Account;
import br.com.ricarte.hookguard.domain.AccountPlan;
import br.com.ricarte.hookguard.domain.AccountRepository;
import br.com.ricarte.hookguard.domain.UsageMonthly;
import br.com.ricarte.hookguard.domain.UsageMonthlyRepository;
import br.com.ricarte.hookguard.web.ApiException;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class UsageService {

    private final UsageMonthlyRepository usageMonthlyRepository;
    private final AccountRepository accountRepository;
    private final HookguardProperties properties;
    private final StripeUsageReporter stripeUsageReporter;
    private final JdbcTemplate jdbcTemplate;

    public UsageService(
            UsageMonthlyRepository usageMonthlyRepository,
            AccountRepository accountRepository,
            HookguardProperties properties,
            StripeUsageReporter stripeUsageReporter,
            JdbcTemplate jdbcTemplate
    ) {
        this.usageMonthlyRepository = usageMonthlyRepository;
        this.accountRepository = accountRepository;
        this.properties = properties;
        this.stripeUsageReporter = stripeUsageReporter;
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
            String identifier = eventId.toString();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        stripeUsageReporter.reportOverageEvent(account, identifier);
                    }
                });
            } else {
                stripeUsageReporter.reportOverageEvent(account, identifier);
            }
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

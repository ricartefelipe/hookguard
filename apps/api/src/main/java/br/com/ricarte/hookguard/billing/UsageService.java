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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsageService {

    private final UsageMonthlyRepository usageMonthlyRepository;
    private final AccountRepository accountRepository;
    private final HookguardProperties properties;
    private final StripeUsageReporter stripeUsageReporter;

    public UsageService(
            UsageMonthlyRepository usageMonthlyRepository,
            AccountRepository accountRepository,
            HookguardProperties properties,
            StripeUsageReporter stripeUsageReporter
    ) {
        this.usageMonthlyRepository = usageMonthlyRepository;
        this.accountRepository = accountRepository;
        this.properties = properties;
        this.stripeUsageReporter = stripeUsageReporter;
    }

    @Transactional
    public void assertWithinQuotaAndIncrement(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "account_not_found"));
        String yearMonth = YearMonth.now(ZoneOffset.UTC).toString();
        long current = usageMonthlyRepository.findByAccountIdAndYearMonth(accountId, yearMonth)
                .map(UsageMonthly::getEventCount)
                .orElse(0L);
        long included = includedEvents(account.getPlan());
        if (account.getPlan() == AccountPlan.FREE && current >= included) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "free_quota_exceeded");
        }
        usageMonthlyRepository.increment(accountId, yearMonth);
        if (account.getPlan() != AccountPlan.FREE && current >= included) {
            stripeUsageReporter.reportOverageEvent(account);
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

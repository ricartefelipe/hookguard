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

    public UsageService(
            UsageMonthlyRepository usageMonthlyRepository,
            AccountRepository accountRepository,
            HookguardProperties properties
    ) {
        this.usageMonthlyRepository = usageMonthlyRepository;
        this.accountRepository = accountRepository;
        this.properties = properties;
    }

    @Transactional
    public void assertWithinQuotaAndIncrement(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "account_not_found"));
        String yearMonth = YearMonth.now(ZoneOffset.UTC).toString();
        long current = usageMonthlyRepository.findByAccountIdAndYearMonth(accountId, yearMonth)
                .map(UsageMonthly::getEventCount)
                .orElse(0L);
        if (account.getPlan() == AccountPlan.FREE && current >= properties.billing().freeMonthlyEvents()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "free_quota_exceeded");
        }
        usageMonthlyRepository.increment(accountId, yearMonth);
    }

    @Transactional(readOnly = true)
    public long currentUsage(UUID accountId) {
        String yearMonth = YearMonth.now(ZoneOffset.UTC).toString();
        return usageMonthlyRepository.findByAccountIdAndYearMonth(accountId, yearMonth)
                .map(UsageMonthly::getEventCount)
                .orElse(0L);
    }
}

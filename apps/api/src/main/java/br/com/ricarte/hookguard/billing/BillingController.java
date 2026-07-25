package br.com.ricarte.hookguard.billing;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.domain.Account;
import br.com.ricarte.hookguard.domain.AccountRepository;
import br.com.ricarte.hookguard.web.AccountContext;
import br.com.ricarte.hookguard.web.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/billing")
public class BillingController {

    private final AccountRepository accountRepository;
    private final UsageService usageService;
    private final HookguardProperties properties;

    public BillingController(
            AccountRepository accountRepository,
            UsageService usageService,
            HookguardProperties properties
    ) {
        this.accountRepository = accountRepository;
        this.usageService = usageService;
        this.properties = properties;
    }

    @GetMapping("/usage")
    public Map<String, Object> usage() {
        UUID accountId = AccountContext.requireAccountId();
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "account_not_found"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan", account.getPlan().toStorage());
        body.put("eventCount", usageService.currentUsage(accountId));
        body.put("freeMonthlyEvents", properties.billing().freeMonthlyEvents());
        body.put("stripeConfigured", properties.billing().stripeApiKey() != null
                && !properties.billing().stripeApiKey().isBlank());
        return body;
    }
}

package br.com.ricarte.hookguard.bootstrap;

import br.com.ricarte.hookguard.billing.UsageService;
import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.domain.Account;
import br.com.ricarte.hookguard.project.ProjectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/bootstrap")
public class BootstrapController {

    private final ProjectService projectService;
    private final UsageService usageService;
    private final HookguardProperties properties;

    public BootstrapController(
            ProjectService projectService,
            UsageService usageService,
            HookguardProperties properties
    ) {
        this.projectService = projectService;
        this.usageService = usageService;
        this.properties = properties;
    }

    @PostMapping("/account")
    public Map<String, Object> createAccount(@Valid @RequestBody BootstrapAccountRequest request) {
        Account account = projectService.ensureAccount(request.email(), request.name());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", account.getId().toString());
        body.put("email", account.getEmail());
        body.put("plan", account.getPlan().toStorage());
        body.put("usage", usageService.currentUsage(account.getId()));
        body.put("freeMonthlyEvents", properties.billing().freeMonthlyEvents());
        return body;
    }

    public record BootstrapAccountRequest(
            @NotBlank @Email String email,
            String name
    ) {
    }
}

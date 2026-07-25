package br.com.ricarte.hookguard.project;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.delivery.DestinationUrlValidator;
import br.com.ricarte.hookguard.domain.Account;
import br.com.ricarte.hookguard.domain.AccountPlan;
import br.com.ricarte.hookguard.domain.AccountRepository;
import br.com.ricarte.hookguard.domain.Project;
import br.com.ricarte.hookguard.domain.ProjectRepository;
import br.com.ricarte.hookguard.domain.ProjectStatus;
import br.com.ricarte.hookguard.web.ApiException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final AccountRepository accountRepository;
    private final HookguardProperties properties;
    private final boolean allowHttpDestinations;
    private final SecureRandom secureRandom = new SecureRandom();

    public ProjectService(
            ProjectRepository projectRepository,
            AccountRepository accountRepository,
            HookguardProperties properties,
            @Value("${hookguard.delivery.allow-http:false}") boolean allowHttpDestinations
    ) {
        this.projectRepository = projectRepository;
        this.accountRepository = accountRepository;
        this.properties = properties;
        this.allowHttpDestinations = allowHttpDestinations;
    }

    @Transactional
    public Account ensureAccount(String email, String name) {
        return accountRepository.findByEmail(email.toLowerCase())
                .orElseGet(() -> accountRepository.save(new Account(
                        UUID.randomUUID(),
                        email.toLowerCase(),
                        name,
                        AccountPlan.FREE,
                        Instant.now()
                )));
    }

    @Transactional
    public Project create(UUID accountId, String name, String destinationUrl, String dedupeHeader) {
        DestinationUrlValidator.validate(destinationUrl, allowHttpDestinations);
        if (!accountRepository.existsById(accountId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "account_not_found");
        }
        Project project = new Project(
                UUID.randomUUID(),
                accountId,
                name,
                randomToken(24),
                destinationUrl,
                properties.delivery().defaultTimeoutMs(),
                properties.delivery().maxAttempts(),
                dedupeHeader,
                randomToken(32),
                ProjectStatus.ACTIVE,
                Instant.now()
        );
        return projectRepository.save(project);
    }

    @Transactional(readOnly = true)
    public List<Project> list(UUID accountId) {
        return projectRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
    }

    @Transactional(readOnly = true)
    public Project getOwned(UUID accountId, UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project_not_found"));
        if (!project.getAccountId().equals(accountId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "project_not_found");
        }
        return project;
    }

    @Transactional
    public Project update(
            UUID accountId,
            UUID projectId,
            String destinationUrl,
            Integer timeoutMs,
            Integer maxAttempts,
            String dedupeHeader
    ) {
        Project project = getOwned(accountId, projectId);
        if (destinationUrl != null) {
            DestinationUrlValidator.validate(destinationUrl, allowHttpDestinations);
            project.setDestinationUrl(destinationUrl);
        }
        if (timeoutMs != null) {
            project.setTimeoutMs(timeoutMs);
        }
        if (maxAttempts != null) {
            project.setMaxAttempts(maxAttempts);
        }
        if (dedupeHeader != null) {
            project.setDedupeHeader(dedupeHeader.isBlank() ? null : dedupeHeader);
        }
        return projectRepository.save(project);
    }

    private String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        secureRandom.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }
}

package br.com.ricarte.hookguard.project;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.delivery.DestinationUrlValidator;
import br.com.ricarte.hookguard.domain.Account;
import br.com.ricarte.hookguard.domain.AccountPlan;
import br.com.ricarte.hookguard.domain.AccountRepository;
import br.com.ricarte.hookguard.domain.Project;
import br.com.ricarte.hookguard.domain.ProjectRepository;
import br.com.ricarte.hookguard.domain.ProjectStatus;
import br.com.ricarte.hookguard.ingest.IngestService;
import br.com.ricarte.hookguard.ingest.SourceAuthVerifier;
import br.com.ricarte.hookguard.web.ApiException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final AccountRepository accountRepository;
    private final IngestService ingestService;
    private final JdbcTemplate jdbcTemplate;
    private final HookguardProperties properties;
    private final boolean allowHttpDestinations;
    private final SecureRandom secureRandom = new SecureRandom();

    public ProjectService(
            ProjectRepository projectRepository,
            AccountRepository accountRepository,
            IngestService ingestService,
            JdbcTemplate jdbcTemplate,
            HookguardProperties properties,
            @Value("${hookguard.delivery.allow-http:false}") boolean allowHttpDestinations
    ) {
        this.projectRepository = projectRepository;
        this.accountRepository = accountRepository;
        this.ingestService = ingestService;
        this.jdbcTemplate = jdbcTemplate;
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
            if (timeoutMs < 500 || timeoutMs > 30_000) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "timeout_out_of_range");
            }
            project.setTimeoutMs(timeoutMs);
        }
        if (maxAttempts != null) {
            if (maxAttempts < 1 || maxAttempts > 20) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "attempts_out_of_range");
            }
            project.setMaxAttempts(maxAttempts);
        }
        if (dedupeHeader != null) {
            if (dedupeHeader.length() > 120 || dedupeHeader.indexOf('\r') >= 0 || dedupeHeader.indexOf('\n') >= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "dedupe_header_invalid");
            }
            project.setDedupeHeader(dedupeHeader.isBlank() ? null : dedupeHeader);
        }
        return projectRepository.save(project);
    }

    @Transactional
    public Project rotateProjectKey(UUID accountId, UUID projectId) {
        Project project = getOwned(accountId, projectId);
        project.setProjectKey(randomToken(24));
        return projectRepository.save(project);
    }

    @Transactional
    public Project rotateSigningSecret(UUID accountId, UUID projectId) {
        Project project = getOwned(accountId, projectId);
        project.setSigningSecretPrevious(project.getSigningSecret());
        project.setSigningSecretRotatedAt(Instant.now());
        project.setSigningSecret(randomToken(32));
        return projectRepository.save(project);
    }

    @Transactional
    public Project setPaused(UUID accountId, UUID projectId, boolean paused) {
        Project project = getOwned(accountId, projectId);
        if (project.getStatus() == ProjectStatus.SUSPENDED) {
            throw new ApiException(HttpStatus.CONFLICT, "project_suspended");
        }
        project.setStatus(paused ? ProjectStatus.PAUSED : ProjectStatus.ACTIVE);
        Project saved = projectRepository.save(project);
        if (!paused) {
            jdbcTemplate.update(
                    "UPDATE delivery_jobs SET available_at = ? WHERE project_id = ? AND state = 'pending'",
                    java.sql.Timestamp.from(Instant.now()),
                    projectId
            );
        }
        return saved;
    }

    @Transactional
    public UUID sendTestEvent(UUID accountId, UUID projectId) {
        Project project = getOwned(accountId, projectId);
        if (project.getStatus() == ProjectStatus.SUSPENDED) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "project_suspended");
        }
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        if (project.getDedupeHeader() != null && !project.getDedupeHeader().isBlank()) {
            headers.put(project.getDedupeHeader(), "test-" + UUID.randomUUID());
        }
        return ingestService.acceptFromOwner(
                project.getProjectKey(),
                headers,
                "{\"hookguard\":\"test\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "application/json"
        );
    }

    @Transactional
    public Project configureSourceAuth(UUID accountId, UUID projectId, String mode, String secret, Integer toleranceSeconds) {
        Project project = getOwned(accountId, projectId);
        SourceAuthVerifier.Mode parsed = SourceAuthVerifier.Mode.fromStorage(mode);
        if (parsed == SourceAuthVerifier.Mode.NONE) {
            project.configureSourceAuth(parsed.toStorage(), null, 300);
            return projectRepository.save(project);
        }
        String trimmed = secret == null ? "" : secret.trim();
        if (trimmed.length() < 8 || trimmed.length() > 200) {
            throw new IllegalArgumentException("source secret must have between 8 and 200 characters");
        }
        int tolerance = toleranceSeconds == null ? 300 : toleranceSeconds;
        if (tolerance < 30 || tolerance > 3600) {
            throw new IllegalArgumentException("toleranceSeconds must be between 30 and 3600");
        }
        project.configureSourceAuth(parsed.toStorage(), trimmed, tolerance);
        return projectRepository.save(project);
    }

    @Transactional
    public void delete(UUID accountId, UUID projectId) {
        getOwned(accountId, projectId);
        jdbcTemplate.update(
                "DELETE FROM delivery_attempts WHERE event_id IN (SELECT id FROM events WHERE project_id = ?)",
                projectId
        );
        jdbcTemplate.update("DELETE FROM delivery_jobs WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM ingest_dedupes WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM events WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM projects WHERE id = ? AND account_id = ?", projectId, accountId);
    }

    private String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        secureRandom.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }
}

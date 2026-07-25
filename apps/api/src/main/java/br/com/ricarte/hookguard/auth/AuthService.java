package br.com.ricarte.hookguard.auth;

import br.com.ricarte.hookguard.billing.UsageService;
import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.domain.Account;
import br.com.ricarte.hookguard.domain.AccountRepository;
import br.com.ricarte.hookguard.domain.LoginToken;
import br.com.ricarte.hookguard.domain.LoginTokenRepository;
import br.com.ricarte.hookguard.domain.Session;
import br.com.ricarte.hookguard.domain.SessionRepository;
import br.com.ricarte.hookguard.project.ProjectService;
import br.com.ricarte.hookguard.web.ApiException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final ProjectService projectService;
    private final AccountRepository accountRepository;
    private final LoginTokenRepository loginTokenRepository;
    private final SessionRepository sessionRepository;
    private final UsageService usageService;
    private final HookguardProperties properties;
    private final JavaMailSender mailSender;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(
            ProjectService projectService,
            AccountRepository accountRepository,
            LoginTokenRepository loginTokenRepository,
            SessionRepository sessionRepository,
            UsageService usageService,
            HookguardProperties properties,
            JavaMailSender mailSender
    ) {
        this.projectService = projectService;
        this.accountRepository = accountRepository;
        this.loginTokenRepository = loginTokenRepository;
        this.sessionRepository = sessionRepository;
        this.usageService = usageService;
        this.properties = properties;
        this.mailSender = mailSender;
    }

    @Transactional
    public Map<String, Object> requestMagicLink(String email, String name) {
        return requestMagicLink(email, name, null);
    }

    public Map<String, Object> requestMagicLink(String email, String name, String appBaseUrlOverride) {
        Account account = projectService.ensureAccount(email, name == null || name.isBlank() ? email : name);
        String rawToken = randomToken(32);
        Instant now = Instant.now();
        LoginToken loginToken = new LoginToken(
                UUID.randomUUID(),
                account.getId(),
                TokenHasher.sha256(rawToken),
                now.plus(properties.auth().magicLinkTtlMinutes(), ChronoUnit.MINUTES),
                now
        );
        loginTokenRepository.save(loginToken);

        String base = appBaseUrlOverride == null || appBaseUrlOverride.isBlank()
                ? properties.auth().appBaseUrl()
                : appBaseUrlOverride;
        String link = base.replaceAll("/$", "") + "/auth/callback?token=" + rawToken;
        sendMail(account.getEmail(), link);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sent", true);
        body.put("email", account.getEmail());
        if (properties.auth().exposeMagicLink()) {
            body.put("magicLink", link);
        }
        return body;
    }

    @Transactional
    public Map<String, Object> verifyMagicLink(String rawToken) {
        LoginToken loginToken = loginTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid_token"));
        Instant now = Instant.now();
        if (loginToken.getConsumedAt() != null || loginToken.getExpiresAt().isBefore(now)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_token");
        }
        loginToken.consume(now);
        loginTokenRepository.save(loginToken);

        Account account = accountRepository.findById(loginToken.getAccountId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid_token"));
        return createSessionForAccount(account);
    }

    @Transactional
    public Map<String, Object> createSessionForAccount(Account account) {
        Instant now = Instant.now();
        String sessionRaw = randomToken(32);
        Session session = new Session(
                UUID.randomUUID(),
                account.getId(),
                TokenHasher.sha256(sessionRaw),
                now.plus(properties.auth().sessionTtlDays(), ChronoUnit.DAYS),
                now
        );
        sessionRepository.save(session);
        return sessionResponse(account, sessionRaw);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> resolveAccountId(String sessionRaw) {
        if (sessionRaw == null || sessionRaw.isBlank()) {
            return Optional.empty();
        }
        return sessionRepository.findByTokenHash(TokenHasher.sha256(sessionRaw))
                .filter(session -> session.active(Instant.now()))
                .map(Session::getAccountId);
    }

    @Transactional
    public void logout(String sessionRaw) {
        sessionRepository.findByTokenHash(TokenHasher.sha256(sessionRaw)).ifPresent(session -> {
            session.revoke(Instant.now());
            sessionRepository.save(session);
        });
    }

    @Transactional(readOnly = true)
    public Map<String, Object> me(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "account_not_found"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", account.getId().toString());
        body.put("email", account.getEmail());
        body.put("plan", account.getPlan().toStorage());
        body.put("usage", usageService.currentUsage(accountId));
        body.put("includedMonthlyEvents", usageService.includedEvents(account.getPlan()));
        body.put("freeMonthlyEvents", properties.billing().freeMonthlyEvents());
        body.put("githubOAuthConfigured", properties.auth().githubClientId() != null
                && !properties.auth().githubClientId().isBlank());
        return body;
    }

    private Map<String, Object> sessionResponse(Account account, String sessionRaw) {
        Map<String, Object> body = me(account.getId());
        body.put("sessionToken", sessionRaw);
        return body;
    }

    private void sendMail(String to, String link) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.auth().fromEmail());
        message.setTo(to);
        message.setSubject("Seu acesso ao HookGuard");
        message.setText("Use este link para entrar (expira em breve):\n\n" + link + "\n");
        mailSender.send(message);
    }

    private String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        secureRandom.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }
}

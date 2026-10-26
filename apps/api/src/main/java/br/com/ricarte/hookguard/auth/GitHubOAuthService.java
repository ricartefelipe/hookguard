package br.com.ricarte.hookguard.auth;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.domain.Account;
import br.com.ricarte.hookguard.domain.AccountPlan;
import br.com.ricarte.hookguard.domain.AccountRepository;
import br.com.ricarte.hookguard.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class GitHubOAuthService {

    private final HookguardProperties properties;
    private final AccountRepository accountRepository;
    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private final SecureRandom secureRandom = new SecureRandom();
    private final String authorizeBase;
    private final String tokenUrl;
    private final String apiBase;

    @Autowired
    public GitHubOAuthService(
            HookguardProperties properties,
            AccountRepository accountRepository,
            AuthService authService,
            ObjectMapper objectMapper,
            @Value("${hookguard.auth.github-authorize-url:https://github.com/login/oauth/authorize}") String authorizeBase,
            @Value("${hookguard.auth.github-token-url:https://github.com/login/oauth/access_token}") String tokenUrl,
            @Value("${hookguard.auth.github-api-url:https://api.github.com}") String apiBase
    ) {
        this.authorizeBase = authorizeBase;
        this.tokenUrl = tokenUrl;
        this.apiBase = apiBase;
        this.properties = properties;
        this.accountRepository = accountRepository;
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    public boolean configured() {
        String id = properties.auth().githubClientId();
        String secret = properties.auth().githubClientSecret();
        return id != null && !id.isBlank() && secret != null && !secret.isBlank();
    }

    /** A fresh browser-bound nonce; the same value goes into a cookie and, signed, into the OAuth state. */
    public String newNonce() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public String authorizeUrl(String nonce) {
        ensureConfigured();
        return UriComponentsBuilder
                .fromUriString(authorizeBase)
                .queryParam("client_id", enc(properties.auth().githubClientId()))
                .queryParam("redirect_uri", enc(callbackUrl()))
                .queryParam("scope", enc("read:user user:email"))
                .queryParam("state", enc(signState(nonce, Instant.now().plus(STATE_TTL))))
                .build(true)
                .toUriString();
    }

    String signState(String nonce, Instant expiresAt) {
        String payload = nonce + "." + expiresAt.getEpochSecond();
        return payload + "." + hmac(payload);
    }

    /** Accepts the callback only for the browser that started the flow, within the state lifetime. */
    void verifyState(String state, String cookieNonce) {
        if (state == null || cookieNonce == null || cookieNonce.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_oauth_state");
        }
        String[] parts = state.split("\\.");
        if (parts.length != 3) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_oauth_state");
        }
        String payload = parts[0] + "." + parts[1];
        boolean signatureOk = MessageDigest.isEqual(
                hmac(payload).getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8));
        boolean cookieOk = MessageDigest.isEqual(
                parts[0].getBytes(StandardCharsets.UTF_8), cookieNonce.getBytes(StandardCharsets.UTF_8));
        long expires;
        try {
            expires = Long.parseLong(parts[1]);
        } catch (NumberFormatException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_oauth_state");
        }
        if (!signatureOk || !cookieOk || Instant.now().getEpochSecond() > expires) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_oauth_state");
        }
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    ("hookguard-github-state:" + properties.auth().githubClientSecret()).getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    @Transactional
    public Map<String, Object> handleCallback(String code, String state, String cookieNonce) {
        ensureConfigured();
        verifyState(state, cookieNonce);
        if (code == null || code.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "missing_code");
        }
        try {
            String accessToken = exchangeCode(code);
            JsonNode user = getJson(apiBase + "/user", accessToken);
            String githubId = user.path("id").asText(null);
            String name = user.path("name").asText(user.path("login").asText("github-user"));
            String email = user.path("email").asText(null);
            if (email == null || email.isBlank()) {
                email = primaryEmail(accessToken);
            }
            if (githubId == null || githubId.isBlank() || email == null || email.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "github_profile_incomplete");
            }
            Account account = upsertGithubAccount(githubId, email.toLowerCase(), name);
            return authService.createSessionForAccount(account);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "github_oauth_failed");
        }
    }

    private Account upsertGithubAccount(String githubId, String email, String name) {
        Optional<Account> byGithub = accountRepository.findByGithubId(githubId);
        if (byGithub.isPresent()) {
            Account account = byGithub.get();
            if (account.getName() == null || account.getName().isBlank()) {
                account.setName(name);
            }
            return accountRepository.save(account);
        }
        Optional<Account> byEmail = accountRepository.findByEmail(email);
        if (byEmail.isPresent()) {
            Account account = byEmail.get();
            account.setGithubId(githubId);
            if (account.getName() == null || account.getName().isBlank()) {
                account.setName(name);
            }
            return accountRepository.save(account);
        }
        Account created = new Account(UUID.randomUUID(), email, name, AccountPlan.FREE, Instant.now());
        created.setGithubId(githubId);
        return accountRepository.save(created);
    }

    private String exchangeCode(String code) throws Exception {
        String body = "client_id=" + enc(properties.auth().githubClientId())
                + "&client_secret=" + enc(properties.auth().githubClientSecret())
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(callbackUrl());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = objectMapper.readTree(response.body());
        String token = json.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "github_token_failed");
        }
        return token;
    }

    private String primaryEmail(String accessToken) throws Exception {
        JsonNode emails = getJson(apiBase + "/user/emails", accessToken);
        if (!emails.isArray()) {
            return null;
        }
        for (JsonNode emailNode : emails) {
            if (emailNode.path("primary").asBoolean(false) && emailNode.path("verified").asBoolean(false)) {
                return emailNode.path("email").asText(null);
            }
        }
        for (JsonNode emailNode : emails) {
            if (emailNode.path("verified").asBoolean(false)) {
                return emailNode.path("email").asText(null);
            }
        }
        return null;
    }

    private JsonNode getJson(String url, String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .header("User-Agent", "hookguard")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "github_api_failed");
        }
        return objectMapper.readTree(response.body());
    }

    private String callbackUrl() {
        return properties.auth().apiBaseUrl().replaceAll("/$", "") + "/v1/auth/github/callback";
    }

    private void ensureConfigured() {
        if (!configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "github_oauth_not_configured");
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

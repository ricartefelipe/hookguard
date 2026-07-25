package br.com.ricarte.hookguard.auth;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.domain.Account;
import br.com.ricarte.hookguard.domain.AccountPlan;
import br.com.ricarte.hookguard.domain.AccountRepository;
import br.com.ricarte.hookguard.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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

    public GitHubOAuthService(
            HookguardProperties properties,
            AccountRepository accountRepository,
            AuthService authService,
            ObjectMapper objectMapper
    ) {
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

    public String authorizeUrl() {
        ensureConfigured();
        return UriComponentsBuilder
                .fromUriString("https://github.com/login/oauth/authorize")
                .queryParam("client_id", properties.auth().githubClientId())
                .queryParam("redirect_uri", callbackUrl())
                .queryParam("scope", "read:user user:email")
                .build(true)
                .toUriString();
    }

    @Transactional
    public Map<String, Object> handleCallback(String code) {
        ensureConfigured();
        if (code == null || code.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "missing_code");
        }
        try {
            String accessToken = exchangeCode(code);
            JsonNode user = getJson("https://api.github.com/user", accessToken);
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
                .uri(URI.create("https://github.com/login/oauth/access_token"))
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
        JsonNode emails = getJson("https://api.github.com/user/emails", accessToken);
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

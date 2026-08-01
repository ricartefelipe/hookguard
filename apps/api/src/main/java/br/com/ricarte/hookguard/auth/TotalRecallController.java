package br.com.ricarte.hookguard.auth;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.domain.Account;
import br.com.ricarte.hookguard.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/totalrecall")
public class TotalRecallController {

    private final AuthService authService;
    private final HookguardProperties properties;

    public TotalRecallController(AuthService authService, HookguardProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @GetMapping("/health")
    public Map<String, Boolean> health(@RequestHeader("X-TotalRecall-Token") String token) {
        authorize(token);
        return Map.of("ok", true);
    }

    @PostMapping("/users")
    public Map<String, Object> users(
            @RequestHeader("X-TotalRecall-Token") String token,
            @Valid @RequestBody ProvisionRequest request
    ) {
        authorize(token);
        validateAction(request.action());
        Account account = authService.provision(
                request.email(), request.name(), request.password(), request.role(), request.expiresAt(), request.action());
        return Map.of("accountId", account.getId().toString(), "email", account.getEmail(), "enabled", account.isEnabled());
    }

    private void authorize(String token) {
        String expected = properties.totalrecall().provisionToken();
        if (expected == null || expected.isBlank()
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized");
        }
    }

    private void validateAction(String action) {
        if (!"upsert".equals(action) && !"disable".equals(action) && !"revoke".equals(action)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_action");
        }
    }

    public record ProvisionRequest(
            @NotBlank @Email String email,
            String name,
            String password,
            String role,
            Instant expiresAt,
            @NotBlank String action
    ) {
    }
}

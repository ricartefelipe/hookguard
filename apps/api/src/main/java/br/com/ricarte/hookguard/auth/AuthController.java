package br.com.ricarte.hookguard.auth;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.web.AccountContext;
import br.com.ricarte.hookguard.web.PublicBaseUrl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final GitHubOAuthService gitHubOAuthService;
    private final HookguardProperties properties;

    public AuthController(
            AuthService authService,
            GitHubOAuthService gitHubOAuthService,
            HookguardProperties properties
    ) {
        this.authService = authService;
        this.gitHubOAuthService = gitHubOAuthService;
        this.properties = properties;
    }

    @PostMapping("/magic-link")
    public Map<String, Object> magicLink(
            @Valid @RequestBody MagicLinkRequest request,
            HttpServletRequest httpRequest
    ) {
        return authService.requestMagicLink(
                request.email(),
                request.name(),
                PublicBaseUrl.resolve(httpRequest, properties)
        );
    }

    @PostMapping("/verify")
    public Map<String, Object> verify(@Valid @RequestBody VerifyRequest request) {
        return authService.verifyMagicLink(request.token());
    }

    @GetMapping("/verify")
    public Map<String, Object> verifyGet(@RequestParam String token) {
        return authService.verifyMagicLink(token);
    }

    @GetMapping("/github")
    public void githubStart(HttpServletResponse response) throws IOException {
        response.sendRedirect(gitHubOAuthService.authorizeUrl());
    }

    @GetMapping("/github/callback")
    public void githubCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        String appBase = PublicBaseUrl.resolve(request, properties);
        if (error != null && !error.isBlank()) {
            response.sendRedirect(appBase + "/?error=" + enc(error));
            return;
        }
        Map<String, Object> session = gitHubOAuthService.handleCallback(code);
        String token = String.valueOf(session.get("sessionToken"));
        response.sendRedirect(appBase + "/auth/callback?sessionToken=" + enc(token));
    }

    @GetMapping("/providers")
    public Map<String, Object> providers() {
        return Map.of(
                "magicLink", true,
                "github", gitHubOAuthService.configured()
        );
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        return authService.me(AccountContext.requireAccountId());
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletRequest request) {
        String bearer = extractBearer(request);
        if (bearer != null) {
            authService.logout(bearer);
        }
        return Map.of("ok", true);
    }

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length()).trim();
        }
        return null;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record MagicLinkRequest(@NotBlank @Email String email, String name) {
    }

    public record VerifyRequest(@NotBlank String token) {
    }
}

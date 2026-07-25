package br.com.ricarte.hookguard.auth;

import br.com.ricarte.hookguard.web.AccountContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/magic-link")
    public Map<String, Object> magicLink(@Valid @RequestBody MagicLinkRequest request) {
        return authService.requestMagicLink(request.email(), request.name());
    }

    @PostMapping("/verify")
    public Map<String, Object> verify(@Valid @RequestBody VerifyRequest request) {
        return authService.verifyMagicLink(request.token());
    }

    @GetMapping("/verify")
    public Map<String, Object> verifyGet(@RequestParam String token) {
        return authService.verifyMagicLink(token);
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

    public record MagicLinkRequest(@NotBlank @Email String email, String name) {
    }

    public record VerifyRequest(@NotBlank String token) {
    }
}

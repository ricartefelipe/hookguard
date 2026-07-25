package br.com.ricarte.hookguard.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AccountAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String path = request.getRequestURI();
            if (requiresAccount(path)) {
                String header = request.getHeader("X-Account-Id");
                if (header == null || header.isBlank()) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"unauthorized\"}");
                    return;
                }
                try {
                    AccountContext.set(UUID.fromString(header.trim()));
                } catch (IllegalArgumentException ex) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"unauthorized\"}");
                    return;
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            AccountContext.clear();
        }
    }

    private boolean requiresAccount(String path) {
        if (path.startsWith("/v1/ingest/")) {
            return false;
        }
        if (path.startsWith("/v1/bootstrap")) {
            return false;
        }
        if (path.startsWith("/v1/billing/stripe/webhook")) {
            return false;
        }
        if (path.startsWith("/actuator")) {
            return false;
        }
        return path.startsWith("/v1/");
    }
}

package br.com.ricarte.hookguard.web;

import br.com.ricarte.hookguard.config.HookguardProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;

public final class PublicBaseUrl {

    private PublicBaseUrl() {
    }

    public static String resolve(HttpServletRequest request, HookguardProperties properties) {
        String configured = properties.auth().appBaseUrl().replaceAll("/$", "");
        if (!properties.auth().trustForwardedHost()) {
            return configured;
        }
        String suffix = properties.auth().forwardedHostSuffix();
        if (suffix == null || suffix.isBlank()) {
            return configured;
        }
        String host = firstNonBlank(request.getHeader("X-Forwarded-Host"), request.getHeader("Host"));
        if (!allowedHost(host, suffix)) {
            return configured;
        }
        String proto = firstNonBlank(request.getHeader("X-Forwarded-Proto"), request.getScheme());
        if (!"http".equals(proto) && !"https".equals(proto)) {
            proto = "https";
        }
        return proto + "://" + host.split(",")[0].trim();
    }

    private static boolean allowedHost(String hostHeader, String suffix) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String host = hostHeader.split(",")[0].trim();
        if (host.indexOf('@') >= 0 || host.indexOf('/') >= 0 || host.indexOf('\\') >= 0 || host.indexOf(' ') >= 0) {
            return false;
        }
        String name = host;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(']') < 0) {
            String port = host.substring(colon + 1);
            if (!port.chars().allMatch(Character::isDigit)) {
                return false;
            }
            name = host.substring(0, colon);
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        String expected = suffix.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        if (!(normalized.equals(expected) || normalized.endsWith("." + expected))) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '.' || ch == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }
}

package br.com.ricarte.hookguard.events;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class PayloadMasker {

    private static final Pattern JSON_SECRET = Pattern.compile(
            "(\"(?:password|pass|secret|token|access_token|refresh_token|authorization|api_key|apiKey|client_secret|card|cvv|ssn|private_key)\"\\s*:\\s*\")([^\"]*)(\")",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[a-z0-9\\-._~+/=]+");

    private PayloadMasker() {
    }

    public static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String masked = JSON_SECRET.matcher(value).replaceAll("$1••••$3");
        return BEARER.matcher(masked).replaceAll("Bearer ••••");
    }

    public static String preview(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        int length = Math.min(body.length, 500);
        return mask(new String(body, 0, length, StandardCharsets.UTF_8));
    }
}

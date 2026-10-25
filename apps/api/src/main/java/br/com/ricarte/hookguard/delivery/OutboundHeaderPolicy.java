package br.com.ricarte.hookguard.delivery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class OutboundHeaderPolicy {

    private static final Set<String> FORWARD = Set.of(
            "stripe-signature",
            "paypal-auth-algo",
            "paypal-cert-url",
            "paypal-transmission-id",
            "paypal-transmission-sig",
            "paypal-transmission-time",
            "x-hub-signature",
            "x-hub-signature-256",
            "x-shopify-api-version",
            "x-shopify-hmac-sha256",
            "x-shopify-shop-domain",
            "x-shopify-topic",
            "x-shopify-webhook-id",
            "x-shopify-triggered-at",
            "x-wc-webhook-delivery-id",
            "x-wc-webhook-event",
            "x-wc-webhook-id",
            "x-wc-webhook-resource",
            "x-wc-webhook-signature",
            "x-wc-webhook-source",
            "x-wc-webhook-topic",
            "x-signature",
            "x-twilio-signature",
            "svix-id",
            "svix-signature",
            "svix-timestamp",
            "webhook-id",
            "webhook-signature",
            "webhook-timestamp",
            "x-slack-request-timestamp",
            "x-slack-signature",
            "x-square-hmacsha256-signature",
            "x-zm-request-timestamp",
            "x-zm-signature"
    );

    private OutboundHeaderPolicy() {
    }

    public static Map<String, String> forStorage(Map<String, String> incoming) {
        Map<String, String> kept = new LinkedHashMap<>();
        if (incoming == null) {
            return kept;
        }
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            if (!forwardableName(entry.getKey())) {
                continue;
            }
            String value = sanitize(entry.getValue());
            if (value == null) {
                continue;
            }
            kept.put(entry.getKey(), value);
        }
        return kept;
    }

    public static Map<String, String> fromStoredJson(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> parsed = mapper.readValue(json, new TypeReference<>() {
            });
            return forStorage(parsed);
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    public static String redactJson(String json, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(fromStoredJson(json, mapper));
        } catch (Exception ex) {
            return "{}";
        }
    }

    static boolean forwardableName(String name) {
        if (name == null || name.isBlank() || !token(name)) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("x-hookguard-")) {
            return false;
        }
        return FORWARD.contains(lower);
    }

    private static boolean token(String name) {
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return null;
        }
        return value;
    }
}

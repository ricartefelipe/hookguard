package br.com.ricarte.hookguard.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds display-only representations of payloads and destination responses.
 * Masking always happens before any truncation, and the masked text is never
 * used for delivery or signature verification: the original bytes stay untouched.
 */
public final class PayloadMasker {

    public static final String MASK = "••••";
    public static final String WITHHELD = "[prévia omitida: conteúdo sem estrutura reconhecível]";
    public static final String RESPONSE_WITHHELD = "[resposta omitida: conteúdo sem estrutura reconhecível]";
    public static final String TOO_LARGE = "[prévia omitida: conteúdo acima do limite de análise]";

    static final int PREVIEW_CHARS = 500;
    static final int INPUT_LIMIT_BYTES = 1_048_576;
    private static final int NESTED_JSON_DEPTH = 3;

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private static final Set<String> EXACT_KEYS = Set.of("pass", "card", "cvv", "cvc", "ssn", "pin", "auth", "otp");
    private static final String[] KEY_FRAGMENTS = {
            "password", "passwd", "secret", "token", "apikey", "privatekey", "authorization",
            "cardnumber", "creditcard", "cvv", "cvc", "accesskey", "sessionid"
    };
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer|basic)\\s+[a-z0-9\\-._~+/=]+");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)([a-z0-9_.\\-]*(?:password|passwd|secret|token|api[_\\-]?key|authorization|private[_\\-]?key)[a-z0-9_.\\-]*\\s*[=:]\\s*)"
                    + "(?:\"[^\"]*\"?|'[^']*'?|[^\\s&,;]+)"
    );

    private PayloadMasker() {
    }

    /** Preview of a stored event body, safe to show in the panel. */
    public static String preview(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return "";
        }
        if (body.length > INPUT_LIMIT_BYTES) {
            return TOO_LARGE;
        }
        String masked;
        try {
            masked = isForm(contentType) ? maskForm(body) : maskJson(body);
        } catch (IOException | RuntimeException ex) {
            return WITHHELD;
        }
        return truncate(masked, PREVIEW_CHARS);
    }

    public static String preview(byte[] body) {
        return preview(body, null);
    }

    /**
     * Masks a destination response before it is truncated and stored. Valid JSON is masked
     * structurally. Cut-off or malformed JSON-looking text goes through the tolerant scanner.
     * Anything else (HTML, plain text, XML) cannot be inspected safely and is not kept.
     */
    public static String maskResponse(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        try {
            return truncate(maskJson(text.getBytes(StandardCharsets.UTF_8)), maxChars);
        } catch (IOException | RuntimeException ex) {
            return looksLikeJson(text) ? truncate(maskLoose(text), maxChars) : RESPONSE_WITHHELD;
        }
    }

    /**
     * Last line of defence when a stored snippet is shown. New rows are already safe; rows written by
     * earlier versions may hold raw text, so only JSON-looking text (re-scanned) or an earlier placeholder passes.
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (text.equals(RESPONSE_WITHHELD)) {
            return text;
        }
        if (looksLikeJson(text)) {
            return maskLoose(text);
        }
        try {
            JsonNode node = JSON.readTree(text);
            if (node != null && !node.isContainerNode()) {
                return maskLoose(text);
            }
        } catch (IOException | RuntimeException ignored) {
            // fall through to the placeholder
        }
        return RESPONSE_WITHHELD;
    }

    private static boolean looksLikeJson(String text) {
        String trimmed = text.stripLeading();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    static boolean sensitive(String name) {
        if (name == null) {
            return false;
        }
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (EXACT_KEYS.contains(key)) {
            return true;
        }
        for (String fragment : KEY_FRAGMENTS) {
            if (key.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isForm(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
    }

    private static String maskJson(byte[] body) throws IOException {
        decodeStrictUtf8(body);
        JsonNode root = JSON.readTree(body);
        if (root == null || root.isMissingNode()) {
            throw new IOException("empty json");
        }
        return JSON.writeValueAsString(maskNode(root, 0));
    }

    private static void decodeStrictUtf8(byte[] body) throws CharacterCodingException {
        StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body));
    }

    private static JsonNode maskNode(JsonNode node, int nesting) {
        if (node.isObject()) {
            ObjectNode out = JSON.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                out.set(field.getKey(), sensitive(field.getKey())
                        ? TextNode.valueOf(MASK)
                        : maskNode(field.getValue(), nesting));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            for (JsonNode item : node) {
                out.add(maskNode(item, nesting));
            }
            return out;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(maskText(node.asText(), nesting));
        }
        return node;
    }

    private static String maskText(String text, int nesting) {
        String trimmed = text.trim();
        if (nesting < NESTED_JSON_DEPTH && (trimmed.startsWith("{") || trimmed.startsWith("["))) {
            try {
                return JSON.writeValueAsString(maskNode(JSON.readTree(trimmed), nesting + 1));
            } catch (JsonProcessingException ex) {
                return maskLoose(text);
            }
        }
        return BEARER.matcher(text).replaceAll("$1 " + MASK);
    }

    private static String maskForm(byte[] body) throws IOException {
        decodeStrictUtf8(body);
        String text = new String(body, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (String pair : text.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawKey = eq < 0 ? pair : pair.substring(0, eq);
            String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(key).append('=');
            out.append(sensitive(key) ? MASK : BEARER.matcher(value).replaceAll("$1 " + MASK));
        }
        return out.toString();
    }

    /**
     * Tolerant scanner for JSON-like text that may be invalid or cut off: the value
     * after a sensitive key is hidden up to its end, or to the end of the text.
     */
    static String maskLoose(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c != '"') {
                out.append(c);
                i++;
                continue;
            }
            int end = stringEnd(text, i);
            boolean closed = end >= 0;
            if (!closed) {
                end = n;
            }
            int colon = closed ? skipWhitespace(text, end) : n;
            if (closed && colon < n && text.charAt(colon) == ':' && sensitive(unescape(text.substring(i + 1, end - 1)))) {
                int valueStart = skipWhitespace(text, colon + 1);
                out.append(text, i, valueStart).append('"').append(MASK).append('"');
                i = skipValue(text, valueStart);
                continue;
            }
            out.append(text, i, end);
            i = end;
        }
        String bearerMasked = BEARER.matcher(out).replaceAll("$1 " + MASK);
        return KEY_VALUE.matcher(bearerMasked)
                .replaceAll(match -> Matcher.quoteReplacement(match.group(1) + MASK));
    }

    private static int stringEnd(String text, int start) {
        int n = text.length();
        int i = start + 1;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            i++;
        }
        return -1;
    }

    private static int skipValue(String text, int start) {
        int n = text.length();
        if (start >= n) {
            return n;
        }
        char c = text.charAt(start);
        if (c == '"') {
            int end = stringEnd(text, start);
            return end < 0 ? n : end;
        }
        if (c == '{' || c == '[') {
            int depth = 0;
            int i = start;
            while (i < n) {
                char ch = text.charAt(i);
                if (ch == '"') {
                    int end = stringEnd(text, i);
                    if (end < 0) {
                        return n;
                    }
                    i = end;
                    continue;
                }
                if (ch == '{' || ch == '[') {
                    depth++;
                } else if (ch == '}' || ch == ']') {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
                i++;
            }
            return n;
        }
        int i = start;
        while (i < n && ",}]".indexOf(text.charAt(i)) < 0) {
            i++;
        }
        return i;
    }

    private static int skipWhitespace(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static String unescape(String raw) {
        try {
            return JSON.readValue("\"" + raw + "\"", String.class);
        } catch (IOException | RuntimeException ex) {
            return raw;
        }
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || maxChars <= 0) {
            return value;
        }
        if (value.codePointCount(0, value.length()) <= maxChars) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxChars);
        return value.substring(0, end);
    }
}

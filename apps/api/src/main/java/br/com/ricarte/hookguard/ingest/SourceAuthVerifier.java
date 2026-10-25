package br.com.ricarte.hookguard.ingest;

import br.com.ricarte.hookguard.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;

/**
 * Verifies that an inbound request was signed by the declared sender, using the original body bytes.
 * Only the modes listed in {@link Mode} are verified; anything else is treated as unauthenticated.
 */
public final class SourceAuthVerifier {

    public enum Mode {
        /** Capability URL only: possession of the project key is the sole credential. */
        NONE("none"),
        /** Stripe webhook endpoint signature: header Stripe-Signature, t + "." + body, HMAC-SHA256. */
        STRIPE("stripe"),
        /** GitHub webhook secret: header X-Hub-Signature-256 = sha256=HMAC-SHA256(body). */
        GITHUB("github"),
        /** Sender-defined HMAC: header X-Signature-256 = sha256=HMAC-SHA256(body). No timestamp. */
        GENERIC_HMAC("generic_hmac");

        private final String storage;

        Mode(String storage) {
            this.storage = storage;
        }

        public String toStorage() {
            return storage;
        }

        public static Mode fromStorage(String value) {
            if (value != null) {
                for (Mode mode : values()) {
                    if (mode.storage.equals(value.trim().toLowerCase(Locale.ROOT))) {
                        return mode;
                    }
                }
            }
            throw new IllegalArgumentException("unsupported source auth mode");
        }
    }

    /** Key under which a verified timestamped signature is remembered to refuse exact replays, or null. */
    public record Result(String replayKey) {
    }

    static final String REPLAY_PREFIX = "hg-sig:";

    private SourceAuthVerifier() {
    }

    public static Result verify(
            Mode mode,
            String secret,
            int toleranceSeconds,
            Map<String, String> headers,
            byte[] body,
            Instant now
    ) {
        if (mode == Mode.NONE) {
            return new Result(null);
        }
        if (secret == null || secret.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "source_auth_misconfigured");
        }
        byte[] payload = body == null ? new byte[0] : body;
        return switch (mode) {
            case STRIPE -> verifyStripe(secret, toleranceSeconds, headers, payload, now);
            case GITHUB -> verifyPrefixed(secret, header(headers, "X-Hub-Signature-256"), payload);
            case GENERIC_HMAC -> verifyPrefixed(secret, header(headers, "X-Signature-256"), payload);
            case NONE -> new Result(null);
        };
    }

    private static Result verifyPrefixed(String secret, String value, byte[] payload) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "signature_missing");
        }
        String trimmed = value.trim();
        if (!trimmed.regionMatches(true, 0, "sha256=", 0, 7)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "signature_invalid");
        }
        byte[] provided = decodeHex(trimmed.substring(7));
        if (provided == null || !MessageDigest.isEqual(hmac(secret, payload), provided)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "signature_invalid");
        }
        return new Result(null);
    }

    private static Result verifyStripe(
            String secret,
            int toleranceSeconds,
            Map<String, String> headers,
            byte[] payload,
            Instant now
    ) {
        String value = header(headers, "Stripe-Signature");
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "signature_missing");
        }
        String timestamp = null;
        List<byte[]> candidates = new ArrayList<>();
        for (String part : value.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String item = part.substring(eq + 1).trim();
            if (key.equals("t")) {
                timestamp = item;
            } else if (key.equals("v1")) {
                byte[] decoded = decodeHex(item);
                if (decoded != null) {
                    candidates.add(decoded);
                }
            }
        }
        long signedAt;
        try {
            signedAt = Long.parseLong(timestamp == null ? "" : timestamp);
        } catch (NumberFormatException ex) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "signature_invalid");
        }
        byte[] prefix = (signedAt + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + payload.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(payload, 0, signed, prefix.length, payload.length);
        byte[] expected = hmac(secret, signed);
        boolean match = false;
        for (byte[] candidate : candidates) {
            match |= MessageDigest.isEqual(expected, candidate);
        }
        if (!match) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "signature_invalid");
        }
        long tolerance = Math.max(toleranceSeconds, 1);
        if (Math.abs(now.getEpochSecond() - signedAt) > tolerance) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "signature_expired");
        }
        return new Result(REPLAY_PREFIX + IngestHashes.sha256Hex(value.trim()));
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static byte[] hmac(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    private static byte[] decodeHex(String value) {
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

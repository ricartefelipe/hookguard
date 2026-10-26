package br.com.ricarte.hookguard.delivery;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HookSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private HookSignature() {
    }

    public static String signatureHeader(
            byte[] body,
            String secret,
            String previousSecret,
            Instant rotatedAt,
            Duration window,
            Instant now,
            UUID eventId
    ) {
        long timestamp = now.getEpochSecond();
        StringBuilder header = new StringBuilder();
        header.append("t=").append(timestamp);
        header.append(",v1=").append(mac(secret, timestamp, eventId, body));
        if (previousSecret != null
                && !previousSecret.isBlank()
                && rotatedAt != null
                && window != null
                && !now.isAfter(rotatedAt.plus(window))) {
            header.append(",v1=").append(mac(previousSecret, timestamp, eventId, body));
        }
        return header.toString();
    }

    public static String mac(String secret, long timestamp, UUID eventId, byte[] body) {
        byte[] payload = body == null ? new byte[0] : body;
        byte[] prefix = (timestamp + "." + eventId + ".").getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[prefix.length + payload.length];
        System.arraycopy(prefix, 0, message, 0, prefix.length);
        System.arraycopy(payload, 0, message, prefix.length, payload.length);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(message));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("Unable to sign payload", ex);
        }
    }
}

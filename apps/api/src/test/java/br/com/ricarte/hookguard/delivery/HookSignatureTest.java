package br.com.ricarte.hookguard.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HookSignatureTest {

    @Test
    void signatureCoversTimestampEventAndBodyAndRotatesWithinTheWindow() {
        byte[] body = "{\"ok\":true}".getBytes();
        UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Instant now = Instant.parse("2026-10-24T12:00:00Z");
        String current = HookSignature.signatureHeader(body, "current-secret", null, null, Duration.ofHours(24), now, eventId);
        String changed = HookSignature.signatureHeader(
                "{\"ok\":false}".getBytes(),
                "current-secret",
                null,
                null,
                Duration.ofHours(24),
                now,
                eventId
        );
        assertThat(current).startsWith("t=" + now.getEpochSecond() + ",v1=");
        assertThat(current).isNotEqualTo(changed);
        assertThat(current).doesNotContain("current-secret");

        String rotated = HookSignature.signatureHeader(
                body,
                "next-secret",
                "current-secret",
                now.minus(Duration.ofHours(1)),
                Duration.ofHours(24),
                now,
                eventId
        );
        assertThat(rotated.split(",v1=", -1)).hasSize(3);

        String expired = HookSignature.signatureHeader(
                body,
                "next-secret",
                "current-secret",
                now.minus(Duration.ofHours(48)),
                Duration.ofHours(24),
                now,
                eventId
        );
        assertThat(expired.split(",v1=", -1)).hasSize(2);
    }

    @Test
    void macChangesWhenASingleBodyByteChanges() {
        UUID eventId = UUID.randomUUID();
        String first = HookSignature.mac("secret", 10L, eventId, new byte[] {1, 2, 3});
        String second = HookSignature.mac("secret", 10L, eventId, new byte[] {1, 2, 4});
        assertThat(first).isNotEqualTo(second);
    }
}

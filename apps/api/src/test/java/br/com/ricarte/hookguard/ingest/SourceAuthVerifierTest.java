package br.com.ricarte.hookguard.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.ricarte.hookguard.ingest.SourceAuthVerifier.Mode;
import br.com.ricarte.hookguard.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SourceAuthVerifierTest {

    private static final String SECRET = "whsec_test_secret_value";
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    private static String hmac(String secret, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data));
    }

    private static String stripeHeader(String secret, long t, byte[] body) throws Exception {
        byte[] prefix = (t + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        return "t=" + t + ",v1=" + hmac(secret, signed);
    }

    private static void rejects(Mode mode, String secret, Map<String, String> headers, byte[] body, String code) {
        assertThatThrownBy(() -> SourceAuthVerifier.verify(mode, secret, 300, headers, body, NOW))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(code);
    }

    @Test
    void stripeAcceptsValidSignatureOverTheOriginalBytes() throws Exception {
        byte[] body = new byte[] {'{', '"', 'a', '"', ':', '1', ' ', ' ', '\n', '}', (byte) 0xff, 0x00};
        String header = stripeHeader(SECRET, NOW.getEpochSecond() - 10, body);
        SourceAuthVerifier.Result result = SourceAuthVerifier.verify(Mode.STRIPE, SECRET, 300, Map.of("stripe-signature", header), body, NOW);
        assertThat(result.replayKey()).startsWith(SourceAuthVerifier.REPLAY_PREFIX);
    }

    @Test
    void stripeAcceptsWhenAnyV1Matches() throws Exception {
        byte[] body = "{}".getBytes();
        long t = NOW.getEpochSecond();
        String valid = stripeHeader(SECRET, t, body);
        String header = "t=" + t + ",v1=" + "00".repeat(32) + "," + valid.substring(valid.indexOf("v1="));
        assertThat(SourceAuthVerifier.verify(Mode.STRIPE, SECRET, 300, Map.of("Stripe-Signature", header), body, NOW)).isNotNull();
    }

    @Test
    void stripeRejectsMissingTamperedWrongSecretAndMalformed() throws Exception {
        byte[] body = "{\"amount\":10}".getBytes();
        String header = stripeHeader(SECRET, NOW.getEpochSecond(), body);
        rejects(Mode.STRIPE, SECRET, Map.of(), body, "signature_missing");
        rejects(Mode.STRIPE, SECRET, Map.of("Stripe-Signature", header), "{\"amount\":11}".getBytes(), "signature_invalid");
        rejects(Mode.STRIPE, "whsec_another_project_secret", Map.of("Stripe-Signature", header), body, "signature_invalid");
        rejects(Mode.STRIPE, SECRET, Map.of("Stripe-Signature", "garbage"), body, "signature_invalid");
        rejects(Mode.STRIPE, SECRET, Map.of("Stripe-Signature", "t=abc,v1=00"), body, "signature_invalid");
        rejects(Mode.STRIPE, SECRET, Map.of("Stripe-Signature", "v1=" + "ab".repeat(32)), body, "signature_invalid");
        rejects(Mode.STRIPE, SECRET, Map.of("Stripe-Signature", header.replace("v1=", "v0=")), body, "signature_invalid");
    }

    @Test
    void stripeEnforcesTheTimestampToleranceInBothDirections() throws Exception {
        byte[] body = "{}".getBytes();
        long old = NOW.getEpochSecond() - 301;
        long future = NOW.getEpochSecond() + 301;
        rejects(Mode.STRIPE, SECRET, Map.of("Stripe-Signature", stripeHeader(SECRET, old, body)), body, "signature_expired");
        rejects(Mode.STRIPE, SECRET, Map.of("Stripe-Signature", stripeHeader(SECRET, future, body)), body, "signature_expired");
        long edge = NOW.getEpochSecond() - 300;
        assertThat(SourceAuthVerifier.verify(Mode.STRIPE, SECRET, 300, Map.of("Stripe-Signature", stripeHeader(SECRET, edge, body)), body, NOW)).isNotNull();
    }

    @Test
    void stripeSignatureCannotBeReusedWithAnotherTimestamp() throws Exception {
        byte[] body = "{}".getBytes();
        String header = stripeHeader(SECRET, NOW.getEpochSecond() - 100, body);
        String forged = header.replaceFirst("t=\\d+", "t=" + NOW.getEpochSecond());
        rejects(Mode.STRIPE, SECRET, Map.of("Stripe-Signature", forged), body, "signature_invalid");
    }

    @Test
    void githubStyleSignatureCoversTheRawBody() throws Exception {
        byte[] body = "{\"zen\":\"keep it\"}".getBytes();
        String header = "sha256=" + hmac(SECRET, body);
        assertThat(SourceAuthVerifier.verify(Mode.GITHUB, SECRET, 300, Map.of("x-hub-signature-256", header), body, NOW).replayKey()).isNull();
        assertThat(SourceAuthVerifier.verify(Mode.GITHUB, SECRET, 300, Map.of("X-Hub-Signature-256", header.toUpperCase().replace("SHA256=", "sha256=")), body, NOW)).isNotNull();
        rejects(Mode.GITHUB, SECRET, Map.of(), body, "signature_missing");
        rejects(Mode.GITHUB, SECRET, Map.of("X-Hub-Signature-256", header), "{\"zen\":\"kept it\"}".getBytes(), "signature_invalid");
        rejects(Mode.GITHUB, SECRET, Map.of("X-Hub-Signature-256", "sha1=" + hmac(SECRET, body)), body, "signature_invalid");
        rejects(Mode.GITHUB, SECRET, Map.of("X-Hub-Signature-256", "sha256=zz"), body, "signature_invalid");
        rejects(Mode.GITHUB, "secret-of-another-project", Map.of("X-Hub-Signature-256", header), body, "signature_invalid");
    }

    @Test
    void genericHmacUsesItsOwnHeader() throws Exception {
        byte[] body = "payload".getBytes();
        String header = "sha256=" + hmac(SECRET, body);
        assertThat(SourceAuthVerifier.verify(Mode.GENERIC_HMAC, SECRET, 300, Map.of("X-Signature-256", header), body, NOW)).isNotNull();
        rejects(Mode.GENERIC_HMAC, SECRET, Map.of("X-Hub-Signature-256", header), body, "signature_missing");
    }

    @Test
    void authenticatedModeWithoutASecretNeverAcceptsSilently() {
        for (Mode mode : new Mode[] {Mode.STRIPE, Mode.GITHUB, Mode.GENERIC_HMAC}) {
            rejects(mode, null, Map.of(), new byte[0], "source_auth_misconfigured");
            rejects(mode, "  ", Map.of("X-Hub-Signature-256", "sha256=00"), new byte[0], "source_auth_misconfigured");
        }
    }

    @Test
    void noneModeIsAnExplicitlyUnauthenticatedCapabilityUrl() {
        assertThat(SourceAuthVerifier.verify(Mode.NONE, null, 300, Map.of(), new byte[0], NOW).replayKey()).isNull();
        assertThatThrownBy(() -> Mode.fromStorage("paypal")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Mode.fromStorage("Stripe")).isEqualTo(Mode.STRIPE);
    }
}

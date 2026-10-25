package br.com.ricarte.hookguard.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.ricarte.hookguard.support.ScriptedServer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DeliveryClientProtocolTest {

    private static final NameResolver LOOPBACK = host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")};

    private static DeliveryClient client(int maxResponse) {
        return new DeliveryClient(LOOPBACK, true, 1000, maxResponse);
    }

    private static DeliveryClient.DeliveryResult post(DeliveryClient client, ScriptedServer server, int timeoutMs) {
        return client.post("http://127.0.0.1:" + server.port() + "/hook", "payload".getBytes(), "text/plain", Map.of(), timeoutMs);
    }

    private static ScriptedServer respondWith(String raw) throws Exception {
        return ScriptedServer.plain((socket, request) -> ScriptedServer.write(socket, raw));
    }

    @ParameterizedTest
    @CsvSource({"200,true", "201,true", "204,true", "301,false", "302,false", "304,false", "400,false", "404,false",
            "408,false", "429,false", "500,false", "503,false"})
    void reportsTheFinalStatusFaithfully(int status, boolean success) throws Exception {
        String body = status == 204 || status == 304 ? "" : "body";
        try (ScriptedServer server = respondWith("HTTP/1.1 " + status + " X\r\nContent-Length: " + body.length()
                + "\r\nConnection: close\r\n\r\n" + body)) {
            DeliveryClient.DeliveryResult result = post(client(1024), server, 2000);
            assertThat(result.httpStatus()).isEqualTo(status);
            assertThat(result.success()).isEqualTo(success);
            assertThat(result.responseBodySnippet()).isEqualTo(body);
        }
    }

    @Test
    void skipsInformationalResponsesAndReturnsTheFinalOne() throws Exception {
        try (ScriptedServer server = respondWith("HTTP/1.1 103 Early Hints\r\nLink: </a.css>; rel=preload\r\n\r\n"
                + "HTTP/1.1 100 Continue\r\n\r\n"
                + "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")) {
            DeliveryClient.DeliveryResult result = post(client(1024), server, 2000);
            assertThat(result.httpStatus()).isEqualTo(200);
            assertThat(result.success()).isTrue();
            assertThat(result.responseBodySnippet()).isEqualTo("ok");
        }
    }

    @Test
    void informationalFloodAndProtocolSwitchAreFailures() throws Exception {
        try (ScriptedServer flood = respondWith("HTTP/1.1 103 H\r\n\r\n".repeat(20) + "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")) {
            DeliveryClient.DeliveryResult result = post(client(1024), flood, 2000);
            assertThat(result.httpStatus()).isNull();
            assertThat(result.errorMessage()).contains("informational");
        }
        try (ScriptedServer upgrade = respondWith("HTTP/1.1 101 Switching\r\nUpgrade: x\r\n\r\n")) {
            assertThat(post(client(1024), upgrade, 2000).httpStatus()).isNull();
        }
    }

    @Test
    void trickledResponseEndsAtTheTotalDeadline() throws Exception {
        try (ScriptedServer server = ScriptedServer.plain((socket, request) -> {
            ScriptedServer.write(socket, "HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\n");
            for (int i = 0; i < 8; i++) {
                Thread.sleep(150);
                socket.getOutputStream().write('x');
                socket.getOutputStream().flush();
            }
        })) {
            long started = System.nanoTime();
            DeliveryClient.DeliveryResult result = post(client(1024), server, 300);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            assertThat(result.success()).isFalse();
            assertThat(result.httpStatus()).isNull();
            assertThat(result.errorMessage()).startsWith("timeout");
            assertThat(elapsedMs).isLessThan(700);
        }
    }

    @Test
    void trickledHeadersAreAlsoBounded() throws Exception {
        try (ScriptedServer server = ScriptedServer.plain((socket, request) -> {
            for (char c : "HTTP/1.1 200 OK\r\nX-Slow: yes\r\n\r\n".toCharArray()) {
                socket.getOutputStream().write(c);
                socket.getOutputStream().flush();
                Thread.sleep(60);
            }
        })) {
            long started = System.nanoTime();
            DeliveryClient.DeliveryResult result = post(client(1024), server, 400);
            assertThat(result.success()).isFalse();
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(900);
        }
    }

    @Test
    void blockedWriteDoesNotHoldTheWorker() throws Exception {
        try (ScriptedServer server = ScriptedServer.silent()) {
            byte[] huge = new byte[64 * 1024 * 1024];
            long started = System.nanoTime();
            DeliveryClient.DeliveryResult result = client(1024).post(
                    "http://127.0.0.1:" + server.port() + "/hook", huge, "application/octet-stream", Map.of(), 500);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).startsWith("timeout");
            assertThat(elapsedMs).isLessThan(1500);
        }
    }

    @Test
    void silentServerEndsAtTheDeadline() throws Exception {
        try (ScriptedServer server = ScriptedServer.plain((socket, request) -> Thread.sleep(5000))) {
            long started = System.nanoTime();
            DeliveryClient.DeliveryResult result = post(client(1024), server, 400);
            assertThat(result.errorMessage()).startsWith("timeout");
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1000);
        }
    }

    @Test
    void slowDnsCountsAgainstTheTotalDeadline() {
        NameResolver stuck = host -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
            }
            throw new UnknownHostException(host);
        };
        DeliveryClient client = new DeliveryClient(stuck, true, 1000, 1024);
        long started = System.nanoTime();
        DeliveryClient.DeliveryResult result = client.post("http://slow.example/hook", new byte[0], null, Map.of(), 300);
        assertThat(result.errorMessage()).startsWith("timeout");
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(900);
    }

    @Test
    void unresolvedHostIsRetryableNotTerminal() {
        NameResolver none = host -> {
            throw new UnknownHostException(host);
        };
        DeliveryClient.DeliveryResult result = new DeliveryClient(none, true, 1000, 1024)
                .post("http://gone.example/hook", new byte[0], null, Map.of(), 500);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.errorMessage()).isEqualTo("dns_unresolved");
    }

    @Test
    void resolvesOnceAndConnectsToTheValidatedAddress() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        NameResolver flipping = host -> lookups.incrementAndGet() == 1
                ? new InetAddress[] {InetAddress.getByName("127.0.0.1")}
                : new InetAddress[] {InetAddress.getByName("169.254.169.254")};
        try (ScriptedServer server = respondWith("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")) {
            DeliveryClient client = new DeliveryClient(flipping, true, 1000, 1024);
            DeliveryClient.DeliveryResult result = client.post("http://rebind.example:" + server.port() + "/h", new byte[0], null, Map.of(), 2000);
            assertThat(result.httpStatus()).isEqualTo(200);
            assertThat(lookups.get()).isEqualTo(1);
            assertThat(server.requests().get(0).header("Host")).isEqualTo("rebind.example:" + server.port());
        }
    }

    @Test
    void rebindToInternalAddressIsRejectedWithoutConnecting() throws Exception {
        NameResolver internal = host -> new InetAddress[] {InetAddress.getByName("169.254.169.254")};
        try (ScriptedServer server = respondWith("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")) {
            assertThatThrownBy(() -> new DeliveryClient(internal, true, 1000, 1024)
                    .post("http://rebind.example:" + server.port() + "/h", new byte[0], null, Map.of(), 1000))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(server.requests()).isEmpty();
        }
    }

    @Test
    void malformedResponsesNeverCountAsSuccess() throws Exception {
        String[] bad = {
                "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nshort",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nZZ\r\nabc\r\n0\r\n\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nabc",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabcXX0\r\n\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\nabc",
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\nab",
                "HTTP/1.1 200 OK\r\nContent-Length: abc\r\n\r\nab",
                "HTTP/1.1 200 OK\r\nbroken header line\r\nContent-Length: 0\r\n\r\n",
                "HTTP/1.1 20 OK\r\nContent-Length: 0\r\n\r\n",
                "SMTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n"
        };
        for (String raw : bad) {
            try (ScriptedServer server = respondWith(raw)) {
                DeliveryClient.DeliveryResult result = post(client(1024), server, 1000);
                assertThat(result.success()).as(raw).isFalse();
                assertThat(result.httpStatus()).as(raw).isNull();
            }
        }
    }

    @Test
    void wellFormedChunkedBodyIsReadAndHeadersMayRepeat() throws Exception {
        try (ScriptedServer server = respondWith("HTTP/1.1 200 OK\r\nX-A: 1\r\nX-A: 2\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "3\r\nabc\r\n2;ext=1\r\nde\r\n0\r\nTrailer: v\r\n\r\n")) {
            DeliveryClient.DeliveryResult result = post(client(1024), server, 2000);
            assertThat(result.httpStatus()).isEqualTo(200);
            assertThat(result.responseBodySnippet()).isEqualTo("abcde");
        }
    }

    @Test
    void aggregateHeaderSizeIsLimited() throws Exception {
        StringBuilder raw = new StringBuilder("HTTP/1.1 200 OK\r\n");
        for (int i = 0; i < 30; i++) {
            raw.append("X-Pad: ").append("p".repeat(2000)).append("\r\n");
        }
        raw.append("Content-Length: 0\r\n\r\n");
        try (ScriptedServer server = respondWith(raw.toString())) {
            assertThat(post(client(1024), server, 2000).httpStatus()).isNull();
        }
        StringBuilder many = new StringBuilder("HTTP/1.1 200 OK\r\n");
        for (int i = 0; i < 150; i++) {
            many.append("X-").append(i).append(": 1\r\n");
        }
        many.append("Content-Length: 0\r\n\r\n");
        try (ScriptedServer server = respondWith(many.toString())) {
            assertThat(post(client(1024), server, 2000).httpStatus()).isNull();
        }
    }

    @Test
    void giantChunkedResponseIsCappedWithoutReadingItAll() throws Exception {
        try (ScriptedServer server = ScriptedServer.plain((socket, request) -> {
            ScriptedServer.write(socket, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n");
            for (int i = 0; i < 100_000; i++) {
                ScriptedServer.write(socket, "400\r\n" + "z".repeat(1024) + "\r\n");
            }
        })) {
            DeliveryClient.DeliveryResult result = post(client(64), server, 2000);
            assertThat(result.httpStatus()).isEqualTo(200);
            assertThat(result.responseBodySnippet()).hasSize(64);
        }
    }

    @Test
    void retryAfterIsParsedAndCapped() throws Exception {
        try (ScriptedServer server = respondWith("HTTP/1.1 429 Too Many\r\nRetry-After: 99999999999\r\nContent-Length: 0\r\n\r\n")) {
            assertThat(post(client(64), server, 2000).retryAfter()).isEqualTo(Duration.ofDays(7));
        }
        try (ScriptedServer server = respondWith("HTTP/1.1 503 X\r\nRetry-After: -5\r\nContent-Length: 0\r\n\r\n")) {
            assertThat(post(client(64), server, 2000).retryAfter()).isEqualTo(Duration.ZERO);
        }
        try (ScriptedServer server = respondWith("HTTP/1.1 503 X\r\nRetry-After: soon\r\nContent-Length: 0\r\n\r\n")) {
            assertThat(post(client(64), server, 2000).retryAfter()).isNull();
        }
    }

    @Test
    void payloadBytesAreSentUnchanged() throws Exception {
        byte[] payload = new byte[1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        try (ScriptedServer server = respondWith("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")) {
            client(64).post("http://127.0.0.1:" + server.port() + "/hook?x=1", payload, "application/octet-stream", Map.of("X-Custom", "v"), 2000);
            ScriptedServer.Request request = server.requests().get(0);
            assertThat(request.body()).isEqualTo(payload);
            assertThat(request.head()).startsWith("POST /hook?x=1 HTTP/1.1\r\n");
            assertThat(request.header("X-Custom")).isEqualTo("v");
            assertThat(request.header("Content-Length")).isEqualTo("1024");
        }
    }

    @Test
    void ipv6LoopbackKeepsBracketsInTheHostHeader() throws Exception {
        try (ScriptedServer server = ScriptedServer.plainIpv6((socket, request) ->
                ScriptedServer.write(socket, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"))) {
            DeliveryClient client = new DeliveryClient(new SystemNameResolver(), true, 1000, 64);
            DeliveryClient.DeliveryResult result = client.post("http://[::1]:" + server.port() + "/h", new byte[0], null, Map.of(), 2000);
            assertThat(result.httpStatus()).isEqualTo(200);
            assertThat(server.requests().get(0).header("Host")).isEqualTo("[::1]:" + server.port());
        }
    }

    @Test
    void validTlsUsesSniAndHostnameVerification() throws Exception {
        try (ScriptedServer server = ScriptedServer.tls((socket, request) ->
                ScriptedServer.write(socket, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"))) {
            DeliveryClient trusting = new DeliveryClient(LOOPBACK, true, 1000, 64,
                    ScriptedServer.clientFactoryTrustingTestCertificate());
            DeliveryClient.DeliveryResult ok = trusting.post("https://hooks.test.example:" + server.port() + "/h", "x".getBytes(), null, Map.of(), 3000);
            assertThat(ok.httpStatus()).isEqualTo(200);
            assertThat(server.requests().get(0).sni()).isEqualTo("hooks.test.example");
            assertThat(server.requests().get(0).header("Host")).isEqualTo("hooks.test.example:" + server.port());

            DeliveryClient.DeliveryResult wrongName = trusting.post("https://other.test.example:" + server.port() + "/h", "x".getBytes(), null, Map.of(), 3000);
            assertThat(wrongName.httpStatus()).isNull();
            assertThat(wrongName.success()).isFalse();
        }
    }

    @Test
    void untrustedCertificateIsRejected() throws Exception {
        try (ScriptedServer server = ScriptedServer.tls((socket, request) ->
                ScriptedServer.write(socket, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"))) {
            DeliveryClient defaults = new DeliveryClient(LOOPBACK, true, 1000, 64);
            DeliveryClient.DeliveryResult result = defaults.post("https://hooks.test.example:" + server.port() + "/h", "x".getBytes(), null, Map.of(), 3000);
            assertThat(result.httpStatus()).isNull();
            assertThat(result.success()).isFalse();
            assertThat(server.requests()).isEmpty();
        }
    }

    @Test
    void socketIsClosedWhenTheDeadlineEnds() throws Exception {
        java.util.concurrent.atomic.AtomicLong closedAt = new java.util.concurrent.atomic.AtomicLong();
        try (ScriptedServer server = ScriptedServer.plain((socket, request) -> {
            ScriptedServer.write(socket, "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nab");
            socket.setSoTimeout(3000);
            try {
                while (socket.getInputStream().read() >= 0) {
                    // wait for the peer to go away
                }
            } catch (java.io.IOException ignored) {
            }
            closedAt.set(System.nanoTime());
        })) {
            DeliveryClient.DeliveryResult result = post(client(1024), server, 300);
            long returned = System.nanoTime();
            assertThat(result.errorMessage()).startsWith("timeout");
            for (int i = 0; i < 30 && closedAt.get() == 0; i++) {
                Thread.sleep(20);
            }
            assertThat(closedAt.get()).isNotZero();
            assertThat((closedAt.get() - returned) / 1_000_000).isLessThan(500);
        }
    }
}

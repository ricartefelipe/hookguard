package br.com.ricarte.hookguard.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class DeliveryClientSecurityTest {

    @Test
    void doesNotConnectWhenDnsReturnsLoopback() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            socket.setSoTimeout(300);
            NameResolver resolver = host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")};
            DeliveryClient client = new DeliveryClient(resolver, false, 500, 1024);
            assertThatThrownBy(() -> client.post(
                    "https://public.example:" + socket.getLocalPort() + "/hook",
                    "x".getBytes(),
                    "text/plain",
                    Map.of(),
                    500
            )).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(socket::accept).isInstanceOf(SocketTimeoutException.class);
        }
    }

    @Test
    void doesNotFollowRedirectToAnotherPort() throws Exception {
        try (MockWebServer destination = new MockWebServer(); MockWebServer internal = new MockWebServer()) {
            destination.start();
            internal.start();
            destination.enqueue(new MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", internal.url("/secret").toString()));
            DeliveryClient client = new DeliveryClient(new SystemNameResolver(), true, 1000, 4096);
            DeliveryClient.DeliveryResult result = client.post(
                    destination.url("/hook").toString(),
                    "payload".getBytes(),
                    "text/plain",
                    Map.of("Stripe-Signature", "t=1,v1=abc"),
                    2000
            );
            assertThat(result.httpStatus()).isEqualTo(302);
            assertThat(result.success()).isFalse();
            assertThat(internal.getRequestCount()).isZero();
            assertThat(destination.getRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void stopsReadingAtTheResponseCap() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread server = new Thread(() -> {
                try (var accepted = socket.accept()) {
                    accepted.getOutputStream().write((
                            "HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n"
                                    + "x".repeat(128)
                    ).getBytes());
                    accepted.getOutputStream().flush();
                    Thread.sleep(1500);
                } catch (Exception ignored) {
                }
            });
            server.setDaemon(true);
            server.start();
            DeliveryClient client = new DeliveryClient(new SystemNameResolver(), true, 500, 32);
            long started = System.nanoTime();
            DeliveryClient.DeliveryResult result = client.post(
                    "http://127.0.0.1:" + socket.getLocalPort() + "/hook",
                    "hi".getBytes(),
                    "text/plain",
                    Map.of(),
                    1000
            );
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            assertThat(result.httpStatus()).isEqualTo(200);
            assertThat(result.responseBodySnippet()).hasSizeLessThanOrEqualTo(32);
            assertThat(elapsedMs).isLessThan(1000);
        }
    }

    @Test
    void readsRetryAfter() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("later"));
            DeliveryClient client = new DeliveryClient(new SystemNameResolver(), true, 1000, 1024);
            DeliveryClient.DeliveryResult result = client.post(
                    server.url("/hook").toString(),
                    "x".getBytes(),
                    "text/plain",
                    Map.of(),
                    2000
            );
            assertThat(result.httpStatus()).isEqualTo(429);
            assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(120));
            assertThat(result.success()).isFalse();
        }
    }
}

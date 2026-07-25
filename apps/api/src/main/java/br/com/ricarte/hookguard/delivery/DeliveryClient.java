package br.com.ricarte.hookguard.delivery;

import br.com.ricarte.hookguard.config.HookguardProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DeliveryClient {

    private final HttpClient httpClient;
    private final HookguardProperties properties;

    public DeliveryClient(HookguardProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.delivery().connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public DeliveryResult post(
            String destinationUrl,
            byte[] body,
            String contentType,
            Map<String, String> headers,
            int timeoutMs
    ) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(destinationUrl))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (contentType != null && !contentType.isBlank()) {
                builder.header("Content-Type", contentType);
            }
            headers.forEach(builder::header);
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            String snippet = response.body() == null
                    ? ""
                    : new String(response.body(), 0, Math.min(response.body().length, 2000));
            return new DeliveryResult(response.statusCode(), null, snippet);
        } catch (Exception ex) {
            return new DeliveryResult(null, ex.getMessage(), null);
        }
    }

    public record DeliveryResult(Integer httpStatus, String errorMessage, String responseBodySnippet) {
        public boolean success() {
            return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
        }
    }
}

package br.com.ricarte.hookguard.auth;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class TotalRecallClient {

    private static final Logger log = LoggerFactory.getLogger(TotalRecallClient.class);

    private final RestClient restClient;
    private final boolean enabled;
    private final String systemSlug;

    public TotalRecallClient(
            @Value("${hookguard.totalrecall.base-url:http://54.94.163.136:9087}") String baseUrl,
            @Value("${hookguard.totalrecall.enabled:true}") boolean enabled,
            @Value("${hookguard.totalrecall.system-slug:hookguard}") String systemSlug
    ) {
        this.enabled = enabled;
        this.systemSlug = systemSlug;
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(8));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl.replaceAll("/$", ""))
                .requestFactory(requestFactory)
                .build();
    }

    public Map<String, Object> login(String email, String password) {
        if (!enabled) return Map.of("valid", false);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.post()
                    .uri("/api/v1/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "password", password, "system", systemSlug))
                    .retrieve()
                    .body(Map.class);
            return body == null ? Map.of("valid", false) : body;
        } catch (Exception ex) {
            log.warn("TotalRecall login failed: {}", ex.getMessage());
            return Map.of("valid", false);
        }
    }

    public Map<String, Object> validateToken(String token) {
        if (!enabled || token == null || token.isBlank()) return Map.of("valid", false);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.post()
                    .uri("/api/v1/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + token)
                    .body(Map.of("system", systemSlug))
                    .retrieve()
                    .body(Map.class);
            return body == null ? Map.of("valid", false) : body;
        } catch (Exception ex) {
            log.warn("TotalRecall validate failed: {}", ex.getMessage());
            return Map.of("valid", false);
        }
    }
}

package br.com.ricarte.hookguard.ingest;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/ingest")
public class IngestController {

    private static final Set<String> STRIPPED = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailers",
            "transfer-encoding",
            "upgrade",
            "host",
            "content-length"
    );

    private final IngestService ingestService;

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/{projectKey}")
    public ResponseEntity<Map<String, String>> ingest(
            @PathVariable String projectKey,
            HttpServletRequest request
    ) throws Exception {
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames()).forEach(name -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!STRIPPED.contains(lower)) {
                headers.put(name, request.getHeader(name));
            }
        });
        UUID eventId = ingestService.accept(projectKey, headers, body, request.getContentType());
        return ResponseEntity.accepted().body(Map.of("eventId", eventId.toString()));
    }
}

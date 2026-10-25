package br.com.ricarte.hookguard.ingest;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/ingest")
public class IngestController {

    private final IngestService ingestService;
    private final HookguardProperties properties;

    public IngestController(IngestService ingestService, HookguardProperties properties) {
        this.ingestService = ingestService;
        this.properties = properties;
    }

    @PostMapping("/{projectKey}")
    public ResponseEntity<Map<String, String>> ingest(
            @PathVariable String projectKey,
            HttpServletRequest request
    ) throws Exception {
        int maxBody = Math.max(properties.ingest().maxBodyBytes(), 1);
        int declared = request.getContentLength();
        if (declared > maxBody) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large");
        }
        byte[] body = LimitedBodyReader.read(request.getInputStream(), maxBody);
        Map<String, String> headers = new LinkedHashMap<>();
        int count = 0;
        for (String name : Collections.list(request.getHeaderNames())) {
            count++;
            if (count > 100) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "too_many_headers");
            }
            String value = request.getHeader(name);
            if (value != null && value.length() > 8192) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "header_too_large");
            }
            headers.put(name, value == null ? "" : value);
        }
        UUID eventId = ingestService.accept(projectKey, headers, body, request.getContentType());
        return ResponseEntity.accepted().body(Map.of("eventId", eventId.toString()));
    }
}

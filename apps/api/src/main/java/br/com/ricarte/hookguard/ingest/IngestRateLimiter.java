package br.com.ricarte.hookguard.ingest;

import br.com.ricarte.hookguard.config.HookguardProperties;
import br.com.ricarte.hookguard.web.ApiException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class IngestRateLimiter {

    private final Map<UUID, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final HookguardProperties properties;

    public IngestRateLimiter(HookguardProperties properties) {
        this.properties = properties;
    }

    public void check(UUID projectId) {
        int limit = properties.rateLimit().ingestPerMinute();
        if (limit <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long cutoff = now - 60_000L;
        Deque<Long> timestamps = windows.computeIfAbsent(projectId, id -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.removeFirst();
            }
            if (timestamps.size() >= limit) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
            }
            timestamps.addLast(now);
        }
    }
}

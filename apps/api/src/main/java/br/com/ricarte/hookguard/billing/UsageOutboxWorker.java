package br.com.ricarte.hookguard.billing;

import br.com.ricarte.hookguard.config.HookguardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UsageOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(UsageOutboxWorker.class);

    private final UsageOutbox outbox;
    private final HookguardProperties properties;

    public UsageOutboxWorker(UsageOutbox outbox, HookguardProperties properties) {
        this.outbox = outbox;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${hookguard.billing.outbox-poll-ms:15000}")
    public void poll() {
        if (!properties.worker().enabled()) {
            return;
        }
        try {
            while (outbox.processDue(20) == 20) {
                // keep draining while full batches come back
            }
        } catch (RuntimeException ex) {
            log.warn("Usage outbox pass failed: {}", ex.getClass().getSimpleName());
        }
    }
}

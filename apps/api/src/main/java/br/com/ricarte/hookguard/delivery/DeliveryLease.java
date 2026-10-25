package br.com.ricarte.hookguard.delivery;

import java.time.Instant;
import java.util.UUID;

record DeliveryLease(
        UUID jobId,
        UUID eventId,
        UUID attemptId,
        String claimToken,
        int attemptNumber,
        int maxAttempts,
        String destinationUrl,
        int timeoutMs,
        byte[] body,
        String contentType,
        String headersJson,
        String signingSecret,
        String signingSecretPrevious,
        Instant signingSecretRotatedAt
) {
}

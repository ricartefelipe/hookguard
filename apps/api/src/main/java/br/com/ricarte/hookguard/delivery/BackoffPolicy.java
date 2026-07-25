package br.com.ricarte.hookguard.delivery;

import java.time.Duration;

public final class BackoffPolicy {

    private static final Duration[] DELAYS = {
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(10),
            Duration.ofHours(1),
            Duration.ofHours(6)
    };

    private BackoffPolicy() {
    }

    public static Duration delayAfterAttempt(int attemptNumberJustFinished) {
        int index = Math.max(0, attemptNumberJustFinished - 1);
        if (index >= DELAYS.length) {
            return DELAYS[DELAYS.length - 1];
        }
        return DELAYS[index];
    }
}

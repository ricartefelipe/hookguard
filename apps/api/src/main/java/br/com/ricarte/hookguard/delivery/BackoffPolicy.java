package br.com.ricarte.hookguard.delivery;

import java.time.Duration;
import java.util.Random;

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

    public static Duration scheduleDelay(int attemptNumber, Duration retryAfter, Duration cap, Random random) {
        long millis = Math.max(1000L, delayAfterAttempt(attemptNumber).toMillis());
        double swing = (random.nextDouble() * 2.0) - 1.0;
        long jittered = millis + Math.round(millis * 0.2d * swing);
        jittered = Math.max(1000L, jittered);
        if (retryAfter != null && retryAfter.toMillis() > jittered) {
            jittered = retryAfter.toMillis();
        }
        if (cap != null && jittered > cap.toMillis()) {
            jittered = cap.toMillis();
        }
        return Duration.ofMillis(Math.max(1000L, jittered));
    }
}

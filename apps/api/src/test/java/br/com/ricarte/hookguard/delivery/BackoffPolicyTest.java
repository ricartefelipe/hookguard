package br.com.ricarte.hookguard.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

    @Test
    void followsConfiguredSequence() {
        assertThat(BackoffPolicy.delayAfterAttempt(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(BackoffPolicy.delayAfterAttempt(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(BackoffPolicy.delayAfterAttempt(3)).isEqualTo(Duration.ofMinutes(10));
        assertThat(BackoffPolicy.delayAfterAttempt(4)).isEqualTo(Duration.ofHours(1));
        assertThat(BackoffPolicy.delayAfterAttempt(5)).isEqualTo(Duration.ofHours(6));
        assertThat(BackoffPolicy.delayAfterAttempt(99)).isEqualTo(Duration.ofHours(6));
    }
}

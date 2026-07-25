package br.com.ricarte.hookguard.delivery;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DestinationUrlValidatorTest {

    @Test
    void acceptsHttpsPublicHost() {
        assertThatCode(() -> DestinationUrlValidator.validate("https://example.com/hooks", false))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPrivateHostInProductionMode() {
        assertThatThrownBy(() -> DestinationUrlValidator.validate("https://127.0.0.1/hooks", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsLocalhostWhenHttpEnabled() {
        assertThatCode(() -> DestinationUrlValidator.validate("http://localhost:8089/hooks", true))
                .doesNotThrowAnyException();
    }
}

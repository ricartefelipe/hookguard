package br.com.ricarte.hookguard.delivery;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class DestinationUrlValidatorTest {

    @Test
    void acceptsHttpsPublicHost() {
        assertThatCode(() -> DestinationUrlValidator.validate("https://example.com/hooks", false))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPrivateAndLoopbackHostsInProductionMode() {
        assertThatThrownBy(() -> DestinationUrlValidator.validate("https://127.0.0.1/hooks", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DestinationUrlValidator.validate("https://10.1.2.3/hooks", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DestinationUrlValidator.validate("https://192.168.0.8/hooks", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DestinationUrlValidator.validate("https://[::1]/hooks", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DestinationUrlValidator.validate("http://localhost/hooks", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsLoopbackOnlyWhenHttpIsEnabled() {
        assertThatCode(() -> DestinationUrlValidator.validate("http://localhost:8089/hooks", true))
                .doesNotThrowAnyException();
        assertThatCode(() -> DestinationUrlValidator.validate("http://127.0.0.1:8089/hooks", true))
                .doesNotThrowAnyException();
    }

    @Test
    void alwaysRejectsMetadataCredentialsAndAmbiguousLiterals() {
        assertThatThrownBy(() -> DestinationUrlValidator.validate("http://169.254.169.254/latest", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DestinationUrlValidator.validate("https://metadata.google.internal/computeMetadata/v1/", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DestinationUrlValidator.validate("https://user:pass@example.com/hooks", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DestinationUrlValidator.validate("https://0177.0.0.1/hooks", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DestinationUrlValidator.validate("https://2130706433/hooks", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDnsAnswerThatContainsAPrivateAddress() {
        NameResolver resolver = host -> new InetAddress[] {
                InetAddress.getByName("1.2.3.4"),
                InetAddress.getByName("127.0.0.1")
        };
        assertThatThrownBy(() -> DestinationUrlValidator.resolve("https://public.example/hook", false, resolver))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

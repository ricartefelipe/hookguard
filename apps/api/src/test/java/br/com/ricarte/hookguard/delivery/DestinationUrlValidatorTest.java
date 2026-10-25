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

    @Test
    void acceptsPublicIpv6LiteralAndKeepsTheBareAddress() {
        NameResolver echo = host -> new InetAddress[] {InetAddress.getByName(host)};
        DestinationUrlValidator.ResolvedEndpoint endpoint =
                DestinationUrlValidator.resolve("https://[2606:4700:4700::1111]:8443/hook", false, echo);
        org.assertj.core.api.Assertions.assertThat(endpoint.host()).isEqualTo("2606:4700:4700::1111");
        org.assertj.core.api.Assertions.assertThat(endpoint.port()).isEqualTo(8443);
        org.assertj.core.api.Assertions.assertThat(endpoint.address().getHostAddress()).startsWith("2606:4700:4700:");
        org.assertj.core.api.Assertions.assertThat(
                DestinationUrlValidator.resolve("https://[2606:4700:4700::1111]/hook", false, echo).port()).isEqualTo(443);
    }

    @Test
    void rejectsNonPublicIpv6Forms() {
        NameResolver echo = host -> new InetAddress[] {InetAddress.getByName(host)};
        String[] blocked = {"[::1]", "[::]", "[fe80::1]", "[fd00::1]", "[fc00::1]", "[ff02::1]", "[::ffff:127.0.0.1]",
                "[::ffff:169.254.169.254]", "[2001:db8::1]", "[64:ff9b::7f00:1]", "[2002:7f00:1::]", "[2002:a9fe:a9fe::]",
                "[fec0::1]", "[2001::1]"};
        for (String host : blocked) {
            assertThatThrownBy(() -> DestinationUrlValidator.resolve("https://" + host + "/hook", false, echo))
                    .as(host)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> DestinationUrlValidator.resolve("https://[fe80::1%25eth0]/hook", false, echo))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DestinationUrlValidator.resolve("https://[fe80::1]/hook", true, echo))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DestinationUrlValidator.resolve("https://[::ffff:169.254.169.254]/hook", true, echo))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIpv6DnsAnswerInPrivateSpace() {
        NameResolver resolver = host -> new InetAddress[] {
                InetAddress.getByName("2606:4700:4700::1111"),
                InetAddress.getByName("fd12::1")
        };
        assertThatThrownBy(() -> DestinationUrlValidator.resolve("https://public.example/hook", false, resolver))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReservedIpv4DocumentationAndFutureRanges() {
        NameResolver docs = host -> new InetAddress[] {InetAddress.getByName("203.0.113.9")};
        assertThatThrownBy(() -> DestinationUrlValidator.resolve("https://public.example/hook", false, docs))
                .isInstanceOf(IllegalArgumentException.class);
        NameResolver future = host -> new InetAddress[] {InetAddress.getByName("240.0.0.1")};
        assertThatThrownBy(() -> DestinationUrlValidator.resolve("https://public.example/hook", false, future))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

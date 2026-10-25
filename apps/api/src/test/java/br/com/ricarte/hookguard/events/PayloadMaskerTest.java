package br.com.ricarte.hookguard.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PayloadMaskerTest {

    @Test
    void masksSecretJsonFieldsAndBearerTokens() {
        assertThat(PayloadMasker.mask("{\"password\":\"super-secret\",\"ok\":true}"))
                .isEqualTo("{\"password\":\"••••\",\"ok\":true}");
        assertThat(PayloadMasker.preview("token Bearer abc.def-123".getBytes())).contains("Bearer ••••");
        assertThat(PayloadMasker.preview("{\"order\":1}".getBytes())).isEqualTo("{\"order\":1}");
    }
}

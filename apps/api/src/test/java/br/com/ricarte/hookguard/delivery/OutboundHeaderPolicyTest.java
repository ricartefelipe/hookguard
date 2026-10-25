package br.com.ricarte.hookguard.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class OutboundHeaderPolicyTest {

    @Test
    void keepsProviderSignaturesAndDropsCredentials() {
        Map<String, String> stored = OutboundHeaderPolicy.forStorage(Map.of(
                "Authorization", "Bearer secret",
                "Cookie", "a=b",
                "X-Api-Key", "key",
                "X-HookGuard-Signature", "forged",
                "Stripe-Signature", "t=1,v1=abc",
                "X-Test", "nope",
                "X-Signature", "bad\r\nInjected: yes"
        ));
        assertThat(stored).containsEntry("Stripe-Signature", "t=1,v1=abc");
        assertThat(stored).doesNotContainKeys("Authorization", "Cookie", "X-Api-Key", "X-HookGuard-Signature", "X-Test", "X-Signature");
    }
}

package br.com.ricarte.hookguard.auth;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Exposure on with no demo access code configured: the link never comes back, and the answer is the same
 * one a wrong code gets when a code is configured, so a caller cannot tell whether a code exists.
 */
@TestPropertySource(properties = {
        "hookguard.auth.expose-magic-link=true",
        "hookguard.auth.demo-access-code="
})
class MagicLinkNoAccessCodeConfiguredTest extends MagicLinkExposureSupport {

    private static final String EMAIL = "no-code@example.com";

    @Test
    void missingCodeDoesNotReveal() throws Exception {
        assertHidden(request(EMAIL, null), EMAIL);
    }

    @Test
    void emptyOrBlankCodeDoesNotMatchTheEmptyConfiguration() throws Exception {
        assertHidden(request(EMAIL, ""), EMAIL);
        SENT.clear();
        assertHidden(request(EMAIL, " "), EMAIL);
    }

    @Test
    void anyCodeDoesNotReveal() throws Exception {
        assertHidden(request(EMAIL, CODE), EMAIL);
    }
}

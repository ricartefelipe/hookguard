package br.com.ricarte.hookguard.auth;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Exposure on with a demo access code configured: the link comes back only for the exact code. */
@TestPropertySource(properties = {
        "hookguard.auth.expose-magic-link=true",
        "hookguard.auth.demo-access-code=" + MagicLinkExposureSupport.CODE
})
class MagicLinkDemoAccessCodeTest extends MagicLinkExposureSupport {

    private static final String EMAIL = "demo-code@example.com";

    @Test
    void matchingCodeRevealsTheMailedLink() throws Exception {
        assertRevealed(request(EMAIL, CODE));
    }

    @Test
    void missingCodeDoesNotReveal() throws Exception {
        assertHidden(request(EMAIL, null), EMAIL);
    }

    @Test
    void wrongCodeAnswersExactlyLikeNoCode() throws Exception {
        assertHidden(request(EMAIL, CODE + "2"), EMAIL);
    }

    @Test
    void nearMissesDoNotReveal() throws Exception {
        for (String attempt : new String[] {
                "", " ", CODE.substring(0, CODE.length() - 1), CODE.toUpperCase(), " " + CODE, CODE + " "
        }) {
            SENT.clear();
            assertHidden(request(EMAIL, attempt), EMAIL);
        }
    }

    @Test
    void requestOriginIsNotProof() throws Exception {
        assertHidden(request(EMAIL, null,
                "X-Forwarded-For", "127.0.0.1", "X-Real-IP", "127.0.0.1", "Forwarded", "for=127.0.0.1"), EMAIL);
        SENT.clear();
        assertHidden(request(EMAIL, CODE + "2", "X-Forwarded-For", "::1"), EMAIL);
    }
}

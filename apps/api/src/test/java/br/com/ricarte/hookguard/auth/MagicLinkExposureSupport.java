package br.com.ricarte.hookguard.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.ricarte.hookguard.support.DatabaseCleaner;
import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared by the tests that decide when the login link may come back in the API answer. Every request goes
 * through the public endpoint and the e-mail is captured, so each case also proves the link was mailed.
 * Each subclass needs its own property set, hence its own context; it is closed after the class so the
 * connection pools do not pile up against the database limit.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Import(MagicLinkExposureSupport.CapturingMail.class)
abstract class MagicLinkExposureSupport extends PostgresIntegrationTest {

    static final String CODE = "demo-code-under-test";
    static final List<String> SENT = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class CapturingMail {
        @Bean
        @Primary
        JavaMailSender capturingSender() {
            return new JavaMailSenderImpl() {
                @Override
                public void send(SimpleMailMessage message) {
                    SENT.add(message.getText());
                }
            };
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired private DatabaseCleaner cleaner;

    @BeforeEach
    void cleanState() {
        cleaner.clean();
        SENT.clear();
    }

    /** Body of the answer to a link request; {@code accessCode} null means the field is not sent at all. */
    protected String request(String email, String accessCode, String... headers) throws Exception {
        String body = accessCode == null
                ? "{\"email\":\"" + email + "\",\"name\":\"X\"}"
                : "{\"email\":\"" + email + "\",\"name\":\"X\",\"accessCode\":\"" + accessCode + "\"}";
        MockHttpServletRequestBuilder builder = post("/v1/auth/magic-link").contentType(MediaType.APPLICATION_JSON)
                .content(body);
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return mvc.perform(builder).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** The one e-mail sent by the last request carries the link; returns its token. */
    protected String mailedToken() {
        assertThat(SENT).hasSize(1);
        String token = SENT.get(0).replaceAll("(?s).*token=([0-9a-f]+).*", "$1");
        assertThat(token).hasSizeGreaterThanOrEqualTo(32);
        return token;
    }

    /** The answer is exactly the "sent" shape: no link, no token, and nothing that tells the cases apart. */
    protected void assertHidden(String response, String email) {
        String token = mailedToken();
        assertThat(response).isEqualTo("{\"sent\":true,\"email\":\"" + email + "\"}");
        assertThat(response).doesNotContain("magicLink").doesNotContain(token).doesNotContain("/auth/callback");
    }

    /** The answer carries the same link that was mailed. */
    protected void assertRevealed(String response) {
        String token = mailedToken();
        assertThat(response).contains("\"sent\":true").contains("\"magicLink\":\"")
                .contains("/auth/callback?token=" + token);
    }
}

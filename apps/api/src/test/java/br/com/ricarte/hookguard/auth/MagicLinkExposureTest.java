package br.com.ricarte.hookguard.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.ricarte.hookguard.support.DatabaseCleaner;
import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** With the shipped default (link exposure off) the API answer carries neither the link nor the token. */
@AutoConfigureMockMvc
@Import(MagicLinkExposureTest.CapturingMail.class)
@TestPropertySource(properties = "hookguard.auth.expose-magic-link=false")
class MagicLinkExposureTest extends PostgresIntegrationTest {

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

    @Autowired private MockMvc mvc;
    @Autowired private DatabaseCleaner cleaner;

    @Test
    void shippedDefaultIsOff() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        String configured = yaml.getObject().getProperty("hookguard.auth.expose-magic-link");
        assertThat(configured).isEqualTo("${HOOKGUARD_EXPOSE_MAGIC_LINK:false}");
    }

    @Test
    void responseRevealsNeitherLinkNorToken() throws Exception {
        cleaner.clean();
        SENT.clear();
        String response = mvc.perform(post("/v1/auth/magic-link").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"hidden-link@example.com\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(SENT).hasSize(1);
        String token = SENT.get(0).replaceAll("(?s).*token=([0-9a-f]+).*", "$1");
        assertThat(token).hasSizeGreaterThanOrEqualTo(32);
        assertThat(response).doesNotContain("magicLink").doesNotContain("token").doesNotContain(token)
                .doesNotContain("/auth/callback");
        assertThat(response).contains("\"sent\":true");
    }
}

package br.com.ricarte.hookguard.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;

/**
 * With the shipped default (link exposure off) the API answer carries neither the link nor the token, even
 * when a demo access code is configured and the caller sends it.
 */
@TestPropertySource(properties = {
        "hookguard.auth.expose-magic-link=false",
        "hookguard.auth.demo-access-code=" + MagicLinkExposureSupport.CODE
})
class MagicLinkExposureTest extends MagicLinkExposureSupport {

    @Test
    void shippedDefaultIsOffWithNoAccessCode() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        assertThat(yaml.getObject().getProperty("hookguard.auth.expose-magic-link"))
                .isEqualTo("${HOOKGUARD_EXPOSE_MAGIC_LINK:false}");
        assertThat(yaml.getObject().getProperty("hookguard.auth.demo-access-code"))
                .isEqualTo("${HOOKGUARD_DEMO_ACCESS_CODE:}");
    }

    @Test
    void responseRevealsNeitherLinkNorTokenWithoutCode() throws Exception {
        assertHidden(request("hidden-link@example.com", null), "hidden-link@example.com");
    }

    @Test
    void matchingCodeDoesNotRevealWhileExposureIsOff() throws Exception {
        assertHidden(request("hidden-link@example.com", CODE), "hidden-link@example.com");
    }

    @Test
    void wrongCodeDoesNotReveal() throws Exception {
        assertHidden(request("hidden-link@example.com", CODE + "2"), "hidden-link@example.com");
    }
}

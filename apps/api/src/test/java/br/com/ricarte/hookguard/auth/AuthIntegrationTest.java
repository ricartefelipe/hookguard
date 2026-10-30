package br.com.ricarte.hookguard.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.ricarte.hookguard.support.DatabaseCleaner;
import br.com.ricarte.hookguard.support.NoOpMailConfig;
import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
@Import(NoOpMailConfig.class)
class AuthIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
    }

    @Test
    void magicLinkCreatesSessionAndProtectsMe() throws Exception {
        String email = "auth-" + UUID.randomUUID() + "@example.com";
        MvcResult request = mockMvc.perform(post("/v1/auth/magic-link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","name":"Auth User"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true))
                .andExpect(jsonPath("$.magicLink").isNotEmpty())
                .andReturn();

        JsonNode body = objectMapper.readTree(request.getResponse().getContentAsString());
        String magicLink = body.get("magicLink").asText();
        String token = magicLink.substring(magicLink.indexOf("token=") + "token=".length());

        MvcResult verified = mockMvc.perform(post("/v1/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s"}
                                """.formatted(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionToken").isNotEmpty())
                .andExpect(jsonPath("$.email").value(email))
                .andReturn();

        String sessionToken = objectMapper.readTree(verified.getResponse().getContentAsString())
                .get("sessionToken")
                .asText();

        mockMvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + sessionToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));

        mockMvc.perform(post("/v1/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s"}
                                """.formatted(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_token"));

        mockMvc.perform(post("/v1/auth/logout").header("Authorization", "Bearer " + sessionToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + sessionToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forgedAccountHeaderDoesNotAuthenticate() throws Exception {
        mockMvc.perform(get("/v1/auth/me"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/projects").header("X-Account-Id", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/projects").header("Authorization", "Bearer not-a-session"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordLoginRejectsUnknownCredentials() throws Exception {
        mockMvc.perform(post("/v1/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"missing@example.com","password":"not-the-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"));
    }
}

package br.com.ricarte.hookguard.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.ricarte.hookguard.support.DatabaseCleaner;
import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The GitHub side is a local stand-in (token endpoint and user API served by MockWebServer).
 * This exercises HookGuard's half of the flow only; it is not evidence of a run against github.com.
 */
@AutoConfigureMockMvc
class GitHubOAuthIntegrationTest extends PostgresIntegrationTest {

    private static final MockWebServer GITHUB = new MockWebServer();
    private static volatile boolean tokenFails;

    static {
        try {
            GITHUB.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    String path = request.getPath();
                    if (path.equals("/login/oauth/access_token")) {
                        String body = request.getBody().readUtf8();
                        if (tokenFails || !body.contains("client_id=test-client")) {
                            return new MockResponse().setResponseCode(200).setBody("{\"error\":\"bad_verification_code\"}");
                        }
                        String code = body.replaceAll(".*code=([^&]*).*", "$1");
                        return new MockResponse().setBody("{\"access_token\":\"tok-" + code + "\"}");
                    }
                    String auth = request.getHeader("Authorization");
                    if (path.equals("/user")) {
                        return switch (auth == null ? "" : auth) {
                            case "Bearer tok-alice" -> new MockResponse().setBody("{\"id\":1001,\"login\":\"alice\",\"name\":\"Alice\",\"email\":\"alice@example.com\"}");
                            case "Bearer tok-bob" -> new MockResponse().setBody("{\"id\":1002,\"login\":\"bob\",\"name\":\"Bob\",\"email\":null}");
                            case "Bearer tok-noemail" -> new MockResponse().setBody("{\"id\":1003,\"login\":\"ghost\",\"name\":null,\"email\":null}");
                            default -> new MockResponse().setResponseCode(401);
                        };
                    }
                    if (path.equals("/user/emails")) {
                        return switch (auth == null ? "" : auth) {
                            case "Bearer tok-bob" -> new MockResponse().setBody("[{\"email\":\"unverified@example.com\",\"primary\":false,\"verified\":false},{\"email\":\"bob@example.com\",\"primary\":true,\"verified\":true}]");
                            default -> new MockResponse().setBody("[]");
                        };
                    }
                    return new MockResponse().setResponseCode(404);
                }
            });
            GITHUB.start();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void github(DynamicPropertyRegistry registry) {
        registry.add("hookguard.auth.github-client-id", () -> "test-client");
        registry.add("hookguard.auth.github-client-secret", () -> "test-client-secret");
        registry.add("hookguard.auth.github-authorize-url", () -> GITHUB.url("/login/oauth/authorize").toString());
        registry.add("hookguard.auth.github-token-url", () -> GITHUB.url("/login/oauth/access_token").toString());
        registry.add("hookguard.auth.github-api-url", () -> GITHUB.url("/").toString().replaceAll("/$", ""));
    }

    @AfterAll
    static void stop() throws Exception {
        GITHUB.shutdown();
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private GitHubOAuthService service;
    @Autowired private DatabaseCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        tokenFails = false;
    }

    private record Started(String nonce, String state) {
    }

    private Started start() throws Exception {
        MvcResult result = mvc.perform(get("/v1/auth/github")).andExpect(status().isFound()).andReturn();
        String cookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(cookie).contains("hg_oauth_state=").contains("HttpOnly").contains("SameSite=Lax");
        String nonce = cookie.replaceAll("hg_oauth_state=([^;]*);.*", "$1");
        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(GITHUB.url("/login/oauth/authorize").toString())
                .contains("client_id=test-client").contains("redirect_uri=");
        String state = URLDecoder.decode(location.replaceAll(".*[?&]state=([^&]*).*", "$1"), StandardCharsets.UTF_8);
        assertThat(state).startsWith(nonce + ".");
        return new Started(nonce, state);
    }

    private MvcResult callback(String code, String state, String nonce) throws Exception {
        var request = get("/v1/auth/github/callback").param("code", code);
        if (state != null) {
            request.param("state", state);
        }
        if (nonce != null) {
            request.cookie(new Cookie("hg_oauth_state", nonce));
        }
        return mvc.perform(request).andReturn();
    }

    private String sessionFrom(MvcResult result) {
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).contains("/auth/callback?sessionToken=");
        return URI.create(location).getQuery().replace("sessionToken=", "");
    }

    @Test
    void completeFlowCreatesTheAccountAndAValidSession() throws Exception {
        Started started = start();
        MvcResult result = callback("alice", started.state(), started.nonce());
        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("hg_oauth_state=;").contains("Max-Age=0");
        String session = sessionFrom(result);

        String me = mvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(me).get("email").asText()).isEqualTo("alice@example.com");
        assertThat(jdbc.queryForObject("SELECT github_id FROM accounts WHERE email = 'alice@example.com'", String.class)).isEqualTo("1001");
    }

    @Test
    void stateIsRequiredBoundToTheBrowserSignedAndFresh() throws Exception {
        Started started = start();
        assertThat(callback("alice", null, started.nonce()).getResponse().getStatus()).isEqualTo(400);
        assertThat(callback("alice", started.state(), null).getResponse().getStatus()).isEqualTo(400);
        assertThat(callback("alice", started.state(), "0".repeat(48)).getResponse().getStatus()).isEqualTo(400);
        String tampered = started.state().substring(0, started.state().length() - 1) + (started.state().endsWith("0") ? "1" : "0");
        assertThat(callback("alice", tampered, started.nonce()).getResponse().getStatus()).isEqualTo(400);
        assertThat(callback("alice", "garbage", started.nonce()).getResponse().getStatus()).isEqualTo(400);
        String expired = service.signState(started.nonce(), Instant.now().minusSeconds(5));
        assertThat(callback("alice", expired, started.nonce()).getResponse().getStatus()).isEqualTo(400);
        // another user's state used with this browser's cookie (login CSRF)
        Started other = start();
        assertThat(callback("alice", other.state(), started.nonce()).getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class)).isZero();
        assertThat(GITHUB.getRequestCount()).isZero();
    }

    @Test
    void providerFailuresAndIncompleteProfilesNeverCreateSessions() throws Exception {
        Started started = start();
        assertThat(callback("unknown", started.state(), started.nonce()).getResponse().getStatus()).isEqualTo(502);
        assertThat(callback("noemail", started.state(), started.nonce()).getResponse().getStatus()).isEqualTo(400);
        tokenFails = true;
        assertThat(callback("alice", started.state(), started.nonce()).getResponse().getStatus()).isEqualTo(502);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions", Integer.class)).isZero();
        MvcResult denied = mvc.perform(get("/v1/auth/github/callback").param("error", "access_denied")).andReturn();
        assertThat(denied.getResponse().getRedirectedUrl()).contains("error=access_denied");
    }

    @Test
    void onlyAVerifiedEmailIsUsedAndAccountsStayIsolated() throws Exception {
        Started a = start();
        String alice = sessionFrom(callback("alice", a.state(), a.nonce()));
        Started b = start();
        String bob = sessionFrom(callback("bob", b.state(), b.nonce()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE email = 'unverified@example.com'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT email FROM accounts WHERE github_id = '1002'", String.class)).isEqualTo("bob@example.com");

        String project = mvc.perform(post("/v1/projects").header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"destinationUrl\":\"" + GITHUB.url("/hook") + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String projectId = json.readTree(project).get("id").asText();
        mvc.perform(get("/v1/projects/" + projectId).header("Authorization", "Bearer " + bob)).andExpect(status().isNotFound());
        mvc.perform(get("/v1/projects").header("Authorization", "Bearer " + bob)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[]"));
    }

    @Test
    void sameGithubIdentityReturnsToTheSameAccount() throws Exception {
        Started first = start();
        callback("alice", first.state(), first.nonce());
        Started second = start();
        callback("alice", second.state(), second.nonce());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE github_id = '1001'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions", Integer.class)).isEqualTo(2);
    }
}

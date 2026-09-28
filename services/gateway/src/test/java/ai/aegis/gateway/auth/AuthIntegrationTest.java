package ai.aegis.gateway.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end auth flow against real Postgres (pgvector) + Redis via Testcontainers:
 * register -> login -> /me, plus protected-route and RBAC checks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        registry.add("jwt.secret", () -> "a-test-secret-that-is-at-least-32-bytes-long!!");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Test
    void registerLoginAndMe() throws Exception {
        String email = "founder+" + System.nanoTime() + "@example.com";
        String registerBody = """
                {"email":"%s","password":"password123","fullName":"Ada Lovelace","organizationName":"Acme SRE"}
                """.formatted(email);

        String response = mvc.perform(post("/api/auth/register")
                        .contentType("application/json").content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.role", is("org_admin")))
                .andExpect(jsonPath("$.orgId", notNullValue()))
                .andReturn().getResponse().getContentAsString();

        // /me with the issued token
        JsonNode json = mapper.readTree(response);
        String token = json.get("accessToken").asText();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is(email)))
                .andExpect(jsonPath("$.role", is("org_admin")));

        // login with the same credentials
        String loginBody = """
                {"email":"%s","password":"password123"}
                """.formatted(email);
        mvc.perform(post("/api/auth/login").contentType("application/json").content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()));
    }

    @Test
    void meRequiresAuth() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsBadCredentials() throws Exception {
        String body = """
                {"email":"nobody@example.com","password":"wrongpass1"}
                """;
        mvc.perform(post("/api/auth/login").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsDuplicateEmail() throws Exception {
        String email = "dup+" + System.nanoTime() + "@example.com";
        String body = """
                {"email":"%s","password":"password123","fullName":"Dup","organizationName":"Dup Org"}
                """.formatted(email);
        mvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andExpect(status().isConflict());
    }
}

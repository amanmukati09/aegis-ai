package ai.aegis.gateway.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import java.io.IOException;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chat/copilot flow against a mocked ML sidecar: sending a message persists the user
 * and assistant messages; another user cannot read someone else's session (404).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ChatIntegrationTest {

    static MockWebServer mlServer;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @BeforeAll
    static void startMl() throws IOException {
        mlServer = new MockWebServer();
        mlServer.start();
    }

    @AfterAll
    static void stopMl() throws IOException {
        mlServer.shutdown();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        registry.add("jwt.secret", () -> "a-test-secret-that-is-at-least-32-bytes-long!!");
        registry.add("ml.service.url", () -> "http://localhost:" + mlServer.getPort());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    private String register() throws Exception {
        String email = "chat+" + System.nanoTime() + "@example.com";
        String body = """
                {"email":"%s","password":"password123","fullName":"User","organizationName":"ChatOrg %d"}
                """.formatted(email, System.nanoTime());
        String res = mvc.perform(post("/api/auth/register")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(res).get("accessToken").asText();
    }

    @Test
    void sendPersistsUserAndAssistantMessages() throws Exception {
        String token = register();
        mlServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"reply\":\"Restart the pod and check the DB pool.\",\"model\":\"openai/gpt-oss-20b\",\"provider\":\"groq\"}"));

        String res = mvc.perform(post("/api/chat/message")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"message\":\"Why is payments failing?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId", notNullValue()))
                .andExpect(jsonPath("$.reply", is("Restart the pod and check the DB pool.")))
                .andReturn().getResponse().getContentAsString();

        String sessionId = mapper.readTree(res).get("sessionId").asText();

        mvc.perform(get("/api/chat/sessions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mvc.perform(get("/api/chat/sessions/" + sessionId + "/messages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].role", is("user")))
                .andExpect(jsonPath("$[1].role", is("assistant")));
    }

    @Test
    void cannotAccessAnotherUsersSession() throws Exception {
        String tokenA = register();
        String tokenB = register();

        mlServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"reply\":\"ok\",\"model\":\"m\",\"provider\":\"groq\"}"));

        String res = mvc.perform(post("/api/chat/message")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType("application/json")
                        .content("{\"message\":\"private question\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sessionId = mapper.readTree(res).get("sessionId").asText();

        // B cannot read A's session messages.
        mvc.perform(get("/api/chat/sessions/" + sessionId + "/messages")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }
}

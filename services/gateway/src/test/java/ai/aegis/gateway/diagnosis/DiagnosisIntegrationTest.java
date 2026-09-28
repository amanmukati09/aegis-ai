package ai.aegis.gateway.diagnosis;

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

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Diagnosis pipeline against a mocked ML sidecar (MockWebServer): the gateway masks
 * PII, calls detect->diagnose->suggest, and persists an incident. Verifies the flow
 * end-to-end without needing a real LLM provider.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DiagnosisIntegrationTest {

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
        String email = "diag+" + System.nanoTime() + "@example.com";
        String body = """
                {"email":"%s","password":"password123","fullName":"Dr","organizationName":"DiagOrg %d"}
                """.formatted(email, System.nanoTime());
        String res = mvc.perform(post("/api/auth/register")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(res).get("accessToken").asText();
    }

    private void enqueue(String json) {
        mlServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(json));
    }

    @Test
    void runsPipelineAndPersistsIncident() throws Exception {
        String token = register();

        // Mock the three ML calls in order: detect, diagnose, remediation.
        enqueue("""
                {"anomaly_detected":true,"anomaly_type":"db_timeout","severity":"high",
                 "affected_component":"payments-svc","description":"DB connection timeouts"}
                """);
        enqueue("""
                {"root_cause":"Connection pool exhausted","confidence":0.82,
                 "evidence":["95% pool in use"],"contributing_factors":["traffic spike"]}
                """);
        enqueue("""
                {"immediate_actions":["Increase pool size"],"diagnostic_commands":["df -h"],
                 "escalation_needed":false,"estimated_recovery_time":"15m",
                 "prevention_measures":["Add autoscaling"]}
                """);

        String body = """
                {"logs":["ERROR payments-svc timeout to db 10.0.0.5","WARN pool 95%"]}
                """;
        String res = mvc.perform(post("/api/diagnose")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentId", notNullValue()))
                .andExpect(jsonPath("$.anomaly.severity", is("high")))
                .andExpect(jsonPath("$.diagnosis.root_cause", is("Connection pool exhausted")))
                .andExpect(jsonPath("$.remediation.immediate_actions[0]", is("Increase pool size")))
                .andReturn().getResponse().getContentAsString();

        String incidentId = mapper.readTree(res).get("incidentId").asText();

        // The incident was persisted and is retrievable (org-scoped).
        mvc.perform(get("/api/incidents/" + incidentId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity", is("high")))
                .andExpect(jsonPath("$.rootCause", is("Connection pool exhausted")));

        // Verify PII was masked before reaching the ML service (first request body).
        String detectBody = mlServer.takeRequest().getBody().readUtf8();
        org.assertj.core.api.Assertions.assertThat(detectBody).doesNotContain("10.0.0.5");
        org.assertj.core.api.Assertions.assertThat(detectBody).contains("REDACTED_IPV4");
    }
}

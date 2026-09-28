package ai.aegis.gateway.platform;

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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5 breadth: streams CRUD, notifications on incident create, and admin authz
 * (a self-registered org_admin can reach admin endpoints; the endpoints are gated).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PlatformIntegrationTest {

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

    private String register() throws Exception {
        String email = "plat+" + System.nanoTime() + "@example.com";
        String body = """
                {"email":"%s","password":"password123","fullName":"Admin","organizationName":"PlatOrg %d"}
                """.formatted(email, System.nanoTime());
        String res = mvc.perform(post("/api/auth/register")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(res).get("accessToken").asText();
    }

    @Test
    void streamsCrud() throws Exception {
        String token = register();
        mvc.perform(post("/api/streams").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"name\":\"prod-metrics\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("prod-metrics")))
                .andExpect(jsonPath("$.sourceType", is("http")));

        mvc.perform(get("/api/streams").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void incidentCreateProducesNotification() throws Exception {
        String token = register();
        mvc.perform(post("/api/incidents").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"title\":\"disk full\",\"severity\":\"high\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount", is(1)))
                .andExpect(jsonPath("$.notifications[0].title", is("Incident created")));
    }

    @Test
    void adminEndpointsReachableByOrgAdmin() throws Exception {
        String token = register(); // self-registration => org_admin
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/admin/metrics").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void workspaceCreateAndList() throws Exception {
        String token = register();
        mvc.perform(post("/api/workspaces").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"name\":\"Platform Team\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/workspaces").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }
}

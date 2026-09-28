package ai.aegis.gateway.incident;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Incident CRUD + multi-tenant isolation: an org_admin in org A must not be able to
 * read or delete an incident that belongs to org B.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IncidentIntegrationTest {

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

    private String register(String org) throws Exception {
        String email = "admin+" + System.nanoTime() + "@example.com";
        String body = """
                {"email":"%s","password":"password123","fullName":"Admin","organizationName":"%s"}
                """.formatted(email, org);
        String res = mvc.perform(post("/api/auth/register")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(res).get("accessToken").asText();
    }

    private String createIncident(String token, String title) throws Exception {
        String body = """
                {"title":"%s","severity":"high","anomalyDescription":"disk full"}
                """.formatted(title);
        String res = mvc.perform(post("/api/incidents")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("open")))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(res).get("id").asText();
    }

    @Test
    void createListResolve() throws Exception {
        String token = register("Acme " + System.nanoTime());
        String id = createIncident(token, "API latency spike");

        mvc.perform(get("/api/incidents").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title", is("API latency spike")));

        mvc.perform(post("/api/incidents/" + id + "/resolve")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"resolutionNotes\":\"restarted\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("resolved")));
    }

    @Test
    void crossOrgIsolation() throws Exception {
        String tokenA = register("OrgA " + System.nanoTime());
        String tokenB = register("OrgB " + System.nanoTime());
        String incidentA = createIncident(tokenA, "OrgA secret incident");

        // B cannot read A's incident
        mvc.perform(get("/api/incidents/" + incidentA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // B cannot delete A's incident
        mvc.perform(delete("/api/incidents/" + incidentA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // A's list does not leak into B's list
        String bList = mvc.perform(get("/api/incidents").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode items = mapper.readTree(bList).get("items");
        for (JsonNode item : items) {
            assert !item.get("title").asText().contains("OrgA secret");
        }

        // A can still delete its own
        mvc.perform(delete("/api/incidents/" + incidentA).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());
    }

    @Test
    void dashboardSummaryShape() throws Exception {
        String token = register("Metrics " + System.nanoTime());
        createIncident(token, "one");
        mvc.perform(get("/api/dashboard/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.open", is(1)));
    }
}

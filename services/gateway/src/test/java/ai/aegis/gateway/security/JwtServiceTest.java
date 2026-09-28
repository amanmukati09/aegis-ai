package ai.aegis.gateway.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "a-test-secret-that-is-at-least-32-bytes-long!!";

    @Test
    void issuesAndParsesToken() {
        JwtService jwt = new JwtService(SECRET, 7);
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        String token = jwt.issue(userId, orgId, "user@example.com", "org_admin");
        Claims claims = jwt.parse(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("org", String.class)).isEqualTo(orgId.toString());
        assertThat(claims.get("email", String.class)).isEqualTo("user@example.com");
        assertThat(claims.get("role", String.class)).isEqualTo("org_admin");
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new JwtService("too-short", 7))
                .isInstanceOf(IllegalStateException.class);
    }
}

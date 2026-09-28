package ai.aegis.gateway.guardrails;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuardrailServiceTest {

    private final GuardrailService guard = new GuardrailService();

    @Test
    void masksIpAndEmail() {
        String masked = guard.maskPii("Failed login from 10.0.0.5 for user alice@example.com");
        assertThat(masked).doesNotContain("10.0.0.5");
        assertThat(masked).doesNotContain("alice@example.com");
        assertThat(masked).contains("[REDACTED_IPV4]");
        assertThat(masked).contains("[REDACTED_EMAIL]");
    }

    @Test
    void masksAwsKey() {
        String masked = guard.maskPii("key=AKIAIOSFODNN7EXAMPLE in config");
        assertThat(masked).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(masked).contains("[REDACTED_AWS_KEY]");
    }

    @Test
    void masksList() {
        List<String> out = guard.maskPii(List.of("ip 192.168.1.1", "clean line"));
        assertThat(out.get(0)).contains("[REDACTED_IPV4]");
        assertThat(out.get(1)).isEqualTo("clean line");
    }

    @Test
    void detectsDestructiveCommand() {
        assertThat(guard.isDestructive("run rm -rf / now")).isTrue();
        assertThat(guard.isDestructive("DROP DATABASE prod")).isTrue();
        assertThat(guard.isDestructive("systemctl restart nginx")).isFalse();
    }
}

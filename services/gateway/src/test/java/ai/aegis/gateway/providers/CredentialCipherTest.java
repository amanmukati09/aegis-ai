package ai.aegis.gateway.providers;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialCipherTest {

    // 32 raw bytes, base64-encoded — a valid CREDENTIAL_ENC_KEY for tests.
    private static final String VALID_KEY =
            Base64.getEncoder().encodeToString("a-test-credential-key-32-bytes!!".substring(0, 32).getBytes());

    /**
     * Feature: ai-provider-flexibility, Property 22: Credential encryption round-trip and non-identity
     * Validates: Requirements 7.1
     */
    @Property(tries = 100)
    @Tag("ai-provider-flexibility")
    @Tag("property-22")
    void encryptDecryptRoundTripAndNonIdentity(@ForAll @NotBlank String plaintext) {
        CredentialCipher cipher = new CredentialCipher(VALID_KEY);

        String encrypted = cipher.encrypt(plaintext);

        assertThat(encrypted).isNotEqualTo(plaintext);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void rejectsMissingKey() {
        assertThatThrownBy(() -> new CredentialCipher(null))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rejectsTooShortKey() {
        // Decodes to far fewer than 32 bytes.
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes());
        assertThatThrownBy(() -> new CredentialCipher(shortKey))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNonBase64Key() {
        assertThatThrownBy(() -> new CredentialCipher("not-valid-base64!@#$%^&*()"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsEmptyKey() {
        assertThatThrownBy(() -> new CredentialCipher(""))
                .isInstanceOf(IllegalStateException.class);
    }
}

package ai.aegis.gateway.providers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts/decrypts Provider_Configuration credential field values with
 * AES-256-GCM. Key comes from env (CREDENTIAL_ENC_KEY, base64-encoded, 32 raw
 * bytes) — no hardcoded key, mirroring JwtService's JWT_SECRET validation.
 *
 * Encoding: base64(iv || ciphertext || tag), where iv is a fresh random
 * 12-byte value generated per encrypt() call (GCM's recommended IV length)
 * and never reused. GCM appends its 16-byte authentication tag to the
 * ciphertext itself, so no separate tag handling is needed on our end.
 */
@Service
public class CredentialCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public CredentialCipher(@Value("${credential.encryption-key}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("CREDENTIAL_ENC_KEY must be set");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("CREDENTIAL_ENC_KEY must be valid base64");
        }
        // Require exactly 32 raw bytes; AES-256 needs a 32-byte key.
        if (bytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException("CREDENTIAL_ENC_KEY must decode to exactly 32 bytes");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertextAndTag = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertextAndTag.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertextAndTag, 0, combined, iv.length, ciphertextAndTag.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt credential", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length < IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("Encoded credential is too short to contain an IV");
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH_BYTES);
            byte[] ciphertextAndTag = Arrays.copyOfRange(combined, IV_LENGTH_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertextAndTag);

            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new IllegalStateException("Credential authentication failed (tampered or wrong key)", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt credential", e);
        }
    }
}

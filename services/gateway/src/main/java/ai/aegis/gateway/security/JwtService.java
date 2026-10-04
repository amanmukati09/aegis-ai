package ai.aegis.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Issues and validates JWT access tokens. Secret and expiry come from env
 * (JWT_SECRET / JWT_EXPIRY_DAYS) — no hardcoded secret like the original app.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expiryDays;

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.expiry-days:7}") long expiryDays) {
        // Require a strong secret; HMAC-SHA256 needs >= 32 bytes.
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expiryDays = expiryDays;
    }

    public String issue(UUID userId, UUID orgId, String email, String role) {
        return issue(userId, orgId, email, role, expiryDays, ChronoUnit.DAYS);
    }

    /**
     * Mint a short-lived token scoped to the same user, for the ML sidecar's agent
     * tool-calls to present back to the gateway. Keeps the exact same claims/validation
     * path as a normal session token (AuthenticationFilter re-resolves the live user
     * row, so workspace-visibility and role checks apply identically) but with a tight
     * expiry since it only needs to live for the duration of one chat turn.
     */
    public String issueToolToken(UUID userId, UUID orgId, String email, String role) {
        return issue(userId, orgId, email, role, 5, ChronoUnit.MINUTES);
    }

    private String issue(UUID userId, UUID orgId, String email, String role, long amount, ChronoUnit unit) {
        Instant now = Instant.now();
        Instant exp = now.plus(amount, unit);
        return Jwts.builder()
                .subject(userId.toString())
                .claims(Map.of(
                        "org", orgId == null ? "" : orgId.toString(),
                        "email", email,
                        "role", role
                ))
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

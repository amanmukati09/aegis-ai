package ai.aegis.gateway.security;

import ai.aegis.gateway.apikey.ApiKey;
import ai.aegis.gateway.apikey.ApiKeyRepository;
import ai.aegis.gateway.user.Role;
import ai.aegis.gateway.user.User;
import ai.aegis.gateway.user.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the caller from either a JWT or an API key in the Authorization header.
 * - "Bearer aegis_..." -> API key lookup (SHA-256 hash match)
 * - "Bearer <jwt>"     -> JWT verification
 * Sets an AuthPrincipal + ROLE_* authority on the SecurityContext. Unauthenticated
 * requests simply pass through; the SecurityConfig decides what requires auth.
 */
@Component
public class AuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ApiKeyRepository apiKeyRepository;

    public AuthenticationFilter(JwtService jwtService, UserRepository userRepository,
                                ApiKeyRepository apiKeyRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring("Bearer ".length()).trim();
            Optional<AuthPrincipal> principal = token.startsWith(ApiKeyHasher.PREFIX)
                    ? resolveApiKey(token)
                    : resolveJwt(token);
            principal.ifPresent(this::authenticate);
        }
        chain.doFilter(request, response);
    }

    private Optional<AuthPrincipal> resolveJwt(String token) {
        try {
            Claims claims = jwtService.parse(token);
            UUID userId = UUID.fromString(claims.getSubject());
            String orgStr = claims.get("org", String.class);
            UUID orgId = (orgStr == null || orgStr.isBlank()) ? null : UUID.fromString(orgStr);
            String email = claims.get("email", String.class);
            Role role = Role.valueOf(claims.get("role", String.class).toUpperCase());
            return Optional.of(new AuthPrincipal(userId, orgId, email, role));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<AuthPrincipal> resolveApiKey(String raw) {
        String hash = ApiKeyHasher.sha256(raw);
        Optional<ApiKey> found = apiKeyRepository.findByKeyHashAndActiveTrue(hash);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiKey key = found.get();
        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(OffsetDateTime.now())) {
            key.setActive(false);
            apiKeyRepository.save(key);
            return Optional.empty();
        }
        key.setLastUsedAt(OffsetDateTime.now());
        apiKeyRepository.save(key);
        return userRepository.findById(key.getUserId())
                .filter(User::isActive)
                .map(u -> new AuthPrincipal(u.getId(), u.getOrgId(), u.getEmail(), u.getRole()));
    }

    private void authenticate(AuthPrincipal principal) {
        var authorities = List.of(new SimpleGrantedAuthority(principal.role().authority()));
        var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}

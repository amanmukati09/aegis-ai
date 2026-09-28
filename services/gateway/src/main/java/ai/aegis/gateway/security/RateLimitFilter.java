package ai.aegis.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Redis-backed fixed-window rate limiter (ports the original's per-minute window).
 * Keyed by principal token/IP. Exempts auth + health. Fails open if Redis is down.
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redis;
    private final int requestsPerMinute;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> exemptPaths = List.of(
            "/api/auth/login", "/api/auth/register", "/actuator", "/oauth2", "/login/oauth2"
    );

    public RateLimitFilter(StringRedisTemplate redis,
                           @Value("${rate-limit.requests-per-minute:100}") int requestsPerMinute) {
        this.redis = redis;
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        for (String exempt : exemptPaths) {
            if (path.startsWith(exempt)) {
                chain.doFilter(request, response);
                return;
            }
        }

        String principal = identify(request);
        long window = System.currentTimeMillis() / 60_000;
        String key = "rate_limit:" + principal + ":" + window;

        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, Duration.ofSeconds(120));
            }
            if (count != null && count > requestsPerMinute) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json");
                mapper.writeValue(response.getWriter(), Map.of(
                        "error", "Too many requests. Please wait and try again.",
                        "limit", requestsPerMinute
                ));
                return;
            }
        } catch (Exception e) {
            // Fail open: never block traffic because the cache is unavailable.
            logger.warn("Rate limiter unavailable, allowing request: " + e.getMessage());
        }

        chain.doFilter(request, response);
    }

    private String identify(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring("Bearer ".length());
            return "t:" + token.substring(0, Math.min(20, token.length()));
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return "ip:" + forwarded.split(",")[0].trim();
        }
        return "ip:" + request.getRemoteAddr();
    }
}

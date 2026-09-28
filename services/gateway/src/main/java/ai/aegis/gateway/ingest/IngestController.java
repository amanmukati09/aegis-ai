package ai.aegis.gateway.ingest;

import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.security.AuthPrincipal;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Fetch logs from an external URL, server-side. SSRF-guarded: only http/https, and
 * the resolved host must not be loopback/private/link-local (blocks metadata endpoints
 * and internal services). Returns the fetched text split into lines (capped).
 */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private static final int MAX_LINES = 20000;
    private static final int MAX_BYTES = 8 * 1024 * 1024; // 8MB
    private final WebClient webClient = WebClient.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_BYTES))
            .build();

    public record UrlRequest(@NotBlank String url) {
    }

    @PostMapping("/from-url")
    public Map<String, Object> fromUrl(@AuthenticationPrincipal AuthPrincipal principal,
                                       @RequestBody UrlRequest req) {
        URI uri = validate(req.url());
        try {
            String body = webClient.get().uri(uri).retrieve()
                    .bodyToMono(String.class).timeout(Duration.ofSeconds(20)).block();
            if (body == null) {
                throw ApiException.badRequest("Empty response from URL");
            }
            List<String> lines = Arrays.stream(body.split("\n"))
                    .map(String::stripTrailing)
                    .filter(l -> !l.isBlank())
                    .limit(MAX_LINES)
                    .toList();
            return Map.of("lines", lines, "count", lines.size(), "source", uri.getHost());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not fetch URL: " + e.getMessage());
        }
    }

    private URI validate(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            throw ApiException.badRequest("Invalid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw ApiException.badRequest("Only http/https URLs are allowed");
        }
        String host = uri.getHost();
        if (host == null) {
            throw ApiException.badRequest("URL has no host");
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                        || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
                    throw ApiException.badRequest("Refusing to fetch a private/internal address");
                }
                // Block cloud metadata endpoint explicitly.
                if (addr.getHostAddress().equals("169.254.169.254")) {
                    throw ApiException.badRequest("Blocked address");
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw ApiException.badRequest("Could not resolve host");
        }
        return uri;
    }
}

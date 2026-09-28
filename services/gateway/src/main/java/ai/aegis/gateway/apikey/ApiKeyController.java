package ai.aegis.gateway.apikey;

import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.security.ApiKeyHasher;
import ai.aegis.gateway.security.AuthPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/api-keys")
public class ApiKeyController {

    private final ApiKeyRepository repository;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyController(ApiKeyRepository repository) {
        this.repository = repository;
    }

    public record CreateRequest(@NotBlank String name, Integer expiresInDays) {
    }

    public record CreateResponse(String id, String name, String key, String keyPrefix) {
    }

    public record KeyView(String id, String name, String keyPrefix, boolean active,
                          OffsetDateTime lastUsedAt, OffsetDateTime expiresAt, OffsetDateTime createdAt) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateResponse create(@Valid @RequestBody CreateRequest req,
                                 @AuthenticationPrincipal AuthPrincipal principal) {
        // Generate a random key shown to the user exactly once; store only its hash.
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String raw = ApiKeyHasher.PREFIX + HexFormat.of().formatHex(bytes);
        String prefix = raw.substring(0, 12);

        OffsetDateTime expiresAt = req.expiresInDays() == null ? null
                : OffsetDateTime.now().plusDays(req.expiresInDays());

        ApiKey key = new ApiKey(UUID.randomUUID(), principal.userId(), req.name(),
                ApiKeyHasher.sha256(raw), prefix, expiresAt);
        repository.save(key);
        return new CreateResponse(key.getId().toString(), key.getName(), raw, prefix);
    }

    @GetMapping
    public List<KeyView> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return repository.findByUserIdAndActiveTrue(principal.userId()).stream()
                .map(k -> new KeyView(k.getId().toString(), k.getName(), k.getKeyPrefix(),
                        k.isActive(), k.getLastUsedAt(), k.getExpiresAt(), k.getCreatedAt()))
                .toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id, @AuthenticationPrincipal AuthPrincipal principal) {
        ApiKey key = repository.findById(id)
                .orElseThrow(() -> ApiException.badRequest("API key not found"));
        if (!key.getUserId().equals(principal.userId()) && !principal.isSuperAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Not allowed");
        }
        key.setActive(false);
        repository.save(key);
    }
}

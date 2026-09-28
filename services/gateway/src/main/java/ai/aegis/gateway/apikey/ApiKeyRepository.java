package ai.aegis.gateway.apikey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    Optional<ApiKey> findByKeyHashAndActiveTrue(String keyHash);

    List<ApiKey> findByUserIdAndActiveTrue(UUID userId);
}

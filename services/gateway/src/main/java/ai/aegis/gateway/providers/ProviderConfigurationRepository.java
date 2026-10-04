package ai.aegis.gateway.providers;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderConfigurationRepository extends JpaRepository<ProviderConfiguration, UUID> {

    List<ProviderConfiguration> findByOrgId(UUID orgId);

    /** Platform-wide fallback rows (Req 1.8). */
    List<ProviderConfiguration> findByOrgIdIsNull();

    List<ProviderConfiguration> findByOrgIdAndProviderType(UUID orgId, String providerType);

    List<ProviderConfiguration> findByOrgIdIsNullAndProviderType(String providerType);

    Optional<ProviderConfiguration> findByIdAndOrgId(UUID id, UUID orgId);

    /** Own-org configurations plus platform-wide fallback rows, for the admin list view. */
    List<ProviderConfiguration> findByOrgIdOrOrgIdIsNull(UUID orgId);
}

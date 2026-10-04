package ai.aegis.gateway.providers;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ModelEntryRepository extends JpaRepository<ModelEntry, UUID> {

    List<ModelEntry> findByProviderConfigurationId(UUID providerConfigurationId);

    Optional<ModelEntry> findByIdAndProviderConfigurationId(UUID id, UUID providerConfigurationId);

    Optional<ModelEntry> findByProviderConfigurationIdAndModelId(UUID providerConfigurationId, String modelId);

    void deleteByProviderConfigurationId(UUID providerConfigurationId);
}

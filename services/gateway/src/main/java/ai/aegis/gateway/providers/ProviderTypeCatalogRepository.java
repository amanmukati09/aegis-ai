package ai.aegis.gateway.providers;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProviderTypeCatalogRepository extends JpaRepository<ProviderTypeCatalogEntry, String> {

    List<ProviderTypeCatalogEntry> findByEnabledTrue();

    List<ProviderTypeCatalogEntry> findByCategory(String category);
}

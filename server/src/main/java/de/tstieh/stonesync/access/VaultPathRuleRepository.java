package de.tstieh.stonesync.access;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VaultPathRuleRepository extends JpaRepository<VaultPathRuleEntity, UUID> {

    List<VaultPathRuleEntity> findByVaultId(UUID vaultId);

    Optional<VaultPathRuleEntity> findByVaultIdAndPathPrefixAndUserId(UUID vaultId, String pathPrefix, UUID userId);

    Optional<VaultPathRuleEntity> findByVaultIdAndPathPrefixAndUserIdIsNull(UUID vaultId, String pathPrefix);

    void deleteByVaultId(UUID vaultId);
}

package de.tstieh.stonesync.sync;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    List<DocumentEntity> findByVaultId(UUID vaultId);

    Optional<DocumentEntity> findByVaultIdAndCurrentPath(UUID vaultId, String currentPath);
}

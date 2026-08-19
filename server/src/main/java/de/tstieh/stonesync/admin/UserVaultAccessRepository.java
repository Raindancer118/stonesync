package de.tstieh.stonesync.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserVaultAccessRepository extends JpaRepository<UserVaultAccessEntity, UUID> {

    List<UserVaultAccessEntity> findByVaultId(UUID vaultId);

    List<UserVaultAccessEntity> findByUserId(UUID userId);

    Optional<UserVaultAccessEntity> findByUserIdAndVaultId(UUID userId, UUID vaultId);
}

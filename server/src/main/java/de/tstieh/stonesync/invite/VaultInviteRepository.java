package de.tstieh.stonesync.invite;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VaultInviteRepository extends JpaRepository<VaultInviteEntity, UUID> {

    Optional<VaultInviteEntity> findByTokenHash(String tokenHash);
}

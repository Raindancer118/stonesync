package de.tstieh.stonesync.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VaultRepository extends JpaRepository<VaultEntity, UUID> {

    List<VaultEntity> findByOwnerId(UUID ownerId);

    /** Lookup by the link namespace, e.g. the "sales" in [[sales:Jahresabschluss]]. */
    java.util.Optional<VaultEntity> findBySlug(String slug);
}

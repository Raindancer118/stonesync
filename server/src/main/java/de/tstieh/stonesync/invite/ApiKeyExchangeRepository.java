package de.tstieh.stonesync.invite;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApiKeyExchangeRepository extends JpaRepository<ApiKeyExchangeEntity, UUID> {

    Optional<ApiKeyExchangeEntity> findByCodeHash(String codeHash);
}

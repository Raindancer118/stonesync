package de.tstieh.stonesync.sync;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DocumentRestoreQueueRepository extends JpaRepository<DocumentRestoreQueueEntity, UUID> {
}

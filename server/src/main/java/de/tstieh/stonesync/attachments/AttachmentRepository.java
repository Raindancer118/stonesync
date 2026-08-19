package de.tstieh.stonesync.attachments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<AttachmentEntity, UUID> {

    boolean existsByContentHash(String contentHash);

    List<AttachmentEntity> findByContentHash(String contentHash);
}

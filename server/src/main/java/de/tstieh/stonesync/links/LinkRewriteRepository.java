package de.tstieh.stonesync.links;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LinkRewriteRepository extends JpaRepository<LinkRewriteEntity, Long> {

    List<LinkRewriteEntity> findByDocumentIdAndAppliedAtIsNullOrderByIdAsc(UUID documentId);

    void deleteByDocumentIdIn(List<UUID> documentIds);
}

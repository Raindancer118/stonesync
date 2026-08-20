package de.tstieh.stonesync.links;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentLinkRepository extends JpaRepository<DocumentLinkEntity, Long> {

    List<DocumentLinkEntity> findBySourceDocumentId(UUID sourceDocumentId);

    /** Inbound links, matched by where they point rather than by a resolved id. */
    List<DocumentLinkEntity> findByTargetVaultSlugAndTargetPath(String targetVaultSlug, String targetPath);

    List<DocumentLinkEntity> findByTargetDocumentId(UUID targetDocumentId);

    void deleteBySourceDocumentId(UUID sourceDocumentId);
}

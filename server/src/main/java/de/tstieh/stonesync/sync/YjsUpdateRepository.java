package de.tstieh.stonesync.sync;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface YjsUpdateRepository extends JpaRepository<YjsUpdateEntity, Long> {

    List<YjsUpdateEntity> findByDocumentIdOrderByIdAsc(UUID documentId);

    long countByDocumentId(UUID documentId);

    void deleteByDocumentId(UUID documentId);

    Optional<YjsUpdateEntity> findTopByDocumentIdOrderByIdDesc(UUID documentId);

    /** Deletes only the log entries that existed at (or before) a captured snapshot watermark. */
    void deleteByDocumentIdAndIdLessThanEqual(UUID documentId, Long maxId);
}

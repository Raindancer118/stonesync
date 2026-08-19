package de.tstieh.stonesync.sync;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface YjsUpdateRepository extends JpaRepository<YjsUpdateEntity, Long> {

    List<YjsUpdateEntity> findByDocumentIdOrderByIdAsc(UUID documentId);

    long countByDocumentId(UUID documentId);

    void deleteByDocumentId(UUID documentId);
}

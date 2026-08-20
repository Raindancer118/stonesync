package de.tstieh.stonesync.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {

    List<AuditEventEntity> findByVaultIdOrderByIdDesc(UUID vaultId, Pageable pageable);

    List<AuditEventEntity> findByVaultIdAndTypeOrderByIdDesc(UUID vaultId, AuditEventType type, Pageable pageable);

    /** Everything that happened to one note, including entries recorded under its former path. */
    @Query("SELECT e FROM AuditEventEntity e WHERE e.vaultId = :vaultId AND (e.path = :path OR e.documentId = :documentId) ORDER BY e.id DESC")
    List<AuditEventEntity> findForDocument(@Param("vaultId") UUID vaultId, @Param("documentId") UUID documentId,
                                            @Param("path") String path, Pageable pageable);
}

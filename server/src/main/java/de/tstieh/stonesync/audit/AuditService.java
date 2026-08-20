package de.tstieh.stonesync.audit;

import de.tstieh.stonesync.admin.UserEntity;
import de.tstieh.stonesync.admin.UserRepository;
import de.tstieh.stonesync.logging.AppLog;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Writes and queries the audit trail: who changed permissions, who changed content, and who was
 * refused. Deliberately best-effort on the write side - an audit failure must never roll back or
 * abort the operation it describes, so recording runs in its own transaction and swallows
 * (but logs) errors.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final UserRepository userRepository;
    private final Clock clock;
    /**
     * Its own transaction, so an audit entry survives even when the operation it describes rolls
     * back afterwards - and so a refused, non-transactional call can be recorded at all. A
     * {@code @Transactional(REQUIRES_NEW)} method would be useless here: it is called from within
     * this same bean, which bypasses the proxy entirely.
     */
    private final TransactionTemplate ownTransaction;

    public AuditService(AuditEventRepository repository, UserRepository userRepository, Clock clock,
                         PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.clock = clock;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(AuditEventType type, UUID actorId, UUID vaultId, UUID documentId, String path,
                        UUID subjectId, String detail) {
        try {
            AuditEventEntity event = new AuditEventEntity(clock.instant(), type, actorId, labelFor(actorId), vaultId,
                    documentId, path, subjectId, detail);
            ownTransaction.executeWithoutResult(status -> repository.save(event));
        } catch (RuntimeException e) {
            AppLog.warn("Failed to record audit event {} ({}): {}", type, detail, e.getMessage());
        }
    }

    /** Convenience for permission changes, which always concern another user. */
    public void recordAccessChange(AuditEventType type, UUID actorId, UUID vaultId, UUID subjectId, String detail) {
        record(type, actorId, vaultId, null, null, subjectId, detail);
    }

    public List<AuditEventEntity> recentForVault(UUID vaultId, AuditEventType type, int limit) {
        PageRequest page = PageRequest.of(0, Math.clamp(limit, 1, 500));
        return type == null
                ? repository.findByVaultIdOrderByIdDesc(vaultId, page)
                : repository.findByVaultIdAndTypeOrderByIdDesc(vaultId, type, page);
    }

    public List<AuditEventEntity> forDocument(UUID vaultId, UUID documentId, String path, int limit) {
        return repository.findForDocument(vaultId, documentId, path, PageRequest.of(0, Math.clamp(limit, 1, 500)));
    }

    private String labelFor(UUID actorId) {
        if (actorId == null) {
            return "system";
        }
        return userRepository.findById(actorId).map(UserEntity::getEmail).orElse(actorId.toString());
    }
}

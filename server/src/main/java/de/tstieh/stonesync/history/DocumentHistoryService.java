package de.tstieh.stonesync.history;

import de.tstieh.stonesync.audit.AuditEventEntity;
import de.tstieh.stonesync.audit.AuditService;
import de.tstieh.stonesync.sync.DocumentService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * "Who changed this note, when, and what exactly" for a single document - readable by anyone who
 * may read the note itself (unlike the vault-wide {@link RestoreService#log}, which spans notes
 * the caller may not see and is therefore owner-only).
 *
 * <p>Two sources, on purpose: the git history carries the actual content diffs, while the audit
 * trail also covers the things git never sees - creation, renames, deletion, refused attempts.</p>
 */
@Service
public class DocumentHistoryService {

    private final DocumentService documentService;
    private final VaultGitRepository gitRepository;
    private final AuditService auditService;

    public DocumentHistoryService(DocumentService documentService, VaultGitRepository gitRepository,
                                   AuditService auditService) {
        this.documentService = documentService;
        this.gitRepository = gitRepository;
        this.auditService = auditService;
    }

    public List<FileHistoryEntry> history(UUID userId, UUID documentId, int limit) {
        DocumentService.DocumentLocation location = documentService.locate(userId, documentId);
        return gitRepository.logForPath(location.vaultId(), location.path(), limit);
    }

    public String diff(UUID userId, UUID documentId, String commitId) {
        DocumentService.DocumentLocation location = documentService.locate(userId, documentId);
        return gitRepository.diffForPath(location.vaultId(), commitId, location.path());
    }

    public List<AuditEventEntity> auditTrail(UUID userId, UUID documentId, int limit) {
        DocumentService.DocumentLocation location = documentService.locate(userId, documentId);
        return auditService.forDocument(location.vaultId(), documentId, location.path(), limit);
    }
}

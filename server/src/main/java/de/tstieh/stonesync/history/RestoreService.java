package de.tstieh.stonesync.history;

import de.tstieh.stonesync.sync.DocumentEntity;
import de.tstieh.stonesync.sync.DocumentService;
import de.tstieh.stonesync.sync.RestoreBroadcaster;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Restores a vault to a point-in-time git commit ("essentially like git", per the original
 * request): every file present in the target commit gets its live document content replaced
 * (immediately for connected clients, queued otherwise - see {@link RestoreBroadcaster}); every
 * currently-existing, non-deleted document whose path is NOT present in the target commit gets
 * tombstoned, so the vault actually matches "the vault at that point in time" rather than only
 * ever adding content back.
 */
@Service
public class RestoreService {

    private final VaultGitRepository gitRepository;
    private final DocumentService documentService;
    private final RestoreBroadcaster broadcaster;

    public RestoreService(VaultGitRepository gitRepository, DocumentService documentService,
                           RestoreBroadcaster broadcaster) {
        this.gitRepository = gitRepository;
        this.documentService = documentService;
        this.broadcaster = broadcaster;
    }

    public RestoreResult restore(UUID vaultId, String commitIsh) {
        Map<String, String> targetFiles = gitRepository.readTreeAtCommit(vaultId, commitIsh);
        Set<String> targetPaths = targetFiles.keySet();

        int restoredCount = 0;
        for (Map.Entry<String, String> entry : targetFiles.entrySet()) {
            UUID documentId = documentService.resolveOrCreateForRestore(vaultId, entry.getKey(),
                    DocumentEntity.ContentType.TEXT);
            broadcaster.broadcastOrQueueRestore(documentId, entry.getValue());
            restoredCount++;
        }

        int tombstonedCount = 0;
        for (DocumentService.DocumentSummary document : documentService.listNonDeletedForRestore(vaultId)) {
            if (!targetPaths.contains(document.path())) {
                documentService.markDeletedForRestore(document.id());
                tombstonedCount++;
            }
        }

        return new RestoreResult(restoredCount, tombstonedCount);
    }

    public record RestoreResult(int restoredDocumentCount, int tombstonedDocumentCount) {
    }
}

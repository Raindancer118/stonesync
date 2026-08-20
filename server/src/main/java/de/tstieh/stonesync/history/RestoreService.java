package de.tstieh.stonesync.history;

import de.tstieh.stonesync.admin.VaultAccessService;
import de.tstieh.stonesync.logging.AppLog;
import de.tstieh.stonesync.sync.DocumentEntity;
import de.tstieh.stonesync.sync.DocumentService;
import de.tstieh.stonesync.sync.RestoreBroadcaster;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Restores a vault to a point-in-time git commit ("essentially like git", per the original
 * request): every file present in the target commit gets its live document content replaced
 * (immediately for connected clients, queued otherwise - see {@link RestoreBroadcaster}); every
 * currently-existing, non-deleted document whose path is NOT present in the target commit gets
 * tombstoned, so the vault actually matches "the vault at that point in time" rather than only
 * ever adding content back (this deliberately includes documents created AFTER the target commit
 * - a git-like point-in-time restore, exactly as requested, not a selective rollback).
 *
 * <p>Every method here requires the caller's {@code userId} and enforces vault-level
 * authorization via {@link VaultAccessService} - unlike most of {@code /api/admin/**}, which only
 * requires any valid API key (see {@code InviteAdminController}), a restore's destructive blast
 * radius (tombstoning an entire vault's documents) means a leaked key must not be able to wipe a
 * vault its holder has no access to. Found via agy architecture review.</p>
 */
@Service
public class RestoreService {

    private final VaultGitRepository gitRepository;
    private final DocumentService documentService;
    private final RestoreBroadcaster broadcaster;
    private final VaultAccessService vaultAccessService;

    public RestoreService(VaultGitRepository gitRepository, DocumentService documentService,
                           RestoreBroadcaster broadcaster, VaultAccessService vaultAccessService) {
        this.gitRepository = gitRepository;
        this.documentService = documentService;
        this.broadcaster = broadcaster;
        this.vaultAccessService = vaultAccessService;
    }

    public List<GitLogEntry> log(UUID userId, UUID vaultId) {
        vaultAccessService.requireAccess(userId, vaultId);
        return gitRepository.log(vaultId);
    }

    public RestoreResult restore(UUID userId, UUID vaultId, String commitIsh) {
        vaultAccessService.requireAccess(userId, vaultId);
        AppLog.warn("User {} is restoring vault {} to commit '{}' - this may tombstone documents", userId, vaultId, commitIsh);

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
                AppLog.warn("Tombstoned {} (document {}) during restore of vault {} - not present at commit '{}'",
                        document.path(), document.id(), vaultId, commitIsh);
                tombstonedCount++;
            }
        }

        AppLog.info("Restore of vault {} to commit '{}' complete: {} restored, {} tombstoned",
                vaultId, commitIsh, restoredCount, tombstonedCount);
        return new RestoreResult(restoredCount, tombstonedCount);
    }

    public record RestoreResult(int restoredDocumentCount, int tombstonedDocumentCount) {
    }
}

package de.tstieh.stonesync.sync;

import de.tstieh.stonesync.admin.VaultAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Document metadata operations. Renames and deletes are metadata-only and completely
 * separate from the Yjs binary channel: the document's UUID (and therefore its Yjs
 * content/update log) is never touched by either operation.
 *
 * <p>Every method here requires the caller's {@code userId} and enforces vault-level
 * authorization via {@link VaultAccessService} before touching any data - an authenticated
 * API key only proves who is calling, not which vaults they may access.</p>
 */
@Service
public class DocumentService {

    private final DocumentRepository repository;
    private final VaultAccessService vaultAccessService;
    private final DocumentDeletionBroadcaster deletionBroadcaster;
    private final DocumentGitEraser gitEraser;
    private final Clock clock;

    public DocumentService(DocumentRepository repository, VaultAccessService vaultAccessService,
                            DocumentDeletionBroadcaster deletionBroadcaster, DocumentGitEraser gitEraser, Clock clock) {
        this.repository = repository;
        this.vaultAccessService = vaultAccessService;
        this.deletionBroadcaster = deletionBroadcaster;
        this.gitEraser = gitEraser;
        this.clock = clock;
    }

    @Transactional
    public void rename(UUID userId, UUID documentId, String newPath) {
        DocumentEntity document = repository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        vaultAccessService.requireAccess(userId, document.getVaultId());
        document.rename(newPath, clock.instant());
        repository.save(document);
    }

    @Transactional
    public void markDeleted(UUID userId, UUID documentId) {
        DocumentEntity document = repository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        vaultAccessService.requireAccess(userId, document.getVaultId());
        document.markDeleted(clock.instant());
        repository.save(document);
        deletionBroadcaster.broadcastDeleteNotice(documentId);
        // A real, user-initiated delete - unlike markDeletedForRestore, this must also erase the
        // document from git history, or a later restore would resurrect it (see DocumentGitEraser).
        gitEraser.removeFromGit(document.getVaultId(), document.getCurrentPath());
    }

    /**
     * Resolves the document UUID for a (vaultId, path) pair, creating a new document row
     * if none exists yet. Called by clients before they open the Yjs sync channel or upload
     * an attachment - they only ever know the vault-relative path, never the UUID up front.
     */
    @Transactional
    public UUID resolveOrCreate(UUID userId, UUID vaultId, String path, DocumentEntity.ContentType contentType) {
        vaultAccessService.requireAccess(userId, vaultId);
        return repository.findByVaultIdAndCurrentPath(vaultId, path)
                .map(DocumentEntity::getId)
                .orElseGet(() -> {
                    DocumentEntity created = new DocumentEntity(UUID.randomUUID(), vaultId, path, contentType, clock.instant());
                    repository.save(created);
                    return created.getId();
                });
    }

    /** Looks up the vault a document belongs to - used by callers that only hold a documentId. */
    public UUID vaultIdOf(UUID documentId) {
        return repository.findById(documentId)
                .map(DocumentEntity::getVaultId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    /**
     * Lists every non-deleted document of a vault - the basis for a bulk vault download (a
     * freshly connected client needs to know the full set of (id, path, contentType) tuples
     * before it can open a {@code DocumentSession} per file or download each attachment).
     * Tombstoned documents are deliberately excluded: they no longer exist from the client's
     * point of view.
     */
    public List<DocumentSummary> listDocuments(UUID userId, UUID vaultId) {
        vaultAccessService.requireAccess(userId, vaultId);
        return repository.findByVaultId(vaultId).stream()
                .filter(document -> !document.isDeleted())
                .map(document -> new DocumentSummary(document.getId(), document.getCurrentPath(), document.getContentType()))
                .toList();
    }

    /** Resolves a document's vault and current path - used by {@code MaterializeService}. */
    public DocumentLocation locate(UUID userId, UUID documentId) {
        DocumentEntity document = repository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        vaultAccessService.requireAccess(userId, document.getVaultId());
        return new DocumentLocation(document.getVaultId(), document.getCurrentPath());
    }

    /**
     * Every non-deleted (id, path) pair of a vault, without a per-user access check - used
     * internally by {@code RestoreService}, which already enforces vault-level access itself
     * once at the top of its own {@code restore}/{@code log} methods before calling any of these
     * *ForRestore methods; repeating the same check per-call here would be redundant.
     */
    public List<DocumentSummary> listNonDeletedForRestore(UUID vaultId) {
        return repository.findByVaultId(vaultId).stream()
                .filter(document -> !document.isDeleted())
                .map(document -> new DocumentSummary(document.getId(), document.getCurrentPath(), document.getContentType()))
                .toList();
    }

    /** System-level counterpart to {@link #resolveOrCreate} - see {@link #listNonDeletedForRestore}. */
    @Transactional
    public UUID resolveOrCreateForRestore(UUID vaultId, String path, DocumentEntity.ContentType contentType) {
        return repository.findByVaultIdAndCurrentPath(vaultId, path)
                .map(DocumentEntity::getId)
                .orElseGet(() -> {
                    DocumentEntity created = new DocumentEntity(UUID.randomUUID(), vaultId, path, contentType, clock.instant());
                    repository.save(created);
                    return created.getId();
                });
    }

    /** System-level counterpart to {@link #markDeleted} - see {@link #listNonDeletedForRestore}. */
    @Transactional
    public void markDeletedForRestore(UUID documentId) {
        DocumentEntity document = repository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        document.markDeleted(clock.instant());
        repository.save(document);
        deletionBroadcaster.broadcastDeleteNotice(documentId);
    }

    public record DocumentSummary(UUID id, String path, DocumentEntity.ContentType contentType) {
    }

    public record DocumentLocation(UUID vaultId, String path) {
    }
}

package de.tstieh.stonesync.sync;

import de.tstieh.stonesync.access.Permission;
import de.tstieh.stonesync.admin.VaultAccessService;
import de.tstieh.stonesync.audit.AuditEventType;
import de.tstieh.stonesync.audit.AuditService;
import de.tstieh.stonesync.logging.AppLog;
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
    private final VaultEventBroadcaster vaultEventBroadcaster;
    private final AuditService auditService;
    private final Clock clock;

    public DocumentService(DocumentRepository repository, VaultAccessService vaultAccessService,
                            DocumentDeletionBroadcaster deletionBroadcaster, DocumentGitEraser gitEraser,
                            VaultEventBroadcaster vaultEventBroadcaster, AuditService auditService, Clock clock) {
        this.repository = repository;
        this.vaultAccessService = vaultAccessService;
        this.deletionBroadcaster = deletionBroadcaster;
        this.gitEraser = gitEraser;
        this.vaultEventBroadcaster = vaultEventBroadcaster;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public void rename(UUID userId, UUID documentId, String newPath) {
        DocumentEntity document = repository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        String oldPath = document.getCurrentPath();
        // Both ends matter: moving a note out of a folder you may write into one you may not
        // would otherwise be a way to smuggle content past a path rule (and vice versa).
        vaultAccessService.requirePathPermission(userId, document.getVaultId(), oldPath, Permission.WRITE);
        vaultAccessService.requirePathPermission(userId, document.getVaultId(), newPath, Permission.WRITE);
        document.rename(newPath, clock.instant());
        repository.save(document);
        auditService.record(AuditEventType.DOCUMENT_RENAMED, userId, document.getVaultId(), documentId, newPath, null,
                "from '" + oldPath + "' to '" + newPath + "'");
        AppLog.info("Renamed document {} from '{}' to '{}'", documentId, oldPath, newPath);
    }

    public void markDeleted(UUID userId, UUID documentId) {
        markDeleted(userId, documentId, null);
    }

    /**
     * @param originSessionId opaque per-plugin-instance id the calling client sent along (see
     *                        {@code DocumentController#delete}), echoed back in the vault-events
     *                        broadcast so that SAME client can recognize and ignore its own event
     *                        instead of missing others while paused (see {@link VaultEventBroadcaster}).
     */
    @Transactional
    public void markDeleted(UUID userId, UUID documentId, String originSessionId) {
        DocumentEntity document = repository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        vaultAccessService.requirePathPermission(userId, document.getVaultId(), document.getCurrentPath(), Permission.WRITE);
        document.markDeleted(clock.instant());
        repository.save(document);
        deletionBroadcaster.broadcastDeleteNotice(documentId);
        // A real, user-initiated delete - unlike markDeletedForRestore, this must also erase the
        // document from git history, or a later restore would resurrect it (see DocumentGitEraser).
        gitEraser.removeFromGit(document.getVaultId(), document.getCurrentPath());
        vaultEventBroadcaster.notifyDocumentDeleted(document.getVaultId(), documentId, document.getCurrentPath(), originSessionId);
        auditService.record(AuditEventType.DOCUMENT_DELETED, userId, document.getVaultId(), documentId,
                document.getCurrentPath(), null, null);
        AppLog.info("Deleted document {} ('{}') by user {}", documentId, document.getCurrentPath(), userId);
    }

    /**
     * Resolves the document UUID for a (vaultId, path) pair, creating a new document row
     * if none exists yet. Called by clients before they open the Yjs sync channel or upload
     * an attachment - they only ever know the vault-relative path, never the UUID up front.
     */
    public UUID resolveOrCreate(UUID userId, UUID vaultId, String path, DocumentEntity.ContentType contentType) {
        return resolveOrCreate(userId, vaultId, path, contentType, null);
    }

    /**
     * @param originSessionId opaque per-plugin-instance id the calling client sent along (see
     *                        {@code DocumentController#resolve}), echoed back in the vault-events
     *                        broadcast - see {@link #markDeleted(UUID, UUID, String)}.
     */
    @Transactional
    public UUID resolveOrCreate(UUID userId, UUID vaultId, String path, DocumentEntity.ContentType contentType,
                                 String originSessionId) {
        return repository.findByVaultIdAndCurrentPath(vaultId, path)
                .map(existing -> {
                    // Opening an existing note only needs read access - a VIEWER must be able to
                    // resolve its id to open the (read-only) sync socket.
                    vaultAccessService.requirePathPermission(userId, vaultId, path, Permission.READ);
                    AppLog.debug("Resolved existing document {} for '{}' in vault {}", existing.getId(), path, vaultId);
                    return existing.getId();
                })
                .orElseGet(() -> {
                    vaultAccessService.requirePathPermission(userId, vaultId, path, Permission.WRITE);
                    DocumentEntity created = new DocumentEntity(UUID.randomUUID(), vaultId, path, contentType, clock.instant());
                    repository.save(created);
                    vaultEventBroadcaster.notifyDocumentCreated(vaultId, created.getId(), path, contentType, originSessionId);
                    auditService.record(AuditEventType.DOCUMENT_CREATED, userId, vaultId, created.getId(), path, null,
                            contentType.name());
                    AppLog.info("Created new document {} for '{}' in vault {}", created.getId(), path, vaultId);
                    return created.getId();
                });
    }

    /**
     * Vault + path of a document without any access check - only for callers that perform the
     * check themselves immediately afterwards (the WebSocket handshake, which needs the path
     * *before* it can ask whether this user may see that path).
     */
    public DocumentLocation locateUnchecked(UUID documentId) {
        return repository.findById(documentId)
                .map(document -> new DocumentLocation(document.getVaultId(), document.getCurrentPath()))
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
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
        vaultAccessService.requireVaultPermission(userId, vaultId, Permission.READ);
        // Filtered per note, not just per vault: a note the caller may not read must not even be
        // mentioned here - this listing is what drives the client's bulk download, so anything
        // listed would be pulled onto their disk.
        List<DocumentSummary> documents = repository.findByVaultId(vaultId).stream()
                .filter(document -> !document.isDeleted())
                .filter(document -> vaultAccessService.canRead(userId, vaultId, document.getCurrentPath()))
                .map(document -> new DocumentSummary(document.getId(), document.getCurrentPath(), document.getContentType()))
                .toList();
        AppLog.debug("Listed {} documents for vault {}", documents.size(), vaultId);
        return documents;
    }

    /** Resolves a document's vault and current path, requiring read access. */
    public DocumentLocation locate(UUID userId, UUID documentId) {
        return locate(userId, documentId, Permission.READ);
    }

    /**
     * Same, but for callers that are about to change the document ({@code MaterializeService}) -
     * writing content through the materialize side-channel must require exactly what editing the
     * note requires, otherwise it would be a way around the read-only role.
     */
    public DocumentLocation locateForWrite(UUID userId, UUID documentId) {
        return locate(userId, documentId, Permission.WRITE);
    }

    private DocumentLocation locate(UUID userId, UUID documentId, Permission permission) {
        DocumentEntity document = repository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        vaultAccessService.requirePathPermission(userId, document.getVaultId(), document.getCurrentPath(), permission);
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

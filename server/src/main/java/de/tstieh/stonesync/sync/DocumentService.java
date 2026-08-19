package de.tstieh.stonesync.sync;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Document metadata operations. Renames and deletes are metadata-only and completely
 * separate from the Yjs binary channel: the document's UUID (and therefore its Yjs
 * content/update log) is never touched by either operation.
 */
@Service
public class DocumentService {

    private final DocumentRepository repository;
    private final Clock clock;

    public DocumentService(DocumentRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void rename(UUID documentId, String newPath) {
        DocumentEntity document = repository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        document.rename(newPath, clock.instant());
        repository.save(document);
    }

    @Transactional
    public void markDeleted(UUID documentId) {
        DocumentEntity document = repository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        document.markDeleted(clock.instant());
        repository.save(document);
    }

    /**
     * Resolves the document UUID for a (vaultId, path) pair, creating a new document row
     * if none exists yet. Called by clients before they open the Yjs sync channel or upload
     * an attachment - they only ever know the vault-relative path, never the UUID up front.
     */
    @Transactional
    public UUID resolveOrCreate(UUID vaultId, String path, DocumentEntity.ContentType contentType) {
        return repository.findByVaultIdAndCurrentPath(vaultId, path)
                .map(DocumentEntity::getId)
                .orElseGet(() -> {
                    DocumentEntity created = new DocumentEntity(UUID.randomUUID(), vaultId, path, contentType, clock.instant());
                    repository.save(created);
                    return created.getId();
                });
    }
}

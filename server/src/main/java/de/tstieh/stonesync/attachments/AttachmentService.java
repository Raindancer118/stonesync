package de.tstieh.stonesync.attachments;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Attachment upload/status handling. Attachments are content-addressed (SHA-256): the client
 * checks {@link #isKnown(String)} before uploading and only transfers bytes for unknown hashes.
 * Conflicting concurrent uploads for the same document are resolved last-writer-wins by
 * comparing the file's modification timestamp.
 */
@Service
public class AttachmentService {

    private final AttachmentRepository repository;
    private final FileSystemAttachmentStorage storage;

    public AttachmentService(AttachmentRepository repository, FileSystemAttachmentStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    public boolean isKnown(String contentHash) {
        return repository.existsByContentHash(contentHash);
    }

    @Transactional
    public void upload(UUID documentId, String contentHash, byte[] bytes, Instant modifiedAt) {
        Optional<AttachmentEntity> existing = repository.findById(documentId);
        if (existing.isEmpty()) {
            String storagePath = storage.store(contentHash, bytes);
            repository.save(new AttachmentEntity(documentId, contentHash, bytes.length, storagePath, modifiedAt));
            return;
        }

        AttachmentEntity entity = existing.get();
        if (modifiedAt.isBefore(entity.getModifiedAt())) {
            // Stale/conflicting write loses against the already-stored, newer version - discard
            // without ever touching the filesystem.
            return;
        }
        String storagePath = storage.store(contentHash, bytes);
        entity.applyIfNewer(contentHash, bytes.length, storagePath, modifiedAt);
        repository.save(entity);
    }
}

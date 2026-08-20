package de.tstieh.stonesync.attachments;

import de.tstieh.stonesync.admin.VaultAccessService;
import de.tstieh.stonesync.sync.DocumentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Attachment upload/status handling. Attachments are content-addressed (SHA-256): the client
 * checks {@link #isKnown(String)} before uploading and only transfers bytes for unknown hashes.
 * Conflicting concurrent uploads for the same document are resolved last-writer-wins by
 * comparing the file's modification timestamp.
 *
 * <p>The claimed {@code contentHash} is never trusted as-is: it is recomputed from the actual
 * uploaded bytes and the upload is rejected on mismatch. This is not just an integrity check -
 * it is also what makes the value safe to use as a filesystem path segment downstream (a
 * verified SHA-256 hex digest can never contain "/" or "..", closing off path traversal).</p>
 */
@Service
public class AttachmentService {

    private final AttachmentRepository repository;
    private final FileSystemAttachmentStorage storage;
    private final DocumentService documentService;
    private final VaultAccessService vaultAccessService;

    public AttachmentService(AttachmentRepository repository, FileSystemAttachmentStorage storage,
                              DocumentService documentService, VaultAccessService vaultAccessService) {
        this.repository = repository;
        this.storage = storage;
        this.documentService = documentService;
        this.vaultAccessService = vaultAccessService;
    }

    public boolean isKnown(String contentHash) {
        return repository.existsByContentHash(contentHash);
    }

    @Transactional
    public void upload(UUID userId, UUID documentId, String contentHash, byte[] bytes, Instant modifiedAt) {
        UUID vaultId = documentService.vaultIdOf(documentId);
        vaultAccessService.requireAccess(userId, vaultId);

        String actualHash = sha256Hex(bytes);
        if (!actualHash.equals(contentHash)) {
            throw new InvalidAttachmentHashException(
                    "Claimed content hash does not match the actual SHA-256 of the uploaded bytes");
        }

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

    /** Streams back the stored bytes for a document's attachment, after verifying vault access. */
    public byte[] download(UUID userId, UUID documentId) {
        UUID vaultId = documentService.vaultIdOf(documentId);
        vaultAccessService.requireAccess(userId, vaultId);

        AttachmentEntity entity = repository.findById(documentId)
                .orElseThrow(() -> new AttachmentNotFoundException(documentId));
        return storage.load(entity.getStoragePath());
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

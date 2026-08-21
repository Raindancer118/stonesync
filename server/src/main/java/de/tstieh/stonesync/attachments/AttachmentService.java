package de.tstieh.stonesync.attachments;

import de.tstieh.stonesync.access.Permission;
import de.tstieh.stonesync.admin.VaultAccessService;
import de.tstieh.stonesync.audit.AuditEventType;
import de.tstieh.stonesync.audit.AuditService;
import de.tstieh.stonesync.logging.AppLog;
import de.tstieh.stonesync.search.AttachmentTextExtractionService;
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
    private final AuditService auditService;
    private final AttachmentTextExtractionService textExtractionService;

    public AttachmentService(AttachmentRepository repository, FileSystemAttachmentStorage storage,
                              DocumentService documentService, VaultAccessService vaultAccessService,
                              AuditService auditService, AttachmentTextExtractionService textExtractionService) {
        this.repository = repository;
        this.storage = storage;
        this.documentService = documentService;
        this.vaultAccessService = vaultAccessService;
        this.auditService = auditService;
        this.textExtractionService = textExtractionService;
    }

    public boolean isKnown(String contentHash) {
        boolean known = repository.existsByContentHash(contentHash);
        AppLog.debug("Attachment hash {} known: {}", contentHash, known);
        return known;
    }

    @Transactional
    public void upload(UUID userId, UUID documentId, String contentHash, byte[] bytes, Instant modifiedAt) {
        DocumentService.DocumentLocation location = documentService.locateForWrite(userId, documentId);
        UUID vaultId = location.vaultId();

        String actualHash = sha256Hex(bytes);
        if (!actualHash.equals(contentHash)) {
            AppLog.warn("Rejected attachment upload for document {}: claimed hash {} did not match actual hash",
                    documentId, contentHash);
            throw new InvalidAttachmentHashException(
                    "Claimed content hash does not match the actual SHA-256 of the uploaded bytes");
        }

        Optional<AttachmentEntity> existing = repository.findById(documentId);
        if (existing.isEmpty()) {
            String storagePath = storage.store(contentHash, bytes);
            repository.save(new AttachmentEntity(documentId, contentHash, bytes.length, storagePath, modifiedAt));
            auditService.record(AuditEventType.ATTACHMENT_UPLOADED, userId, vaultId, documentId, location.path(),
                    null, bytes.length + " bytes");
            AppLog.info("Stored new attachment for document {} ({} bytes, hash {})", documentId, bytes.length, contentHash);
            textExtractionService.extractAndIndex(documentId, bytes, location.path());
            return;
        }

        AttachmentEntity entity = existing.get();
        if (modifiedAt.isBefore(entity.getModifiedAt())) {
            // Stale/conflicting write loses against the already-stored, newer version - discard
            // without ever touching the filesystem.
            AppLog.warn("Discarded stale/conflicting attachment upload for document {} (modifiedAt {} is older than stored {})",
                    documentId, modifiedAt, entity.getModifiedAt());
            return;
        }
        String storagePath = storage.store(contentHash, bytes);
        entity.applyIfNewer(contentHash, bytes.length, storagePath, modifiedAt);
        repository.save(entity);
        auditService.record(AuditEventType.ATTACHMENT_UPLOADED, userId, vaultId, documentId, location.path(), null,
                bytes.length + " bytes (replaced)");
        AppLog.info("Updated attachment for document {} ({} bytes, hash {})", documentId, bytes.length, contentHash);
        textExtractionService.extractAndIndex(documentId, bytes, location.path());
    }

    /** Streams back the stored bytes for a document's attachment, after verifying vault access. */
    public byte[] download(UUID userId, UUID documentId) {
        documentService.locate(userId, documentId); // read access on the attachment's own path

        AttachmentEntity entity = repository.findById(documentId)
                .orElseThrow(() -> {
                    AppLog.warn("Attachment download requested for unknown document {}", documentId);
                    return new AttachmentNotFoundException(documentId);
                });
        AppLog.debug("Serving attachment download for document {}", documentId);
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

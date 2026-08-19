package de.tstieh.stonesync.attachments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "attachments")
public class AttachmentEntity {

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(nullable = false)
    private long size;

    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    @Column(name = "modified_at", nullable = false)
    private Instant modifiedAt;

    protected AttachmentEntity() {
        // JPA
    }

    public AttachmentEntity(UUID documentId, String contentHash, long size, String storagePath, Instant modifiedAt) {
        this.documentId = documentId;
        this.contentHash = contentHash;
        this.size = size;
        this.storagePath = storagePath;
        this.modifiedAt = modifiedAt;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public String getContentHash() {
        return contentHash;
    }

    public long getSize() {
        return size;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public Instant getModifiedAt() {
        return modifiedAt;
    }

    /** Applies a new upload if - and only if - it is not older than what is already stored (LWW). */
    public boolean applyIfNewer(String newHash, long newSize, String newStoragePath, Instant newModifiedAt) {
        if (newModifiedAt.isBefore(this.modifiedAt)) {
            return false;
        }
        this.contentHash = newHash;
        this.size = newSize;
        this.storagePath = newStoragePath;
        this.modifiedAt = newModifiedAt;
        return true;
    }
}

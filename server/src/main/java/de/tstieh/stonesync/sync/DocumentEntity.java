package de.tstieh.stonesync.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents")
public class DocumentEntity {

    @Id
    private UUID id;

    @Column(name = "vault_id", nullable = false)
    private UUID vaultId;

    @Column(name = "current_path", nullable = false)
    private String currentPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false)
    private ContentType contentType;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Plaintext used for full-text search only (see migration V7 for the generated
     * {@code search_vector} column/trigger) - notes get it from the materialize side-channel,
     * attachments from {@code AttachmentTextExtractionService} (PDF text / OCR). Never used to
     * reconstruct actual content: for TEXT documents the Yjs update log remains the only source
     * of truth, this is a lossy, search-only copy that can lag behind by a few seconds.
     */
    @Column(name = "plain_text")
    private String plainText;

    protected DocumentEntity() {
        // JPA
    }

    public DocumentEntity(UUID id, UUID vaultId, String currentPath, ContentType contentType, Instant now) {
        this.id = id;
        this.vaultId = vaultId;
        this.currentPath = currentPath;
        this.contentType = contentType;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getVaultId() {
        return vaultId;
    }

    public String getCurrentPath() {
        return currentPath;
    }

    public void rename(String newPath, Instant when) {
        this.currentPath = newPath;
        this.updatedAt = when;
    }

    public ContentType getContentType() {
        return contentType;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void markDeleted(Instant when) {
        this.deletedAt = when;
        this.updatedAt = when;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getPlainText() {
        return plainText;
    }

    /** Search-index-only - does not touch {@code updatedAt} (that reflects real content edits). */
    public void updatePlainText(String plainText) {
        this.plainText = plainText;
    }

    public enum ContentType {
        TEXT, ATTACHMENT
    }
}

package de.tstieh.stonesync.links;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One cross-vault link found in a note, as of that note's last materialized content. */
@Entity
@Table(name = "document_links")
public class DocumentLinkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_document_id", nullable = false)
    private UUID sourceDocumentId;

    @Column(name = "source_vault_id", nullable = false)
    private UUID sourceVaultId;

    @Column(name = "target_vault_slug", nullable = false)
    private String targetVaultSlug;

    @Column(name = "target_path", nullable = false)
    private String targetPath;

    /** Filled in once the target actually exists; a link may legitimately point at nothing yet. */
    @Column(name = "target_document_id")
    private UUID targetDocumentId;

    @Column(name = "link_text", nullable = false)
    private String linkText;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DocumentLinkEntity() {
        // JPA
    }

    public DocumentLinkEntity(UUID sourceDocumentId, UUID sourceVaultId, String targetVaultSlug, String targetPath,
                               UUID targetDocumentId, String linkText, Instant updatedAt) {
        this.sourceDocumentId = sourceDocumentId;
        this.sourceVaultId = sourceVaultId;
        this.targetVaultSlug = targetVaultSlug;
        this.targetPath = targetPath;
        this.targetDocumentId = targetDocumentId;
        this.linkText = linkText;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getSourceDocumentId() {
        return sourceDocumentId;
    }

    public UUID getSourceVaultId() {
        return sourceVaultId;
    }

    public String getTargetVaultSlug() {
        return targetVaultSlug;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public UUID getTargetDocumentId() {
        return targetDocumentId;
    }

    public String getLinkText() {
        return linkText;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

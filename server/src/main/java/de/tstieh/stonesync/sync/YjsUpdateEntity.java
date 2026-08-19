package de.tstieh.stonesync.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "yjs_updates")
public class YjsUpdateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "update_bytes", nullable = false)
    private byte[] updateBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected YjsUpdateEntity() {
        // JPA
    }

    public YjsUpdateEntity(UUID documentId, byte[] updateBytes, Instant createdAt) {
        this.documentId = documentId;
        this.updateBytes = updateBytes;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public byte[] getUpdateBytes() {
        return updateBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

package de.tstieh.stonesync.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "yjs_snapshots")
public class YjsSnapshotEntity {

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "state_bytes", nullable = false)
    private byte[] stateBytes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected YjsSnapshotEntity() {
        // JPA
    }

    public YjsSnapshotEntity(UUID documentId, byte[] stateBytes, Instant updatedAt) {
        this.documentId = documentId;
        this.stateBytes = stateBytes;
        this.updatedAt = updatedAt;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public byte[] getStateBytes() {
        return stateBytes;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

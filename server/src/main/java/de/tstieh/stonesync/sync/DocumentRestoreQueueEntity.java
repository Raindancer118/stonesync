package de.tstieh.stonesync.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * At most one pending restore per document, for a client that's currently offline. Keyed
 * directly by documentId (not a synthetic id) since only the latest pending restore for a
 * document ever matters - a second restore before the first was delivered simply replaces it.
 */
@Entity
@Table(name = "document_restore_queue")
public class DocumentRestoreQueueEntity {

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Column(nullable = false)
    private String content;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    protected DocumentRestoreQueueEntity() {
        // JPA
    }

    public DocumentRestoreQueueEntity(UUID documentId, String content, Instant requestedAt) {
        this.documentId = documentId;
        this.content = content;
        this.requestedAt = requestedAt;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public String getContent() {
        return content;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }
}
